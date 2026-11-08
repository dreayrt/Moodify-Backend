package com.laphuth.moodify.dto.karaoke.rooms;

public record KaraokeRoomQueueItemResponse(
        Long id,
        String trackId,
        String title,
        String artist,
        String imageUrl,
        Long durationMs,
        String requestedBy,
        boolean requestedByMe
) {}
