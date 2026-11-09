package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.GoogleAuthRequest;
import com.laphuth.moodify.dto.auth.GoogleCodeRequest;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.dto.auth.UpdateProfileRequest;
import com.laphuth.moodify.services.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationApi {
    private final AuthenticationService authenticationService;

    public AuthenticationApi(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(authenticationService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(authenticationService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> loginWithGoogle(
            @Valid @RequestBody GoogleAuthRequest request
    ) {
        return ResponseEntity.ok(authenticationService.loginWithGoogle(request));
    }

    // Popup web and native clients both exchange an authorization code here.
    @PostMapping("/google/code")
    public ResponseEntity<AuthResponse> loginWithGoogleCode(
            @Valid @RequestBody GoogleCodeRequest request,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith
    ) {
        return ResponseEntity.ok(authenticationService.loginWithGoogleCode(request, requestedWith));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ResponseEntity.ok(authenticationService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authenticationService.logout();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<java.util.Map<String, Object>> changePassword(
            Authentication authentication,
            @RequestBody(required = false) java.util.Map<String, String> body
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String currentPassword = body != null ? body.get("currentPassword") : null;
        String newPassword = body != null ? body.get("newPassword") : null;
        return ResponseEntity.ok(
                authenticationService.changePassword(authentication.getName(), currentPassword, newPassword)
        );
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        return ResponseEntity.ok(
                authenticationService.getCurrentUserProfile(authentication.getName())
        );
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(authenticationService.updateProfile(authentication.getName(), request));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserProfileResponse> uploadAvatar(
            Authentication authentication,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                authenticationService.updateAvatar(authentication.getName(), file)
        );
    }

    @GetMapping("/devices")
    public ResponseEntity<java.util.Map<String, Object>> getMyDevices(Authentication authentication) {
        return ResponseEntity.ok(authenticationService.getUserDevices(authentication.getName()));
    }

    @PostMapping("/devices/register")
    public ResponseEntity<java.util.Map<String, Object>> registerDevice(
            Authentication authentication,
            @RequestBody java.util.Map<String, String> body
    ) {
        String deviceUuid = body != null ? body.get("deviceUuid") : null;
        String deviceName = body != null ? body.get("deviceName") : null;
        String platform = body != null ? body.get("platform") : null;
        authenticationService.registerCurrentDevice(authentication.getName(), deviceUuid, deviceName, platform);
        return ResponseEntity.ok(java.util.Map.of("message", "Device registered successfully"));
    }

    @PatchMapping("/devices/{id}/revoke")
    public ResponseEntity<java.util.Map<String, Object>> revokeMyDevice(
            Authentication authentication,
            @PathVariable Long id
    ) {
        boolean ok = authenticationService.revokeUserDevice(authentication.getName(), id);
        return ResponseEntity.ok(java.util.Map.of("success", ok, "id", id));
    }

    @PostMapping("/devices/revoke-others")
    public ResponseEntity<java.util.Map<String, Object>> revokeOtherDevices(
            Authentication authentication,
            @RequestBody(required = false) java.util.Map<String, String> body
    ) {
        String currentDeviceUuid = body != null ? body.get("deviceUuid") : null;
        int count = authenticationService.revokeOtherDevices(authentication.getName(), currentDeviceUuid);
        return ResponseEntity.ok(java.util.Map.of("revokedCount", count));
    }

    @GetMapping("/devices/ping")
    public ResponseEntity<java.util.Map<String, Object>> pingDevice(
            Authentication authentication,
            @RequestHeader(value = "X-Device-Uuid", required = false) String headerDeviceUuid
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        boolean active = authenticationService.isDeviceActive(authentication.getName(), headerDeviceUuid);
        if (!active) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(java.util.Map.of(
                "active", false,
                "error", "DEVICE_REVOKED",
                "message", "Thiết bị này đã bị thu hồi quyền truy cập do tài khoản đã đăng nhập trên thiết bị khác vượt quá hạn mức thiết bị của gói cước."
            ));
        }
        return ResponseEntity.ok(java.util.Map.of("active", true));
    }
}
