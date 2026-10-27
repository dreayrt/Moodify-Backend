package com.laphuth.moodify.dto.artist;

import java.util.List;

public record ArtistReviewsPageResponse(
    List<ArtistReviewResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    double averageRating
) {}
