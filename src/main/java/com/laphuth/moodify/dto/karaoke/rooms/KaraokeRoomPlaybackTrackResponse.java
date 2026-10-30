package com.laphuth.moodify.dto.karaoke.rooms;

import java.util.Map;

public record KaraokeRoomPlaybackTrackResponse(
        String trackId,
        String title,
        String artist,
        String imageUrl,
        Long durationMs,
        String instrumentalUrl,
        String lyricsPlain,
        String lyricsSynced,
        Map<String, Object> lyricTimeline
) {}
