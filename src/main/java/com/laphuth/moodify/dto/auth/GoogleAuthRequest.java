package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthRequest(
    @NotBlank(message = "Google ID token cannot be blank")
    String idToken,
    String deviceUuid,
    String deviceName,
    String platform
) {
}
