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
     * Tự động khởi tạo dữ liệu mẫu cho các gói dịch vụ nếu bảng chưa có hoặc cập nhật gói chuẩn.
     * Mỗi gói được seed sẵn features_json (quyền hạn) để phân quyền theo gói hoạt động ngay.
     */
    @PostConstruct
    public void initDefaultPackages() {
        try {
            String basicJson = entitlementsJson("INDIVIDUAL_BASIC", "DAILY_QUOTA", 15, 7, "LIMITED", 30, "HQ_320", true, 50, 1, false, 0);
            String fullJson = entitlementsJson("INDIVIDUAL_FULL", "NO_ADS", 0, 0, "UNLIMITED", 0, "LOSSLESS_FLAC", true, 999, 1, false, 0);
            String familyJson = entitlementsJson("FAMILY", "NO_ADS", 0, 0, "UNLIMITED", 0, "LOSSLESS_FLAC", true, 999, 6, true, 6);

            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (1, 'Gói VIP Tiết Kiệm (30 Ngày)', 'Dành cho 1 người: 15 bài hát/ngày không quảng cáo, 30 lượt skip/ngày, tải 50 bài offline.', 29000, 30, 1, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                basicJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (2, 'Gói VIP Tiết Kiệm (1 Năm)', 'Tiết kiệm 20%: Trọn gói 365 ngày nghe nhạc tiết kiệm.', 279000, 365, 2, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                basicJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (3, 'Gói Cá Nhân FULL (30 Ngày)', 'Dành cho 1 người: 100% không quảng cáo vô hạn, chuyển bài và tải nhạc offline vô hạn.', 49000, 30, 3, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                fullJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (4, 'Gói Cá Nhân FULL (90 Ngày)', 'Tiết kiệm 12%: 3 tháng âm nhạc không quảng cáo vô hạn.', 129000, 90, 4, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                fullJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (5, 'Gói Cá Nhân FULL (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng, trọn bộ đặc quyền cá nhân không giới hạn.', 469000, 365, 5, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                fullJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (6, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản/thiết bị đồng thời: Trọn bộ đặc quyền FULL chia sẻ cả gia đình.', 79000, 30, 6, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                familyJson
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, features_json) " +
                "VALUES (7, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 790000, 365, 7, 'ACTIVE', ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), features_json = VALUES(features_json), status = 'ACTIVE'",
                familyJson
            );
        } catch (Exception e) {
            System.err.println("Note: Auto-init service_packages encountered: " + e.getMessage());
        }
    }

    /** Sinh chuỗi JSON quyền hạn (features_json) cho một gói. */
    private String entitlementsJson(String tier, String adPolicy, int adFreeDailyLimit, int adIntervalAfterLimit,
                                    String skipPolicy, int skipDailyLimit, String audioQuality,
                                    boolean offlineAllowed, int offlineMaxTracks, int maxDevices,
                                    boolean familySharing, int familyMembers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", tier);
        m.put("adPolicy", adPolicy);
        m.put("adFreeDailyLimit", adFreeDailyLimit);
        m.put("adIntervalAfterLimit", adIntervalAfterLimit);
        m.put("skipPolicy", skipPolicy);
        m.put("skipDailyLimit", skipDailyLimit);
        m.put("audioQuality", audioQuality);
        m.put("offlineAllowed", offlineAllowed);
        m.put("offlineMaxTracks", offlineMaxTracks);
        m.put("maxDevices", maxDevices);
        m.put("syncedLyrics", true);
        m.put("vipBadge", true);
        m.put("customThemes", true);
        m.put("familySharing", familySharing);
        if (familySharing) {
            m.put("familyMembers", familyMembers);
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lấy danh sách các gói dịch vụ đang hoạt động (kèm quyền hạn theo gói).
     */
    public List<Map<String, Object>> getActivePackages() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, name, description, price, duration_days, display_order, features_json " +
            "FROM service_packages " +
            "WHERE status = 'ACTIVE' " +
            "ORDER BY display_order ASC, price ASC"
        );
        for (Map<String, Object> row : rows) {
            String featuresJson = row.get("features_json") != null ? row.get("features_json").toString() : null;
            Map<String, Object> entitlements = parseEntitlements(featuresJson);
            if (entitlements.get("tier") == null) {
                entitlements.put("tier", inferTierFromName((String) row.get("name")));
            }
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
            "       p.name as package_name, p.price, p.duration_days, p.features_json, " +
            "       DATEDIFF(s.end_at, NOW()) as days_remaining " +
            "FROM subscriptions s " +
            "JOIN service_packages p ON s.service_package_id = p.id " +
            "WHERE s.user_id = ? AND s.status = 'ACTIVE' AND s.end_at > NOW() " +
            "ORDER BY s.end_at DESC " +
            "LIMIT 1",
            user.getId()
        );

        if (!activeSubs.isEmpty()) {
            Map<String, Object> sub = activeSubs.get(0);
            String pkgName = (String) sub.get("package_name");
            String featuresJson = (String) sub.get("features_json");
            Map<String, Object> entitlements = parseEntitlements(featuresJson);

            // Ưu tiên tier khai báo trong features_json (phân quyền theo gói),
            // chỉ fallback về so-tên-gói khi admin chưa cấu hình entitlements.
            String tier = (String) entitlements.get("tier");
            if (tier == null || tier.isBlank()) {
                tier = inferTierFromName(pkgName);
            }
            if (!Set.of("FAMILY", "INDIVIDUAL_FULL", "INDIVIDUAL_BASIC").contains(tier)) {
                tier = inferTierFromName(pkgName);
            }
            entitlements.put("tier", tier);

            res.put("isPremium", true);
            res.put("tier", tier);
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
            res.put("isPremium", false);
            res.put("tier", "FREE");
            res.put("packageName", "Tài khoản Miễn phí");
            res.put("daysRemaining", 0L);
            res.put("entitlements", freeEntitlements());
            res.put("benefits", Collections.emptyList());
        }

        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        return res;
    }

    /** Đọc quyền hạn (entitlements) từ cột features_json của gói. */
    private Map<String, Object> parseEntitlements(String featuresJson) {
        Map<String, Object> defaults = defaultEntitlements();
        if (featuresJson == null || featuresJson.isBlank()) {
            return defaults;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(featuresJson, Map.class);
            if (parsed.containsKey("features") && parsed.get("features") instanceof List) {
                // Chỉ chứa mảng bullet mô tả -> không phải entitlements, dùng mặc định
                return defaults;
            }
            // Ghi đè mặc định bằng giá trị admin cấu hình
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (e.getValue() != null) {
                    defaults.put(e.getKey(), e.getValue());
                }
            }
            return defaults;
        } catch (Exception e) {
            return defaults;
        }
    }

    /** Entitlements mặc định cho gói premium khi admin chưa cấu hình chi tiết. */
    private Map<String, Object> defaultEntitlements() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", null);
        m.put("adPolicy", "NO_ADS");
        m.put("adFreeDailyLimit", 0);
        m.put("adIntervalAfterLimit", 7);
        m.put("skipPolicy", "UNLIMITED");
        m.put("skipDailyLimit", 0);
        m.put("audioQuality", "HQ_320");
        m.put("offlineAllowed", true);
        m.put("offlineMaxTracks", 100);
        m.put("maxDevices", 1);
        m.put("syncedLyrics", true);
        m.put("vipBadge", true);
        m.put("customThemes", true);
        m.put("familySharing", false);
        return m;
    }

    /** Entitlements cho tài khoản FREE (chưa đăng ký gói). */
    private Map<String, Object> freeEntitlements() {
        Map<String, Object> m = defaultEntitlements();
        m.put("tier", "FREE");
        m.put("adPolicy", "FULL_ADS");
        m.put("adFreeDailyLimit", 0);
        m.put("adIntervalAfterLimit", 2);
        m.put("skipPolicy", "LIMITED");
        m.put("skipDailyLimit", 6);
        m.put("audioQuality", "STANDARD_128");
        m.put("offlineAllowed", false);
        m.put("offlineMaxTracks", 0);
        m.put("maxDevices", 1);
        m.put("syncedLyrics", false);
        m.put("vipBadge", false);
        m.put("customThemes", false);
        m.put("familySharing", false);
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
                "SELECT id, name, price, duration_days FROM service_packages WHERE id = ?",
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

            // Xác định tier trả về
            String pkgName = (String) pkg.get("name");
            String tier = "INDIVIDUAL_BASIC";
            if (pkgName != null && pkgName.contains("Gia Đình")) {
                tier = "FAMILY";
            } else if (pkgName != null && (pkgName.contains("FULL") || pkgName.contains("Full"))) {
                tier = "INDIVIDUAL_FULL";
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
