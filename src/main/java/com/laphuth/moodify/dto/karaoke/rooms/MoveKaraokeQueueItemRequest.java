package com.laphuth.moodify.dto.karaoke.rooms;

import jakarta.validation.constraints.NotNull;

public record MoveKaraokeQueueItemRequest(@NotNull Direction direction) {
    public enum Direction { UP, DOWN }
}
