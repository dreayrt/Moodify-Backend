package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.entities.enums.userStatus;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.artistRepoository;
import com.laphuth.moodify.repositories.userRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.security.TokenType;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class authenticationService {
    private final userRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final artistRepoository artistRepository;

    public authenticationService(
        userRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        artistRepoository artistRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.artistRepository = artistRepository;
    }

    public AuthResponse register(RegisterRequest request) {
        validateRegisterRequest(request);

        String normalizedEmail = normalizeEmail(request.email());
        String normalizedUsername = normalizeUsername(request.username());
        String normalizedPhone = request.phone().trim();

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

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Phone number already exists"
            );
        }

        User newUser = new User();
        newUser.setFullname(request.fullName().trim());
        newUser.setPhone(normalizedPhone);
        newUser.setEmail(normalizedEmail);
        newUser.setUsername(normalizedUsername);
        newUser.setPassword(passwordEncoder.encode(request.password()));
        userRole role = resolveRegistrationRole(request.role());
        newUser.setRole(role);
        if (request.avatarUrl() != null && !request.avatarUrl().isBlank()) {
            newUser.setAvatarUrl(request.avatarUrl().trim());
        }
        newUser.setStatus(userStatus.ACTIVE);

        if (role == userRole.ARTIST || role == userRole.CONTENT_LEAD) {
            String stageName = (request.stageName() != null && !request.stageName().isBlank())
                ? request.stageName().trim()
                : request.fullName().trim();

            String artistSpotifyId = "artist_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            Artist artist = new Artist();
            artist.setName(stageName);
            artist.setSpotifyId(artistSpotifyId);
            artist.setImageUrl(newUser.getAvatarUrl());
            artist.setFollowers(0);
            artist.setPopularity(0);

            List<String> rawGenres = (request.genres() != null && !request.genres().isEmpty())
                ? request.genres().stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList()
                : List.of("pop");

            List<String> normalizedGenres = rawGenres.stream()
                .map(String::toLowerCase)
                .toList();

            artist.setGenres(normalizedGenres.isEmpty() ? List.of("pop") : normalizedGenres);
            artist.setGenresRaw(rawGenres.isEmpty() ? List.of("pop") : rawGenres);

            artist.setCreatedAt(Instant.now());
            artist.setUpdatedAt(Instant.now());
            artistRepository.save(artist);

            newUser.setArtistSpotifyId(artistSpotifyId);

        }

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

        currentUser.setLastLoginAt(LocalDateTime.now());
        userRepository.save(currentUser);

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

    public UserProfileResponse updateAvatar(String principal, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar image file is required");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only image files are allowed");
        }

        User currentUser = userRepository
            .findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));

        try {
            Path uploadDir = Paths.get("uploads", "avatars");
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String originalFilename = file.getOriginalFilename();
            String extension = ".png";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            String fileName = UUID.randomUUID() + extension;
            Path filePath = uploadDir.resolve(fileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            String avatarUrl = "/uploads/avatars/" + fileName;
            currentUser.setAvatarUrl(avatarUrl);
            User savedUser = userRepository.save(currentUser);

            syncArtistAvatar(savedUser, avatarUrl);

            return UserProfileResponse.fromUser(savedUser);
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to save avatar image: " + e.getMessage()
            );
        }
    }

    public UserProfileResponse updateAvatarUrl(String principal, String avatarUrl) {
        User currentUser = userRepository
            .findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));

        currentUser.setAvatarUrl(avatarUrl != null ? avatarUrl.trim() : null);
        User savedUser = userRepository.save(currentUser);
        syncArtistAvatar(savedUser, currentUser.getAvatarUrl());
        return UserProfileResponse.fromUser(savedUser);
    }

    private void syncArtistAvatar(User user, String avatarUrl) {
        if ((user.getRole() == userRole.ARTIST || user.getRole() == userRole.CONTENT_LEAD) && user.getArtistSpotifyId() != null) {
            artistRepository.findBySpotifyId(user.getArtistSpotifyId()).ifPresent(artist -> {
                artist.setImageUrl(avatarUrl);
                artist.setUpdatedAt(Instant.now());
                artistRepository.save(artist);
            });
        }
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

        if (requestedRole != userRole.USER && requestedRole != userRole.ARTIST && requestedRole != userRole.CONTENT_LEAD) {                                     
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You are not allowed to self-register as " + requestedRole.name()
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
