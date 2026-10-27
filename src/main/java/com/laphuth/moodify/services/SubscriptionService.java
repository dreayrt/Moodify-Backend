package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.dto.payment.SepayWebhookDto;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SubscriptionService {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== Cấu hình SePay Payment (VietQR) =====
    private final String sepayBankAccount;
    private final String sepayBankName;
    private final String sepayAccountName;
    private final boolean maintenanceEnabled;

    public SubscriptionService(
        JdbcTemplate jdbcTemplate,
        UserRepository userRepository,
        @Value("${sepay.bank-account:}") String sepayBankAccount,
        @Value("${sepay.bank-name:MBBank}") String sepayBankName,
        @Value("${sepay.account-name:}") String sepayAccountName,
        @Value("${app.maintenance.enabled:true}") boolean maintenanceEnabled,
        NotificationService notificationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.sepayBankAccount = sepayBankAccount;
        this.sepayBankName = sepayBankName;
        this.sepayAccountName = sepayAccountName;
        this.maintenanceEnabled = maintenanceEnabled;
    }

    /**
     * Tự động khởi tạo dữ liệu mẫu cho các tầng gói dịch vụ (subscription_tiers)
     * và các gói cước bán hàng (service_packages) nếu bảng chưa có dữ liệu.
     */
    @PostConstruct
    public void initDefaultPackages() {
        if (!maintenanceEnabled) return;
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
            // Gói 1 & 2 (Tiết Kiệm): Đã ngưng cung cấp
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (1, 'Gói VIP Tiết Kiệm (30 Ngày)', 'Đã ngưng cung cấp.', 29000, 30, 1, 'INACTIVE', 'INDIVIDUAL_BASIC') " +
                "ON DUPLICATE KEY UPDATE status = 'INACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (2, 'Gói VIP Tiết Kiệm (1 Năm)', 'Đã ngưng cung cấp.', 279000, 365, 2, 'INACTIVE', 'INDIVIDUAL_BASIC') " +
                "ON DUPLICATE KEY UPDATE status = 'INACTIVE'"
            );
            // Gói 3, 4, 5 (VIP Cá Nhân FULL): Giảm giá gói
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (3, 'Gói Cá Nhân FULL (30 Ngày)', 'Dành cho 1 người: 100% không quảng cáo vô hạn, chuyển bài và tải nhạc offline vô hạn.', 29000, 30, 3, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = 29000, duration_days = 30, tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (4, 'Gói Cá Nhân FULL (90 Ngày)', 'Tiết kiệm 9%: 3 tháng âm nhạc không quảng cáo vô hạn.', 79000, 90, 4, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = 79000, duration_days = 90, tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (5, 'Gói Cá Nhân FULL (1 Năm)', 'Tiết kiệm tối đa: Trọn bộ đặc quyền cá nhân không giới hạn 365 ngày.', 279000, 365, 5, 'ACTIVE', 'INDIVIDUAL_FULL') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = 279000, duration_days = 365, tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            // Gói 6, 7 (Gia Đình): Giảm giá gói
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (6, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản/thiết bị đồng thời: Trọn bộ đặc quyền FULL chia sẻ cả gia đình.', 59000, 30, 6, 'ACTIVE', 'FAMILY') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = 59000, duration_days = 30, tier_id = VALUES(tier_id), status = 'ACTIVE'"
            );
            jdbcTemplate.update(
                "INSERT INTO service_packages (id, name, description, price, duration_days, display_order, status, tier_id) " +
                "VALUES (7, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 590000, 365, 7, 'ACTIVE', 'FAMILY') " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), price = 590000, duration_days = 365, tier_id = VALUES(tier_id), status = 'ACTIVE'"
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
            "WHERE s.user_id = ? AND s.status = 'ACTIVE' AND s.start_at <= NOW() AND s.end_at > NOW() " +
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

    /** Returns the signed-in user's subscription history with the payment receipts attached. */
    public List<Map<String, Object>> getMySubscriptionManagement(String principal) {
        User user = findUserByPrincipal(principal);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id AS subscription_id, s.service_package_id, s.start_at, s.end_at, " +
                        "s.auto_renew, s.status AS subscription_status, s.created_at AS subscription_created_at, " +
                        "p.name AS package_name, p.description AS package_description, p.price AS catalog_price, " +
                        "p.duration_days, p.tier_id, t.name AS tier_name, " +
                        "pt.id AS payment_id, pt.amount AS payment_amount, pt.payment_method, pt.provider, " +
                        "pt.provider_transaction_id, pt.status AS payment_status, pt.paid_at, " +
                        "pt.created_at AS payment_created_at " +
                        "FROM subscriptions s " +
                        "JOIN service_packages p ON p.id = s.service_package_id " +
                        "LEFT JOIN subscription_tiers t ON t.id = p.tier_id " +
                        "LEFT JOIN payment_transactions pt ON pt.subscription_id = s.id " +
                        "WHERE s.user_id = ? " +
                        "ORDER BY s.created_at DESC, s.id DESC, pt.created_at DESC, pt.id DESC",
                user.getId());

        Map<Long, Map<String, Object>> subscriptions = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> paymentsBySubscription = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> row : rows) {
            Long subscriptionId = ((Number) row.get("subscription_id")).longValue();
            Map<String, Object> subscription = subscriptions.get(subscriptionId);
            if (subscription == null) {
                LocalDateTime startAt = toLocalDateTime(row.get("start_at"));
                LocalDateTime endAt = toLocalDateTime(row.get("end_at"));
                String status = String.valueOf(row.get("subscription_status"));
                boolean current = "ACTIVE".equals(status) && startAt != null && !startAt.isAfter(now)
                        && endAt != null && endAt.isAfter(now);
                boolean scheduled = "ACTIVE".equals(status) && startAt != null && startAt.isAfter(now)
                        && endAt != null && endAt.isAfter(now);

                subscription = new LinkedHashMap<>();
                subscription.put("id", subscriptionId);
                subscription.put("packageId", row.get("service_package_id"));
                subscription.put("packageName", row.get("package_name"));
                subscription.put("packageDescription", row.get("package_description"));
                subscription.put("catalogPrice", row.get("catalog_price"));
                subscription.put("durationDays", row.get("duration_days"));
                subscription.put("tierId", row.get("tier_id"));
                subscription.put("tierName", row.get("tier_name"));
                subscription.put("startAt", startAt == null ? null : startAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                subscription.put("endAt", endAt == null ? null : endAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                subscription.put("createdAt", row.get("subscription_created_at") == null ? null : row.get("subscription_created_at").toString());
                subscription.put("autoRenew", Boolean.TRUE.equals(row.get("auto_renew")) || "1".equals(String.valueOf(row.get("auto_renew"))));
                subscription.put("status", status);
                subscription.put("isCurrent", current);
                subscription.put("isScheduled", scheduled);
                List<Map<String, Object>> subscriptionPayments = new ArrayList<>();
                subscription.put("payments", subscriptionPayments);
                subscriptions.put(subscriptionId, subscription);
                paymentsBySubscription.put(subscriptionId, subscriptionPayments);
            }

            if (row.get("payment_id") != null) {
                String providerTransactionId = row.get("provider_transaction_id") == null
                        ? null : String.valueOf(row.get("provider_transaction_id"));
                String orderCode = providerTransactionId;
                String providerReference = null;
                if (providerTransactionId != null && providerTransactionId.contains("|")) {
                    String[] parts = providerTransactionId.split("\\|", 2);
                    orderCode = parts[0];
                    providerReference = parts.length > 1 ? parts[1] : null;
                }
                Map<String, Object> payment = new LinkedHashMap<>();
                payment.put("id", row.get("payment_id"));
                payment.put("amount", row.get("payment_amount"));
                payment.put("paymentMethod", row.get("payment_method"));
                payment.put("provider", row.get("provider"));
                payment.put("orderCode", orderCode);
                payment.put("providerReference", providerReference);
                payment.put("status", row.get("payment_status"));
                payment.put("paidAt", row.get("paid_at") == null ? null : row.get("paid_at").toString());
                payment.put("createdAt", row.get("payment_created_at") == null ? null : row.get("payment_created_at").toString());
                paymentsBySubscription.get(subscriptionId).add(payment);
            }
        }

        for (Map.Entry<Long, Map<String, Object>> entry : subscriptions.entrySet()) {
            Map<String, Object> subscription = entry.getValue();
            List<Map<String, Object>> payments = paymentsBySubscription.get(entry.getKey());
            boolean hasPendingPayment = payments.stream().anyMatch(p -> "PENDING".equals(p.get("status")));
            boolean hasSuccessfulPayment = payments.stream().anyMatch(p -> "SUCCESS".equals(p.get("status")));
            boolean pendingOrder = "PENDING".equals(subscription.get("status")) && hasPendingPayment && !hasSuccessfulPayment;
            boolean cancellableSchedule = Boolean.TRUE.equals(subscription.get("isScheduled")) && hasSuccessfulPayment;
            subscription.put("canCancel", pendingOrder || cancellableSchedule);
            subscription.put("cancelKind", pendingOrder ? "PENDING_ORDER" : cancellableSchedule ? "SCHEDULED_PAID" : null);
        }

        return new ArrayList<>(subscriptions.values());
    }

    /** Cancels only an unpaid pending order or a paid subscription whose start date is still in the future. */
    @Transactional
    public Map<String, Object> cancelMyManagedSubscription(String principal, Long subscriptionId) {
        User user = findUserByPrincipal(principal);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, status, start_at, end_at FROM subscriptions " +
                        "WHERE id = ? AND user_id = ? FOR UPDATE",
                subscriptionId, user.getId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói dịch vụ trong tài khoản của bạn.");
        }

        Map<String, Object> subscription = rows.get(0);
        String status = String.valueOf(subscription.get("status"));
        LocalDateTime startAt = toLocalDateTime(subscription.get("start_at"));
        LocalDateTime endAt = toLocalDateTime(subscription.get("end_at"));
        List<Map<String, Object>> payments = jdbcTemplate.queryForList(
                "SELECT id, status FROM payment_transactions WHERE subscription_id = ? ORDER BY id DESC FOR UPDATE",
                subscriptionId);
        boolean hasPendingPayment = payments.stream().anyMatch(p -> "PENDING".equals(p.get("status")));
        boolean hasSuccessfulPayment = payments.stream().anyMatch(p -> "SUCCESS".equals(p.get("status")));
        LocalDateTime now = LocalDateTime.now();
        boolean pendingOrder = "PENDING".equals(status) && hasPendingPayment && !hasSuccessfulPayment;
        boolean scheduledPaid = "ACTIVE".equals(status) && startAt != null && startAt.isAfter(now)
                && endAt != null && endAt.isAfter(now) && hasSuccessfulPayment;
        if (!pendingOrder && !scheduledPaid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chỉ có thể hủy đơn đang chờ thanh toán hoặc gói đã thanh toán nhưng chưa bắt đầu.");
        }

        if (pendingOrder) {
            jdbcTemplate.update(
                    "UPDATE payment_transactions SET status = 'CANCELLED', updated_at = NOW() " +
                            "WHERE subscription_id = ? AND status = 'PENDING'",
                    subscriptionId);
        }
        jdbcTemplate.update(
                "UPDATE subscriptions SET status = 'CANCELLED', updated_at = NOW() " +
                        "WHERE id = ? AND user_id = ? AND status = ?",
                subscriptionId, user.getId(), status);

        String message = pendingOrder
                ? "Đơn thanh toán gói dịch vụ đã được hủy theo yêu cầu. Giao dịch chưa được xác nhận; nếu bạn đã chuyển tiền, vui lòng liên hệ hỗ trợ."
                : "Lịch áp dụng gói đã được hủy theo yêu cầu. Giao dịch thanh toán vẫn được lưu trong lịch sử và hệ thống không tự động hoàn tiền.";
        notificationService.notifySubscriptionManagementCancelled(
                user.getId(), subscriptionId, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("subscriptionId", subscriptionId);
        result.put("message", pendingOrder ? "Đã hủy đơn thanh toán đang chờ." : "Đã hủy lịch áp dụng gói.");
        result.put("refundIssued", false);
        return result;
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
            "SELECT id, name, price, duration_days, tier_id FROM service_packages WHERE id = ? AND status = 'ACTIVE'",
            packageId
        );
        if (pkgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gói dịch vụ không tồn tại hoặc đã ngừng cung cấp.");
        }
        Map<String, Object> pkg = pkgs.get(0);
        PriceBreakdown price = calculatePackagePrice(user.getId(), String.valueOf(pkg.get("tier_id")), pkg.get("price"));
        String method = (paymentMethod == null || paymentMethod.isBlank()) ? "QR_TRANSFER" : paymentMethod.trim().toUpperCase();

        PlanApplication plan = applyPaidPackage(user.getId(), pkg, price, LocalDateTime.now());

        // Tạo bản ghi Subscription
        jdbcTemplate.update(
            "INSERT INTO subscriptions (user_id, service_package_id, start_at, end_at, auto_renew, status) " +
            "VALUES (?, ?, ?, ?, FALSE, 'ACTIVE')",
            user.getId(), packageId, plan.startAt(), plan.endAt()
        );

        Long subscriptionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // Tạo giao dịch thanh toán thành công
        String txCode = "TX-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000);
        jdbcTemplate.update(
            "INSERT INTO payment_transactions (subscription_id, amount, payment_method, provider, provider_transaction_id, status, paid_at) " +
            "VALUES (?, ?, ?, 'SANDBOX', ?, 'SUCCESS', NOW())",
            subscriptionId, BigDecimal.valueOf(price.payableAmount()), method, txCode
        );
        notificationService.notifySubscriptionPaymentSucceeded(
                user.getId(), txCode, String.valueOf(pkg.get("name")),
                price.payableAmount(), plan.action(), plan.actionDescription(), plan.startAt(), plan.endAt());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Đăng ký thành công " + pkg.get("name") + "!");
        result.put("subscriptionId", subscriptionId);
        result.put("transactionCode", txCode);
        result.put("packageName", pkg.get("name"));
        result.put("baseAmount", price.baseAmount());
        result.put("discountAmount", price.discountAmount());
        result.put("discountPercent", price.discountApplied() ? 20 : 0);
        result.put("discountApplied", price.discountApplied());
        result.put("amount", price.payableAmount());
        result.put("startAt", plan.startAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("expiresAt", plan.endAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("action", plan.action());
        result.put("actionLabel", plan.actionLabel());
        result.put("actionDescription", plan.actionDescription());
        result.put("bonusDays", plan.bonusDays());
        result.put("isPremium", plan.startAt().isBefore(LocalDateTime.now()));

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
            if (packageId == null) packageId = 3L;

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

    /** Tạo đơn thanh toán PENDING và sinh mã QR VietQR để người dùng chuyển khoản. */
    @Transactional
    public Map<String, Object> createPendingPayment(String principal, Long packageId) {
        User user = findUserByPrincipal(principal);

        // 1. Lấy thông tin gói dịch vụ
        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
            "SELECT id, name, price, duration_days, tier_id FROM service_packages WHERE id = ? AND status = 'ACTIVE'",
            packageId
        );
        if (pkgs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gói dịch vụ không tồn tại hoặc đã ngừng cung cấp.");
        }
        Map<String, Object> pkg = pkgs.get(0);
        PriceBreakdown price = calculatePackagePrice(user.getId(), String.valueOf(pkg.get("tier_id")), pkg.get("price"));
        int durationDays = ((Number) pkg.get("duration_days")).intValue();
        PlanApplication preview = previewPaidPackage(user.getId(), pkg, price, LocalDateTime.now());

        // Hủy các đơn PENDING cũ khác của user và ghi lại kết quả vào hộp thư.
        cancelPendingPaymentsForUser(user.getId());

        // 2. Tạo subscription trạng thái PENDING
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO subscriptions (user_id, service_package_id, start_at, end_at, auto_renew, status) " +
            "VALUES (?, ?, ?, ?, FALSE, 'PENDING')",
            user.getId(), packageId, now, now.plusDays(durationDays)
        );
        Long subscriptionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 3. Tạo payment_transaction trạng thái PENDING
        jdbcTemplate.update(
            "INSERT INTO payment_transactions (subscription_id, amount, payment_method, provider, status) " +
            "VALUES (?, ?, 'QR_TRANSFER', 'SEPAY', 'PENDING')",
            subscriptionId, BigDecimal.valueOf(price.payableAmount())
        );
        Long paymentId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 4. Sinh mã đơn hàng: MD + paymentId (ví dụ: MD1001)
        String orderCode = "MD" + paymentId;

        // Cập nhật provider_transaction_id = orderCode để tra cứu sau
        jdbcTemplate.update(
            "UPDATE payment_transactions SET provider_transaction_id = ? WHERE id = ?",
            orderCode, paymentId
        );

        // 5. Sinh URL ảnh mã QR VietQR từ SePay
        long amountLong = price.payableAmount();
        String qrContent = orderCode; // Nội dung chuyển khoản = mã đơn hàng
        String qrUrl = String.format(
            "https://qr.sepay.vn/img?acc=%s&bank=%s&amount=%d&des=%s&template=compact",
            URLEncoder.encode(sepayBankAccount, StandardCharsets.UTF_8),
            URLEncoder.encode(sepayBankName, StandardCharsets.UTF_8),
            amountLong,
            URLEncoder.encode(qrContent, StandardCharsets.UTF_8)
        );

        // 6. Trả kết quả về Frontend
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderCode", orderCode);
        result.put("paymentId", paymentId);
        result.put("subscriptionId", subscriptionId);
        result.put("packageId", packageId);
        result.put("packageName", pkg.get("name"));
        result.put("baseAmount", price.baseAmount());
        result.put("discountAmount", price.discountAmount());
        result.put("discountPercent", price.discountApplied() ? 20 : 0);
        result.put("discountApplied", price.discountApplied());
        result.put("amount", amountLong);
        result.put("action", preview.action());
        result.put("actionLabel", preview.actionLabel());
        result.put("actionDescription", preview.actionDescription());
        result.put("startAt", preview.startAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("expiresAt", preview.endAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("bonusDays", preview.bonusDays());
        result.put("bankAccount", sepayBankAccount);
        result.put("bankName", sepayBankName);
        result.put("accountName", sepayAccountName);
        result.put("transferContent", qrContent);
        result.put("qrUrl", qrUrl);
        result.put("status", "PENDING");
        result.put("message", "Vui lòng quét mã QR hoặc chuyển khoản với nội dung: " + orderCode);

        System.out.println("[SePay Checkout] Tạo đơn thanh toán: " + orderCode +
            " | Gói: " + pkg.get("name") + " | Số tiền: " + amountLong + "đ" +
            " | User: " + user.getUsername());

        return result;
    }

    /**
     * Calculates the 20% returning-customer offer by entitlement tier. Package
     * variants with different durations share the same tier and offer eligibility.
     */
    private PriceBreakdown calculatePackagePrice(Long userId, String targetTierId, Object rawPrice) {
        BigDecimal catalogPrice = rawPrice instanceof BigDecimal value
                ? value
                : new BigDecimal(String.valueOf(rawPrice));

        Boolean hasPaidThisTierBefore = jdbcTemplate.queryForObject(
                "SELECT EXISTS (" +
                        "SELECT 1 FROM payment_transactions paid_tx " +
                        "JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id " +
                        "JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id " +
                        "WHERE paid_sub.user_id = ? " +
                        "AND paid_package.tier_id = ? " +
                        "AND paid_tx.status = 'SUCCESS' " +
                        "AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')" +
                        ")",
                Boolean.class, userId, targetTierId);

        return calculatePackagePrice(catalogPrice, Boolean.TRUE.equals(hasPaidThisTierBefore));
    }

    private PriceBreakdown calculatePackagePrice(BigDecimal catalogPrice, boolean discountApplied) {
        long baseAmount = catalogPrice.setScale(0, RoundingMode.HALF_UP).longValueExact();
        BigDecimal payable = discountApplied
                ? catalogPrice.multiply(new BigDecimal("0.80"))
                : catalogPrice;
        long payableAmount = payable.setScale(0, RoundingMode.HALF_UP).longValueExact();
        long discountAmount = Math.max(0L, baseAmount - payableAmount);
        return new PriceBreakdown(baseAmount, discountAmount, payableAmount, discountApplied);
    }

    private record PriceBreakdown(long baseAmount, long discountAmount, long payableAmount,
                                  boolean discountApplied) {}

    /** Returns a price and policy preview for each package visible in the catalog. */
    public List<Map<String, Object>> getPackageQuotes(String principal) {
        User user = findUserByPrincipal(principal);
        Set<String> paidTierIds = findPaidTierIds(user.getId());
        PlanContext context = getPlanContext(user.getId());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> quotes = new ArrayList<>();

        for (Map<String, Object> pkg : getActivePackages()) {
            String tierId = String.valueOf(pkg.get("tier_id"));
            BigDecimal catalogPrice = asBigDecimal(pkg.get("price"));
            PriceBreakdown price = calculatePackagePrice(catalogPrice, paidTierIds.contains(tierId));
            PlanApplication preview = previewPaidPackage(user.getId(), pkg, price, now, context);

            Map<String, Object> quote = new LinkedHashMap<>(pkg);
            quote.put("baseAmount", price.baseAmount());
            quote.put("discountAmount", price.discountAmount());
            quote.put("discountPercent", price.discountApplied() ? 20 : 0);
            quote.put("discountApplied", price.discountApplied());
            quote.put("amount", price.payableAmount());
            quote.put("action", preview.action());
            quote.put("actionLabel", preview.actionLabel());
            quote.put("actionDescription", preview.actionDescription());
            quote.put("startAt", preview.startAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            quote.put("expiresAt", preview.endAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            quote.put("bonusDays", preview.bonusDays());
            quote.put("isCurrentPackage", context.current() != null
                    && ((Number) context.current().get("service_package_id")).longValue()
                    == ((Number) pkg.get("id")).longValue());
            quotes.add(quote);
        }
        return quotes;
    }

    private Set<String> findPaidTierIds(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DISTINCT paid_package.tier_id " +
                        "FROM payment_transactions paid_tx " +
                        "JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id " +
                        "JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id " +
                        "WHERE paid_sub.user_id = ? AND paid_tx.status = 'SUCCESS' " +
                        "AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')",
                userId);
        Set<String> tiers = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get("tier_id") != null) tiers.add(String.valueOf(row.get("tier_id")));
        }
        return tiers;
    }

    private PlanContext getPlanContext(Long userId) {
        String planSql = "SELECT s.id AS subscription_id, s.service_package_id, s.start_at, s.end_at, " +
                "p.name AS package_name, p.tier_id, p.price, p.duration_days, " +
                "(SELECT pt.amount FROM payment_transactions pt " +
                "WHERE pt.subscription_id = s.id AND pt.status = 'SUCCESS' " +
                "AND COALESCE(pt.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX') " +
                "ORDER BY pt.paid_at DESC, pt.id DESC LIMIT 1) AS paid_amount " +
                "FROM subscriptions s JOIN service_packages p ON p.id = s.service_package_id " +
                "WHERE s.user_id = ? AND s.status = 'ACTIVE' AND s.end_at > NOW() ";
        List<Map<String, Object>> current = jdbcTemplate.queryForList(
                planSql + "AND s.start_at <= NOW() ORDER BY s.end_at DESC, s.id DESC LIMIT 1", userId);
        List<Map<String, Object>> scheduled = jdbcTemplate.queryForList(
                planSql + "AND s.start_at > NOW() ORDER BY s.end_at DESC, s.id DESC LIMIT 1", userId);
        return new PlanContext(current.isEmpty() ? null : current.get(0),
                scheduled.isEmpty() ? null : scheduled.get(0));
    }

    private PlanApplication previewPaidPackage(Long userId, Map<String, Object> targetPackage,
                                                PriceBreakdown price, LocalDateTime now) {
        return previewPaidPackage(userId, targetPackage, price, now, getPlanContext(userId));
    }

    private PlanApplication previewPaidPackage(Long userId, Map<String, Object> targetPackage,
                                                PriceBreakdown price, LocalDateTime now,
                                                PlanContext context) {
        int durationDays = ((Number) targetPackage.get("duration_days")).intValue();
        Map<String, Object> anchorPlan = context.scheduled() != null ? context.scheduled() : context.current();
        LocalDateTime anchor = anchorPlan == null ? null : toLocalDateTime(anchorPlan.get("end_at"));
        if (anchor == null || !anchor.isAfter(now)) anchor = now;

        if (context.scheduled() != null) {
            LocalDateTime start = anchor;
            LocalDateTime end = start.plusDays(durationDays);
            return new PlanApplication(start, end, "AFTER_SCHEDULED", "Sau gói đã thanh toán",
                    "Gói đã thanh toán trước sẽ được giữ nguyên; gói này bắt đầu sau ngày " + formatDate(anchor) + ".", 0);
        }

        if (context.current() == null) {
            LocalDateTime end = now.plusDays(durationDays);
            return new PlanApplication(now, end, "NEW", "Đăng ký gói",
                    "Gói bắt đầu ngay sau khi thanh toán được xác nhận.", 0);
        }

        String currentTier = String.valueOf(context.current().get("tier_id"));
        String targetTier = String.valueOf(targetPackage.get("tier_id"));
        int comparison = compareTier(targetTier, currentTier);
        if (comparison > 0) {
            int bonusDays = calculateUpgradeBonusDays(context.current(), durationDays, price, now);
            LocalDateTime end = now.plusDays((long) durationDays + bonusDays);
            String detail = bonusDays > 0
                    ? "Có hiệu lực ngay; ước tính cộng " + bonusDays + " ngày quy đổi từ thời gian còn lại của gói hiện tại."
                    : "Có hiệu lực ngay sau khi thanh toán được xác nhận.";
            return new PlanApplication(now, end, "UPGRADE", "Nâng cấp ngay", detail, bonusDays);
        }

        LocalDateTime start = anchor;
        LocalDateTime end = start.plusDays(durationDays);
        if (comparison == 0) {
            return new PlanApplication(start, end, "RENEWAL", "Gia hạn",
                    "Quyền lợi hiện tại được giữ nguyên đến " + formatDate(start) + "; gói gia hạn bắt đầu sau thời điểm đó.", 0);
        }
        return new PlanApplication(start, end, "DOWNGRADE", "Hạ gói sau khi hết hạn",
                "Gói hiện tại được giữ nguyên đến " + formatDate(start) + "; gói mới bắt đầu sau thời điểm đó.", 0);
    }

    /** FAMILY is the highest package tier, followed by FULL and BASIC. */
    private int compareTier(String targetTier, String currentTier) {
        if (Objects.equals(targetTier, currentTier)) return 0;
        if (tierRank(targetTier) == 0 || tierRank(currentTier) == 0) return -1;
        return Integer.compare(tierRank(targetTier), tierRank(currentTier));
    }

    private int tierRank(String tierId) {
        return switch (tierId) {
            case "FAMILY" -> 3;
            case "INDIVIDUAL_FULL" -> 2;
            case "INDIVIDUAL_BASIC" -> 1;
            default -> 0;
        };
    }

    private int calculateUpgradeBonusDays(Map<String, Object> currentPackage, int targetDurationDays,
                                           PriceBreakdown targetPrice, LocalDateTime now) {
        LocalDateTime currentEnd = toLocalDateTime(currentPackage.get("end_at"));
        if (currentEnd == null || !currentEnd.isAfter(now) || targetDurationDays <= 0 || targetPrice.payableAmount() <= 0) {
            return 0;
        }
        long currentDurationSeconds = ((Number) currentPackage.get("duration_days")).longValue() * 86_400L;
        long remainingSeconds = Math.min(Duration.between(now, currentEnd).getSeconds(), currentDurationSeconds);
        if (remainingSeconds <= 0 || currentDurationSeconds <= 0) return 0;

        Object rawPaidAmount = currentPackage.get("paid_amount");
        BigDecimal paidAmount = rawPaidAmount == null ? BigDecimal.ZERO : asBigDecimal(rawPaidAmount);
        BigDecimal remainingValue = paidAmount.multiply(BigDecimal.valueOf(remainingSeconds))
                .divide(BigDecimal.valueOf(currentDurationSeconds), 2, RoundingMode.HALF_UP);
        BigDecimal targetDailyPrice = BigDecimal.valueOf(targetPrice.payableAmount())
                .divide(BigDecimal.valueOf(targetDurationDays), 8, RoundingMode.HALF_UP);
        if (remainingValue.signum() <= 0 || targetDailyPrice.signum() <= 0) return 0;
        return remainingValue.divide(targetDailyPrice, 0, RoundingMode.CEILING).intValue();
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal amount) return amount;
        return new BigDecimal(String.valueOf(value));
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /** Expires abandoned QR orders even when the user has closed the checkout page. */
    @Scheduled(fixedDelayString = "${subscription.payment-expiry-scan-ms:60000}",
            initialDelayString = "${subscription.payment-expiry-initial-delay-ms:60000}")
    @Transactional
    public void expirePendingPaymentsAndNotify() {
        List<Long> paymentIds = jdbcTemplate.queryForList(
                "SELECT pt.id FROM payment_transactions pt " +
                        "JOIN subscriptions s ON s.id = pt.subscription_id " +
                        "WHERE pt.status = 'PENDING' AND s.status = 'PENDING' " +
                        "AND pt.created_at <= DATE_SUB(NOW(), INTERVAL 15 MINUTE) " +
                        "ORDER BY pt.created_at ASC LIMIT 100",
                Long.class);
        for (Long paymentId : paymentIds) {
            expirePendingPayment(paymentId);
        }
    }

    private void cancelPendingPaymentsForUser(Long userId) {
        List<Map<String, Object>> pendingPayments = jdbcTemplate.queryForList(
                "SELECT pt.id AS payment_id, pt.subscription_id, pt.amount, pt.provider_transaction_id, " +
                        "s.user_id, p.name AS package_name " +
                        "FROM payment_transactions pt " +
                        "JOIN subscriptions s ON s.id = pt.subscription_id " +
                        "JOIN service_packages p ON p.id = s.service_package_id " +
                        "WHERE s.user_id = ? AND s.status = 'PENDING' AND pt.status = 'PENDING'",
                userId);

        for (Map<String, Object> payment : pendingPayments) {
            Long paymentId = ((Number) payment.get("payment_id")).longValue();
            int changed = jdbcTemplate.update(
                    "UPDATE payment_transactions SET status = 'CANCELLED' " +
                            "WHERE id = ? AND status = 'PENDING'",
                    paymentId);
            if (changed == 0) continue;

            jdbcTemplate.update(
                    "UPDATE subscriptions SET status = 'CANCELLED', updated_at = NOW() " +
                            "WHERE id = ? AND status = 'PENDING'",
                    payment.get("subscription_id"));
            String orderCode = payment.get("provider_transaction_id") == null
                    ? "MD" + paymentId : String.valueOf(payment.get("provider_transaction_id"));
            notificationService.notifySubscriptionPaymentFailed(
                    userId, orderCode, String.valueOf(payment.get("package_name")),
                    ((Number) payment.get("amount")).longValue(),
                    "Đơn này đã được hủy vì bạn tạo một đơn thanh toán mới; mã QR cũ không còn hiệu lực.");
        }
    }

    /** Atomically expires a single order and emits one inbox notification if this call won the transition. */
    private boolean expirePendingPayment(Long paymentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pt.subscription_id, pt.amount, pt.provider_transaction_id, " +
                        "s.user_id, p.name AS package_name " +
                        "FROM payment_transactions pt " +
                        "JOIN subscriptions s ON s.id = pt.subscription_id " +
                        "JOIN service_packages p ON p.id = s.service_package_id " +
                        "WHERE pt.id = ? AND pt.status = 'PENDING' AND s.status = 'PENDING' " +
                        "AND pt.created_at <= DATE_SUB(NOW(), INTERVAL 15 MINUTE)",
                paymentId);
        if (rows.isEmpty()) return false;

        Map<String, Object> payment = rows.get(0);
        int changed = jdbcTemplate.update(
                "UPDATE payment_transactions pt " +
                        "JOIN subscriptions s ON s.id = pt.subscription_id " +
                        "SET pt.status = 'CANCELLED', s.status = 'CANCELLED', s.updated_at = NOW() " +
                        "WHERE pt.id = ? AND pt.status = 'PENDING' AND s.status = 'PENDING' " +
                        "AND pt.created_at <= DATE_SUB(NOW(), INTERVAL 15 MINUTE)",
                paymentId);
        if (changed == 0) return false;

        String orderCode = payment.get("provider_transaction_id") == null
                ? "MD" + paymentId : String.valueOf(payment.get("provider_transaction_id"));
        notificationService.notifySubscriptionPaymentFailed(
                ((Number) payment.get("user_id")).longValue(), orderCode,
                String.valueOf(payment.get("package_name")),
                ((Number) payment.get("amount")).longValue(),
                "Đơn QR đã hết hạn sau 15 phút mà hệ thống chưa xác nhận đủ tiền.");
        return true;
    }

    private record PlanContext(Map<String, Object> current, Map<String, Object> scheduled) {}

    private record PlanApplication(LocalDateTime startAt, LocalDateTime endAt, String action,
                                   String actionLabel, String actionDescription, int bonusDays) {}

    private PlanApplication applyPaidPackage(Long userId, Map<String, Object> targetPackage,
                                             PriceBreakdown price, LocalDateTime now) {
        PlanContext context = getPlanContext(userId);
        PlanApplication plan = previewPaidPackage(userId, targetPackage, price, now, context);

        if ("UPGRADE".equals(plan.action())) {
            jdbcTemplate.update(
                    "UPDATE subscriptions SET status = 'CANCELLED', updated_at = NOW() " +
                            "WHERE user_id = ? AND status = 'ACTIVE' AND start_at <= NOW() AND end_at > NOW()",
                    userId);
        }
        return plan;
    }

    /** Xử lý webhook callback từ SePay khi có giao dịch tiền vào tài khoản ngân hàng. */
    @Transactional
    public Map<String, Object> processSepayWebhook(SepayWebhookDto webhook) {
        Long sepayTxId = webhook.getId();

        // Chỉ xử lý giao dịch tiền vào.
        if (webhook.getTransferType() == null || !webhook.getTransferType().equalsIgnoreCase("in")) {
            return Map.of("success", false, "message", "Bỏ qua giao dịch tiền ra.");
        }

        String content = webhook.getContent();
        if (content == null || content.isBlank()) {
            return Map.of("success", false, "message", "Nội dung chuyển khoản trống.");
        }

        // Tìm mã đơn hàng MD<id> trong nội dung chuyển khoản.
        Pattern pattern = Pattern.compile("MD(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            System.out.println("[SePay Webhook] Không tìm thấy mã đơn hàng (MD...) trong nội dung: " + content);
            return Map.of("success", false, "message", "Không tìm thấy mã đơn hàng trong nội dung chuyển khoản.");
        }

        String orderCode = matcher.group(0).toUpperCase(); // "MD1001"
        Long paymentId = Long.valueOf(matcher.group(1));     // 1001

        System.out.println("[SePay Webhook] Tìm thấy mã đơn hàng: " + orderCode + " (paymentId=" + paymentId + ")");

        // Khóa bản ghi để các callback đọc và cập nhật trạng thái nhất quán trong transaction.
        List<Map<String, Object>> txRows = jdbcTemplate.queryForList(
            "SELECT pt.id, pt.subscription_id, pt.amount, pt.status, pt.created_at, " +
            "       s.user_id, s.service_package_id, sp.duration_days, sp.name as package_name, " +
            "       sp.price as package_price, sp.tier_id " +
            "FROM payment_transactions pt " +
            "JOIN subscriptions s ON pt.subscription_id = s.id " +
            "JOIN service_packages sp ON s.service_package_id = sp.id " +
            "WHERE pt.id = ? FOR UPDATE",
            paymentId
        );

        if (txRows.isEmpty()) {
            System.out.println("[SePay Webhook] Không tìm thấy giao dịch với paymentId=" + paymentId);
            return Map.of("success", false, "message", "Không tìm thấy đơn hàng " + orderCode);
        }

        Map<String, Object> tx = txRows.get(0);
        String currentStatus = (String) tx.get("status");

        // SePay xác nhận đã có tiền vào nên vẫn nhận đơn CANCELLED nếu callback
        // đến muộn sau lúc QR hết hạn hoặc người dùng đã tạo đơn mới.
        if (!"PENDING".equals(currentStatus)
                && !"SUCCESS".equals(currentStatus)
                && !"CANCELLED".equals(currentStatus)) {
            System.out.println("[SePay Webhook] Đơn " + orderCode + " ở trạng thái không thể xử lý: " + currentStatus);
            return Map.of("success", false, "message", "Đơn hàng " + orderCode + " đang ở trạng thái: " + currentStatus);
        }

        // Kiểm tra số tiền.
        double expectedAmount = ((Number) tx.get("amount")).doubleValue();
        double receivedAmount = webhook.getTransferAmount() != null ? webhook.getTransferAmount() : 0;

        if (receivedAmount < expectedAmount) {
            System.out.println("[SePay Webhook] ❌ Số tiền không đủ: nhận " + receivedAmount + " < cần " + expectedAmount);
            notificationService.notifySubscriptionPaymentAmountMismatch(
                    ((Number) tx.get("user_id")).longValue(), orderCode,
                    String.valueOf(tx.get("package_name")), (long) expectedAmount,
                    (long) receivedAmount);
            return Map.of(
                "success", false,
                "message", "Số tiền chuyển khoản (" + (long) receivedAmount + "đ) không đủ so với giá gói (" + (long) expectedAmount + "đ).",
                "orderCode", orderCode
            );
        }

        // Khóa user để các callback cho hai đơn khác nhau của cùng tài khoản
        // không đồng thời đọc cùng một gói đang hiệu lực.
        Long subscriptionId = ((Number) tx.get("subscription_id")).longValue();
        Long userId = ((Number) tx.get("user_id")).longValue();
        jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);

        Map<String, Object> targetPackage = new HashMap<>();
        targetPackage.put("id", tx.get("service_package_id"));
        targetPackage.put("name", tx.get("package_name"));
        targetPackage.put("price", tx.get("package_price"));
        targetPackage.put("duration_days", tx.get("duration_days"));
        targetPackage.put("tier_id", tx.get("tier_id"));

        // Tính giá ưu đãi trước khi đánh dấu đơn hiện tại SUCCESS để giao dịch
        // đầu tiên không tự đủ điều kiện giảm giá cho chính nó.
        PriceBreakdown calculatedPrice = calculatePackagePrice(
                userId, String.valueOf(tx.get("tier_id")), tx.get("package_price"));
        long expectedPaymentAmount = ((Number) tx.get("amount")).longValue();
        PriceBreakdown paidPrice = new PriceBreakdown(calculatedPrice.baseAmount(),
                Math.max(0L, calculatedPrice.baseAmount() - expectedPaymentAmount), expectedPaymentAmount,
                expectedPaymentAmount < calculatedPrice.baseAmount());
        PlanApplication plan = applyPaidPackage(userId, targetPackage, paidPrice, LocalDateTime.now());

        // Thanh toán thành công: cập nhật payment_transaction và kích hoạt hoặc
        // xếp lịch subscription theo chính sách gia hạn/nâng/hạ gói.
        String sepayRefCode = webhook.getReferenceCode() != null ? webhook.getReferenceCode() : "SEPAY-" + sepayTxId;
        jdbcTemplate.update(
            "UPDATE payment_transactions SET status = 'SUCCESS', provider_transaction_id = ?, paid_at = NOW() WHERE id = ?",
            orderCode + "|" + sepayRefCode, paymentId
        );
        jdbcTemplate.update(
            "UPDATE subscriptions SET status = 'ACTIVE', start_at = ?, end_at = ? WHERE id = ?",
            plan.startAt(), plan.endAt(), subscriptionId
        );

        String packageName = (String) tx.get("package_name");
        notificationService.notifySubscriptionPaymentSucceeded(
                userId, orderCode, packageName, expectedPaymentAmount,
                plan.action(), plan.actionDescription(), plan.startAt(), plan.endAt());
        System.out.println("[SePay Webhook] ✅ THÀNH CÔNG! Đã kích hoạt gói " + packageName +
            " cho userId=" + userId + " | Bắt đầu: " + plan.startAt() + " | Hạn đến: " + plan.endAt());

        return Map.of(
            "success", true,
            "message", "Thanh toán thành công! " + plan.actionDescription(),
            "orderCode", orderCode,
            "packageName", packageName,
            "startAt", plan.startAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "expiresAt", plan.endAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "action", plan.action(),
            "bonusDays", plan.bonusDays()
        );
    }

    /**
     * Kiểm tra trạng thái thanh toán của một đơn hàng (polling từ Frontend).
     *
     * @param orderCode Mã đơn hàng (ví dụ: MD1001)
     * @return Map chứa status (PENDING/SUCCESS/CANCELLED/EXPIRED), thông tin gói...
     */
    @Transactional
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
            "SELECT pt.id, CASE WHEN pt.status = 'CANCELLED' " +
            "AND pt.created_at <= DATE_SUB(NOW(), INTERVAL 15 MINUTE) THEN 'EXPIRED' " +
            "ELSE pt.status END AS status, pt.amount, pt.paid_at, " +
            "       pt.created_at, s.status as sub_status, s.start_at, s.end_at, " +
            "       sp.name as package_name, sp.duration_days " +
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
            expirePendingPayment(paymentId);
            // Refresh because the webhook and the expiry scheduler may have won concurrently.
            rows = jdbcTemplate.queryForList(
                "SELECT pt.id, CASE WHEN pt.status = 'CANCELLED' " +
                "AND pt.created_at <= DATE_SUB(NOW(), INTERVAL 15 MINUTE) THEN 'EXPIRED' " +
                "ELSE pt.status END AS status, pt.amount, pt.paid_at, pt.created_at, " +
                "       s.status as sub_status, s.start_at, s.end_at, " +
                "       sp.name as package_name, sp.duration_days " +
                "FROM payment_transactions pt " +
                "JOIN subscriptions s ON pt.subscription_id = s.id " +
                "JOIN service_packages sp ON s.service_package_id = sp.id " +
                "WHERE pt.id = ?",
                paymentId);
            row = rows.get(0);
            paymentStatus = (String) row.get("status");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderCode", orderCode);
        result.put("status", paymentStatus);
        result.put("amount", row.get("amount"));
        result.put("packageName", row.get("package_name"));

        if ("SUCCESS".equals(paymentStatus)) {
            result.put("paidAt", row.get("paid_at") != null ? row.get("paid_at").toString() : null);
            result.put("startAt", row.get("start_at") != null ? row.get("start_at").toString() : null);
            result.put("expiresAt", row.get("end_at") != null ? row.get("end_at").toString() : null);
            LocalDateTime startsAt = toLocalDateTime(row.get("start_at"));
            LocalDateTime endsAt = toLocalDateTime(row.get("end_at"));
            boolean isEffectiveNow = "ACTIVE".equals(row.get("sub_status"))
                    && startsAt != null && !startsAt.isAfter(LocalDateTime.now())
                    && endsAt != null && endsAt.isAfter(LocalDateTime.now());
            result.put("isEffectiveNow", isEffectiveNow);
            result.put("isPremium", isEffectiveNow);
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
