package org.engine.pickerengine.dto;

public record InstagramSearchUser(
        String id,
        String username,
        String fullName,
        String profilePicUrl,
        boolean isPrivate,
        boolean isVerified,
        Integer followerCount
) {
}
