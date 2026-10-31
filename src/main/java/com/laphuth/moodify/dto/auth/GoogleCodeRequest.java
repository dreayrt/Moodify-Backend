package com.laphuth.moodify.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request đổi Authorization Code (PKCE) lấy từ Google sang phiên đăng nhập Moodify.
 * Mobile dùng flow Authorization Code (Google đã chặn implicit flow cho client mới),
 * backend giữ client_secret và tự gọi Google token endpoint.
 *
 * clientId: client id đã cấp code. Với client native (Android/iOS) — Google không
 * phát hành client_secret, exchange bỏ qua secret; với client web — bắt buộc secret.
 */
public record GoogleCodeRequest(
    @NotBlank(message = "Authorization code cannot be blank")
    String code,

    @NotBlank(message = "PKCE code verifier cannot be blank")
    String codeVerifier,

    @NotBlank(message = "Redirect URI cannot be blank")
    String redirectUri,

    String clientId,

    String deviceUuid,
    String deviceName,
    String platform
) {
}
