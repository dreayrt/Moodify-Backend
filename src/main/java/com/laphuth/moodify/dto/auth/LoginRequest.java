package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Identifier is required")
    String identifier,

    @NotBlank(message = "Password is required")
    String password
) {
}
