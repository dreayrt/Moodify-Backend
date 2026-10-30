package com.laphuth.moodify.dto.karaoke.rooms;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record KaraokePlaybackCommandRequest(@NotNull @Min(0) Long expectedVersion) {}
