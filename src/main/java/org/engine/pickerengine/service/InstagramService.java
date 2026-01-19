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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final long httpRateLimitMs;
    private final int httpMaxConcurrency;
    private final int httpMaxRetries;
    private final long httpBackoffMs;
    private final long httpMaxBackoffMs;
    private final int httpCircuitBreakerThreshold;
    private final long httpCircuitBreakerCooldownMs;
    private final long httpTtlCacheMs;
    private final HttpClient httpClient;
    private final InstagramCacheService cacheService;
    private final AtomicLong httpCooldownUntil = new AtomicLong(0);
    private final AtomicInteger consecutive429 = new AtomicInteger(0);
    private final InstagramHttpRateLimiter httpRateLimiter;
    private final SimpleTtlCache<String, JsonNode> userNodeCache;
    private final SimpleTtlCache<String, List<InstagramPost>> userPostsCache;

    public InstagramService(
            @Value("${instagram.sessionid:}") String sessionId,
            @Value("${instagram.max-posts:18}") int maxPosts,
            @Value("${instagram.http.rate-limit-ms:1000}") long httpRateLimitMs,
            @Value("${instagram.http.max-concurrency:1}") int httpMaxConcurrency,
            @Value("${instagram.http.max-retries:2}") int httpMaxRetries,
            @Value("${instagram.http.backoff-ms:30000}") long httpBackoffMs,
            @Value("${instagram.http.max-backoff-ms:180000}") long httpMaxBackoffMs,
            @Value("${instagram.http.circuit-breaker.threshold:3}") int httpCircuitBreakerThreshold,
            @Value("${instagram.http.circuit-breaker.cooldown-ms:120000}") long httpCircuitBreakerCooldownMs,
            @Value("${instagram.http.ttl-cache-ms:0}") long httpTtlCacheMs,
            InstagramCacheService cacheService) {
        String resolved = sessionId;
        if (resolved == null || resolved.isBlank()) {
            resolved = System.getenv("IG_SESSIONID");
        }
        this.sessionId = resolved == null ? "" : resolved.trim();
        this.maxPosts = Math.max(0, maxPosts);
        this.httpRateLimitMs = Math.max(0, httpRateLimitMs);
        this.httpMaxConcurrency = Math.max(1, httpMaxConcurrency);
        this.httpMaxRetries = Math.max(0, httpMaxRetries);
        this.httpBackoffMs = Math.max(0, httpBackoffMs);
        this.httpMaxBackoffMs = Math.max(this.httpBackoffMs, httpMaxBackoffMs);
        this.httpCircuitBreakerThreshold = Math.max(0, httpCircuitBreakerThreshold);
        this.httpCircuitBreakerCooldownMs = Math.max(0, httpCircuitBreakerCooldownMs);
        this.httpTtlCacheMs = Math.max(0, httpTtlCacheMs);
        this.httpClient = buildHttpClient();
        this.cacheService = cacheService;
        this.httpRateLimiter = new InstagramHttpRateLimiter(this.httpMaxConcurrency, this.httpRateLimitMs);
        this.userNodeCache = this.httpTtlCacheMs > 0 ? new SimpleTtlCache<>(this.httpTtlCacheMs) : null;
        this.userPostsCache = this.httpTtlCacheMs > 0 ? new SimpleTtlCache<>(this.httpTtlCacheMs) : null;
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

    public String fetchAccountId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String normalized = normalizeUsername(userId);
        JsonNode user = fetchUserNodeForUsername(normalized);
        return textValue(user, "id");
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
            HttpResponse<String> response = sendWithBackoff(request);
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


    public List<InstagramSearchUser> searchKeywordUsersExpanded(
            String query,
            int pages) {

        return fetchFbSearchUsers(query, pages);
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
        JsonNode cached = getCachedUserNode(userId);
        if (cached != null) {
            return cached;
        }
        URI uri = URI.create(String.format(PROFILE_ENDPOINT, userId));
        HttpRequest request = baseRequest(uri, "https://www.instagram.com/" + userId + "/")
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithBackoff(request);
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode user = extractUserNode(root);
            cacheUserNode(userId, user);
            return user;
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
        List<InstagramPost> cached = getCachedUserPosts(userId);
        if (cached != null) {
            return cached;
        }
        URI uri = URI.create(String.format(USER_FEED_ENDPOINT, userId, maxPosts));
        HttpRequest request = baseRequest(uri, "https://www.instagram.com/" + username + "/")
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithBackoff(request);
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
            cacheUserPosts(userId, posts);
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
            HttpResponse<String> response = sendWithBackoff(request);
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

    private JsonNode getCachedUserNode(String userId) {
        if (userNodeCache == null) {
            return null;
        }
        return userNodeCache.get(userId);
    }

    private void cacheUserNode(String userId, JsonNode user) {
        if (userNodeCache == null || user == null) {
            return;
        }
        userNodeCache.put(userId, user);
    }

    private List<InstagramPost> getCachedUserPosts(String userId) {
        if (userPostsCache == null) {
            return null;
        }
        return userPostsCache.get(userId);
    }

    private void cacheUserPosts(String userId, List<InstagramPost> posts) {
        if (userPostsCache == null || posts == null) {
            return;
        }
        userPostsCache.put(userId, posts);
    }

    private HttpRequest.Builder baseRequest(URI uri, String referer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-IG-App-ID", WEB_APP_ID)
                .header("Referer", referer);
        if (!sessionId.isBlank()) {
            builder.header("Cookie", "sessionid=" + sessionId);
        }
        return builder;
    }

    private HttpResponse<String> sendWithBackoff(HttpRequest request) throws Exception {
        int attempt = 0;
        int maxAttempts = httpMaxRetries + 1;
        while (true) {
            waitForCooldown();
            HttpResponse<String> response;
            boolean acquired = false;
            try {
                httpRateLimiter.acquire();
                acquired = true;
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } finally {
                if (acquired) {
                    httpRateLimiter.release();
                }
            }
            int status = response.statusCode();
            if (status != 429 && status < 500) {
                consecutive429.set(0);
                return response;
            }
            boolean is429 = status == 429;
            if (is429) {
                int streak = consecutive429.incrementAndGet();
                if (httpCircuitBreakerThreshold > 0 && streak >= httpCircuitBreakerThreshold) {
                    applyCooldown(httpCircuitBreakerCooldownMs);
                }
            } else {
                consecutive429.set(0);
            }
            if (attempt >= httpMaxRetries) {
                return response;
            }
            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse("");
            long retryAfterMs = InstagramHttpBackoffPolicy.parseRetryAfterMs(retryAfterHeader);
            long delayMs = InstagramHttpBackoffPolicy.computeDelayMs(
                    retryAfterHeader,
                    attempt,
                    httpBackoffMs,
                    httpMaxBackoffMs);
            if (is429 && delayMs < 1000) {
                delayMs = 1000;
            }
            if (is429) {
                LOGGER.warn(
                        "Instagram 429 rate limit (endpoint={}, retryCount={}, waitMs={}, retryAfter={})",
                        request.uri(),
                        attempt + 1,
                        delayMs,
                        retryAfterMs > 0);
            }
            applyCooldown(delayMs);
            attempt += 1;
        }
    }

    private void waitForCooldown() {
        long now = System.currentTimeMillis();
        long until = httpCooldownUntil.get();
        if (until > now) {
            sleepMillis(until - now);
        }
    }

    private void applyCooldown(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        long target = System.currentTimeMillis() + delayMs;
        httpCooldownUntil.updateAndGet(current -> Math.max(current, target));
    }

    private static final class InstagramHttpRateLimiter {
        private final Semaphore semaphore;
        private final long minIntervalMs;
        private final Object intervalLock = new Object();
        private long lastRequestAt;

        private InstagramHttpRateLimiter(int maxConcurrency, long minIntervalMs) {
            this.semaphore = new Semaphore(Math.max(1, maxConcurrency), true);
            this.minIntervalMs = Math.max(0, minIntervalMs);
        }

        private void acquire() throws InterruptedException {
            semaphore.acquire();
            if (minIntervalMs <= 0) {
                return;
            }
            try {
                synchronized (intervalLock) {
                    long now = System.currentTimeMillis();
                    long waitMs = lastRequestAt + minIntervalMs - now;
                    if (waitMs > 0) {
                        Thread.sleep(waitMs);
                    }
                    lastRequestAt = System.currentTimeMillis();
                }
            } catch (InterruptedException exception) {
                semaphore.release();
                throw exception;
            }
        }

        private void release() {
            semaphore.release();
        }
    }

    private static final class SimpleTtlCache<K, V> {
        private final long ttlMs;
        private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

        private SimpleTtlCache(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        private V get(K key) {
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAt <= System.currentTimeMillis()) {
                entries.remove(key, entry);
                return null;
            }
            return entry.value;
        }

        private void put(K key, V value) {
            if (value == null) {
                return;
            }
            long expiresAt = System.currentTimeMillis() + ttlMs;
            entries.put(key, new Entry<>(value, expiresAt));
        }

        private static final class Entry<V> {
            private final V value;
            private final long expiresAt;

            private Entry(V value, long expiresAt) {
                this.value = value;
                this.expiresAt = expiresAt;
            }
        }
    }

    private void sleepMillis(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
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
