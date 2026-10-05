package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
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

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        return ResponseEntity.ok(
                authenticationService.getCurrentUserProfile(authentication.getName())
        );
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
}
