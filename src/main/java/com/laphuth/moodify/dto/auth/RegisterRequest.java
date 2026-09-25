package com.laphuth.moodify.dto.auth;

import com.laphuth.moodify.entities.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Full name is required")
    String fullName,

    @NotBlank(message = "Phone is required")
    @Pattern(
        regexp = "^[0-9+\\-()\\s]{8,20}$",
        message = "Phone number format is invalid"
    )
    String phone,

    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    String email,

    @NotBlank(message = "Username is required")
    @Size(min = 4, max = 50, message = "Username must be between 4 and 50 characters")
    String username,

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 255, message = "Password must be at least 6 characters")
    String password,

    @NotBlank(message = "Confirm password is required")
    String confirmPassword,

    UserRole role,
    String avatarUrl,
    String stageName,
    java.util.List<String> genres
) {
    public RegisterRequest(
        String fullName,
        String phone,
        String email,
        String username,
        String password,
        String confirmPassword,
        UserRole role
    ) {
        this(fullName, phone, email, username, password, confirmPassword, role, null, null, null);
    }

    public RegisterRequest(
        String fullName,
        String phone,
        String email,
        String username,
        String password,
        String confirmPassword,
        UserRole role,
        String avatarUrl
    ) {
        this(fullName, phone, email, username, password, confirmPassword, role, avatarUrl, null, null);
    }

    public RegisterRequest(
        String fullName,
        String phone,
        String email,
        String username,
        String password,
        String confirmPassword,
        UserRole role,
        String avatarUrl,
        String stageName
    ) {
        this(fullName, phone, email, username, password, confirmPassword, role, avatarUrl, stageName, null);
    }
}

