package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Identifier is required")
    String identifier,

    @NotBlank(message = "Password is required")
    String password,

    String deviceUuid,
    String deviceName,
    String platform
) {
    public LoginRequest(String identifier, String password) {
        this(identifier, password, null, null, null);
    }
}
