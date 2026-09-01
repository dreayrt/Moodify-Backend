package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.*;
import com.laphuth.moodify.services.authenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationApi {
    private final authenticationService authenticationService;

    public AuthenticationApi(authenticationService authenticationService) {
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
}
