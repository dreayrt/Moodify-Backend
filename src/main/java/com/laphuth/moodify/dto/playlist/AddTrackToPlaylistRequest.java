package com.laphuth.moodify.dto.playlist;

import jakarta.validation.constraints.NotBlank;

public record AddTrackToPlaylistRequest(
    @NotBlank(message = "Track spotifyId cannot be empty")
    String trackSpotifyId
) {}
