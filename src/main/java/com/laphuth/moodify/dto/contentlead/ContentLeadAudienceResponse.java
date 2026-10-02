package com.laphuth.moodify.dto.contentlead;

import java.util.List;

public record ContentLeadAudienceResponse(
    long totalReach,
    long uniqueListeners,
    double avgCompletionRate,
    double totalListeningHours,
    double growthRate,
    String period,
    String selectedTrackId,
    List<AudienceChannelDto> channels,
    List<AudienceTrendDto> dailyTrends,
    List<AudienceTrackStatDto> topTracks
) {}
