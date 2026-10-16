package com.laphuth.moodify.dto.album;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAlbumRequest(
    @NotBlank(message = "Tên album không được để trống")
    @Size(max = 200, message = "Tên album tối đa 200 ký tự")
    String title,

    @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự")
    String description,

    String coverUrl,

    Boolean isPublic
) {}
