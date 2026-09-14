package com.laphuth.moodify.dto.artist;

import com.laphuth.moodify.entities.Artist;

import java.time.Instant;
import java.util.List;

public record ArtistProfileResponse(
    String id,
    String spotifyId,
    String name,
    String imageUrl,
    Integer followers,
    Integer popularity,
    List<String> genres,
    List<String> genresRaw,
    Instant createdAt,
    Instant updatedAt
) {
    public static ArtistProfileResponse from(Artist artist) {
        return new ArtistProfileResponse(
            artist.getId(),
            artist.getSpotifyId(),
            artist.getName(),
            artist.getImageUrl(),
            artist.getFollowers(),
            artist.getPopularity(),
            artist.getGenres(),
            artist.getGenresRaw(),
            artist.getCreatedAt(),
            artist.getUpdatedAt()
        );
    }
}
