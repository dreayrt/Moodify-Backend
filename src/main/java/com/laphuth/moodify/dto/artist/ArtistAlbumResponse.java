package com.laphuth.moodify.dto.artist;

import com.laphuth.moodify.entities.Album;

import java.time.Instant;
import java.util.List;

public record ArtistAlbumResponse(
    String id,
    String spotifyId,
    String name,
    String artistName,
    String artistSpotifyId,
    String imageUrl,
    String releaseDate,
    Integer totalTracks,
    Integer downloadedTracksCount,
    Boolean fullyDownloaded,
    List<String> trackIds,
    Instant createdAt,
    Instant updatedAt
) {
    public static ArtistAlbumResponse from(Album album) {
        return new ArtistAlbumResponse(
            album.getId(),
            album.getSpotifyId(),
            album.getName(),
            album.getArtistName(),
            album.getArtistSpotifyId(),
            album.getImageUrl(),
            album.getReleaseDate(),
            album.getTotalTracks(),
            album.getDownloadedTracksCount(),
            album.getFullyDownloaded(),
            album.getTrackIds(),
            album.getCreatedAt(),
            album.getUpdatedAt()
        );
    }
}
