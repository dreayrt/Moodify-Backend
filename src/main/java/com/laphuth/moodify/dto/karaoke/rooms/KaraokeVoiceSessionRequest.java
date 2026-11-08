package com.laphuth.moodify.dto.karaoke.rooms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KaraokeVoiceSessionRequest(
        @NotBlank String sessionId,
        @NotNull Long userId,
        @NotNull Long roomId,
        @Size(max = 8192)
        String ticket
) {}
