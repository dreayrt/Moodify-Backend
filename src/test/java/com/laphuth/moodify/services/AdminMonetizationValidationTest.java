package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMonetizationValidationTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TrackRepository trackRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AlbumRepository albumRepository;
    @Mock private ArtistRepository artistRepository;
    @Mock private NotificationService notificationService;

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
        adminService.setNotificationService(notificationService);
    }

    @Test
    void mapTierToEntitlementsComputesAdIntervalByPolicy() {
        Map<String, Object> fullAds = SubscriptionService.mapTierToEntitlements(Map.of(
                "id", "FREE",
                "ad_policy", "FULL_ADS"
        ));
        assertThat(fullAds.get("adIntervalAfterLimit")).isEqualTo(2);
        assertThat(fullAds.get("vipBadge")).isEqualTo(false);

        Map<String, Object> dailyQuota = SubscriptionService.mapTierToEntitlements(Map.of(
                "id", "INDIVIDUAL_BASIC",
                "ad_policy", "DAILY_QUOTA",
                "ad_free_daily_limit", 15
        ));
        assertThat(dailyQuota.get("adIntervalAfterLimit")).isEqualTo(7);
        assertThat(dailyQuota.get("vipBadge")).isEqualTo(true);

        Map<String, Object> noAds = SubscriptionService.mapTierToEntitlements(Map.of(
                "id", "INDIVIDUAL_FULL",
                "ad_policy", "NO_ADS"
        ));
        assertThat(noAds.get("adIntervalAfterLimit")).isEqualTo(0);
    }

    @Test
    void createSubscriptionTierNormalizesDisabledQuotasAndAutoBundlesVipUi() {
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*) FROM subscription_tiers"), eq(Integer.class), any()))
                .thenReturn(0);

        Map<String, Object> created = adminService.createSubscriptionTier(Map.of(
                "id", "VIP_CUSTOM_1",
                "name", "Gói Tùy Chỉnh",
                "description", "Không quảng cáo, nghe offline tắt",
                "adPolicy", "NO_ADS",
                "adFreeDailyLimit", 25,
                "skipPolicy", "UNLIMITED",
                "skipDailyLimit", 40,
                "offlineAllowed", false,
                "offlineMaxTracks", 500,
                "maxDevices", 3
        ));

        assertThat(created.get("id")).isEqualTo("VIP_CUSTOM_1");
        assertThat(created.get("adFreeDailyLimit")).isEqualTo(0);
        assertThat(created.get("skipDailyLimit")).isEqualTo(0);
        assertThat(created.get("offlineMaxTracks")).isEqualTo(0);
        assertThat(created.get("vipBadge")).isEqualTo(true);
        assertThat(created.get("syncedLyrics")).isEqualTo(true);
        verify(jdbcTemplate).update(
                contains("INSERT INTO subscription_tiers"),
                eq("VIP_CUSTOM_1"),
                eq("Gói Tùy Chỉnh"),
                eq("Không quảng cáo, nghe offline tắt"),
                eq("NO_ADS"),
                eq(0),
                eq("UNLIMITED"),
                eq(0),
                eq(false),
                eq(0),
                eq(3),
                eq(true),
                eq(true),
                eq(true),
                eq(3)
        );
    }

    @Test
    void createSubscriptionTierRejectsInvalidPoliciesAndDeviceBounds() {
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*) FROM subscription_tiers"), eq(Integer.class), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> adminService.createSubscriptionTier(Map.of(
                "name", "Bad Ad Policy",
                "adPolicy", "UNKNOWN_POLICY",
                "skipPolicy", "UNLIMITED",
                "offlineAllowed", false,
                "maxDevices", 1
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> adminService.createSubscriptionTier(Map.of(
                "name", "Bad Skip Policy",
                "adPolicy", "NO_ADS",
                "skipPolicy", "SOMETHING",
                "offlineAllowed", false,
                "maxDevices", 1
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> adminService.createSubscriptionTier(Map.of(
                "name", "Too Many Devices",
                "adPolicy", "NO_ADS",
                "skipPolicy", "UNLIMITED",
                "offlineAllowed", false,
                "maxDevices", 11
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateSubscriptionTierKeepsFreeWithoutVipBadgeAndEnablesVipOnPaidTier() {
        Map<String, Object> existingFree = new HashMap<>();
        existingFree.put("id", "FREE");
        existingFree.put("name", "Miễn phí");
        existingFree.put("description", "");
        existingFree.put("ad_policy", "FULL_ADS");
        existingFree.put("ad_free_daily_limit", 10);
        existingFree.put("skip_policy", "LIMITED");
        existingFree.put("skip_daily_limit", 6);
        existingFree.put("offline_allowed", false);
        existingFree.put("offline_max_tracks", 50);
        existingFree.put("max_devices", 1);

        when(jdbcTemplate.queryForList("SELECT * FROM subscription_tiers WHERE id = ?", "FREE"))
                .thenReturn(List.of(existingFree));
        when(jdbcTemplate.update(
                contains("UPDATE subscription_tiers SET"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("FREE")
        )).thenReturn(1);

        adminService.updateSubscriptionTier("FREE", Map.of(
                "adPolicy", "FULL_ADS",
                "adFreeDailyLimit", 99,
                "offlineAllowed", false,
                "offlineMaxTracks", 500
        ));

        verify(jdbcTemplate).update(
                contains("UPDATE subscription_tiers SET"),
                eq("Miễn phí"),
                eq(""),
                eq("FULL_ADS"),
                eq(0),
                eq("LIMITED"),
                eq(6),
                eq(false),
                eq(0),
                eq(1),
                eq(true),
                eq(false),
                eq(false),
                eq(0),
                eq("FREE")
        );

        Map<String, Object> existingPaid = new HashMap<>(existingFree);
        existingPaid.put("id", "INDIVIDUAL_FULL");
        when(jdbcTemplate.queryForList("SELECT * FROM subscription_tiers WHERE id = ?", "INDIVIDUAL_FULL"))
                .thenReturn(List.of(existingPaid));
        when(jdbcTemplate.update(
                contains("UPDATE subscription_tiers SET"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("INDIVIDUAL_FULL")
        )).thenReturn(1);

        adminService.updateSubscriptionTier("INDIVIDUAL_FULL", Map.of(
                "name", "Cá nhân nâng cao",
                "adPolicy", "NO_ADS",
                "skipPolicy", "UNLIMITED",
                "offlineAllowed", true,
                "offlineMaxTracks", 9999,
                "maxDevices", 2
        ));

        verify(jdbcTemplate).update(
                contains("UPDATE subscription_tiers SET"),
                eq("Cá nhân nâng cao"),
                eq(""),
                eq("NO_ADS"),
                eq(0),
                eq("UNLIMITED"),
                eq(0),
                eq(true),
                eq(9999),
                eq(2),
                eq(true),
                eq(true),
                eq(true),
                eq(2),
                eq("INDIVIDUAL_FULL")
        );
    }

    @Test
    void createPackageValidatesInputsAndRejectsFreeTier() {
        assertThatThrownBy(() -> adminService.createPackage(Map.of(
                "name", "Gói Free",
                "price", 0,
                "durationDays", 30,
                "tierId", "FREE"
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> adminService.createPackage(Map.of(
                "name", "Gói Âm",
                "price", -1000,
                "durationDays", 30,
                "tierId", "INDIVIDUAL_FULL"
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> adminService.createPackage(Map.of(
                "name", "Gói 0 ngày",
                "price", 29000,
                "durationDays", 0,
                "tierId", "INDIVIDUAL_FULL"
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> adminService.createPackage(Map.of(
                "name", "   ",
                "price", 29000,
                "durationDays", 30,
                "tierId", "INDIVIDUAL_FULL"
        ))).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletePackageRejectsWhenActiveSubscriptionsExistEvenWithoutPayments() {
        when(jdbcTemplate.queryForObject(contains("FROM payment_transactions"), eq(Long.class), eq(12L)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains("FROM subscriptions WHERE service_package_id = ? AND status = 'ACTIVE'"), eq(Long.class), eq(12L)))
                .thenReturn(2L);

        assertThatThrownBy(() -> adminService.deletePackage(12L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelSubscriptionRejectsExpiredAndCancelsLinkedPendingPayment() {
        when(jdbcTemplate.queryForList(contains("FROM subscriptions s"), eq(88L)))
                .thenReturn(List.of(Map.of(
                        "id", 88L,
                        "user_id", 7L,
                        "status", "EXPIRED",
                        "package_name", "VIP Cá nhân"
                )));

        assertThatThrownBy(() -> adminService.cancelSubscription(88L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        when(jdbcTemplate.queryForList(contains("FROM subscriptions s"), eq(89L)))
                .thenReturn(List.of(Map.of(
                        "id", 89L,
                        "user_id", 7L,
                        "status", "PENDING",
                        "package_name", "VIP Cá nhân"
                )));

        adminService.cancelSubscription(89L);

        verify(jdbcTemplate).update("UPDATE subscriptions SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?", 89L);
        verify(jdbcTemplate).update("UPDATE payment_transactions SET status = 'CANCELLED', updated_at = NOW() WHERE subscription_id = ? AND status = 'PENDING'", 89L);
        verify(notificationService).notifySubscriptionManagementCancelled(
                eq(7L),
                eq(89L),
                contains("VIP Cá nhân")
        );
    }
}
