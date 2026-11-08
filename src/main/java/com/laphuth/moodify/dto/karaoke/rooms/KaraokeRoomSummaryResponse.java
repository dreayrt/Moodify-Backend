package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.entities.KaraokeRoomVisibility;

import java.time.LocalDateTime;

public record KaraokeRoomSummaryResponse(
        Long id,
        String name,
        String hostName,
        String hostAvatarUrl,
        long memberCount,
        Integer maxMembers,
        KaraokeRoomVisibility visibility,
        LocalDateTime createdAt
) {}
