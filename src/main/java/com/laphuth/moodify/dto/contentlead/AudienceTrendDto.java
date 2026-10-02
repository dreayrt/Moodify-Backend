package com.laphuth.moodify.dto.contentlead;

public record AudienceTrendDto(
    String date,
    String label,
    long streams,
    long mobileStreams,
    long webStreams,
    long otherStreams
) {}
