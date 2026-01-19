package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramDmResponse;
import org.engine.pickerengine.dto.InstagramKeywordResponse;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class InstagramDmService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_DM_PROMPT_VERSION = "v1";
    private static final String DEFAULT_KEYWORDS_PROMPT = """
            너는 인스타그램 칭찬 DM을 작성하는 마케팅 전문가다.

            [입력 정보]
            - keywords: {{KEYWORDS}}

            [작성 목표]
            - 상대가 “내 계정을 제대로 보고 보낸 메시지”라고 느끼게 한다.

            [작성 규칙]
            1. 키워드를 직접 나열하지 말고 자연스럽게 문장에 흡수할 것
            2. 첫 문장은 가벼운 관찰 또는 인상으로 시작할 것
            3. 전체 분량은 3~5문장으로, DM에 어울리는 길이를 유지할 것
            4. 존댓말을 사용하고 부드럽고 인간적인 톤을 유지할 것
            5. 평범한 20~30대가 쓸 법한 자연스러운 말투를 사용할 것
            6. 협업 제안은 하지 않을 것
            7. 수신자는 1명의 인플루언서로 가정할 것

            [출력 형식]
            - 인스타그램 DM으로 바로 보낼 수 있는 단일 메시지
            """;

    private final InstagramKeywordService keywordService;
    private final InstagramService instagramService;
    private final InstagramDmPromptService dmPromptService;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String defaultPromptVersion;

    public InstagramDmService(
            InstagramKeywordService keywordService,
            InstagramService instagramService,
            InstagramDmPromptService dmPromptService,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${instagram.dm-prompt-version:" + DEFAULT_DM_PROMPT_VERSION + "}") String promptVersion,
            @Value("${openai.timeout-seconds:20}") int timeoutSeconds) {
        this.keywordService = keywordService;
        this.instagramService = instagramService;
        this.dmPromptService = dmPromptService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.defaultPromptVersion = promptVersion == null || promptVersion.isBlank()
                ? DEFAULT_DM_PROMPT_VERSION
                : promptVersion.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public InstagramDmResponse generateDm(
            String userId,
            String keywordVersion,
            String customKeywordPrompt,
            String dmVersion,
            String customDmPrompt,
            boolean ignoreCache) {
        if (userId == null || userId.isBlank() || apiKey.isBlank()) {
            return new InstagramDmResponse("", List.of(), List.of(), List.of(), "");
        }
        InstagramKeywordResponse keywords = keywordService.extractKeywords(
                userId,
                keywordVersion,
                customKeywordPrompt,
                ignoreCache);
        InstagramProfileWithPosts profileWithPosts = instagramService.fetchProfileWithPosts(userId);
        DmPromptContext context = buildPromptContext(profileWithPosts.profile(), keywords);
        String prompt = buildPrompt(context, resolvePromptVersion(dmVersion), customDmPrompt);
        String message = callModel(prompt);
        return new InstagramDmResponse(
                message,
                context.moodKeywords(),
                context.contentKeywords(),
                context.toneKeywords(),
                context.impressionSummary());
    }

    public InstagramDmResponse generateDmFromKeywords(
            List<String> keywords,
            String dmVersion,
            String customDmPrompt) {
        if (apiKey.isBlank()) {
            return new InstagramDmResponse("", List.of(), List.of(), List.of(), "");
        }
        List<String> cleanedKeywords = sanitizeKeywords(keywords);
        String prompt = buildKeywordsPrompt(cleanedKeywords, resolvePromptVersion(dmVersion), customDmPrompt);
        String message = callModel(prompt);
        return new InstagramDmResponse(
                message,
                cleanedKeywords,
                List.of(),
                List.of(),
                "");
    }

    private DmPromptContext buildPromptContext(InstagramProfile profile, InstagramKeywordResponse keywords) {
        List<String> keywordList = keywords == null ? List.of() : keywords.keywords();
        List<String> categoryList = keywords == null ? List.of() : keywords.category();

        List<String> mood = new ArrayList<>();
        List<String> content = new ArrayList<>();
        List<String> tone = new ArrayList<>();

        if (!categoryList.isEmpty()) {
            mood.addAll(categoryList);
            splitKeywords(keywordList, content, tone);
        } else {
            splitKeywords(keywordList, mood, content, tone);
        }

        String summary = buildImpressionSummary(profile);
        return new DmPromptContext(mood, content, tone, summary);
    }

    private String buildPrompt(DmPromptContext context, String version, String customPrompt) {
        String template = customPrompt;
        if (template == null || template.isBlank()) {
            template = dmPromptService.loadTemplateRaw(version);
        }
        return template
                .replace("{{MOOD_KEYWORDS}}", joinKeywords(context.moodKeywords()))
                .replace("{{CONTENT_KEYWORDS}}", joinKeywords(context.contentKeywords()))
                .replace("{{TONE_KEYWORDS}}", joinKeywords(context.toneKeywords()))
                .replace("{{IMPRESSION_SUMMARY}}", context.impressionSummary());
    }

    private String buildKeywordsPrompt(List<String> keywords, String version, String customPrompt) {
        String template = customPrompt;
        if (template == null || template.isBlank()) {
            template = loadKeywordsTemplate(version);
        }
        return template.replace("{{KEYWORDS}}", joinKeywords(keywords));
    }

    private String loadKeywordsTemplate(String version) {
        String resolved = resolvePromptVersion(version);
        String path = "prompts/instagram_dm_keywords_" + resolved + ".txt";
        try (InputStream stream = InstagramDmService.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                return DEFAULT_KEYWORDS_PROMPT;
            }
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return template.isBlank() ? DEFAULT_KEYWORDS_PROMPT : template;
        } catch (Exception ignored) {
            return DEFAULT_KEYWORDS_PROMPT;
        }
    }

    private List<String> sanitizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String trimmed = keyword.trim();
            if (!trimmed.isBlank()) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    private void splitKeywords(List<String> keywords, List<String> content, List<String> tone) {
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        int half = Math.max(1, keywords.size() / 2);
        content.addAll(keywords.subList(0, Math.min(half, keywords.size())));
        tone.addAll(keywords.subList(Math.min(half, keywords.size()), keywords.size()));
    }

    private void splitKeywords(List<String> keywords, List<String> mood, List<String> content, List<String> tone) {
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        int chunk = Math.max(1, keywords.size() / 3);
        int firstEnd = Math.min(chunk, keywords.size());
        int secondEnd = Math.min(firstEnd + chunk, keywords.size());
        mood.addAll(keywords.subList(0, firstEnd));
        content.addAll(keywords.subList(firstEnd, secondEnd));
        tone.addAll(keywords.subList(secondEnd, keywords.size()));
    }

    private String joinKeywords(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "(없음)";
        }
        return String.join(", ", values);
    }

    private String buildImpressionSummary(InstagramProfile profile) {
        if (profile == null) {
            return "계정 정보가 제한적입니다.";
        }
        String bio = profile.biography();
        if (bio != null && !bio.isBlank()) {
            return bio;
        }
        String fullName = profile.fullName();
        String category = profile.categoryName();
        if ((fullName == null || fullName.isBlank()) && (category == null || category.isBlank())) {
            return "계정 정보가 제한적입니다.";
        }
        StringBuilder builder = new StringBuilder();
        if (fullName != null && !fullName.isBlank()) {
            builder.append(fullName.trim());
        }
        if (category != null && !category.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(category.trim());
        }
        builder.append(" 계정으로 보입니다.");
        return builder.toString();
    }

    private String callModel(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        try {
            ObjectNode payload = buildPayload(prompt);
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

    private ObjectNode buildPayload(String prompt) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", model);
        ArrayNode input = payload.putArray("input");
        ObjectNode userNode = input.addObject();
        userNode.put("role", "user");
        ArrayNode content = userNode.putArray("content");
        content.addObject()
                .put("type", "input_text")
                .put("text", prompt);
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

    private String resolvePromptVersion(String version) {
        if (version == null || version.isBlank()) {
            return defaultPromptVersion;
        }
        return version.trim();
    }

    private record DmPromptContext(
            List<String> moodKeywords,
            List<String> contentKeywords,
            List<String> toneKeywords,
            String impressionSummary) {
    }
}
