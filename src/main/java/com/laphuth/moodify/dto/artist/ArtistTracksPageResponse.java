package com.laphuth.moodify.dto.artist;

import java.util.List;

public record ArtistTracksPageResponse(
    List<ArtistTrackResponse> tracks,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
