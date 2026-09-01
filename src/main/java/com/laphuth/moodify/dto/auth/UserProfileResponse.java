package com.laphuth.moodify.dto.auth;

import com.laphuth.moodify.entities.User;

import java.time.LocalDateTime;

public record UserProfileResponse(
    Long id,
    String fullName,
    String phone,
    String email,
    String username,
    String avatarUrl,
    String role,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static UserProfileResponse fromUser(User currentUser) {
        return new UserProfileResponse(
            currentUser.getId(),
            currentUser.getFullname(),
            currentUser.getPhone(),
            currentUser.getEmail(),
            currentUser.getUsername(),
            currentUser.getAvatarUrl(),
            currentUser.getRole().name(),
            currentUser.getStatus().name(),
            currentUser.getCreatedAt(),
            currentUser.getUpdatedAt()
        );
    }
}
