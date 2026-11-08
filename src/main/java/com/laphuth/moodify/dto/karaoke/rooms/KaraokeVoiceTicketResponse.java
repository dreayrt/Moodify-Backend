package com.laphuth.moodify.dto.karaoke.rooms;

import java.time.Instant;

public record KaraokeVoiceTicketResponse(String ticket, String websocketUrl, Instant expiresAt) {}
