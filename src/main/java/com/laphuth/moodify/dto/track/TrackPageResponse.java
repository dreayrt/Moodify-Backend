package com.laphuth.moodify.dto.track;

import java.util.List;

public record TrackPageResponse(
    List<TrackResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
