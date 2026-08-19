package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.RegisterRequest;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.repositories.userRepository;
import com.laphuth.moodify.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class authenticationServiceTest {
    @Mock
    private userRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private authenticationService authenticationService;

    @Captor
    private ArgumentCaptor<com.laphuth.moodify.entities.user> userCaptor;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.existsByUsername(any())).thenReturn(false);
    }

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

    private void stubSuccessfulRegister() {
        when(userRepository.save(userCaptor.capture())).thenAnswer(invocation -> {
            com.laphuth.moodify.entities.user savedUser = invocation.getArgument(0);
            savedUser.setId(1L);
            return savedUser;
        });
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(1200L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);
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
