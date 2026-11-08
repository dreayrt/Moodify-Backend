package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.entities.KaraokeRoomVisibility;

public record KaraokeRoomCreatedResponse(
        Long id,
        String name,
        KaraokeRoomVisibility visibility,
        Integer maxMembers,
        String inviteCode
) {}
