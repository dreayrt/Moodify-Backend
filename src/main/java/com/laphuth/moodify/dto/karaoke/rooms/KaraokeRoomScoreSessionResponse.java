package com.laphuth.moodify.dto.karaoke.rooms;

import java.util.Map;

public record KaraokeRoomScoreSessionResponse(
        String sessionId,
        String playbackRunId,
        String songId,
        String status,
        boolean ended,
        int participantCount,
        Map<String, Object> score) {}
