package com.laphuth.moodify.dto.artist;

import com.laphuth.moodify.entities.Track;

import java.time.Instant;
import java.util.List;

public record ArtistTrackResponse(
    String id,
    String spotifyId,
    String title,
    String artist,
    String genre,
    String duration,
    String status,
    String visibility,
    int plays,
    int likes,
    int commentsCount,
    Integer bpm,
    String key,
    String coverUrl,
    String audioUrl,
    String spotifyUrl,
    String downloadStatus,
    String moderationStatus,
    Double moderationScore,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
    public static ArtistTrackResponse from(Track track) {
        String moderationStatus = normalizeStatus(track.getModerationStatus());
        boolean published = "approved".equals(moderationStatus);

        return new ArtistTrackResponse(
            track.getId(),
            track.getSpotifyId(),
            track.getName(),
            track.getArtistName(),
            firstGenre(track.getGenres(), track.getGenresRaw()),
            resolveDuration(track),
            published ? "published" : "draft",
            published ? "public" : "private",
            0,
            0,
            0,
            resolveBpm(track),
            resolveKey(track),
            track.getImageUrl(),
            track.getLocalPath(),
            track.getSpotifyUrl(),
            track.getDownloadStatus(),
            track.getModerationStatus(),
            track.getModerationScore(),
            track.getAlbumName(),
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

    private static Integer resolveBpm(Track track) {
        if (track.getAudioFeatures() == null || track.getAudioFeatures().getBpm() == null) {
            return null;
        }
        return (int) Math.round(track.getAudioFeatures().getBpm());
    }

    private static String resolveKey(Track track) {
        if (track.getAudioFeatures() == null) {
            return null;
        }
        return track.getAudioFeatures().getKeySignature();
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }
}
