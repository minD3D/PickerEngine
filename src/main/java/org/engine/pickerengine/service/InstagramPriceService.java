package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramPricePromptResponse;
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

@Service
public class InstagramPriceService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_PROMPT_VERSION = "v1";

    private final InstagramPricePromptService promptService;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String defaultPromptVersion;

    public InstagramPriceService(
            InstagramPricePromptService promptService,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${instagram.price-prompt-version:" + DEFAULT_PROMPT_VERSION + "}") String promptVersion,
            @Value("${openai.timeout-seconds:20}") int timeoutSeconds) {
        this.promptService = promptService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.defaultPromptVersion = promptVersion == null || promptVersion.isBlank()
                ? DEFAULT_PROMPT_VERSION
                : promptVersion.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String extractPrices(String text, String imageUrl, String version, String customPrompt) {
        if (apiKey.isBlank()) {
            return "";
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        if (!hasText && !hasImage) {
            return "";
        }
        String resolvedVersion = resolvePromptVersion(version);
        String prompt = promptService.buildPromptFromTemplate(text, resolveTemplate(resolvedVersion, customPrompt));
        return callModel(prompt, imageUrl);
    }

    public InstagramPricePromptResponse buildPromptPreview(String text, String version, String customPrompt) {
        String resolved = resolvePromptVersion(version);
        String template = resolveTemplate(resolved, customPrompt);
        String prompt = promptService.buildPromptFromTemplate(text, template);
        return new InstagramPricePromptResponse(resolved, prompt, template);
    }

    private String callModel(String prompt, String imageUrl) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        try {
            ObjectNode payload = buildPayload(prompt, imageUrl);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            return extractOutputText(root);
        } catch (Exception ignored) {
            return "";
        }
    }

    private ObjectNode buildPayload(String prompt, String imageUrl) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", model);
        ArrayNode input = payload.putArray("input");
        ObjectNode userNode = input.addObject();
        userNode.put("role", "user");
        ArrayNode content = userNode.putArray("content");
        content.addObject()
                .put("type", "input_text")
                .put("text", prompt);
        if (imageUrl != null && !imageUrl.isBlank()) {
            content.addObject()
                    .put("type", "input_image")
                    .put("image_url", imageUrl.trim());
        }
        return payload;
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

    private JsonNode parsePriceResponse(String text) {
        if (text == null || text.isBlank()) {
            return emptyResponse();
        }
        String cleaned = stripBom(text).trim();
        JsonNode root = tryParseJson(cleaned);
        if (root != null) {
            return normalizeResponse(root);
        }
        String extracted = extractFirstJsonBlock(cleaned);
        if (extracted != null) {
            JsonNode extractedNode = tryParseJson(extracted);
            if (extractedNode != null) {
                return normalizeResponse(extractedNode);
            }
        }
        int objStart = cleaned.indexOf('{');
        int objEnd = cleaned.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            JsonNode objNode = tryParseJson(cleaned.substring(objStart, objEnd + 1));
            if (objNode != null) {
                return normalizeResponse(objNode);
            }
        }
        int arrStart = cleaned.indexOf('[');
        int arrEnd = cleaned.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            JsonNode arrNode = tryParseJson(cleaned.substring(arrStart, arrEnd + 1));
            if (arrNode != null) {
                return normalizeResponse(arrNode);
            }
        }
        return emptyResponse();
    }

    private JsonNode tryParseJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private String extractFirstJsonBlock(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '{' || c == '[') {
                int end = findJsonBlockEnd(value, i);
                if (end > i) {
                    return value.substring(i, end + 1);
                }
            }
        }
        return null;
    }

    private int findJsonBlockEnd(String value, int start) {
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                stack.push('}');
            } else if (c == '[') {
                stack.push(']');
            } else if (!stack.isEmpty() && c == stack.peek()) {
                stack.pop();
                if (stack.isEmpty()) {
                    return i;
                }
            } else if ((c == '}' || c == ']') && stack.isEmpty()) {
                return -1;
            }
        }
        return -1;
    }

    private JsonNode normalizeResponse(JsonNode root) {
        if (root == null || root.isNull()) {
            return emptyResponse();
        }
        if (root.isArray()) {
            ObjectNode wrapper = emptyResponse();
            wrapper.set("items", root);
            return wrapper;
        }
        if (root.isObject()) {
            ObjectNode obj = (ObjectNode) root;
            if (!obj.has("unit")) {
                obj.set("unit", buildUnitNode());
            }
            if (!obj.has("items")) {
                obj.set("items", OBJECT_MAPPER.createArrayNode());
            }
            if (!obj.has("global_notes")) {
                obj.set("global_notes", OBJECT_MAPPER.createArrayNode());
            }
            return obj;
        }
        return emptyResponse();
    }

    private ObjectNode emptyResponse() {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.set("unit", buildUnitNode());
        root.set("items", OBJECT_MAPPER.createArrayNode());
        root.set("global_notes", OBJECT_MAPPER.createArrayNode());
        return root;
    }

    private ObjectNode buildUnitNode() {
        ObjectNode unit = OBJECT_MAPPER.createObjectNode();
        unit.put("currency", "");
        unit.put("amount_unit", "");
        unit.put("vat", "unknown");
        return unit;
    }

    private String resolvePromptVersion(String version) {
        if (version == null || version.isBlank()) {
            return defaultPromptVersion;
        }
        return version.trim();
    }

    private String resolveTemplate(String version, String customPrompt) {
        if (customPrompt != null && !customPrompt.isBlank()) {
            return customPrompt;
        }
        return promptService.loadTemplateRaw(version);
    }
}
