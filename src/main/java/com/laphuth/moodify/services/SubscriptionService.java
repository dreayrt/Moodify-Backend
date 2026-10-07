package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SubscriptionService {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubscriptionService(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    /**
     * Tự động khởi tạo dữ liệu mẫu cho các tầng gói dịch vụ (subscription_tiers)
     * và các gói cước bán hàng (service_packages) nếu bảng chưa có dữ liệu.
     */
    @PostConstruct
    public void initDefaultPackages() {
        try {
            // 1. Đảm bảo bảng subscription_tiers có đủ 4 bậc tiêu chuẩn
            jdbcTemplate.update(
                "INSERT INTO subscription_tiers (id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit, offline_allowed, offline_max_tracks, max_devices, synced_lyrics, vip_badge, family_sharing, family_members) " +
                "VALUES ('FREE', 'Tài khoản Miễn Phí', 'Dành cho người nghe thông thường', 'FULL_ADS', 0, 'LIMITED', 6, FALSE, 0, 1, FALSE, FALSE, FALSE, 0) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), ad_policy = VALUES(ad_policy)"
            );
            jdbcTemplate.update(
                "INSERT INTO subscription_tiers (id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit, offline_allowed, offline_max_tracks, max_devices, synced_lyrics, vip_badge, family_sharing, family_members) " +
                "VALUES ('INDIVIDUAL_BASIC', 'Gói Tiết Kiệm (Basic)', '15 bài không quảng cáo mỗi ngày, 30 skip/ngày, 50 bài offline', 'DAILY_QUOTA', 15, 'LIMITED', 30, TRUE, 50, 1, TRUE, TRUE, FALSE, 0) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), ad_policy = VALUES(ad_policy), ad_free_daily_limit = VALUES(ad_free_daily_limit)"
            );
            jdbcTemplate.update(
                "INSERT INTO subscription_tiers (id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit, offline_allowed, offline_max_tracks, max_devices, synced_lyrics, vip_badge, family_sharing, family_members) " +
                "VALUES ('INDIVIDUAL_FULL', 'Cá Nhân VIP FULL', '100% không quảng cáo, chuyển bài và tải offline không giới hạn', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 1, TRUE, TRUE, FALSE, 0) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), ad_policy = VALUES(ad_policy)"
            );
            jdbcTemplate.update(
                "INSERT INTO subscription_tiers (id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit, offline_allowed, offline_max_tracks, max_devices, synced_lyrics, vip_badge, family_sharing, family_members) " +
                "VALUES ('FAMILY', 'Gói Gia Đình VIP', 'Trọn bộ đặc quyền FULL cho tối đa 6 thành viên/thiết bị', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 6, TRUE, TRUE, TRUE, 6) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), ad_policy = VALUES(ad_policy), max_devices = VALUES(max_devices), family_sharing = VALUES(family_sharing)"
            );

            // 2. Đồng bộ các gói bán hàng gắn khóa ngoại tier_id vào subscription_tiers
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (1, 'Gói VIP Tiết Kiệm (30 Ngày)', 'Dành cho 1 người: 15 bài hát/ngày không quảng cáo, 30 lượt skip/ngày, tải 50 bài offline.', 29000, 30, 1, 'ACTIVE', 'INDIVIDUAL_BASIC') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (2, 'Gói VIP Tiết Kiệm (1 Năm)', 'Tiết kiệm 20%: Trọn gói 365 ngày nghe nhạc tiết kiệm.', 279000, 365, 2, 'ACTIVE', 'INDIVIDUAL_BASIC') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (3, 'Gói Cá Nhân FULL (30 Ngày)', 'Dành cho 1 người: 100% không quảng cáo vô hạn, chuyển bài và tải nhạc offline vô hạn.', 49000, 30, 3, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (4, 'Gói Cá Nhân FULL (90 Ngày)', 'Tiết kiệm 12%: 3 tháng âm nhạc không quảng cáo vô hạn.', 129000, 90, 4, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (5, 'Gói Cá Nhân FULL (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng, trọn bộ đặc quyền cá nhân không giới hạn.', 469000, 365, 5, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (6, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản/thiết bị đồng thời: Trọn bộ đặc quyền FULL chia sẻ cả gia đình.', 79000, 30, 6, 'ACTIVE', 'FAMILY') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (7, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 790000, 365, 7, 'ACTIVE', 'FAMILY') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
        } catch (Exception e) {
            System.err.println("Note: Auto-init service_packages encountered: " + e.getMessage());
        }
    }

    /**
     * Chuyển một hàng dữ liệu từ bảng subscription_tiers thành Map entitlements chuẩn hóa.
     */
    public static Map<String, Object> mapTierToEntitlements(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        String tierId = (String) row.getOrDefault("tier_id", row.get("id"));
        if (tierId == null) tierId = "INDIVIDUAL_BASIC";

        String adPolicy = (String) row.getOrDefault("ad_policy", "NO_ADS");
        m.put("tier", tierId);
        m.put("tierName", row.get("tier_name") != null ? row.get("tier_name") : row.get("name"));
        m.put("adPolicy", adPolicy);
        m.put("adFreeDailyLimit", toInt(row.get("ad_free_daily_limit"), 0));
        m.put("adIntervalAfterLimit", "DAILY_QUOTA".equals(adPolicy) ? 7 : 0);
        m.put("skipPolicy", row.getOrDefault("skip_policy", "UNLIMITED"));
        m.put("skipDailyLimit", toInt(row.get("skip_daily_limit"), 0));
        m.put("offlineAllowed", toBool(row.get("offline_allowed"), true));
        m.put("offlineMaxTracks", toInt(row.get("offline_max_tracks"), 100));
        m.put("maxDevices", toInt(row.get("max_devices"), 1));
        m.put("syncedLyrics", toBool(row.get("synced_lyrics"), true));
        m.put("vipBadge", toBool(row.get("vip_badge"), true));
        m.put("familySharing", toBool(row.get("family_sharing"), false));
        m.put("familyMembers", toInt(row.get("family_members"), 0));
        return m;
    }

    private static int toInt(Object val, int def) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString().trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean toBool(Object val, boolean def) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).intValue() != 0;
        if (val != null) return Boolean.parseBoolean(val.toString().trim()) || "1".equals(val.toString().trim());
        return def;
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Lấy danh sách các gói dịch vụ đang hoạt động kèm quyền hạn từ bảng subscription_tiers.
     */
    public List<Map<String, Object>> getActivePackages() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT p.id, p.name, p.description, p.price, p.duration_days, p.display_order, p.tier_id, " +
            "       t.name as tier_name, t.ad_policy, t.ad_free_daily_limit, t.skip_policy, t.skip_daily_limit, " +
            "       t.offline_allowed, t.offline_max_tracks, t.max_devices, " +
            "       t.synced_lyrics, t.vip_badge, t.family_sharing, t.family_members " +
            "FROM service_packages p " +
            "LEFT JOIN subscription_tiers t ON p.tier_id = t.id " +
            "WHERE p.status = 'ACTIVE' " +
            "ORDER BY p.display_order ASC, p.price ASC"
        );
        for (Map<String, Object> row : rows) {
            Map<String, Object> entitlements = mapTierToEntitlements(row);
            String featuresJson = toJsonString(entitlements);
            row.put("tierId", row.get("tier_id"));
            row.put("featuresJson", featuresJson);
            row.put("entitlements", entitlements);
        }
        return rows;
    }

    /**
     * Lấy thông tin bản quyền và gói cước hiện tại của người dùng.
     */
    public Map<String, Object> getCurrentUserSubscription(String principal) {
        User user = findUserByPrincipal(principal);
        Map<String, Object> res = new HashMap<>();

        List<Map<String, Object>> activeSubs = jdbcTemplate.queryForList(
            "SELECT s.id as subscription_id, s.service_package_id, s.start_at, s.end_at, s.status, " +
            "       p.name as package_name, p.price, p.duration_days, p.tier_id, " +
            "       t.name as tier_name, t.ad_policy, t.ad_free_daily_limit, t.skip_policy, t.skip_daily_limit, " +
            "       t.offline_allowed, t.offline_max_tracks, t.max_devices, " +
            "       t.synced_lyrics, t.vip_badge, t.family_sharing, t.family_members, " +
            "       DATEDIFF(s.end_at, NOW()) as days_remaining " +
            "FROM subscriptions s " +
            "JOIN service_packages p ON s.service_package_id = p.id " +
            "LEFT JOIN subscription_tiers t ON p.tier_id = t.id " +
            "WHERE s.user_id = ? AND s.status = 'ACTIVE' AND s.end_at > NOW() " +
            "ORDER BY s.end_at DESC " +
            "LIMIT 1",
            user.getId()
        );

        if (!activeSubs.isEmpty()) {
            Map<String, Object> sub = activeSubs.get(0);
            String pkgName = (String) sub.get("package_name");
            String tierId = (String) sub.get("tier_id");
            if (tierId == null || tierId.isBlank()) {
                tierId = inferTierFromName(pkgName);
                sub.put("tier_id", tierId);
            }

            Map<String, Object> entitlements = mapTierToEntitlements(sub);
            String featuresJson = toJsonString(entitlements);

            res.put("isPremium", true);
            res.put("tier", tierId);
            res.put("tierId", tierId);
            res.put("tierName", sub.get("tier_name"));
            res.put("featuresJson", featuresJson);
            res.put("entitlements", entitlements);
            res.put("benefits", deriveBenefits(entitlements));
            res.put("subscriptionId", sub.get("subscription_id"));
            res.put("packageId", sub.get("service_package_id"));
            res.put("packageName", pkgName);
            res.put("price", sub.get("price"));
            res.put("startAt", sub.get("start_at") != null ? sub.get("start_at").toString() : null);
            res.put("expiresAt", sub.get("end_at") != null ? sub.get("end_at").toString() : null);
            res.put("daysRemaining", sub.get("days_remaining") != null ? ((Number) sub.get("days_remaining")).longValue() : 0L);
        } else {
            Map<String, Object> freeEnt = getFreeTierEntitlements();
            res.put("isPremium", false);
            res.put("tier", "FREE");
            res.put("tierId", "FREE");
            res.put("packageName", "Tài khoản Miễn phí");
            res.put("daysRemaining", 0L);
            res.put("entitlements", freeEnt);
            res.put("featuresJson", toJsonString(freeEnt));
            res.put("benefits", Collections.emptyList());
        }

        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        return res;
    }

    /** Lấy quyền hạn của tầng FREE từ subscription_tiers hoặc mặc định. */
    private Map<String, Object> getFreeTierEntitlements() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM subscription_tiers WHERE id = 'FREE' LIMIT 1"
            );
            if (!rows.isEmpty()) {
                return mapTierToEntitlements(rows.get(0));
            }
        } catch (Exception ignored) {}
        return freeEntitlements();
    }

    /** Entitlements mặc định cho tài khoản FREE khi chưa có trong DB. */
    private Map<String, Object> freeEntitlements() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", "FREE");
        m.put("tierName", "Tài khoản Miễn Phí");
        m.put("adPolicy", "FULL_ADS");
        m.put("adFreeDailyLimit", 0);
        m.put("adIntervalAfterLimit", 2);
        m.put("skipPolicy", "LIMITED");
        m.put("skipDailyLimit", 6);
        m.put("offlineAllowed", false);
        m.put("offlineMaxTracks", 0);
        m.put("maxDevices", 1);
        m.put("syncedLyrics", false);
        m.put("vipBadge", false);
        m.put("familySharing", false);
        m.put("familyMembers", 0);
        return m;
    }

    /** Fallback: suy ra tier từ tên gói (cách hành xử cũ, giữ để tương thích dữ liệu cũ). */
    private String inferTierFromName(String pkgName) {
        if (pkgName != null && pkgName.contains("Gia Đình")) {
            return "FAMILY";
        }
        if (pkgName != null && (pkgName.contains("FULL") || pkgName.contains("Full") || pkgName.contains("Không Giới Hạn"))) {
            return "INDIVIDUAL_FULL";
        }
        return "INDIVIDUAL_BASIC";
    }

    /** Sinh danh sách benefit code từ entitlements để client hiển thị. */
    private List<String> deriveBenefits(Map<String, Object> e) {
        List<String> benefits = new ArrayList<>();
        String adPolicy = String.valueOf(e.getOrDefault("adPolicy", "FULL_ADS"));
        if ("NO_ADS".equals(adPolicy)) {
            benefits.add("AD_FREE_UNLIMITED");
        } else if ("DAILY_QUOTA".equals(adPolicy)) {
            benefits.add("AD_FREE_DAILY_" + e.getOrDefault("adFreeDailyLimit", 15));
        }
        if ("UNLIMITED".equals(e.get("skipPolicy"))) {
            benefits.add("UNLIMITED_SKIPS");
        } else {
            benefits.add("SKIPS_DAILY_" + e.getOrDefault("skipDailyLimit", 30));
        }
        boolean offline = Boolean.TRUE.equals(e.get("offlineAllowed"))
                || Boolean.parseBoolean(String.valueOf(e.get("offlineAllowed")));
        if (offline) {
            benefits.add("OFFLINE_DOWNLOAD_" + e.getOrDefault("offlineMaxTracks", 50));
        }
        benefits.add("MAX_DEVICES_" + e.getOrDefault("maxDevices", 1));
        boolean family = Boolean.TRUE.equals(e.get("familySharing"))
                || Boolean.parseBoolean(String.valueOf(e.get("familySharing")));
        if (family) {
            benefits.add("FAMILY_SHARING");
        }
        return benefits;
    }

    /**
     * Đăng ký mua một gói dịch vụ và kích hoạt ngay.
     */
    @Transactional
    public Map<String, Object> subscribePackage(String principal, Long packageId, String paymentMethod) {
        User user = findUserByPrincipal(principal);

        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
            "SELECT id, name, price, duration_days FROM service_packages WHERE id = ? AND status = 'ACTIVE'",
            packageId
        );
        if (pkgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gói dịch vụ không tồn tại hoặc đã ngừng cung cấp.");
        }
        Map<String, Object> pkg = pkgs.get(0);
        int durationDays = ((Number) pkg.get("duration_days")).intValue();
        double amount = ((Number) pkg.get("price")).doubleValue();
        String method = (paymentMethod == null || paymentMethod.isBlank()) ? "QR_TRANSFER" : paymentMethod.trim().toUpperCase();

        // Kiểm tra xem có đang có gói active không để cộng dồn ngày
        List<Map<String, Object>> currentActive = jdbcTemplate.queryForList(
            "SELECT end_at FROM subscriptions WHERE user_id = ? AND status = 'ACTIVE' AND end_at > NOW() ORDER BY end_at DESC LIMIT 1",
            user.getId()
        );

        LocalDateTime startAt = LocalDateTime.now();
        LocalDateTime endAt;
        if (!currentActive.isEmpty()) {
            // Gia hạn cộng dồn thời gian
            java.sql.Timestamp existingEnd = (java.sql.Timestamp) currentActive.get(0).get("end_at");
            LocalDateTime currentEndDate = existingEnd.toLocalDateTime();
            endAt = currentEndDate.plusDays(durationDays);
        } else {
            endAt = startAt.plusDays(durationDays);
        }

        // Tạo bản ghi Subscription
        jdbcTemplate.update(
            "INSERT INTO subscriptions (user_id, service_package_id, start_at, end_at, auto_renew, status) " +
            "VALUES (?, ?, ?, ?, FALSE, 'ACTIVE')",
            user.getId(), packageId, startAt, endAt
        );

        Long subscriptionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // Tạo giao dịch thanh toán thành công
        String txCode = "TX-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000);
        jdbcTemplate.update(
            "INSERT INTO payment_transactions (subscription_id, amount, payment_method, provider, provider_transaction_id, status, paid_at) " +
            "VALUES (?, ?, ?, 'SANDBOX', ?, 'SUCCESS', NOW())",
            subscriptionId, amount, method, txCode
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Đăng ký thành công " + pkg.get("name") + "!");
        result.put("subscriptionId", subscriptionId);
        result.put("transactionCode", txCode);
        result.put("packageName", pkg.get("name"));
        result.put("amount", amount);
        result.put("startAt", startAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("expiresAt", endAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("isPremium", true);

        return result;
    }

    /**
     * Dành riêng cho DEV / DEMO: Bật / Tắt Premium tức thì cho tài khoản hiện tại hoặc một user cụ thể.
     */
    @Transactional
    public Map<String, Object> devTogglePremium(String principal, boolean enable, Integer days, Long targetPackageId) {
        User user = findUserByPrincipal(principal);
        int duration = (days != null && days > 0) ? days : 30;

        // Luôn hủy các gói cũ đang active trước khi kích hoạt gói mới hoặc khi chuyển về Free
        jdbcTemplate.update(
            "UPDATE subscriptions SET status = 'EXPIRED' WHERE user_id = ? AND status = 'ACTIVE'",
            user.getId()
        );

        if (enable) {
            initDefaultPackages();
            Long packageId = targetPackageId;
            if (packageId == null) {
                packageId = jdbcTemplate.queryForObject(
                    "SELECT id FROM service_packages WHERE status = 'ACTIVE' ORDER BY display_order ASC LIMIT 1",
                    Long.class
                );
            }
            if (packageId == null) packageId = 1L;

            Map<String, Object> pkg = jdbcTemplate.queryForMap(
                "SELECT id, name, price, duration_days, tier_id FROM service_packages WHERE id = ?",
                packageId
            );

            LocalDateTime startAt = LocalDateTime.now();
            LocalDateTime endAt = startAt.plusDays(duration);

            jdbcTemplate.update(
                "INSERT INTO subscriptions (user_id, service_package_id, start_at, end_at, auto_renew, status) " +
                "VALUES (?, ?, ?, ?, FALSE, 'ACTIVE')",
                user.getId(), packageId, startAt, endAt
            );
            Long subId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            jdbcTemplate.update(
                "INSERT INTO payment_transactions (subscription_id, amount, payment_method, provider, provider_transaction_id, status, paid_at) " +
                "VALUES (?, 0, 'DEV_TEST', 'DEV_SANDBOX', CONCAT('DEV-', NOW()), 'SUCCESS', NOW())",
                subId
            );

            // Xác định tier trả về trực tiếp từ tier_id
            String tier = (String) pkg.get("tier_id");
            if (tier == null || tier.isBlank()) {
                String pkgName = (String) pkg.get("name");
                tier = inferTierFromName(pkgName);
            }

            return Map.of(
                "success", true,
                "isPremium", true,
                "tier", tier,
                "packageId", packageId,
                "packageName", pkg.get("name"),
                "message", "Đã chuyển tài khoản sang " + pkg.get("name") + " (" + duration + " ngày) thành công!",
                "expiresAt", endAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            );
        } else {
            return Map.of(
                "success", true,
                "isPremium", false,
                "tier", "FREE",
                "packageName", "Tài khoản Miễn phí",
                "message", "Đã chuyển tài khoản " + user.getUsername() + " về trạng thái Miễn phí (Free)."
            );
        }
    }

    private User findUserByPrincipal(String principal) {
        return userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Không tìm thấy thông tin tài khoản người dùng."
            ));
    }
}
