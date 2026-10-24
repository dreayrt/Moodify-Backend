package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record FacebookAuthRequest(
    @NotBlank(message = "Facebook access token cannot be blank")
    String accessToken,
    String deviceUuid,
    String deviceName,
    String platform
) {
}
