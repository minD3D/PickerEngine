package org.engine.pickerengine.dto;

import java.util.List;

public record InstagramSearchResponse(
        String query,
        String status,
        List<InstagramSearchHashtag> hashtags,
        List<InstagramSearchUser> users,
        List<InstagramSearchPlace> places
) {
}
