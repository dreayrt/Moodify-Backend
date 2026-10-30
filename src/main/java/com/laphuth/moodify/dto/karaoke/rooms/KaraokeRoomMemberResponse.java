package com.laphuth.moodify.dto.karaoke.rooms;

public record KaraokeRoomMemberResponse(
        Long userId,
        String name,
        String avatarUrl,
        boolean host
) {}
