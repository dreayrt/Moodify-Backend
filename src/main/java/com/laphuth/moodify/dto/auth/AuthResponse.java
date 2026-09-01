package com.laphuth.moodify.dto.auth;

import com.laphuth.moodify.entities.User;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    long refreshExpiresIn,
    Long userId,
    String fullName,
    String email,
    String username,
    String role,
    String status
) {
    public static AuthResponse fromUser(
        User currentUser,
        String accessToken,
        String refreshToken,
        long expiresIn,
        long refreshExpiresIn
    ) {
        return new AuthResponse(
            accessToken,
            refreshToken,
            "Bearer",
            expiresIn,
            refreshExpiresIn,
            currentUser.getId(),
            currentUser.getFullname(),
            currentUser.getEmail(),
            currentUser.getUsername(),
            currentUser.getRole().name(),
            currentUser.getStatus().name()
        );
    }
}
