package com.laphuth.moodify.dto.album;

import jakarta.validation.constraints.NotBlank;

public record AddTrackToAlbumRequest(
    @NotBlank(message = "Mã bài hát không được để trống")
    String trackId
) {}
