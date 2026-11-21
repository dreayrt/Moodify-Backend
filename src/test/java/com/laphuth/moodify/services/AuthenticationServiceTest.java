package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.LoginRequest;
import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.dto.auth.UpdateProfileRequest;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.repositories.ArtistRepository;
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
class AuthenticationServiceTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private ArtistRepository artistRepository;

    @InjectMocks
    private AuthenticationService authenticationService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Captor
    private ArgumentCaptor<Artist> artistCaptor;

    @Test
    void profileUpdateChangesOnlyAllowedFieldsAndRejectsAnotherUsersPhone() {
        User user = new User();
        user.setId(1L);
        user.setUsername("user");
        user.setEmail("user@example.test");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByEmailOrUsername("user", "user")).thenReturn(java.util.Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        UserProfileResponse result = authenticationService.updateProfile("user", new UpdateProfileRequest(" Tên mới ", "0900000000"));
        assertThat(result.fullName()).isEqualTo("Tên mới");
        assertThat(result.phone()).isEqualTo("0900000000");
        assertThat(result.email()).isEqualTo("user@example.test");
    }

    @Test
    void profileUpdateRejectsAnotherUsersPhone() {
        User user = new User(); user.setId(1L);
        User other = new User(); other.setId(2L);
        when(userRepository.findByEmailOrUsername("user", "user")).thenReturn(java.util.Optional.of(user));
        when(userRepository.findByPhone("0900000000")).thenReturn(java.util.Optional.of(other));
        assertThatThrownBy(() -> authenticationService.updateProfile("user", new UpdateProfileRequest("Tên", "0900000000")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void moderatorProfileRemainsUpdatedWhenReadAgain() {
        User user = activeUser("existing-hash");
        user.setRole(UserRole.MODERATOR);
        when(userRepository.findByEmailOrUsername("moderator01", "moderator01"))
            .thenReturn(java.util.Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        authenticationService.updateProfile("moderator01", new UpdateProfileRequest("Nguyễn Văn Moderator", "0901234567"));
        UserProfileResponse reloaded = authenticationService.getCurrentUserProfile("moderator01");
        assertThat(reloaded.fullName()).isEqualTo("Nguyễn Văn Moderator");
        assertThat(reloaded.phone()).isEqualTo("0901234567");
        assertThat(reloaded.role()).isEqualTo("MODERATOR");
    }

    @Test
    void moderatorCanChangePasswordAndOnlyEncodedPasswordIsSaved() {
        User user = activeUser("old-hash");
        user.setRole(UserRole.MODERATOR);
        when(userRepository.findByEmailOrUsername("moderator01", "moderator01"))
            .thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded-hash");
        assertThat(authenticationService.changePassword("moderator01", "current-password", "new-password"))
            .containsEntry("success", true);
        assertThat(user.getPassword()).isEqualTo("new-encoded-hash");
        verify(userRepository).save(user);
    }

    @Test
    void incorrectCurrentPasswordCannotChangeStoredPassword() {
        User user = activeUser("old-hash");
        when(userRepository.findByEmailOrUsername("moderator01", "moderator01"))
            .thenReturn(java.util.Optional.of(user));
        assertThatThrownBy(() -> authenticationService.changePassword("moderator01", "wrong-password", "new-password"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Mật khẩu hiện tại không đúng");
        assertThat(user.getPassword()).isEqualTo("old-hash");
        verify(userRepository, never()).save(any());
    }

    @Test
    void passwordCannotBeChangedToCurrentPassword() {
        User user = activeUser("old-hash");
        when(userRepository.findByEmailOrUsername("moderator01", "moderator01"))
            .thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        assertThatThrownBy(() -> authenticationService.changePassword("moderator01", "current-password", "current-password"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("phải khác mật khẩu hiện tại");
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerShouldDefaultToUserRole() {
        stubSuccessfulRegister();
        RegisterRequest request = buildRequest(null);

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void registerShouldAllowContentLeadRole() {
        stubSuccessfulRegister();
        RegisterRequest request = buildRequest(UserRole.CONTENT_LEAD);

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.CONTENT_LEAD);
        assertThat(userCaptor.getValue().getArtistSpotifyId()).isNotNull();
        verify(artistRepository).save(artistCaptor.capture());
        assertThat(artistCaptor.getValue().getGenres()).containsExactly("pop");
        assertThat(artistCaptor.getValue().getGenresRaw()).containsExactly("pop");
    }

    @Test
    void registerShouldRejectAdminRole() {
        RegisterRequest request = buildRequest(UserRole.ADMIN);

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
        RegisterRequest request = buildRequest(UserRole.MODERATOR);

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
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(passwordEncoder.matches("123456", currentUser.getPassword())).thenReturn(true);
        stubSuccessfulTokens();

        AuthResponse response = authenticationService.login(
            new LoginRequest(" ContentLead01 ", "123456")
        );

        assertThat(response.username()).isEqualTo("contentlead01");
        assertThat(currentUser.getLastLoginAt()).isNotNull();
        verify(userRepository).save(currentUser);
        verify(passwordEncoder, never()).encode("123456");
    }

    @Test
    void loginShouldRejectWrongPassword() {
        User currentUser = activeUser("$2a$10$existing-bcrypt-hash");
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(passwordEncoder.matches("wrong-password", currentUser.getPassword()))
            .thenReturn(false);

        assertThatThrownBy(() -> authenticationService.login(
            new LoginRequest("contentlead01", "wrong-password")
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
        RegisterRequest request = buildRequest(UserRole.USER);

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
        RegisterRequest request = buildRequest(UserRole.USER);

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
        RegisterRequest request = buildRequest(UserRole.USER);

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
            UserRole.USER,
            "https://example.com/avatar.png"
        );

        authenticationService.register(request);

        assertThat(userCaptor.getValue().getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void updateAvatarUrlShouldUpdateUserAvatar() {
        User currentUser = activeUser("$2a$10$existing-bcrypt-hash");
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(java.util.Optional.of(currentUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = authenticationService.updateAvatarUrl(
            "contentlead01",
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
        currentUser.setFullname("Moodify Content Lead");
        currentUser.setEmail("contentlead@moodify.local");
        currentUser.setUsername("contentlead01");
        currentUser.setPassword(storedPassword);
        currentUser.setRole(UserRole.CONTENT_LEAD);
        currentUser.setStatus(UserStatus.ACTIVE);
        return currentUser;
    }

    private RegisterRequest buildRequest(UserRole role) {
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
