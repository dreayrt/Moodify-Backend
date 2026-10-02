package com.laphuth.moodify.dto.analytics;

public record TrackVisitRequest(
    String targetType,      // "TRACK", "ARTIST", "GENERAL"
    String targetId,        // Spotify ID or MongoDB ID
    String platform,        // "WEB", "ANDROID", "IOS", "OTHER"
    String referrerType,    // "DIRECT", "SEARCH", "TIKTOK", "FACEBOOK", "AI_RECOMMEND", "OTHER"
    String sessionId
) {}
