package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramPost;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class InstagramPromptService {

    private static final String DEFAULT_TEMPLATE =
            "Profile name: {{profile_name}}\n"
                    + "Profile bio: {{profile_bio}}\n"
                    + "Profile category: {{profile_category}}\n"
                    + "Post captions:\n"
                    + "{{post_captions}}\n";
    private static final String DEFAULT_VERSIONS = "v1,v2";

    private final List<String> availableVersions;

    public InstagramPromptService(
            @Value("${instagram.keyword-versions:" + DEFAULT_VERSIONS + "}") String versions) {
        this.availableVersions = parseVersions(versions);
    }

    public String buildPrompt(InstagramProfileWithPosts data, int postLimit, String version) {
        InstagramProfile profile = data.profile();
        String template = loadTemplate(version);
        String captions = buildCaptions(data, postLimit);

        return template
                .replace("{{profile_name}}", nullToEmpty(profile.fullName()))
                .replace("{{profile_bio}}", nullToEmpty(profile.biography()))
                .replace("{{profile_category}}", nullToEmpty(profile.categoryName()))
                .replace("{{post_captions}}", captions);
    }

    public String loadTemplateRaw(String version) {
        return loadTemplate(version);
    }

    private String loadTemplate(String version) {
        String path = "prompts/instagram_keywords_" + version + ".txt";
        try (InputStream stream = InstagramPromptService.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                return DEFAULT_TEMPLATE;
            }
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return template.isBlank() ? DEFAULT_TEMPLATE : template;
        } catch (Exception ignored) {
            return DEFAULT_TEMPLATE;
        }
    }

    private String buildCaptions(InstagramProfileWithPosts data, int postLimit) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (InstagramPost post : data.posts()) {
            if (count >= postLimit) {
                break;
            }
            String caption = post.caption();
            if (caption == null || caption.isBlank()) {
                continue;
            }
            builder.append("- ").append(caption).append('\n');
            count++;
        }
        if (builder.length() == 0) {
            builder.append("- (없음)\n");
        }
        return builder.toString().trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public List<String> listVersions() {
        return List.copyOf(availableVersions);
    }

    private List<String> parseVersions(String versions) {
        if (versions == null || versions.isBlank()) {
            return List.of("v1", "v2");
        }
        String[] parts = versions.split(",");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                list.add(trimmed);
            }
        }
        if (list.isEmpty()) {
            list.add("v1");
            list.add("v2");
        }
        return list;
    }
}
