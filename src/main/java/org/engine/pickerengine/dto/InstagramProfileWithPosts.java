package org.engine.pickerengine.dto;

import java.util.List;

public record InstagramProfileWithPosts(
        InstagramProfile profile,
        List<InstagramPost> posts
) {
}
