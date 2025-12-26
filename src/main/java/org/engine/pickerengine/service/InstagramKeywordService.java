package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramKeywordPromptResponse;
import org.engine.pickerengine.dto.InstagramKeywordResponse;
import org.engine.pickerengine.dto.InstagramPost;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class InstagramKeywordService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_PROMPT_VERSION = "v2";

    private final InstagramService instagramService;
    private final InstagramPromptService promptService;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final int postLimit;
    private final Duration timeout;
    private final String defaultPromptVersion;

    public InstagramKeywordService(
            InstagramService instagramService,
            InstagramPromptService promptService,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${instagram.keyword-post-limit:10}") int postLimit,
            @Value("${instagram.keyword-prompt-version:" + DEFAULT_PROMPT_VERSION + "}") String promptVersion,
            @Value("${openai.timeout-seconds:20}") int timeoutSeconds) {
        this.instagramService = instagramService;
        this.promptService = promptService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.postLimit = Math.max(0, postLimit);
        this.defaultPromptVersion = promptVersion == null || promptVersion.isBlank()
                ? DEFAULT_PROMPT_VERSION
                : promptVersion.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public InstagramKeywordResponse extractKeywords(String userId, String version) {
        if (userId == null || userId.isBlank() || apiKey.isBlank()) {
            return emptyResponse();
        }
        InstagramProfileWithPosts data = instagramService.fetchProfileWithPosts(userId);
        if (data == null || data.profile() == null) {
            return emptyResponse();
        }
        return callModel(data, version);
    }

    public InstagramKeywordPromptResponse buildPromptPreview(String userId, String version) {
        String resolved = resolvePromptVersion(version);
        if (userId == null || userId.isBlank()) {
            return new InstagramKeywordPromptResponse(resolved, "", promptService.loadTemplateRaw(resolved));
        }
        InstagramProfileWithPosts data = instagramService.fetchProfileWithPosts(userId);
        if (data == null || data.profile() == null) {
            return new InstagramKeywordPromptResponse(resolved, "", promptService.loadTemplateRaw(resolved));
        }
        String prompt = promptService.buildPrompt(data, postLimit, resolved);
        return new InstagramKeywordPromptResponse(resolved, prompt, promptService.loadTemplateRaw(resolved));
    }

    private InstagramKeywordResponse callModel(InstagramProfileWithPosts data, String version) {
        try {
            ObjectNode payload = buildPayload(data, version);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return emptyResponse();
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            String text = extractOutputText(root);
            return parseKeywordResponse(text);
        } catch (Exception ignored) {
            return emptyResponse();
        }
    }

    private ObjectNode buildPayload(InstagramProfileWithPosts data, String version) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", model);

        ArrayNode input = payload.putArray("input");
        ObjectNode userNode = input.addObject();
        userNode.put("role", "user");
        ArrayNode content = userNode.putArray("content");

        content.addObject()
                .put("type", "input_text")
                .put("text", promptService.buildPrompt(data, postLimit, resolvePromptVersion(version)));

        for (String imageUrl : collectImageUrls(data)) {
            content.addObject()
                    .put("type", "input_image")
                    .put("image_url", imageUrl);
        }
        return payload;
    }


    private List<String> collectImageUrls(InstagramProfileWithPosts data) {
        Set<String> urls = new LinkedHashSet<>();
        InstagramProfile profile = data.profile();
        if (profile != null && profile.profilePicUrl() != null && !profile.profilePicUrl().isBlank()) {
            urls.add(profile.profilePicUrl());
        }
        int count = 0;
        for (InstagramPost post : data.posts()) {
            if (count >= postLimit) {
                break;
            }
            String image = post.thumbnailUrl();
            if (image == null || image.isBlank()) {
                image = post.displayUrl();
            }
            if (image != null && !image.isBlank()) {
                urls.add(image);
                count++;
            }
        }
        return new ArrayList<>(urls);
    }

    private String extractOutputText(JsonNode root) {
        if (root == null || root.isNull()) {
            return "";
        }
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : output) {
            JsonNode contents = item.path("content");
            if (!contents.isArray()) {
                continue;
            }
            for (JsonNode content : contents) {
                if ("output_text".equals(content.path("type").asText())) {
                    builder.append(content.path("text").asText(""));
                }
            }
        }
        return builder.toString();
    }

    private InstagramKeywordResponse parseKeywordResponse(String text) {
        if (text == null || text.isBlank()) {
            return emptyResponse();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(text);
            return parseKeywordNode(root);
        } catch (Exception ignored) {
            int objStart = text.indexOf('{');
            int objEnd = text.lastIndexOf('}');
            if (objStart >= 0 && objEnd > objStart) {
                String json = text.substring(objStart, objEnd + 1);
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(json);
                    return parseKeywordNode(root);
                } catch (Exception ignoredAgain) {
                    return emptyResponse();
                }
            }
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String json = text.substring(start, end + 1);
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(json);
                    return parseKeywordNode(root);
                } catch (Exception ignoredAgain) {
                    return emptyResponse();
                }
            }
            return emptyResponse();
        }
    }

    private InstagramKeywordResponse parseKeywordNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return emptyResponse();
        }
        if (root.isArray()) {
            return new InstagramKeywordResponse(parseStringList(root), List.of());
        }
        List<String> keywords = parseStringList(root.path("keywords"));
        List<String> categories = parseStringList(root.path("category"));
        return new InstagramKeywordResponse(keywords, categories);
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                String text = item.asText();
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private InstagramKeywordResponse emptyResponse() {
        return new InstagramKeywordResponse(List.of(), List.of());
    }

    private String resolvePromptVersion(String version) {
        if (version == null || version.isBlank()) {
            return defaultPromptVersion;
        }
        return version.trim();
    }
}
