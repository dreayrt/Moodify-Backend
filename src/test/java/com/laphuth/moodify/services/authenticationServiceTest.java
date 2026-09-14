package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.entities.enums.userStatus;
import com.laphuth.moodify.repositories.userRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.repositories.artistRepoository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class authenticationServiceTest {
    @Mock
    private userRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private artistRepoository artistRepository;

    @InjectMocks
    private authenticationService authenticationService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Test
    void registerShouldDefaultToUserRole() {
        stubSuccessfulRegister();
        RegisterRequest request = buildRequest(null);

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getRole()).isEqualTo(userRole.USER);
    }

    @Test
    void registerShouldAllowArtistRole() {
        stubSuccessfulRegister();
        RegisterRequest request = buildRequest(userRole.ARTIST);

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getRole()).isEqualTo(userRole.ARTIST);
        assertThat(userCaptor.getValue().getArtistSpotifyId()).isNotNull();
        verify(artistRepository).save(any(Artist.class));
    }

    @Test
    void registerShouldRejectAdminRole() {
        RegisterRequest request = buildRequest(userRole.ADMIN);

        assertThatThrownBy(() -> authenticationService.register(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(responseException.getReason())
                    .isEqualTo("You are not allowed to self-register as ADMIN");
            });
    }

    @Test
    void registerShouldRejectModeratorRole() {
        RegisterRequest request = buildRequest(userRole.MODERATOR);

        assertThatThrownBy(() -> authenticationService.register(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(responseException.getReason())
                    .isEqualTo("You are not allowed to self-register as MODERATOR");
            });
    }

    @Test
    void loginShouldAcceptBcryptPasswordAndUpdateLastLogin() {
        User currentUser = activeUser("$2a$10$existing-bcrypt-hash");
        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(passwordEncoder.matches("123456", currentUser.getPassword())).thenReturn(true);
        stubSuccessfulTokens();

        AuthResponse response = authenticationService.login(
            new LoginRequest(" Artist01 ", "123456")
        );

        assertThat(response.username()).isEqualTo("artist01");
        assertThat(currentUser.getLastLoginAt()).isNotNull();
        verify(userRepository).save(currentUser);
        verify(passwordEncoder, never()).encode("123456");
    }

    @Test
    void loginShouldRejectWrongPassword() {
        User currentUser = activeUser("$2a$10$existing-bcrypt-hash");
        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(passwordEncoder.matches("wrong-password", currentUser.getPassword()))
            .thenReturn(false);

        assertThatThrownBy(() -> authenticationService.login(
            new LoginRequest("artist01", "wrong-password")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(
                ((ResponseStatusException) exception).getStatusCode()
            ).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerShouldRejectDuplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
        RegisterRequest request = buildRequest(userRole.USER);

        assertThatThrownBy(() -> authenticationService.register(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(responseException.getReason()).isEqualTo("Email already exists");
            });
    }

    @Test
    void registerShouldRejectDuplicateUsername() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.existsByUsername("moodifyuser")).thenReturn(true);
        RegisterRequest request = buildRequest(userRole.USER);

        assertThatThrownBy(() -> authenticationService.register(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(responseException.getReason()).isEqualTo("Username already exists");
            });
    }

    @Test
    void registerShouldRejectDuplicatePhone() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByPhone("0900000000")).thenReturn(true);
        RegisterRequest request = buildRequest(userRole.USER);

        assertThatThrownBy(() -> authenticationService.register(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(responseException.getReason()).isEqualTo("Phone number already exists");
            });
    }

    @Test
    void registerShouldSetAvatarUrlWhenProvided() {
        stubSuccessfulRegister();
        RegisterRequest request = new RegisterRequest(
            "Moodify User",
            "0900000000",
            "user@example.com",
            "moodifyuser",
            "password123",
            "password123",
            userRole.USER,
            "https://example.com/avatar.png"
        );

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void updateAvatarUrlShouldUpdateUserAvatar() {
        User currentUser = activeUser("$2a$10$existing-bcrypt-hash");
        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = authenticationService.updateAvatarUrl(
            "artist01",
            "https://example.com/avatar.jpg"
        );

        assertThat(response.avatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        verify(userRepository).save(currentUser);
    }

    private void stubSuccessfulRegister() {
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByPhone(any())).thenReturn(false);
        when(userRepository.save(userCaptor.capture())).thenAnswer(invocation -> {
            User savedUser = invocation.getArgument(0);
            savedUser.setId(1L);
            return savedUser;
        });
        stubSuccessfulTokens();
    }

    private void stubSuccessfulTokens() {
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(1200L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);
    }

    private User activeUser(String storedPassword) {
        User currentUser = new User();
        currentUser.setId(2L);
        currentUser.setFullname("Moodify Artist");
        currentUser.setEmail("artist@moodify.local");
        currentUser.setUsername("artist01");
        currentUser.setPassword(storedPassword);
        currentUser.setRole(userRole.ARTIST);
        currentUser.setStatus(userStatus.ACTIVE);
        return currentUser;
    }

    private RegisterRequest buildRequest(userRole role) {
        return new RegisterRequest(
            "Moodify User",
            "0900000000",
            "user@example.com",
            "moodifyuser",
            "password123",
            "password123",
            role
        );
    }
}
