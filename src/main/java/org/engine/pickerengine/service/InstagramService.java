package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramPost;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.engine.pickerengine.dto.InstagramSearchHashtag;
import org.engine.pickerengine.dto.InstagramSearchPlace;
import org.engine.pickerengine.dto.InstagramSearchResponse;
import org.engine.pickerengine.dto.InstagramSearchUser;
import org.engine.pickerengine.dto.InstagramSearchUsersPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class InstagramService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstagramService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String PROFILE_ENDPOINT =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=%s";
    private static final String USER_FEED_ENDPOINT =
            "https://www.instagram.com/api/v1/feed/user/%s/?count=%d";
    private static final String KEYWORD_SEARCH_ENDPOINT =
            "https://www.instagram.com/api/v1/web/search/topsearch/?context=blended&query=%s";
    private static final String HASHTAG_SECTIONS_ENDPOINT =
            "https://www.instagram.com/api/v1/tags/%s/sections/?tab=recent&count=%d";
    private static final String HASHTAG_WEB_INFO_ENDPOINT =
            "https://www.instagram.com/api/v1/tags/web_info/?tag_name=%s";
    private static final String FBSEARCH_TOP_SERP_ENDPOINT =
            "https://www.instagram.com/api/v1/fbsearch/web/top_serp/?enable_metadata=true&query=%s";
    private static final String WEB_APP_ID = "936619743392459";
    private static final String DEFAULT_USER_AGENT = (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String sessionId;
    private final int maxPosts;
    private final HttpClient httpClient;
    private final InstagramCacheService cacheService;

    public InstagramService(
            @Value("${instagram.sessionid:}") String sessionId,
            @Value("${instagram.max-posts:18}") int maxPosts,
            InstagramCacheService cacheService) {
        String resolved = sessionId;
        if (resolved == null || resolved.isBlank()) {
            resolved = System.getenv("IG_SESSIONID");
        }
        this.sessionId = resolved == null ? "" : resolved.trim();
        this.maxPosts = Math.max(0, maxPosts);
        this.httpClient = buildHttpClient();
        this.cacheService = cacheService;
    }

    public List<InstagramProfile> fetchProfiles(String userId) {
        InstagramProfile profile = fetchProfile(userId);
        return profile == null ? List.of() : List.of(profile);
    }

    public InstagramProfileWithPosts fetchProfileWithPosts(String userId) {
        if (userId == null || userId.isBlank()) {
            return new InstagramProfileWithPosts(null, List.of());
        }
        String normalized = normalizeUsername(userId);
        LocalDateTime threshold = LocalDateTime.now().minusMonths(6);
        return cacheService.findFreshProfile(normalized, threshold)
                .map(cached -> {
                    LOGGER.info("Instagram cache hit: {}", normalized);
                    return cached;
                })
                .orElseGet(() -> {
                    LOGGER.info("Instagram cache miss: {}", normalized);
                    return fetchAndCache(normalized);
                });
    }

    public InstagramSearchResponse searchKeyword(String query) {
        if (query == null || query.isBlank()) {
            return emptySearchResponse(query);
        }
        if (sessionId.isBlank()) {
            LOGGER.warn("Instagram keyword search skipped (missing session id): {}", query);
            return emptySearchResponse(query);
        }
        String normalized = query.trim();
        String encodedQuery = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        URI uri = URI.create(String.format(KEYWORD_SEARCH_ENDPOINT, encodedQuery));
        String referer = "https://www.instagram.com/explore/search/keyword/?q=" + encodedQuery;
        HttpRequest request = baseRequest(uri, referer)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return emptySearchResponse(normalized);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return emptySearchResponse(normalized);
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            List<InstagramSearchHashtag> hashtags = parseHashtagResults(root);
            List<InstagramSearchUser> users = parseUserResults(root);
            List<InstagramSearchPlace> places = parsePlaceResults(root);
            String status = textValue(root, "status", "");
            LOGGER.info(
                    "Instagram keyword search: {} (hashtags={}, users={}, places={})",
                    normalized,
                    hashtags.size(),
                    users.size(),
                    places.size());
            return new InstagramSearchResponse(normalized, status, hashtags, users, places);
        } catch (Exception ignored) {
            return emptySearchResponse(normalized);
        }
    }

    public List<InstagramSearchUser> searchKeywordUsers(String query) {
        InstagramSearchResponse response = searchKeyword(query);
        return response == null || response.users() == null ? List.of() : response.users();
    }

    public List<InstagramSearchUser> searchKeywordUsersExpanded(
            String query,
            int maxUsers,
            int feedCount,
            int pages) {
        List<InstagramSearchUser> base = searchKeywordUsers(query);
        if (query == null || query.isBlank()) {
            return base;
        }
        List<InstagramSearchUser> fbSearchUsers = fetchFbSearchUsers(query, pages);
        String tagName = normalizeTagName(query);
        if (tagName.isBlank()) {
            return mergeSearchUsers(base, fbSearchUsers, maxUsers);
        }
        List<InstagramSearchUser> feedUsers = fetchHashtagFeedUsers(tagName, feedCount);
        List<InstagramSearchUser> merged = mergeSearchUsers(base, fbSearchUsers, maxUsers);
        return mergeSearchUsers(merged, feedUsers, maxUsers);
    }

    public InstagramSearchUsersPage searchKeywordUsersPage(
            String query,
            String nextMaxId,
            String searchSessionId,
            String rankToken) {
        if (query == null || query.isBlank()) {
            return emptyUsersPage(query, rankToken);
        }
        if (sessionId.isBlank()) {
            LOGGER.warn("Instagram fbsearch page skipped (missing session id): {}", query);
            return emptyUsersPage(query, rankToken);
        }
        String normalized = query.trim();
        String encodedQuery = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        String referer = "https://www.instagram.com/explore/search/keyword/?q=" + encodedQuery;
        String resolvedRankToken = (rankToken == null || rankToken.isBlank())
                ? UUID.randomUUID().toString()
                : rankToken.trim();
        URI uri = buildFbSearchUri(encodedQuery, nextMaxId, resolvedRankToken, searchSessionId);
        JsonNode root = fetchJson(uri, referer);
        if (root == null) {
            return new InstagramSearchUsersPage(
                    normalized,
                    List.of(),
                    nextMaxId,
                    searchSessionId,
                    resolvedRankToken,
                    false);
        }
        List<InstagramSearchUser> users = parseFbSearchUsers(root);
        String resolvedSession = searchSessionId;
        String sessionFromResponse = textValue(root, "search_session_id", "");
        if (sessionFromResponse != null && !sessionFromResponse.isBlank()) {
            resolvedSession = sessionFromResponse;
        }
        String cursor = readCursor(root);
        boolean hasMore = hasMore(root) && cursor != null && !cursor.isBlank();
        return new InstagramSearchUsersPage(
                normalized,
                users,
                cursor,
                resolvedSession,
                resolvedRankToken,
                hasMore);
    }

    private InstagramProfile fetchProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String normalized = normalizeUsername(userId);
        LocalDateTime threshold = LocalDateTime.now().minusMonths(6);
        return cacheService.findFreshProfile(normalized, threshold)
                .map(InstagramProfileWithPosts::profile)
                .orElseGet(() -> {
                    InstagramProfileWithPosts fetched = fetchAndCache(normalized);
                    return fetched.profile();
                });
    }

    public InstagramProfileWithPosts fetchCachedProfileWithPosts(String userId) {
        if (userId == null || userId.isBlank()) {
            return new InstagramProfileWithPosts(null, List.of());
        }
        String normalized = normalizeUsername(userId);
        return cacheService.findProfile(normalized)
                .map(cached -> {
                    LOGGER.info("Instagram cache read: {}", normalized);
                    return cached;
                })
                .orElseGet(() -> {
                    LOGGER.info("Instagram cache empty: {}", normalized);
                    return new InstagramProfileWithPosts(null, List.of());
                });
    }

    private InstagramProfileWithPosts fetchAndCache(String userId) {
        JsonNode user = fetchUserNodeForUsername(userId);
        if (user == null || user.isNull()) {
            LOGGER.warn("Instagram fetch failed: {}", userId);
            return new InstagramProfileWithPosts(null, List.of());
        }
        InstagramProfile profile = buildProfile(user, userId);
        List<InstagramPost> posts = fetchPosts(user, userId);
        InstagramProfileWithPosts saved = cacheService.saveProfileWithPosts(profile, posts);
        LOGGER.info("Instagram cache stored: {} (posts={})", userId, posts.size());
        return saved;
    }

    private JsonNode fetchUserNodeForUsername(String userId) {
        if (userId == null || userId.isBlank() || sessionId.isBlank()) {
            return null;
        }
        URI uri = URI.create(String.format(PROFILE_ENDPOINT, userId));
        HttpRequest request = baseRequest(uri, "https://www.instagram.com/" + userId + "/")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            return extractUserNode(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<InstagramPost> fetchPosts(JsonNode user, String username) {
        if (user == null || user.isNull() || maxPosts <= 0 || sessionId.isBlank()) {
            return List.of();
        }
        String userId = textValue(user, "id");
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        URI uri = URI.create(String.format(USER_FEED_ENDPOINT, userId, maxPosts));
        HttpRequest request = baseRequest(uri, "https://www.instagram.com/" + username + "/")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return List.of();
            }
            List<InstagramPost> posts = new ArrayList<>();
            for (JsonNode item : items) {
                if (posts.size() >= maxPosts) {
                    break;
                }
                InstagramPost post = parsePost(item);
                if (post != null) {
                    posts.add(post);
                }
            }
            return posts;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private InstagramProfile buildProfile(JsonNode user, String fallbackUsername) {
        return new InstagramProfile(
                textValue(user, "biography"),
                textValue(user, "category_name"),
                textValue(user, "external_url"),
                intValue(user.path("edge_followed_by"), "count"),
                intValue(user.path("edge_follow"), "count"),
                textValue(user, "full_name"),
                booleanValue(user, "is_private"),
                booleanValue(user, "is_verified"),
                intValue(user.path("edge_owner_to_timeline_media"), "count"),
                profileImage(user),
                LocalDateTime.now().format(FORMATTER),
                textValue(user, "username", fallbackUsername));
    }

    private InstagramPost parsePost(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        String postId = textValue(item, "id", textValue(item, "pk"));
        if (postId == null || postId.isBlank()) {
            return null;
        }
        String shortcode = textValue(item, "code", textValue(item, "shortcode", ""));
        String caption = "";
        JsonNode captionNode = item.path("caption");
        if (captionNode.isObject()) {
            caption = textValue(captionNode, "text", "");
        }
        String takenAt = formatTakenAt(item.path("taken_at").asLong(0));

        JsonNode baseMedia = item;
        if (item.path("media_type").asInt() == 8
                && item.path("carousel_media").isArray()
                && item.path("carousel_media").size() > 0) {
            baseMedia = item.path("carousel_media").get(0);
        }
        String thumbnailUrl = firstCandidateUrl(baseMedia);
        String displayUrl = (thumbnailUrl == null || thumbnailUrl.isBlank())
                ? firstCandidateUrl(item)
                : thumbnailUrl;
        boolean isVideo = hasVideo(baseMedia);
        Integer videoViews = firstNonNull(
                intValueNullable(baseMedia, "play_count"),
                intValueNullable(baseMedia, "view_count"),
                intValueNullable(item, "play_count"),
                intValueNullable(item, "view_count"));
        Integer likeCount = intValueNullable(item, "like_count");
        Integer commentCount = intValueNullable(item, "comment_count");
        String permalink = shortcode.isBlank()
                ? ""
                : "https://www.instagram.com/p/" + shortcode + "/";
        String mediaType = textValue(item, "product_type");
        if (mediaType == null) {
            mediaType = textValue(item, "media_type");
        }

        return new InstagramPost(
                postId,
                shortcode,
                caption,
                thumbnailUrl,
                displayUrl,
                permalink,
                isVideo,
                videoViews,
                likeCount,
                commentCount,
                takenAt,
                mediaType);
    }

    private static JsonNode extractUserNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode graphql = root.path("graphql");
        if (!graphql.isMissingNode()) {
            JsonNode user = graphql.path("user");
            if (!user.isMissingNode()) {
                return user;
            }
        }
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            JsonNode user = data.path("user");
            if (!user.isMissingNode()) {
                return user;
            }
        }
        return null;
    }

    private static String textValue(JsonNode node, String field) {
        return textValue(node, field, null);
    }

    private static String textValue(JsonNode node, String field, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static int intValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return 0;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static boolean booleanValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return false;
        }
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.asBoolean();
    }

    private static Integer intValueNullable(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String formatTakenAt(long epochSeconds) {
        if (epochSeconds <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(epochSeconds)
                .atOffset(ZoneOffset.UTC)
                .format(ISO_FORMATTER);
    }

    private static String firstCandidateUrl(JsonNode media) {
        if (media == null || media.isNull()) {
            return null;
        }
        JsonNode candidates = media.path("image_versions2").path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                String url = textValue(candidate, "url");
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        String url = textValue(media, "thumbnail_url");
        if (url != null && !url.isBlank()) {
            return url;
        }
        return textValue(media, "display_url");
    }

    private static boolean hasVideo(JsonNode media) {
        if (media == null || media.isNull()) {
            return false;
        }
        JsonNode versions = media.path("video_versions");
        return versions.isArray() && versions.size() > 0;
    }

    private static Integer firstNonNull(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static List<InstagramSearchHashtag> parseHashtagResults(JsonNode root) {
        JsonNode items = root.path("hashtags");
        if (!items.isArray()) {
            return List.of();
        }
        List<InstagramSearchHashtag> results = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode hashtag = item.path("hashtag");
            if (hashtag.isMissingNode() || hashtag.isNull()) {
                continue;
            }
            String name = textValue(hashtag, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            results.add(new InstagramSearchHashtag(
                    name,
                    textValue(hashtag, "id"),
                    intValueNullable(hashtag, "media_count"),
                    textValue(hashtag, "profile_pic_url"),
                    textValue(hashtag, "search_result_subtitle", textValue(hashtag, "subtitle"))));
        }
        return results;
    }

    private static List<InstagramSearchUser> parseUserResults(JsonNode root) {
        JsonNode items = root.path("users");
        List<InstagramSearchUser> results = new ArrayList<>();
        appendUserResults(items, results);
        return results;
    }

    private static List<InstagramSearchPlace> parsePlaceResults(JsonNode root) {
        JsonNode items = root.path("places");
        if (!items.isArray()) {
            return List.of();
        }
        List<InstagramSearchPlace> results = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode place = item.path("place");
            if (place.isMissingNode() || place.isNull()) {
                continue;
            }
            String title = textValue(place, "title", textValue(place, "name"));
            if (title == null || title.isBlank()) {
                continue;
            }
            results.add(new InstagramSearchPlace(
                    textValue(place, "location_id", textValue(place, "pk", textValue(place, "id"))),
                    title,
                    textValue(place, "subtitle"),
                    textValue(place, "address")));
        }
        return results;
    }

    private List<InstagramSearchUser> fetchFbSearchUsers(String query, int pages) {
        if (sessionId.isBlank()) {
            LOGGER.warn("Instagram fbsearch skipped (missing session id): {}", query);
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.trim();
        int resolvedPages = clamp(pages, 1, 10);
        String encodedQuery = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        String referer = "https://www.instagram.com/explore/search/keyword/?q=" + encodedQuery;
        String rankToken = UUID.randomUUID().toString();
        String searchSessionId = "";
        String nextMaxId = null;
        List<InstagramSearchUser> results = new ArrayList<>();

        for (int page = 0; page < resolvedPages; page++) {
            URI uri = buildFbSearchUri(encodedQuery, nextMaxId, rankToken, searchSessionId);
            JsonNode root = fetchJson(uri, referer);
            if (root == null) {
                break;
            }
            results.addAll(parseFbSearchUsers(root));
            String sessionFromResponse = textValue(root, "search_session_id", "");
            if (sessionFromResponse != null && !sessionFromResponse.isBlank()) {
                searchSessionId = sessionFromResponse;
            }
            String cursor = readCursor(root);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
            nextMaxId = cursor;
            if (!hasMore(root)) {
                break;
            }
        }
        return results;
    }

    private static URI buildFbSearchUri(
            String encodedQuery,
            String nextMaxId,
            String rankToken,
            String searchSessionId) {
        StringBuilder url = new StringBuilder(String.format(FBSEARCH_TOP_SERP_ENDPOINT, encodedQuery));
        if (searchSessionId != null && !searchSessionId.isBlank()) {
            url.append("&search_session_id=")
                    .append(URLEncoder.encode(searchSessionId, StandardCharsets.UTF_8));
        }
        if (nextMaxId != null && !nextMaxId.isBlank()) {
            url.append("&next_max_id=")
                    .append(URLEncoder.encode(nextMaxId, StandardCharsets.UTF_8));
        }
        if (rankToken != null && !rankToken.isBlank()) {
            url.append("&rank_token=")
                    .append(URLEncoder.encode(rankToken, StandardCharsets.UTF_8));
        }
        return URI.create(url.toString());
    }

    private static List<InstagramSearchUser> parseFbSearchUsers(JsonNode root) {
        List<InstagramSearchUser> results = new ArrayList<>();
        appendUserResults(root.path("users"), results);
        appendUserResults(root.path("results"), results);
        appendUserResults(root.path("items"), results);
        appendUserResults(root.path("top_results"), results);
        appendUserResults(root.path("data").path("users"), results);
        appendUserResults(root.path("data").path("results"), results);
        return results;
    }

    private static void appendUserResults(JsonNode items, List<InstagramSearchUser> results) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            InstagramSearchUser parsed = parseUserFromItem(item);
            if (parsed != null) {
                results.add(parsed);
            }
        }
    }

    private static InstagramSearchUser parseUserFromItem(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        JsonNode user = item.path("user");
        if (!user.isObject()) {
            user = item.path("user").path("user");
        }
        if (!user.isObject() && item.isObject()) {
            user = item;
        }
        return toSearchUser(user);
    }

    private static String readCursor(JsonNode root) {
        String nextMaxId = textValue(root, "next_max_id", "");
        if (nextMaxId != null && !nextMaxId.isBlank()) {
            return nextMaxId;
        }
        JsonNode pageInfo = root.path("page_info");
        String endCursor = textValue(pageInfo, "end_cursor", "");
        if (endCursor != null && !endCursor.isBlank()) {
            return endCursor;
        }
        return null;
    }

    private static boolean hasMore(JsonNode root) {
        JsonNode pageInfo = root.path("page_info");
        if (pageInfo.isObject() && pageInfo.has("has_next_page")) {
            return pageInfo.path("has_next_page").asBoolean(false);
        }
        return booleanValue(root, "has_more");
    }

    private List<InstagramSearchUser> fetchHashtagFeedUsers(String tagName, int feedCount) {
        if (sessionId.isBlank()) {
            LOGGER.warn("Instagram hashtag feed skipped (missing session id): {}", tagName);
            return List.of();
        }
        String normalized = normalizeTagName(tagName);
        if (normalized.isBlank()) {
            return List.of();
        }
        int resolvedCount = clamp(feedCount, 1, 50);
        String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        String referer = "https://www.instagram.com/explore/tags/" + encoded + "/";
        URI uri = URI.create(String.format(HASHTAG_SECTIONS_ENDPOINT, encoded, resolvedCount));
        JsonNode root = fetchJson(uri, referer);
        if (root == null) {
            URI fallback = URI.create(String.format(HASHTAG_WEB_INFO_ENDPOINT, encoded));
            root = fetchJson(fallback, referer);
        }
        if (root == null) {
            return List.of();
        }
        List<JsonNode> mediaNodes = collectMediaNodesFromTagResponse(root);
        return collectUsersFromMediaNodes(mediaNodes);
    }

    private JsonNode fetchJson(URI uri, String referer) {
        HttpRequest request = baseRequest(uri, referer)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<JsonNode> collectMediaNodesFromTagResponse(JsonNode root) {
        List<JsonNode> medias = new ArrayList<>();
        collectMediaFromSections(root.path("sections"), medias);
        JsonNode data = root.path("data");
        collectMediaFromSections(data.path("top").path("sections"), medias);
        collectMediaFromSections(data.path("recent").path("sections"), medias);
        appendMediaFromArray(root.path("items"), medias);
        return medias;
    }

    private static void collectMediaFromSections(JsonNode sections, List<JsonNode> medias) {
        if (!sections.isArray()) {
            return;
        }
        for (JsonNode section : sections) {
            collectMediaFromLayout(section.path("layout_content"), medias);
            collectMediaFromLayout(section, medias);
        }
    }

    private static void collectMediaFromLayout(JsonNode layout, List<JsonNode> medias) {
        appendMediaFromArray(layout.path("medias"), medias);
        appendMediaFromArray(layout.path("fill_items"), medias);
    }

    private static void appendMediaFromArray(JsonNode items, List<JsonNode> medias) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            JsonNode media = item.path("media");
            if (media.isMissingNode() || media.isNull()) {
                media = item.path("media_or_ad");
            }
            if (media.isMissingNode() || media.isNull()) {
                media = item;
            }
            if (media.isObject()) {
                medias.add(media);
            }
        }
    }

    private static List<InstagramSearchUser> collectUsersFromMediaNodes(List<JsonNode> medias) {
        List<InstagramSearchUser> results = new ArrayList<>();
        for (JsonNode media : medias) {
            if (media == null || media.isNull()) {
                continue;
            }
            JsonNode user = media.path("user");
            if (!user.isObject()) {
                user = media.path("owner");
            }
            InstagramSearchUser parsed = toSearchUser(user);
            if (parsed != null) {
                results.add(parsed);
            }
        }
        return results;
    }

    private static InstagramSearchUser toSearchUser(JsonNode user) {
        if (user == null || user.isNull() || !user.isObject()) {
            return null;
        }
        String username = textValue(user, "username");
        if (username == null || username.isBlank()) {
            return null;
        }
        return new InstagramSearchUser(
                textValue(user, "pk", textValue(user, "id")),
                username,
                textValue(user, "full_name"),
                textValue(user, "profile_pic_url"),
                booleanValue(user, "is_private"),
                booleanValue(user, "is_verified"),
                intValueNullable(user, "follower_count"));
    }

    private static List<InstagramSearchUser> mergeSearchUsers(
            List<InstagramSearchUser> base,
            List<InstagramSearchUser> extra,
            int maxUsers) {
        int limit = maxUsers > 0 ? maxUsers : Integer.MAX_VALUE;
        LinkedHashMap<String, InstagramSearchUser> merged = new LinkedHashMap<>();
        appendUsers(merged, base, limit);
        appendUsers(merged, extra, limit);
        return new ArrayList<>(merged.values());
    }

    private static void appendUsers(
            LinkedHashMap<String, InstagramSearchUser> merged,
            List<InstagramSearchUser> source,
            int limit) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (InstagramSearchUser user : source) {
            if (user == null) {
                continue;
            }
            String key = user.username();
            if (key == null || key.isBlank()) {
                key = user.id();
            }
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!merged.containsKey(key)) {
                merged.put(key, user);
            }
            if (merged.size() >= limit) {
                return;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static InstagramSearchUsersPage emptyUsersPage(String query, String rankToken) {
        String normalized = query == null ? "" : query.trim();
        String resolvedRankToken = (rankToken == null || rankToken.isBlank())
                ? UUID.randomUUID().toString()
                : rankToken.trim();
        return new InstagramSearchUsersPage(
                normalized,
                List.of(),
                null,
                "",
                resolvedRankToken,
                false);
    }

    private static String normalizeTagName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int tagIndex = lower.indexOf("/explore/tags/");
        if (tagIndex >= 0) {
            int start = tagIndex + "/explore/tags/".length();
            String sliced = trimmed.substring(start);
            int end = sliced.indexOf('/');
            if (end > -1) {
                sliced = sliced.substring(0, end);
            }
            int queryIndex = sliced.indexOf('?');
            if (queryIndex > -1) {
                sliced = sliced.substring(0, queryIndex);
            }
            trimmed = sliced;
        }
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        if (space > -1) {
            trimmed = trimmed.substring(0, space);
        }
        return trimmed.trim();
    }

    private HttpRequest.Builder baseRequest(URI uri, String referer) {
        return HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-IG-App-ID", WEB_APP_ID)
                .header("Referer", referer);
    }

    private HttpClient buildHttpClient() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        if (!sessionId.isBlank()) {
            CookieStore store = cookieManager.getCookieStore();
            HttpCookie cookie = new HttpCookie("sessionid", sessionId);
            cookie.setDomain(".instagram.com");
            cookie.setPath("/");
            cookie.setSecure(true);
            cookie.setHttpOnly(true);
            store.add(URI.create("https://www.instagram.com"), cookie);
        }
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .build();
    }

    private static String profileImage(JsonNode user) {
        String image = textValue(user, "profile_pic_url_hd");
        if (image != null) {
            return image;
        }
        return textValue(user, "profile_pic_url");
    }

    private static String normalizeUsername(String userId) {
        return userId.trim().toLowerCase(Locale.ROOT);
    }

    private static InstagramSearchResponse emptySearchResponse(String query) {
        String normalized = query == null ? "" : query.trim();
        return new InstagramSearchResponse(normalized, "", List.of(), List.of(), List.of());
    }
}
