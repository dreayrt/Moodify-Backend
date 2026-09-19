package com.laphuth.moodify.dto.track;

import com.laphuth.moodify.entities.Track;

import java.time.Instant;
import java.util.List;

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
    String downloadStatus,
    Instant createdAt,
    Instant updatedAt
) {
    public static TrackResponse from(Track track) {
        return new TrackResponse(
            track.getId(),
            track.getSpotifyId(),
            track.getName(),
            track.getArtistName(),
            track.getArtistSpotifyId(),
            track.getAlbumSpotifyId(),
            track.getAlbumName(),
            track.getImageUrl(),
            track.getReleaseDate(),
            track.getTrackNumber(),
            track.getDurationMs(),
            track.isExplicit(),
            track.getGenres(),
            track.getLyricsPlain(),
            track.getLyricsSynced(),
            track.getLocalPath(),
            track.getDownloadStatus(),
            track.getCreatedAt(),
            track.getUpdatedAt()
        );
    }
}
