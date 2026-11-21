package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSecurityAndStatsTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TrackRepository trackRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AlbumRepository albumRepository;
    @Mock private ArtistRepository artistRepository;
    @Mock private UserDeviceService userDeviceService;
    @Mock private CatalogIntegrityService catalogIntegrityService;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(
                jdbcTemplate,
                trackRepository,
                mongoTemplate,
                userRepository,
                passwordEncoder,
                albumRepository,
                artistRepository
        );
        adminService.setUserDeviceService(userDeviceService);
        adminService.setCatalogIntegrity(catalogIntegrityService);
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "encoded:" + inv.getArgument(0));
    }

    @Test
    void updateUserStatusPreventsSelfBanAndLastAdminBanAndRevokesSessions() {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin01");
        admin.setEmail("admin@moodify.click");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminService.updateUserStatus(1L, "BANNED", "test", "admin01"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        when(jdbcTemplate.queryForObject(contains("role = 'ADMIN'"), eq(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> adminService.updateUserStatus(1L, "BANNED", "test", "other_admin"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        User listener = new User();
        listener.setId(9L);
        listener.setUsername("listener01");
        listener.setRole(UserRole.USER);
        listener.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(9L)).thenReturn(Optional.of(listener));

        adminService.updateUserStatus(9L, "BANNED", "Vi phạm", "admin01");
        assertThat(listener.getStatus()).isEqualTo(UserStatus.BANNED);
        verify(userDeviceService).revokeAllDevicesForUser(9L);
    }

    @Test
    void updateUserRoleBlocksSelfOrLastAdminAndAutoCreatesArtistForContentLead() {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin01");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminService.updateUserRole(1L, "USER", null, null, "admin01"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        when(jdbcTemplate.queryForObject(contains("role = 'ADMIN'"), eq(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> adminService.updateUserRole(1L, "MODERATOR", null, null, "superadmin"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        User regularUser = new User();
        regularUser.setId(20L);
        regularUser.setUsername("creator01");
        regularUser.setFullname("Creator Demo");
        regularUser.setRole(UserRole.USER);
        regularUser.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(20L)).thenReturn(Optional.of(regularUser));

        adminService.updateUserRole(20L, "CONTENT_LEAD", null, null, "admin01");

        assertThat(regularUser.getRole()).isEqualTo(UserRole.CONTENT_LEAD);
        assertThat(regularUser.getArtistSpotifyId()).isNotBlank();
        ArgumentCaptor<Artist> artistCaptor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(artistCaptor.capture());
        assertThat(artistCaptor.getValue().getName()).isEqualTo("Creator Demo");
        verify(userDeviceService).revokeAllDevicesForUser(20L);
    }

    @Test
    void resetUserPasswordBlocksSelfAndRevokesTargetSessions() {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin01");
        admin.setRole(UserRole.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminService.resetUserPassword(1L, "admin01"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        User target = new User();
        target.setId(5L);
        target.setUsername("user05");
        target.setRole(UserRole.USER);
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));

        String tempPassword = adminService.resetUserPassword(5L, "admin01");
        assertThat(tempPassword).hasSize(10);
        assertThat(target.getPassword()).isEqualTo("encoded:" + tempPassword);
        verify(userDeviceService).revokeAllDevicesForUser(5L);
    }

    @Test
    void getSubscriptionTiersMapsSnakeCaseColumnsToCamelCase() {
        when(jdbcTemplate.queryForList(contains("FROM subscription_tiers"))).thenReturn(List.of(Map.ofEntries(
                Map.entry("id", "INDIVIDUAL_FULL"),
                Map.entry("name", "Cá nhân FULL"),
                Map.entry("description", "Nghe nhạc không quảng cáo"),
                Map.entry("ad_policy", "NO_ADS"),
                Map.entry("ad_free_daily_limit", 0),
                Map.entry("skip_policy", "UNLIMITED"),
                Map.entry("skip_daily_limit", 0),
                Map.entry("offline_allowed", 1),
                Map.entry("offline_max_tracks", 9999),
                Map.entry("max_devices", 2),
                Map.entry("synced_lyrics", 1),
                Map.entry("vip_badge", 1),
                Map.entry("family_sharing", 0),
                Map.entry("family_members", 1)
        )));

        List<Map<String, Object>> tiers = adminService.getSubscriptionTiers();
        assertThat(tiers).hasSize(1);
        Map<String, Object> tier = tiers.getFirst();
        assertThat(tier.get("adPolicy")).isEqualTo("NO_ADS");
        assertThat(tier.get("skipPolicy")).isEqualTo("UNLIMITED");
        assertThat(tier.get("offlineAllowed")).isEqualTo(true);
        assertThat(tier.get("offlineMaxTracks")).isEqualTo(9999);
        assertThat(tier.get("maxDevices")).isEqualTo(2);
        assertThat(tier.get("syncedLyrics")).isEqualTo(true);
        assertThat(tier.get("vipBadge")).isEqualTo(true);
        assertThat(tier.get("familySharing")).isEqualTo(false);
        assertThat(tier.get("entitlements")).isInstanceOf(Map.class);
    }

    @Test
    void getRevenueStatsBuildsContinuousDailySeriesEvenWithSparseTransactions() {
        when(jdbcTemplate.queryForObject(contains("FROM payment_transactions WHERE status = 'SUCCESS'"), eq(Double.class)))
                .thenReturn(199000.0);
        when(jdbcTemplate.queryForMap(contains("period_revenue"), eq(6)))
                .thenReturn(Map.of("period_revenue", 99000.0, "period_success_count", 1L));
        when(jdbcTemplate.queryForObject(contains("DATE(COALESCE(paid_at, created_at)) < DATE_SUB"), eq(Double.class), eq(13), eq(6)))
                .thenReturn(100000.0);
        when(jdbcTemplate.queryForList(contains("GROUP BY status")))
                .thenReturn(List.of(Map.of("status", "SUCCESS", "cnt", 2L), Map.of("status", "PENDING", "cnt", 1L)));
        when(jdbcTemplate.queryForObject(contains("FROM subscriptions WHERE status = 'ACTIVE'"), eq(Long.class)))
                .thenReturn(4L);
        when(jdbcTemplate.queryForObject(contains("COUNT(DISTINCT s.user_id)"), eq(Long.class)))
                .thenReturn(2L);

        String todayIso = LocalDate.now().toString();
        when(jdbcTemplate.queryForList(contains("as tx_date"), eq(6))).thenReturn(List.of(
                Map.of(
                        "tx_date", todayIso,
                        "revenue", 99000.0,
                        "success_count", 1L,
                        "pending_count", 0L,
                        "failed_count", 0L,
                        "refunded_count", 0L
                )
        ));
        when(jdbcTemplate.queryForList(contains("FROM service_packages p"))).thenReturn(List.of(
                Map.of(
                        "package_id", 2L,
                        "package_name", "VIP 1 Tháng",
                        "tier_id", "INDIVIDUAL_FULL",
                        "package_price", 99000.0,
                        "package_status", "ACTIVE",
                        "success_count", 2L,
                        "total_count", 3L,
                        "revenue", 199000.0,
                        "active_subs", 4L
                )
        ));
        when(jdbcTemplate.queryForList(contains("provider_name"))).thenReturn(List.of(
                Map.of(
                        "provider_name", "SEPAY",
                        "payment_method", "BANK_TRANSFER",
                        "success_count", 2L,
                        "total_count", 3L,
                        "revenue", 199000.0
                )
        ));

        Map<String, Object> stats = adminService.getRevenueStats(7);

        assertThat(stats.get("periodDays")).isEqualTo(7);
        assertThat(stats.get("totalRevenue")).isEqualTo(199000.0);
        assertThat(stats.get("periodRevenue")).isEqualTo(99000.0);
        assertThat(stats.get("growthPercent")).isEqualTo(-1.0);
        assertThat(stats.get("successCount")).isEqualTo(2L);
        assertThat(stats.get("pendingCount")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) stats.get("dailySeries");
        assertThat(series).hasSize(7);
        assertThat(series.getLast().get("revenue")).isEqualTo(99000.0);
        assertThat(series.getFirst().get("revenue")).isEqualTo(0.0);
    }

    @Test
    void deleteFavoriteTargetsRequestedTableByType() {
        when(jdbcTemplate.update("DELETE FROM favorite_artists WHERE id = ?", 12L)).thenReturn(1);
        adminService.deleteFavorite(12L, "ARTIST");
        verify(jdbcTemplate).update("DELETE FROM favorite_artists WHERE id = ?", 12L);

        when(jdbcTemplate.update("DELETE FROM favorite_albums WHERE id = ?", 99L)).thenReturn(0);
        assertThatThrownBy(() -> adminService.deleteFavorite(99L, "ALBUM"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
