package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.auth.AuthResponse;
import com.laphuth.moodify.dto.auth.RefreshTokenRequest;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.security.JwtAuthenticationFilter;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.security.TokenType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionManagementSecurityTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private ArtistRepository artistRepository;
    @Mock private UserDeviceService userDeviceService;

    private AuthenticationService authenticationService;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authenticationService = new AuthenticationService(
                userRepository,
                passwordEncoder,
                jwtService,
                artistRepository,
                userDeviceService
        );
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService, userRepository, userDeviceService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logoutRevokesCurrentDeviceSessionWhenDeviceUuidProvided() {
        User admin = new User();
        admin.setId(4L);
        admin.setUsername("admin01");
        when(userRepository.findByEmailOrUsername("admin01", "admin01")).thenReturn(Optional.of(admin));

        authenticationService.logout("admin01", "dev-uuid-123", null);

        verify(userDeviceService).revokeDeviceByUuid(4L, "dev-uuid-123");
    }

    @Test
    void refreshRejectsRevokedDeviceWithForbiddenAndAllowsActiveDevice() {
        User user = new User();
        user.setId(7L);
        user.setUsername("listener07");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);

        when(jwtService.extractUsername("refresh-tok")).thenReturn("listener07");
        when(userRepository.findByUsername("listener07")).thenReturn(Optional.of(user));
        when(jwtService.isTokenValid("refresh-tok", "listener07", TokenType.REFRESH)).thenReturn(true);

        when(userDeviceService.isDeviceActive(7L, "revoked-dev")).thenReturn(false);
        assertThatThrownBy(() -> authenticationService.refresh(new RefreshTokenRequest("refresh-tok"), "revoked-dev"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).isEqualTo("DEVICE_REVOKED");
                });

        when(userDeviceService.isDeviceActive(7L, "active-dev")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("new-access");
        when(jwtService.generateRefreshToken(user)).thenReturn("new-refresh");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthResponse response = authenticationService.refresh(new RefreshTokenRequest("refresh-tok"), "active-dev");
        assertThat(response.accessToken()).isEqualTo("new-access");
    }

    @Test
    void changePasswordRevokesOtherDevicesWhenCurrentDevicePresent() {
        User user = new User();
        user.setId(10L);
        user.setUsername("admin01");
        user.setPassword("hashed-old");
        user.setRole(UserRole.ADMIN);

        when(userRepository.findByEmailOrUsername("admin01", "admin01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPass", "hashed-old")).thenReturn(true);
        when(passwordEncoder.matches("newPass123", "hashed-old")).thenReturn(false);
        when(passwordEncoder.encode("newPass123")).thenReturn("hashed-new");

        authenticationService.changePassword("admin01", "oldPass", "newPass123", "current-dev");

        verify(userRepository).save(user);
        verify(userDeviceService).revokeOtherDevices(10L, "current-dev");
    }

    @Test
    void jwtFilterBlocksRevokedAdminDeviceFromReclaimAndAdminEndpointsWhileAllowingLogoutAndUserReclaim() throws Exception {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin01");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);

        when(jwtService.extractUsername("admin-jwt")).thenReturn("admin01");
        when(userRepository.findByUsername("admin01")).thenReturn(Optional.of(admin));
        when(jwtService.isTokenValid("admin-jwt", "admin01", TokenType.ACCESS)).thenReturn(true);
        when(userDeviceService.isDeviceActive(1L, "revoked-admin-dev")).thenReturn(false);

        for (String blockedPath : new String[]{
                "/api/auth/devices/revoke-others",
                "/api/auth/devices/register",
                "/api/admin/overview"
        }) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest req = new MockHttpServletRequest("POST", blockedPath);
            req.addHeader("Authorization", "Bearer admin-jwt");
            req.addHeader("X-Device-Uuid", "revoked-admin-dev");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            jwtAuthenticationFilter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            assertThat(res.getContentAsString()).contains("DEVICE_REVOKED");
            verify(chain, never()).doFilter(req, res);
        }

        // Logout & Ping remain permitted so the client can cleanly sign out
        for (String allowedPath : new String[]{"/api/auth/logout", "/api/auth/devices/ping"}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest req = new MockHttpServletRequest("POST", allowedPath);
            req.addHeader("Authorization", "Bearer admin-jwt");
            req.addHeader("X-Device-Uuid", "revoked-admin-dev");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            jwtAuthenticationFilter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            verify(chain, times(1)).doFilter(req, res);
        }

        // Listener (USER) can still reach /api/auth/devices/revoke-others to reclaim session from player modal
        User listener = new User();
        listener.setId(2L);
        listener.setUsername("user02");
        listener.setRole(UserRole.USER);
        listener.setStatus(UserStatus.ACTIVE);

        when(jwtService.extractUsername("user-jwt")).thenReturn("user02");
        when(userRepository.findByUsername("user02")).thenReturn(Optional.of(listener));
        when(jwtService.isTokenValid("user-jwt", "user02", TokenType.ACCESS)).thenReturn(true);

        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/devices/revoke-others");
        req.addHeader("Authorization", "Bearer user-jwt");
        req.addHeader("X-Device-Uuid", "revoked-user-dev");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        jwtAuthenticationFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(req, res);
    }
}
