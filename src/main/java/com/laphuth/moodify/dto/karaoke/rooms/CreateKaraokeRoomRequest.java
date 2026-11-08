package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.entities.KaraokeRoomVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateKaraokeRoomRequest(
        @NotBlank @Size(max = 40) String name,
        @NotNull @Min(1) @Max(6) Integer maxMembers,
        @NotNull KaraokeRoomVisibility visibility
) {}
