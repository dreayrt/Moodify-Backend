package com.laphuth.moodify.dto.karaoke.rooms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueueKaraokeSongRequest(@NotBlank @Size(max = 64) String trackId) {}
