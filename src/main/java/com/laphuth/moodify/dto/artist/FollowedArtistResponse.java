package com.laphuth.moodify.dto.artist;

import java.time.LocalDateTime;
import java.util.List;

public record FollowedArtistResponse(
    String artistId,
    String name,
    String imageUrl,
    List<String> genres,
    boolean verified,
    long followersCount,
    LocalDateTime followedAt
) {}
