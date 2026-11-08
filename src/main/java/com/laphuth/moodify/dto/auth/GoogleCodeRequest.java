package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request đổi Google authorization code thành phiên đăng nhập Moodify.
 * Native clients use PKCE; web clients authenticate the code exchange with the
 * server-side client secret.
 *
 * clientId: client id đã cấp code. Với client native (Android/iOS) — Google không
 * phát hành client_secret, exchange bỏ qua secret; với client web — bắt buộc secret.
 */
public record GoogleCodeRequest(
    @NotBlank(message = "Authorization code cannot be blank")
    String code,

    String codeVerifier,

    @NotBlank(message = "Redirect URI cannot be blank")
    String redirectUri,

    String clientId,

    String deviceUuid,
    String deviceName,
    String platform
) {
}
