package com.laphuth.moodify.dto.artist;

public record CreateArtistReviewRequest(
    Integer rating,
    String comment
) {}
