package org.engine.pickerengine.dto;

import java.util.List;

public record InstagramDmKeywordsRequest(
        List<String> keywords,
        String dmVersion,
        String customDmPrompt) {
    public String dmVersionOrDefault() {
        return dmVersion == null || dmVersion.isBlank() ? null : dmVersion;
    }
}
