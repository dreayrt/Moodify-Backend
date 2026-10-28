package com.laphuth.moodify.dto.album;

import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.AlbumEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AlbumDetailResponse(
    Object id,
    String title,
    String description,
    String coverUrl,
    LocalDate releaseDate,
    Boolean isPublic,
    String status,
    Long userId,
    String authorName,
    String authorAvatar,
    int trackCount,
    boolean isFavorited,
    List<TrackResponse> tracks,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AlbumDetailResponse from(AlbumEntity entity, List<TrackResponse> tracks, boolean isFavorited) {
        String effectiveCover = entity.getCoverUrl();
        if ((effectiveCover == null || effectiveCover.isBlank()) && tracks != null && !tracks.isEmpty()) {
            effectiveCover = tracks.get(0).imageUrl();
        }
        if (effectiveCover == null || effectiveCover.isBlank()) {
            effectiveCover = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300";
        }

        String authorName = (entity.getUser() != null && entity.getUser().getFullname() != null)
                ? entity.getUser().getFullname()
                : "Moodify Artist";
        String authorAvatar = (entity.getUser() != null) ? entity.getUser().getAvatarUrl() : null;

        return new AlbumDetailResponse(
            entity.getId(),
            entity.getTitle(),
            entity.getDescription(),
            effectiveCover,
            entity.getReleaseDate(),
            entity.getIsPublic(),
            entity.getStatus() != null ? entity.getStatus().name() : "ACTIVE",
            entity.getUser() != null ? entity.getUser().getId() : null,
            authorName,
            authorAvatar,
            tracks != null ? tracks.size() : 0,
            isFavorited,
            tracks,
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
