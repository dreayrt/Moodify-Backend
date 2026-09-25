package com.laphuth.moodify.dto.contentlead;

import com.laphuth.moodify.entities.Artist;

import java.time.Instant;
import java.util.List;

public record ContentLeadProfileResponse(
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
    public static ContentLeadProfileResponse from(Artist artist) {
        return new ContentLeadProfileResponse(
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
