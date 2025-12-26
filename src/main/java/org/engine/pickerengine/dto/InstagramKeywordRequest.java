package org.engine.pickerengine.dto;

public record InstagramKeywordRequest(String userId, String version, Boolean ignoreCache) {
    public boolean ignoreCacheOrDefault() {
        return ignoreCache != null && ignoreCache;
    }
}
