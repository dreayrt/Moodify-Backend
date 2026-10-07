package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.dto.payment.SepayWebhookDto;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SubscriptionService {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== Cấu hình SePay Payment (VietQR) =====
    private final String sepayBankAccount;
    private final String sepayBankName;
    private final String sepayAccountName;

    public SubscriptionService(
        JdbcTemplate jdbcTemplate,
        UserRepository userRepository,
        @Value("${sepay.bank-account:}") String sepayBankAccount,
        @Value("${sepay.bank-name:MBBank}") String sepayBankName,
        @Value("${sepay.account-name:}") String sepayAccountName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.sepayBankAccount = sepayBankAccount;
        this.sepayBankName = sepayBankName;
        this.sepayAccountName = sepayAccountName;
    }

    /**
     * Tự động khởi tạo dữ liệu mẫu cho các tầng gói dịch vụ (subscription_tiers)
     * và các gói cước bán hàng (service_packages) nếu bảng chưa có dữ liệu.
     */
    @PostConstruct
    public void initDefaultPackages() {
        try {
            // 0. Tự động tạo bảng subscription_tiers nếu chưa có (Hỗ trợ teammate pull code chạy được ngay)
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS subscription_tiers (" +
                "    id VARCHAR(50) PRIMARY KEY, " +
                "    name VARCHAR(100) NOT NULL, " +
                "    description VARCHAR(255) NULL, " +
                "    ad_policy VARCHAR(20) NOT NULL DEFAULT 'NO_ADS', " +
                "    ad_free_daily_limit INT NOT NULL DEFAULT 0, " +
                "    skip_policy VARCHAR(20) NOT NULL DEFAULT 'UNLIMITED', " +
                "    skip_daily_limit INT NOT NULL DEFAULT 0, " +
                "    offline_allowed BOOLEAN NOT NULL DEFAULT TRUE, " +
                "    offline_max_tracks INT NOT NULL DEFAULT 100, " +
                "    max_devices INT NOT NULL DEFAULT 1, " +
                "    synced_lyrics BOOLEAN NOT NULL DEFAULT TRUE, " +
                "    vip_badge BOOLEAN NOT NULL DEFAULT TRUE, " +
                "    family_sharing BOOLEAN NOT NULL DEFAULT FALSE, " +
                "    family_members INT NOT NULL DEFAULT 0" +
                ")"
            );

            // Tự động thêm cột tier_id vào service_packages nếu chưa có
            try {
                jdbcTemplate.execute("ALTER TABLE service_packages ADD COLUMN tier_id VARCHAR(50) NOT NULL DEFAULT 'INDIVIDUAL_BASIC'");
            } catch (Exception ignored) {}

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
            LocalDateTime currentEndDate = toLocalDateTime(currentActive.get(0).get("end_at"));
            endAt = (currentEndDate != null ? currentEndDate : startAt).plusDays(durationDays);
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

    // ======================================================================
    //  SEPAY PAYMENT: Luồng thanh toán chuyển khoản ngân hàng qua VietQR
    // ======================================================================

    /**
     * Tạo đơn thanh toán PENDING và sinh mã QR VietQR để người dùng chuyển khoản.
     * Hỗ trợ cơ chế Idempotency: nếu client gửi idempotencyKey hoặc có đơn PENDING cùng gói chưa hết hạn,
     * sẽ trả về kết quả cũ thay vì tạo trùng đơn hàng.
     */
    @Transactional
    public Map<String, Object> createPendingPayment(String principal, Long packageId) {
        return createPendingPayment(principal, packageId, null);
    }

    /**
     * Tạo đơn thanh toán PENDING kèm theo Idempotency-Key từ client.
     */
    @Transactional
    public Map<String, Object> createPendingPayment(String principal, Long packageId, String idempotencyKey) {
        User user = findUserByPrincipal(principal);

        // 0. Kiểm tra Idempotency Key nếu client có gửi
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Map<String, Object>> existingKeys = jdbcTemplate.queryForList(
                "SELECT response_payload FROM payment_idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                user.getId(), idempotencyKey.trim()
            );
            if (!existingKeys.isEmpty()) {
                try {
                    String cachedJson = (String) existingKeys.get(0).get("response_payload");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cachedResult = objectMapper.readValue(cachedJson, Map.class);
                    // Cập nhật lại status thực tế từ DB nếu có thay đổi
                    String orderCode = (String) cachedResult.get("orderCode");
                    if (orderCode != null) {
                        try {
                            Map<String, Object> currentStatus = getPaymentStatus(orderCode);
                            cachedResult.put("status", currentStatus.get("status"));
                        } catch (Exception ignored) {}
                    }
                    cachedResult.put("idempotentReplay", true);
                    System.out.println("[SePay Checkout Idempotency] Trả về cached response cho key: " + idempotencyKey);
                    return cachedResult;
                } catch (Exception e) {
                    System.err.println("[SePay Checkout Idempotency] Lỗi đọc cached response: " + e.getMessage());
                }
            }
        }

        // 1. Lấy thông tin gói dịch vụ
        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
            "SELECT id, name, price, duration_days FROM service_packages WHERE id = ? AND status = 'ACTIVE'",
            packageId
        );
        if (pkgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gói dịch vụ không tồn tại hoặc đã ngừng cung cấp.");
        }
        Map<String, Object> pkg = pkgs.get(0);
        double amount = ((Number) pkg.get("price")).doubleValue();
        int durationDays = ((Number) pkg.get("duration_days")).intValue();

        // 2. Tự nhiên chống click đúp: nếu user có đơn PENDING cùng gói vừa tạo trong 10 phút gần đây và chưa hết hạn,
        // trả về luôn đơn đó mà không cần tạo mới
        List<Map<String, Object>> activePending = jdbcTemplate.queryForList(
            "SELECT pt.id as payment_id, pt.subscription_id, pt.amount, pt.provider_transaction_id, pt.created_at " +
            "FROM payment_transactions pt " +
            "JOIN subscriptions s ON pt.subscription_id = s.id " +
            "WHERE s.user_id = ? AND s.service_package_id = ? AND pt.status = 'PENDING' AND s.status = 'PENDING' " +
            "  AND pt.created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE) " +
            "ORDER BY pt.id DESC LIMIT 1",
            user.getId(), packageId
        );

        if (!activePending.isEmpty()) {
            Map<String, Object> existingTx = activePending.get(0);
            Long existingPaymentId = ((Number) existingTx.get("payment_id")).longValue();
            Long existingSubscriptionId = ((Number) existingTx.get("subscription_id")).longValue();
            String existingOrderCode = "MD" + existingPaymentId;
            long amountLong = (long) amount;

            String qrUrl = String.format(
                "https://qr.sepay.vn/img?acc=%s&bank=%s&amount=%d&des=%s&template=compact",
                URLEncoder.encode(sepayBankAccount, StandardCharsets.UTF_8),
                URLEncoder.encode(sepayBankName, StandardCharsets.UTF_8),
                amountLong,
                URLEncoder.encode(existingOrderCode, StandardCharsets.UTF_8)
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("orderCode", existingOrderCode);
            result.put("paymentId", existingPaymentId);
            result.put("subscriptionId", existingSubscriptionId);
            result.put("packageId", packageId);
            result.put("packageName", pkg.get("name"));
            result.put("amount", amountLong);
            result.put("bankAccount", sepayBankAccount);
            result.put("bankName", sepayBankName);
            result.put("accountName", sepayAccountName);
            result.put("transferContent", existingOrderCode);
            result.put("qrUrl", qrUrl);
            result.put("status", "PENDING");
            result.put("idempotentReplay", true);
            result.put("message", "Đơn hàng đang chờ thanh toán. Vui lòng quét mã QR hoặc chuyển khoản với nội dung: " + existingOrderCode);

            saveIdempotencyKey(user.getId(), idempotencyKey, packageId, result);
            System.out.println("[SePay Checkout] Tái sử dụng đơn PENDING còn hiệu lực: " + existingOrderCode + " cho user: " + user.getUsername());
            return result;
        }

        // Hủy các đơn PENDING cũ khác của user để tránh xung đột
        jdbcTemplate.update(
            "UPDATE payment_transactions SET status = 'CANCELLED' " +
            "WHERE subscription_id IN (SELECT id FROM subscriptions WHERE user_id = ? AND status = 'PENDING') " +
            "AND status = 'PENDING'",
            user.getId()
        );
        jdbcTemplate.update(
            "UPDATE subscriptions SET status = 'CANCELLED' WHERE user_id = ? AND status = 'PENDING'",
            user.getId()
        );

        // 3. Tạo subscription trạng thái PENDING
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO subscriptions (user_id, service_package_id, start_at, end_at, auto_renew, status) " +
            "VALUES (?, ?, ?, ?, FALSE, 'PENDING')",
            user.getId(), packageId, now, now.plusDays(durationDays)
        );
        Long subscriptionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 4. Tạo payment_transaction trạng thái PENDING
        jdbcTemplate.update(
            "INSERT INTO payment_transactions (subscription_id, amount, payment_method, provider, status) " +
            "VALUES (?, ?, 'QR_TRANSFER', 'SEPAY', 'PENDING')",
            subscriptionId, amount
        );
        Long paymentId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 5. Sinh mã đơn hàng: MD + paymentId (ví dụ: MD1001)
        String orderCode = "MD" + paymentId;

        // Cập nhật provider_transaction_id = orderCode để tra cứu sau
        jdbcTemplate.update(
            "UPDATE payment_transactions SET provider_transaction_id = ? WHERE id = ?",
            orderCode, paymentId
        );

        // 6. Sinh URL ảnh mã QR VietQR từ SePay
        long amountLong = (long) amount;
        String qrContent = orderCode; // Nội dung chuyển khoản = mã đơn hàng
        String qrUrl = String.format(
            "https://qr.sepay.vn/img?acc=%s&bank=%s&amount=%d&des=%s&template=compact",
            URLEncoder.encode(sepayBankAccount, StandardCharsets.UTF_8),
            URLEncoder.encode(sepayBankName, StandardCharsets.UTF_8),
            amountLong,
            URLEncoder.encode(qrContent, StandardCharsets.UTF_8)
        );

        // 7. Trả kết quả về Frontend
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderCode", orderCode);
        result.put("paymentId", paymentId);
        result.put("subscriptionId", subscriptionId);
        result.put("packageId", packageId);
        result.put("packageName", pkg.get("name"));
        result.put("amount", amountLong);
        result.put("bankAccount", sepayBankAccount);
        result.put("bankName", sepayBankName);
        result.put("accountName", sepayAccountName);
        result.put("transferContent", qrContent);
        result.put("qrUrl", qrUrl);
        result.put("status", "PENDING");
        result.put("message", "Vui lòng quét mã QR hoặc chuyển khoản với nội dung: " + orderCode);

        // Lưu idempotency key nếu client có gửi
        saveIdempotencyKey(user.getId(), idempotencyKey, packageId, result);

        System.out.println("[SePay Checkout] Tạo đơn thanh toán: " + orderCode +
            " | Gói: " + pkg.get("name") + " | Số tiền: " + amountLong + "đ" +
            " | User: " + user.getUsername());

        return result;
    }

    /**
     * Xử lý webhook callback từ SePay khi có giao dịch tiền vào tài khoản ngân hàng.
     * Tích hợp toàn diện cơ chế Idempotency chống retry trùng lặp và race-condition.
     */
    @Transactional
    public Map<String, Object> processSepayWebhook(SepayWebhookDto webhook) {
        return processSepayWebhook(webhook, null);
    }

    /**
     * Xử lý webhook callback từ SePay kèm raw payload để lưu audit log.
     */
    @Transactional
    public Map<String, Object> processSepayWebhook(SepayWebhookDto webhook, String rawPayload) {
        Long sepayTxId = webhook.getId();

        // 1. Kiểm tra Idempotency tầng Webhook qua payment_webhook_logs:
        // Nếu giao dịch này đã từng được xử lý trước đó từ cổng SePay (do SePay retry)
        if (sepayTxId != null) {
            List<Map<String, Object>> existingLogs = jdbcTemplate.queryForList(
                "SELECT id, status, order_code, message FROM payment_webhook_logs WHERE provider = 'SEPAY' AND provider_transaction_id = ?",
                String.valueOf(sepayTxId)
            );
            if (!existingLogs.isEmpty()) {
                Map<String, Object> logRow = existingLogs.get(0);
                String logStatus = (String) logRow.get("status");
                String loggedOrderCode = (String) logRow.get("order_code");
                System.out.println("[Payment Webhook Idempotency] ⚠️ Webhook lặp lại! Provider=SEPAY, TxId=" + sepayTxId +
                    " đã được xử lý với status=" + logStatus + " (orderCode=" + loggedOrderCode + ")");

                return Map.of(
                    "success", true,
                    "isDuplicate", true,
                    "message", "Giao dịch đã được ghi nhận trước đó (Idempotent response).",
                    "orderCode", (loggedOrderCode != null ? loggedOrderCode : ""),
                    "provider", "SEPAY",
                    "sepayTransactionId", sepayTxId
                );
            }
        }

        // 2. Chỉ xử lý giao dịch tiền vào
        if (webhook.getTransferType() == null || !webhook.getTransferType().equalsIgnoreCase("in")) {
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), null, null,
                webhook.getTransferAmount(), "out", rawPayload, "IGNORED", "Bỏ qua giao dịch tiền ra.");
            return Map.of("success", false, "message", "Bỏ qua giao dịch tiền ra.");
        }

        String content = webhook.getContent();
        if (content == null || content.isBlank()) {
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), null, null,
                webhook.getTransferAmount(), "in", rawPayload, "FAILED", "Nội dung chuyển khoản trống.");
            return Map.of("success", false, "message", "Nội dung chuyển khoản trống.");
        }

        // 3. Tìm mã đơn hàng MD<id> trong nội dung chuyển khoản
        Pattern pattern = Pattern.compile("MD(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            System.out.println("[SePay Webhook] Không tìm thấy mã đơn hàng (MD...) trong nội dung: " + content);
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), null, null,
                webhook.getTransferAmount(), "in", rawPayload, "FAILED", "Không tìm thấy mã đơn hàng trong nội dung chuyển khoản.");
            return Map.of("success", false, "message", "Không tìm thấy mã đơn hàng trong nội dung chuyển khoản.");
        }

        String orderCode = matcher.group(0).toUpperCase(); // "MD1001"
        Long paymentId = Long.valueOf(matcher.group(1));     // 1001

        System.out.println("[SePay Webhook] Tìm thấy mã đơn hàng: " + orderCode + " (paymentId=" + paymentId + ")");

        // 4. Pessimistic Lock (FOR UPDATE) để ngăn chặn Race Condition khi 2 webhook đồng thời đến
        List<Map<String, Object>> txRows = jdbcTemplate.queryForList(
            "SELECT pt.id, pt.subscription_id, pt.amount, pt.status, " +
            "       s.user_id, s.service_package_id, sp.duration_days, sp.name as package_name " +
            "FROM payment_transactions pt " +
            "JOIN subscriptions s ON pt.subscription_id = s.id " +
            "JOIN service_packages sp ON s.service_package_id = sp.id " +
            "WHERE pt.id = ? FOR UPDATE",
            paymentId
        );

        if (txRows.isEmpty()) {
            System.out.println("[SePay Webhook] Không tìm thấy giao dịch với paymentId=" + paymentId);
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), orderCode, paymentId,
                webhook.getTransferAmount(), "in", rawPayload, "FAILED", "Không tìm thấy đơn hàng " + orderCode);
            return Map.of("success", false, "message", "Không tìm thấy đơn hàng " + orderCode);
        }

        Map<String, Object> tx = txRows.get(0);
        String currentStatus = (String) tx.get("status");

        // 5. Kiểm tra Idempotency trạng thái: Nếu đơn hàng đã SUCCESS trước đó
        if ("SUCCESS".equals(currentStatus)) {
            System.out.println("[SePay Webhook Idempotency] Đơn " + orderCode + " đã SUCCESS trước đó. Trả về kết quả an toàn.");
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), orderCode, paymentId,
                webhook.getTransferAmount(), "in", rawPayload, "DUPLICATE", "Đơn hàng đã thanh toán thành công trước đó.");
            return Map.of(
                "success", true,
                "isDuplicate", true,
                "message", "Đơn hàng đã được thanh toán thành công trước đó (Idempotent response).",
                "orderCode", orderCode
            );
        }

        if (!"PENDING".equals(currentStatus)) {
            System.out.println("[SePay Webhook] Đơn " + orderCode + " ở trạng thái không thể xử lý: " + currentStatus);
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), orderCode, paymentId,
                webhook.getTransferAmount(), "in", rawPayload, "FAILED", "Trạng thái đơn hàng không hợp lệ: " + currentStatus);
            return Map.of("success", false, "message", "Đơn hàng " + orderCode + " đang ở trạng thái: " + currentStatus);
        }

        // 6. Kiểm tra số tiền
        double expectedAmount = ((Number) tx.get("amount")).doubleValue();
        double receivedAmount = webhook.getTransferAmount() != null ? webhook.getTransferAmount() : 0;

        if (receivedAmount < expectedAmount) {
            System.out.println("[SePay Webhook] ❌ Số tiền không đủ: nhận " + receivedAmount + " < cần " + expectedAmount);
            saveWebhookLog(sepayTxId, webhook.getReferenceCode(), orderCode, paymentId,
                receivedAmount, "in", rawPayload, "FAILED", "Số tiền không đủ: cần " + expectedAmount + ", nhận " + receivedAmount);
            return Map.of(
                "success", false,
                "message", "Số tiền chuyển khoản (" + (long) receivedAmount + "đ) không đủ so với giá gói (" + (long) expectedAmount + "đ).",
                "orderCode", orderCode
            );
        }

        // 7. ✅ Thanh toán thành công → Cập nhật payment_transaction
        String sepayRefCode = webhook.getReferenceCode() != null ? webhook.getReferenceCode() : "SEPAY-" + sepayTxId;
        jdbcTemplate.update(
            "UPDATE payment_transactions SET status = 'SUCCESS', provider_transaction_id = ?, paid_at = NOW() WHERE id = ?",
            orderCode + "|" + sepayRefCode, paymentId
        );

        // 8. Kích hoạt subscription → ACTIVE, tính lại end_at
        Long subscriptionId = ((Number) tx.get("subscription_id")).longValue();
        Long userId = ((Number) tx.get("user_id")).longValue();
        int durationDays = ((Number) tx.get("duration_days")).intValue();

        // Kiểm tra xem user có gói ACTIVE nào đang chạy không → cộng dồn
        List<Map<String, Object>> currentActive = jdbcTemplate.queryForList(
            "SELECT end_at FROM subscriptions WHERE user_id = ? AND status = 'ACTIVE' AND end_at > NOW() ORDER BY end_at DESC LIMIT 1",
            userId
        );

        LocalDateTime startAt = LocalDateTime.now();
        LocalDateTime endAt;
        if (!currentActive.isEmpty()) {
            LocalDateTime existingEnd = toLocalDateTime(currentActive.get(0).get("end_at"));
            endAt = (existingEnd != null ? existingEnd : startAt).plusDays(durationDays);
        } else {
            endAt = startAt.plusDays(durationDays);
        }

        jdbcTemplate.update(
            "UPDATE subscriptions SET status = 'ACTIVE', start_at = ?, end_at = ? WHERE id = ?",
            startAt, endAt, subscriptionId
        );

        String packageName = (String) tx.get("package_name");
        System.out.println("[SePay Webhook] ✅ THÀNH CÔNG! Đã kích hoạt gói " + packageName +
            " cho userId=" + userId + " | Hạn đến: " + endAt);

        // 9. Lưu vào log Webhook để khóa idempotency
        saveWebhookLog(sepayTxId, webhook.getReferenceCode(), orderCode, paymentId,
            receivedAmount, "in", rawPayload, "SUCCESS", "Kích hoạt gói " + packageName + " thành công");

        return Map.of(
            "success", true,
            "message", "Thanh toán thành công! Đã kích hoạt " + packageName,
            "orderCode", orderCode,
            "packageName", packageName,
            "expiresAt", endAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }

    private void saveIdempotencyKey(Long userId, String key, Long packageId, Map<String, Object> payload) {
        if (key == null || key.isBlank() || userId == null) return;
        try {
            String json = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update(
                "INSERT INTO payment_idempotency_keys (idempotency_key, user_id, provider, endpoint, package_id, response_payload) " +
                "VALUES (?, ?, 'SEPAY', 'CHECKOUT', ?, ?) ON DUPLICATE KEY UPDATE response_payload = VALUES(response_payload)",
                key.trim(), userId, packageId, json
            );
        } catch (Exception e) {
            System.err.println("[Payment Idempotency] Không thể lưu Idempotency Key: " + e.getMessage());
        }
    }

    private void saveWebhookLog(Long sepayTxId, String refCode, String orderCode, Long paymentId,
                                Double amount, String transferType, String rawPayload, String status, String message) {
        if (sepayTxId == null) return;
        try {
            jdbcTemplate.update(
                "INSERT INTO payment_webhook_logs (provider, provider_transaction_id, reference_code, order_code, payment_id, amount, transfer_type, raw_payload, status, message) " +
                "VALUES ('SEPAY', ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE status = VALUES(status), message = VALUES(message)",
                String.valueOf(sepayTxId), refCode, orderCode, paymentId, (amount != null ? amount : 0),
                (transferType != null ? transferType : "in"), rawPayload, status, message
            );
        } catch (Exception e) {
            System.err.println("[Payment Idempotency] Không thể lưu Webhook Log: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra trạng thái thanh toán của một đơn hàng (polling từ Frontend).
     *
     * @param orderCode Mã đơn hàng (ví dụ: MD1001)
     * @return Map chứa status (PENDING/SUCCESS/CANCELLED/EXPIRED), thông tin gói...
     */
    public Map<String, Object> getPaymentStatus(String orderCode) {
        // Tách paymentId từ orderCode: "MD1001" → 1001
        String idStr = orderCode.replaceAll("(?i)^MD", "");
        Long paymentId;
        try {
            paymentId = Long.valueOf(idStr);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã đơn hàng không hợp lệ: " + orderCode);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT pt.id, pt.status, pt.amount, pt.paid_at, " +
            "       s.status as sub_status, s.end_at, sp.name as package_name, sp.duration_days " +
            "FROM payment_transactions pt " +
            "JOIN subscriptions s ON pt.subscription_id = s.id " +
            "JOIN service_packages sp ON s.service_package_id = sp.id " +
            "WHERE pt.id = ?",
            paymentId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng " + orderCode);
        }

        Map<String, Object> row = rows.get(0);
        String paymentStatus = (String) row.get("status");

        // Kiểm tra nếu đơn PENDING quá 15 phút → tự động hết hạn
        if ("PENDING".equals(paymentStatus)) {
            // Kiểm tra thời gian tạo đơn
            List<Map<String, Object>> createdRows = jdbcTemplate.queryForList(
                "SELECT created_at FROM payment_transactions WHERE id = ?", paymentId
            );
            if (!createdRows.isEmpty()) {
                LocalDateTime created = toLocalDateTime(createdRows.get(0).get("created_at"));
                if (created != null && created.plusMinutes(15).isBefore(LocalDateTime.now())) {
                    // Đơn đã quá 15 phút → đánh dấu hết hạn
                    jdbcTemplate.update("UPDATE payment_transactions SET status = 'CANCELLED' WHERE id = ?", paymentId);
                    jdbcTemplate.update(
                        "UPDATE subscriptions SET status = 'CANCELLED' WHERE id = (SELECT subscription_id FROM payment_transactions WHERE id = ?)",
                        paymentId
                    );
                    paymentStatus = "EXPIRED";
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderCode", orderCode);
        result.put("status", paymentStatus);
        result.put("amount", row.get("amount"));
        result.put("packageName", row.get("package_name"));

        if ("SUCCESS".equals(paymentStatus)) {
            result.put("paidAt", row.get("paid_at") != null ? row.get("paid_at").toString() : null);
            result.put("expiresAt", row.get("end_at") != null ? row.get("end_at").toString() : null);
            result.put("isPremium", true);
        } else {
            result.put("isPremium", false);
        }

        return result;
    }

    private User findUserByPrincipal(String principal) {
        return userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Không tìm thấy thông tin tài khoản người dùng."
            ));
    }

    private LocalDateTime toLocalDateTime(Object dateObj) {
        if (dateObj == null) return null;
        if (dateObj instanceof LocalDateTime) {
            return (LocalDateTime) dateObj;
        }
        if (dateObj instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) dateObj).toLocalDateTime();
        }
        if (dateObj instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) dateObj).getTime()).toLocalDateTime();
        }
        try {
            return LocalDateTime.parse(dateObj.toString().replace(" ", "T"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
