package com.laphuth.moodify.dto.artist;

public record FollowArtistResponse(
    String artistId,
    boolean isFollowed,
    long followersCount,
    String message
) {}
