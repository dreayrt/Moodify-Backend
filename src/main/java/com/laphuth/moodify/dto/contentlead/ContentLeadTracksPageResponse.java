package com.laphuth.moodify.dto.contentlead;

import java.util.List;

public record ContentLeadTracksPageResponse(
    List<ContentLeadTrackResponse> tracks,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
