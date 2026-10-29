package com.laphuth.moodify.dto.track;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.services.AudioUrlResolver;
import com.laphuth.moodify.services.MoodTrackScorer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TrackResponse(
    String id,
    String spotifyId,
    String name,
    String artistName,
    String artistSpotifyId,
    String albumSpotifyId,
    String albumName,
    String imageUrl,
    String releaseDate,
    Integer trackNumber,
    Integer durationMs,
    boolean explicit,
    List<String> genres,
    String lyricsPlain,
    String lyricsSynced,
    String localPath,
    String audioUrl,
    String downloadStatus,
    Instant createdAt,
    Instant updatedAt,
    Double moodMatchDistance,
    Double moodFeatureCoverage,
    List<String> moodMatchedTags,
    Map<String, MoodTrackScorer.FeatureDistance> moodFeatureDistances
) {
    public static TrackResponse from(Track track) {
        return from(track, true);
    }

    public static TrackResponse fromSummary(Track track) {
        return from(track, false);
    }

    public static TrackResponse from(Track track, boolean includeLyrics) {
        if (track == null) return null;

        String resolvedArtistId = track.getArtistSpotifyId();
        if (resolvedArtistId == null || resolvedArtistId.isBlank()) {
            resolvedArtistId = track.getArtistName();
        }
        return new TrackResponse(
            track.getId(),
            track.getSpotifyId(),
            track.getName(),
            track.getArtistName(),
            resolvedArtistId,
            track.getAlbumSpotifyId(),
            track.getAlbumName(),
            AudioUrlResolver.resolveImageUrl(track.getImageUrl()),
            track.getReleaseDate(),
            track.getTrackNumber(),
            track.getDurationMs(),
            track.isExplicit(),
            track.getGenres(),
            includeLyrics ? track.getLyricsPlain() : null,
            includeLyrics ? track.getLyricsSynced() : null,
            track.getLocalPath(),
            AudioUrlResolver.resolve(track.getLocalPath()),
            track.getDownloadStatus(),
            track.getCreatedAt(),
            track.getUpdatedAt(),
            null,
            null,
            null,
            null
        );
    }

    public static TrackResponse fromMood(Track track, MoodTrackScorer.Score score) {
        TrackResponse response = fromSummary(track);
        return new TrackResponse(
            response.id(),
            response.spotifyId(),
            response.name(),
            response.artistName(),
            response.artistSpotifyId(),
            response.albumSpotifyId(),
            response.albumName(),
            response.imageUrl(),
            response.releaseDate(),
            response.trackNumber(),
            response.durationMs(),
            response.explicit(),
            response.genres(),
            response.lyricsPlain(),
            response.lyricsSynced(),
            response.localPath(),
            response.audioUrl(),
            response.downloadStatus(),
            response.createdAt(),
            response.updatedAt(),
            score.distance(),
            score.featureCoverage(),
            score.matchedTags(),
            score.featureDistances()
        );
    }
}
