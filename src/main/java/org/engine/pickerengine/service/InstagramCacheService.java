package org.engine.pickerengine.service;

import org.engine.pickerengine.dto.InstagramPost;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.engine.pickerengine.entity.InstagramPostEntity;
import org.engine.pickerengine.entity.InstagramProfileEntity;
import org.engine.pickerengine.repository.InstagramPostRepository;
import org.engine.pickerengine.repository.InstagramProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class InstagramCacheService {

    private final InstagramProfileRepository profileRepository;
    private final InstagramPostRepository postRepository;

    public InstagramCacheService(
            InstagramProfileRepository profileRepository,
            InstagramPostRepository postRepository) {
        this.profileRepository = profileRepository;
        this.postRepository = postRepository;
    }

    public Optional<InstagramProfileWithPosts> findFreshProfile(String username, LocalDateTime threshold) {
        return profileRepository.findById(username)
                .filter(profile -> profile.getUpdatedAt() != null && profile.getUpdatedAt().isAfter(threshold))
                .map(profile -> toProfileWithPosts(profile, postRepository.findByUsername(username)));
    }

    public Optional<InstagramProfileWithPosts> findProfile(String username) {
        return profileRepository.findById(username)
                .map(profile -> toProfileWithPosts(profile, postRepository.findByUsername(username)));
    }

    @Transactional
    public InstagramProfileWithPosts saveProfileWithPosts(InstagramProfile profile, List<InstagramPost> posts) {
        String username = profile.username();
        InstagramProfileEntity entity = toEntity(profile);
        entity.setUpdatedAt(LocalDateTime.now());
        profileRepository.save(entity);

        postRepository.deleteByUsername(username);
        List<InstagramPostEntity> postEntities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (InstagramPost post : posts) {
            InstagramPostEntity postEntity = toEntity(username, post);
            postEntity.setUpdatedAt(now);
            postEntities.add(postEntity);
        }
        postRepository.saveAll(postEntities);
        return toProfileWithPosts(entity, postEntities);
    }

    private InstagramProfileWithPosts toProfileWithPosts(
            InstagramProfileEntity profile,
            List<InstagramPostEntity> posts) {
        return new InstagramProfileWithPosts(toDto(profile), toPostDtos(posts));
    }

    private InstagramProfile toDto(InstagramProfileEntity entity) {
        return new InstagramProfile(
                entity.getBiography(),
                entity.getCategoryName(),
                entity.getExternalUrl(),
                entity.getFollowers(),
                entity.getFollowing(),
                entity.getFullName(),
                entity.isPrivate(),
                entity.isVerified(),
                entity.getMediaCount(),
                entity.getProfilePicUrl(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString(),
                entity.getUsername());
    }

    private List<InstagramPost> toPostDtos(List<InstagramPostEntity> posts) {
        List<InstagramPost> results = new ArrayList<>();
        for (InstagramPostEntity post : posts) {
            results.add(new InstagramPost(
                    post.getPostId(),
                    post.getShortcode(),
                    post.getCaption(),
                    post.getThumbnailUrl(),
                    post.getDisplayUrl(),
                    post.getPermalink(),
                    post.isVideo(),
                    post.getVideoViewCount(),
                    post.getLikeCount(),
                    post.getCommentCount(),
                    post.getTakenAt(),
                    post.getMediaType()));
        }
        return results;
    }

    private InstagramProfileEntity toEntity(InstagramProfile profile) {
        InstagramProfileEntity entity = new InstagramProfileEntity(profile.username());
        entity.setBiography(profile.biography());
        entity.setCategoryName(profile.categoryName());
        entity.setExternalUrl(profile.externalUrl());
        entity.setFollowers(profile.followers());
        entity.setFollowing(profile.following());
        entity.setFullName(profile.fullName());
        entity.setPrivate(profile.isPrivate());
        entity.setVerified(profile.isVerified());
        entity.setMediaCount(profile.mediaCount());
        entity.setProfilePicUrl(profile.profilePicUrl());
        return entity;
    }

    private InstagramPostEntity toEntity(String username, InstagramPost post) {
        InstagramPostEntity entity = new InstagramPostEntity(post.postId());
        entity.setUsername(username);
        entity.setShortcode(post.shortcode());
        entity.setCaption(post.caption());
        entity.setThumbnailUrl(post.thumbnailUrl());
        entity.setDisplayUrl(post.displayUrl());
        entity.setPermalink(post.permalink());
        entity.setVideo(post.isVideo());
        entity.setVideoViewCount(post.videoViewCount());
        entity.setLikeCount(post.likeCount());
        entity.setCommentCount(post.commentCount());
        entity.setTakenAt(post.takenAt());
        entity.setMediaType(post.mediaType());
        return entity;
    }
}
