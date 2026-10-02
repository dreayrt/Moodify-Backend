package com.laphuth.moodify.dto.contentlead;

public record AudienceTrackStatDto(
    String trackId,
    String title,
    String coverUrl,
    long streams,
    double completionRate,
    String primaryChannel
) {}
