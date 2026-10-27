package com.laphuth.moodify.dto.artist;

import com.laphuth.moodify.entities.ArtistReviewEntity;
import java.time.LocalDateTime;

public record ArtistReviewResponse(
    Long id,
    Long userId,
    String userName,
    String userAvatar,
    int rating,
    String comment,
    String status,
    String artistReply,
    LocalDateTime artistRepliedAt,
    LocalDateTime createdAt
) {
    public static ArtistReviewResponse from(ArtistReviewEntity entity) {
        String name = entity.getUser() != null ? entity.getUser().getFullname() : "Thính giả Moodify";
        String avatar = entity.getUser() != null ? entity.getUser().getAvatarUrl() : null;
        Long uid = entity.getUser() != null ? entity.getUser().getId() : null;

        return new ArtistReviewResponse(
            entity.getId(),
            uid,
            name,
            avatar,
            entity.getRating() != null ? entity.getRating() : 5,
            entity.getComment(),
            entity.getStatus(),
            entity.getArtistReply(),
            entity.getArtistRepliedAt(),
            entity.getCreatedAt()
        );
    }
}
