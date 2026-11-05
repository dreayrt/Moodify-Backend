package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank(message = "Vui lòng nhập họ và tên.")
    @Size(max = 255, message = "Họ và tên quá dài.") String fullName,
    @Pattern(regexp = "^$|^[0-9+\\-()\\s]{8,20}$", message = "Số điện thoại không hợp lệ.") String phone
) {}
