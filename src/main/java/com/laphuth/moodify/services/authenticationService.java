package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.entities.enums.userStatus;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.userRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.security.TokenType;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class authenticationService {
    private final userRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public authenticationService(
        userRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        validateRegisterRequest(request);

        String normalizedEmail = normalizeEmail(request.email());
        String normalizedUsername = normalizeUsername(request.username());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Email already exists"
            );
        }

        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Username already exists"
            );
        }

        User newUser = new User();
        newUser.setFullname(request.fullName().trim());
        newUser.setPhone(request.phone().trim());
        newUser.setEmail(normalizedEmail);
        newUser.setUsername(normalizedUsername);
        newUser.setPassword(passwordEncoder.encode(request.password()));
        newUser.setRole(resolveRegistrationRole(request.role()));
        newUser.setStatus(userStatus.ACTIVE);

        User savedUser = userRepository.save(newUser);
        return buildAuthResponse(savedUser);
    }

    public AuthResponse login(LoginRequest request) {
        User currentUser = userRepository
            .findByEmailOrUsername(
                normalizeIdentifier(request.identifier()),
                normalizeIdentifier(request.identifier())
            )
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid credentials"
            ));

        if (currentUser.getStatus() != userStatus.ACTIVE) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Your account is not active"
            );
        }

        if (!passwordEncoder.matches(request.password(), currentUser.getPassword())) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid credentials"
            );
        }

        return buildAuthResponse(currentUser);
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawRefreshToken = request.refreshToken().trim();
        String username;

        try {
            username = jwtService.extractUsername(rawRefreshToken);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid refresh token"
            );
        }

        User currentUser = userRepository
            .findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid refresh token"
            ));

        if (
            currentUser.getStatus() != userStatus.ACTIVE ||
            !jwtService.isTokenValid(
                rawRefreshToken,
                currentUser.getUsername(),
                TokenType.REFRESH
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid refresh token"
            );
        }

        return buildAuthResponse(currentUser);
    }

    public void logout() {
        SecurityContextHolder.clearContext();
    }

    public UserProfileResponse getCurrentUserProfile(String principal) {
        User currentUser = userRepository
            .findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));
        return UserProfileResponse.fromUser(currentUser);
    }

    private AuthResponse buildAuthResponse(User currentUser) {
        String accessToken = jwtService.generateAccessToken(currentUser);
        String refreshTokenValue = jwtService.generateRefreshToken(currentUser);

        return AuthResponse.fromUser(
            currentUser,
            accessToken,
            refreshTokenValue,
            jwtService.getAccessTokenExpirationSeconds()
            ,
            jwtService.getRefreshTokenExpirationSeconds()
        );
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Confirm password does not match"
            );
        }
    }

    private userRole resolveRegistrationRole(userRole requestedRole) {
        if (requestedRole == null) {
            return userRole.USER;
        }

        if (requestedRole == userRole.ADMIN) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You are not allowed to self-register as ADMIN"
            );
        }

        return requestedRole;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIdentifier(String identifier) {
        return identifier.trim().toLowerCase(Locale.ROOT);
    }

}
