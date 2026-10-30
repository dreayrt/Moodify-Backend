package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.entities.KaraokePlaybackStatus;

import java.time.LocalDateTime;

public record KaraokeRoomPlaybackResponse(
        KaraokePlaybackStatus status,
        KaraokeRoomPlaybackTrackResponse track,
        long positionMs,
        LocalDateTime startedAt,
        long version,
        long serverTimeMs,
        String playbackRunId
) {}
