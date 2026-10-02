package com.laphuth.moodify.services;

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

    public SubscriptionService(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    /**
     * Tự động khởi tạo dữ liệu mẫu cho các gói dịch vụ nếu bảng chưa có hoặc cập nhật gói chuẩn.
     */
    @PostConstruct
    public void initDefaultPackages() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_packages WHERE status = 'ACTIVE'",
                Integer.class
            );
            if (count == null || count < 3) {
                // Đảm bảo có các gói chuẩn theo bảng giá đề xuất
                jdbcTemplate.update(
                    "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status) " +
                    "VALUES (1, 'Gói Cá Nhân (30 Ngày)', 'Dành cho 1 tài khoản: Chặn 100% quảng cáo, tải nhạc offline, phát tùy thích.', 49000, 30, 1, 'ACTIVE') " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), status = 'ACTIVE'"
                );
                jdbcTemplate.update(
                    "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status) " +
                    "VALUES (2, 'Gói Cá Nhân (90 Ngày)', 'Tiết kiệm 12%: 3 tháng nghe nhạc thả ga, đầy đủ đặc quyền VIP.', 129000, 90, 2, 'ACTIVE') " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), status = 'ACTIVE'"
                );
                jdbcTemplate.update(
                    "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status) " +
                    "VALUES (3, 'Gói Cá Nhân (1 Năm)', 'Tiết kiệm 20% (Tặng 2 tháng): Trọn gói 365 ngày âm nhạc không giới hạn.', 469000, 365, 3, 'ACTIVE') " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), status = 'ACTIVE'"
                );
                jdbcTemplate.update(
                    "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status) " +
                    "VALUES (4, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản (~13.1k/người): Mỗi người có thư viện và playlist riêng biệt.', 79000, 30, 4, 'ACTIVE') " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), status = 'ACTIVE'"
                );
                jdbcTemplate.update(
                    "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status) " +
                    "VALUES (5, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 790000, 365, 5, 'ACTIVE') " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = VALUES(price), duration_days = VALUES(duration_days), status = 'ACTIVE'"
                );
            }
        } catch (Exception e) {
            System.err.println("Note: Auto-init service_packages encountered: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách các gói dịch vụ đang hoạt động.
     */
    public List<Map<String, Object>> getActivePackages() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, description, price, duration_days, display_order " +
            "FROM service_packages " +
            "WHERE status = 'ACTIVE' " +
            "ORDER BY display_order ASC, price ASC"
        );
    }

    /**
     * Lấy thông tin bản quyền và gói cước hiện tại của người dùng.
     */
    public Map<String, Object> getCurrentUserSubscription(String principal) {
        User user = findUserByPrincipal(principal);
        Map<String, Object> res = new HashMap<>();

        List<Map<String, Object>> activeSubs = jdbcTemplate.queryForList(
            "SELECT s.id as subscription_id, s.service_package_id, s.start_at, s.end_at, s.status, " +
            "       p.name as package_name, p.price, p.duration_days, " +
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
            boolean isFamily = pkgName != null && pkgName.contains("Gia Đình");

            res.put("isPremium", true);
            res.put("tier", isFamily ? "FAMILY" : "INDIVIDUAL");
            res.put("subscriptionId", sub.get("subscription_id"));
            res.put("packageId", sub.get("service_package_id"));
            res.put("packageName", pkgName);
            res.put("price", sub.get("price"));
            res.put("startAt", sub.get("start_at") != null ? sub.get("start_at").toString() : null);
            res.put("expiresAt", sub.get("end_at") != null ? sub.get("end_at").toString() : null);
            res.put("daysRemaining", sub.get("days_remaining") != null ? ((Number) sub.get("days_remaining")).longValue() : 0L);
            res.put("benefits", List.of(
                "AD_FREE",
                "OFFLINE_DOWNLOAD",
                "ON_DEMAND",
                "VIP_MASCOT",
                "ROYAL_GOLD_THEME"
            ));
        } else {
            res.put("isPremium", false);
            res.put("tier", "FREE");
            res.put("packageName", "Tài khoản Miễn phí");
            res.put("daysRemaining", 0L);
            res.put("benefits", Collections.emptyList());
        }

        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        return res;
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
    public Map<String, Object> devTogglePremium(String principal, boolean enable, Integer days) {
        User user = findUserByPrincipal(principal);
        int duration = (days != null && days > 0) ? days : 30;

        if (enable) {
            // Đảm bảo có gói số 1
            initDefaultPackages();
            Long packageId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_packages WHERE status = 'ACTIVE' ORDER BY display_order ASC LIMIT 1",
                Long.class
            );
            if (packageId == null) packageId = 1L;

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

            return Map.of(
                "success", true,
                "isPremium", true,
                "message", "Đã kích hoạt gói Moodify VIP (" + duration + " ngày) cho tài khoản " + user.getUsername() + " thành công!",
                "expiresAt", endAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            );
        } else {
            // Hủy kích hoạt tất cả gói active
            jdbcTemplate.update(
                "UPDATE subscriptions SET status = 'EXPIRED' WHERE user_id = ? AND status = 'ACTIVE'",
                user.getId()
            );
            return Map.of(
                "success", true,
                "isPremium", false,
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
