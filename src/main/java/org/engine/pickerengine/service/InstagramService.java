package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramPost;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class InstagramService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstagramService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String PROFILE_ENDPOINT =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=%s";
    private static final String USER_FEED_ENDPOINT =
            "https://www.instagram.com/api/v1/feed/user/%s/?count=%d";
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
}
