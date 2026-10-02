package com.laphuth.moodify.dto.contentlead;

import java.util.Map;

public record AudienceChannelDto(
    String id,
    String channel,
    String subtitle,
    long streams,
    double share,
    String shareFormatted,
    long uniqueListeners,
    double completionRate,
    String color,
    String icon,
    Map<String, Object> details
) {}
