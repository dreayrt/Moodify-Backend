package com.laphuth.moodify.dto.playlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePlaylistRequest(
    @NotBlank(message = "Playlist name cannot be empty")
    @Size(max = 100, message = "Playlist name must be under 100 characters")
    String name,

    @Size(max = 500, message = "Description must be under 500 characters")
    String description,

    String coverUrl,

    Boolean isPublic
) {}
