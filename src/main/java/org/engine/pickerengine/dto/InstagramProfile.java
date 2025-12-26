package org.engine.pickerengine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InstagramProfile(
        String biography,
        @JsonProperty("category_name") String categoryName,
        @JsonProperty("external_url") String externalUrl,
        int followers,
        int following,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("is_private") boolean isPrivate,
        @JsonProperty("is_verified") boolean isVerified,
        @JsonProperty("media_count") int mediaCount,
        @JsonProperty("profile_pic_url") String profilePicUrl,
        @JsonProperty("updated_at") String updatedAt,
        String username
) {
}
