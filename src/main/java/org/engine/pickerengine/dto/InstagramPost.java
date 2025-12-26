package org.engine.pickerengine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InstagramPost(
        @JsonProperty("post_id") String postId,
        String shortcode,
        String caption,
        @JsonProperty("thumbnail_url") String thumbnailUrl,
        @JsonProperty("display_url") String displayUrl,
        String permalink,
        @JsonProperty("is_video") boolean isVideo,
        @JsonProperty("video_view_count") Integer videoViewCount,
        @JsonProperty("like_count") Integer likeCount,
        @JsonProperty("comment_count") Integer commentCount,
        @JsonProperty("taken_at") String takenAt,
        @JsonProperty("media_type") String mediaType
) {
}
