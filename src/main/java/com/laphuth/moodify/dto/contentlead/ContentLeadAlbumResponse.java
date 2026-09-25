package com.laphuth.moodify.dto.contentlead;

import com.laphuth.moodify.entities.Album;

import java.time.Instant;
import java.util.List;

public record ContentLeadAlbumResponse(
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
    public static ContentLeadAlbumResponse from(Album album) {
        return new ContentLeadAlbumResponse(
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
