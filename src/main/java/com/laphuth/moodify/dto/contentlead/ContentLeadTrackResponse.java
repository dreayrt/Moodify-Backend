package com.laphuth.moodify.dto.contentlead;

import com.laphuth.moodify.entities.Track;

import java.time.Instant;
import java.util.List;

public record ContentLeadTrackResponse(
    String id,
    String spotifyId,
    String title,
    String artist,
    String genre,
    String albumName,
    String featuredArtists,
    String duration,
    String status,
    String visibility,
    int plays,
    int likes,
    int commentsCount,
    String coverUrl,
    String audioUrl,
    String spotifyUrl,
    String downloadStatus,
    String moderationStatus,
    Double moderationScore,
    String description,
    boolean explicit,
    Instant createdAt,
    Instant updatedAt
) {
    public static ContentLeadTrackResponse from(Track track) {
        String moderationStatus = normalizeStatus(track.getModerationStatus());
        String status = normalizeStatus(track.getStatus());
        if (!"draft".equals(status) && !"published".equals(status) && !"scheduled".equals(status)) {
            status = "approved".equals(moderationStatus) ? "published" : "draft";
        }
        String visibility = normalizeStatus(track.getVisibility());
        if (!"public".equals(visibility) && !"private".equals(visibility) && !"unlisted".equals(visibility)) {
            visibility = "published".equals(status) ? "public" : "private";
        }

        return new ContentLeadTrackResponse(
            track.getId(),
            track.getSpotifyId(),
            track.getName(),
            track.getArtistName(),
            firstGenre(track.getGenres(), track.getGenresRaw()),
            track.getAlbumName(),
            track.getFeaturedArtists(),
            resolveDuration(track),
            status,
            visibility,
            0,
            0,
            0,
            track.getImageUrl(),
            track.getLocalPath(),
            track.getSpotifyUrl(),
            track.getDownloadStatus(),
            track.getModerationStatus(),
            track.getModerationScore(),
            track.getDescription(),
            track.isExplicit(),
            track.getCreatedAt(),
            track.getUpdatedAt()
        );
    }

    private static String firstGenre(List<String> genres, List<String> genresRaw) {
        if (genres != null && !genres.isEmpty()) {
            return genres.get(0);
        }
        if (genresRaw != null && !genresRaw.isEmpty()) {
            return genresRaw.get(0);
        }
        return "Chưa phân loại";
    }

    private static String resolveDuration(Track track) {
        if (track.getDurationFormatted() != null && !track.getDurationFormatted().isBlank()) {
            return track.getDurationFormatted();
        }
        Integer durationMs = track.getDurationMs();
        if (durationMs == null || durationMs <= 0) {
            return "0:00";
        }
        int totalSeconds = durationMs / 1000;
        return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }
}
