package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.security.TokenType;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.laphuth.moodify.dto.auth.FacebookAuthRequest;
import com.laphuth.moodify.dto.auth.GoogleAuthRequest;
import java.util.Collections;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ArtistRepository artistRepository;
    private final UserDeviceService userDeviceService;

    @Value("${google.client-id:}")
    private String googleClientId;

    @Value("${facebook.app-id:}")
    private String facebookAppId;

    @Value("${facebook.app-secret:}")
    private String facebookAppSecret;

    public AuthenticationService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        ArtistRepository artistRepository
    ) {
        this(userRepository, passwordEncoder, jwtService, artistRepository, null);
    }

    @Autowired
    public AuthenticationService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        ArtistRepository artistRepository,
        UserDeviceService userDeviceService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.artistRepository = artistRepository;
        this.userDeviceService = userDeviceService;
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
        UserRole role = resolveRegistrationRole(request.role());
        newUser.setRole(role);
        if (request.avatarUrl() != null && !request.avatarUrl().isBlank()) {
            newUser.setAvatarUrl(request.avatarUrl().trim());
        }
        newUser.setStatus(UserStatus.ACTIVE);

        if (role == UserRole.CONTENT_LEAD) {
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

        if (currentUser.getStatus() != UserStatus.ACTIVE) {
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

        if (userDeviceService != null && request.deviceUuid() != null && !request.deviceUuid().isBlank()) {
            userDeviceService.registerDeviceOnLogin(
                currentUser.getId(),
                currentUser.getUsername(),
                request.deviceUuid(),
                request.deviceName(),
                request.platform()
            );
        }

        return buildAuthResponse(currentUser);
    }

    public AuthResponse loginWithGoogle(GoogleAuthRequest request) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Google Client ID is not configured on the server"
            );
        }

        String email = null;
        String name = null;
        String pictureUrl = null;
        String rawToken = request.idToken() != null ? request.idToken().trim() : "";

        // 1. Nếu là JWT ID Token (gồm 3 phần phân tách bởi dấu chấm)
        if (rawToken.split("\\.").length == 3) {
            try {
                GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance()
                )
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

                GoogleIdToken idToken = verifier.verify(rawToken);
                if (idToken != null) {
                    GoogleIdToken.Payload payload = idToken.getPayload();
                    email = payload.getEmail();
                    name = (String) payload.get("name");
                    pictureUrl = (String) payload.get("picture");
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Nếu không phải JWT hoặc xác thực ID token không được, xác thực theo dạng OAuth2 Access Token qua userinfo
        if (email == null || email.isBlank()) {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                    .header("Authorization", "Bearer " + rawToken)
                    .GET()
                    .build();
                java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                    if (root.has("email")) {
                        email = root.get("email").asText();
                    }
                    if (root.has("name")) {
                        name = root.get("name").asText();
                    }
                    if (root.has("picture")) {
                        pictureUrl = root.get("picture").asText();
                    }
                }
            } catch (Exception e) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Failed to verify Google token with Google userinfo API: " + e.getMessage()
                );
            }
        }

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid or expired Google token"
            );
        }

        email = normalizeEmail(email);

        User currentUser = userRepository.findByEmail(email).orElse(null);

        if (currentUser == null) {
            currentUser = new User();
            currentUser.setEmail(email);
            currentUser.setFullname(name != null && !name.isBlank() ? name.trim() : email.split("@")[0]);

            String baseUsername = normalizeUsername(email.split("@")[0].replaceAll("[^a-zA-Z0-9_.]", ""));
            if (baseUsername.isBlank()) {
                baseUsername = "user";
            }
            if (baseUsername.length() > 40) {
                baseUsername = baseUsername.substring(0, 40);
            }
            String candidateUsername = baseUsername;
            int counter = 1;
            while (userRepository.existsByUsername(candidateUsername)) {
                candidateUsername = baseUsername + counter;
                counter++;
            }
            currentUser.setUsername(candidateUsername);

            currentUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            currentUser.setAvatarUrl(pictureUrl);
            currentUser.setRole(UserRole.USER);
            currentUser.setStatus(UserStatus.ACTIVE);
            currentUser.setLastLoginAt(LocalDateTime.now());
            currentUser = userRepository.save(currentUser);
        } else {
            if (currentUser.getStatus() != UserStatus.ACTIVE) {
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Your account is not active"
                );
            }

            if ((currentUser.getAvatarUrl() == null || currentUser.getAvatarUrl().isBlank()) && pictureUrl != null) {
                if (pictureUrl.length() > 500) {
                    pictureUrl = pictureUrl.substring(0, 500);
                }
                currentUser.setAvatarUrl(pictureUrl);
            }
            currentUser.setLastLoginAt(LocalDateTime.now());
            currentUser = userRepository.save(currentUser);
        }

        if (userDeviceService != null && request.deviceUuid() != null && !request.deviceUuid().isBlank()) {
            userDeviceService.registerDeviceOnLogin(
                currentUser.getId(),
                currentUser.getUsername(),
                request.deviceUuid(),
                request.deviceName(),
                request.platform()
            );
        }

        return buildAuthResponse(currentUser);
    }

    public AuthResponse loginWithFacebook(FacebookAuthRequest request) {
        String rawToken = request.accessToken() != null ? request.accessToken().trim() : "";
        if (rawToken.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Facebook access token cannot be blank"
            );
        }

        String fbId = null;
        String email = null;
        String name = null;
        String pictureUrl = null;

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String graphUrl = "https://graph.facebook.com/v19.0/me?fields=id,name,email,picture.width(500).height(500)&access_token="
                + java.net.URLEncoder.encode(rawToken, java.nio.charset.StandardCharsets.UTF_8);

            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(graphUrl))
                .GET()
                .build();

            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                if (root.has("id")) {
                    fbId = root.get("id").asText();
                }
                if (root.has("email")) {
                    email = root.get("email").asText();
                }
                if (root.has("name")) {
                    name = root.get("name").asText();
                }
                if (root.has("picture") && root.get("picture").has("data") && root.get("picture").get("data").has("url")) {
                    pictureUrl = root.get("picture").get("data").get("url").asText();
                }
            } else {
                log.warn("Facebook Graph API error response: {}", resp.body());
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired Facebook access token"
                );
            }
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Failed to verify Facebook token: " + e.getMessage()
            );
        }

        if (fbId == null || fbId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Unable to retrieve Facebook user information"
            );
        }

        // Nếu tài khoản Facebook đăng ký bằng SĐT hoặc không có email, sinh email fallback
        if (email == null || email.isBlank()) {
            email = "fb_" + fbId + "@facebook.moodify.com";
        }

        email = normalizeEmail(email);

        User currentUser = userRepository.findByEmail(email).orElse(null);

        if (currentUser == null) {
            currentUser = new User();
            currentUser.setEmail(email);

            String finalName = (name != null && !name.isBlank()) ? name.trim() : email.split("@")[0];
            if (finalName.length() > 100) {
                finalName = finalName.substring(0, 100);
            }
            currentUser.setFullname(finalName);

            String baseUsername;
            if (email.endsWith("@facebook.moodify.com")) {
                baseUsername = normalizeUsername("fb_" + fbId);
            } else {
                baseUsername = normalizeUsername(email.split("@")[0].replaceAll("[^a-zA-Z0-9_.]", ""));
            }
            if (baseUsername.isBlank()) {
                baseUsername = "fb_user";
            }
            if (baseUsername.length() > 40) {
                baseUsername = baseUsername.substring(0, 40);
            }
            String candidateUsername = baseUsername;
            int counter = 1;
            while (userRepository.existsByUsername(candidateUsername)) {
                candidateUsername = baseUsername + counter;
                counter++;
            }
            currentUser.setUsername(candidateUsername);

            currentUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));

            if (pictureUrl != null && pictureUrl.length() > 500) {
                pictureUrl = pictureUrl.substring(0, 500);
            }
            currentUser.setAvatarUrl(pictureUrl);
            currentUser.setRole(UserRole.USER);
            currentUser.setStatus(UserStatus.ACTIVE);
            currentUser.setLastLoginAt(LocalDateTime.now());
            currentUser = userRepository.save(currentUser);
        } else {
            if (currentUser.getStatus() != UserStatus.ACTIVE) {
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Your account is not active"
                );
            }

            if ((currentUser.getAvatarUrl() == null || currentUser.getAvatarUrl().isBlank()) && pictureUrl != null) {
                if (pictureUrl.length() > 500) {
                    pictureUrl = pictureUrl.substring(0, 500);
                }
                currentUser.setAvatarUrl(pictureUrl);
            }
            currentUser.setLastLoginAt(LocalDateTime.now());
            currentUser = userRepository.save(currentUser);
        }

        if (userDeviceService != null && request.deviceUuid() != null && !request.deviceUuid().isBlank()) {
            userDeviceService.registerDeviceOnLogin(
                currentUser.getId(),
                currentUser.getUsername(),
                request.deviceUuid(),
                request.deviceName(),
                request.platform()
            );
        }

        return buildAuthResponse(currentUser);
    }

    public java.util.Map<String, Object> getUserDevices(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (userDeviceService == null) {
            return java.util.Map.of("devices", java.util.Collections.emptyList(), "maxDevices", 1, "activeCount", 0);
        }
        return userDeviceService.getUserDevicesSummary(user.getId(), username);
    }

    public void registerCurrentDevice(String username, String deviceUuid, String deviceName, String platform) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (userDeviceService != null) {
            userDeviceService.registerDeviceOnLogin(user.getId(), username, deviceUuid, deviceName, platform);
        }
    }

    public boolean revokeUserDevice(String username, Long deviceId) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (userDeviceService == null) {
            return false;
        }
        return userDeviceService.revokeUserDevice(user.getId(), deviceId);
    }

    public int revokeOtherDevices(String username, String currentDeviceUuid) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (userDeviceService == null) {
            return 0;
        }
        return userDeviceService.revokeOtherDevices(user.getId(), currentDeviceUuid);
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
            currentUser.getStatus() != UserStatus.ACTIVE ||
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
        if (user.getRole() == UserRole.CONTENT_LEAD && user.getArtistSpotifyId() != null) {
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

    private UserRole resolveRegistrationRole(UserRole requestedRole) {
        if (requestedRole == null) {
            return UserRole.USER;
        }

        if (requestedRole != UserRole.USER && requestedRole != UserRole.CONTENT_LEAD) {
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
