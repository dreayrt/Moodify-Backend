package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Notification inbox and broadcast history backed by MySQL. */
@Service
public class NotificationService {

    private static final DateTimeFormatter BILLING_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final List<String> VALID_TYPES = List.of(
            "SYSTEM", "PROMO", "BILLING", "MODERATION", "ANNOUNCEMENT");
    private static final List<String> VALID_ROLES = List.of(
            "USER", "CONTENT_LEAD", "MODERATOR", "ADMIN");
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    public NotificationService(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    /** Persists a deduplicated billing notification for a completed subscription payment. */
    public void notifySubscriptionPaymentSucceeded(Long userId, Long paymentId, String orderCode,
                                                   String packageName, long amount, String action,
                                                   String actionDescription, LocalDateTime startAt,
                                                   LocalDateTime endAt) {
        String title = switch (action == null ? "NEW" : action) {
            case "RENEWAL" -> "Gia hạn gói thành công";
            case "UPGRADE" -> "Nâng cấp gói thành công";
            case "DOWNGRADE" -> "Đã đăng ký gói mới";
            case "AFTER_SCHEDULED" -> "Thanh toán gói thành công";
            default -> "Đăng ký gói thành công";
        };

        StringBuilder message = new StringBuilder("Bạn đã thanh toán ")
                .append(String.format("%,d", amount).replace(',', '.'))
                .append(" đ cho ").append(packageName).append(" (mã đơn ").append(orderCode).append("). ");
        if (actionDescription != null && !actionDescription.isBlank()) {
            message.append(actionDescription).append(' ');
        }
        if (startAt != null && endAt != null) {
            message.append("Thời gian áp dụng: từ ")
                    .append(BILLING_DATE_FORMAT.format(startAt)).append(" đến ")
                    .append(BILLING_DATE_FORMAT.format(endAt)).append('.');
        }

        insertBillingNotification(userId, title, message.toString(),
                "subscription-payment:" + paymentId + ":success");
    }

    /** Persists a deduplicated notice when a payment is cancelled, expires, or cannot be confirmed. */
    public void notifySubscriptionPaymentFailed(Long userId, Long paymentId, String orderCode,
                                                 String packageName, long amount, String reason,
                                                 String eventKey) {
        String message = "Đơn " + orderCode + " cho " + packageName + " (" +
                String.format("%,d", amount).replace(',', '.') + " đ) chưa hoàn tất. " +
                reason + " Gói dịch vụ chưa được áp dụng; bạn có thể tạo đơn thanh toán mới.";
        insertBillingNotification(userId, "Thanh toán gói chưa thành công", message,
                "subscription-payment:" + paymentId + ":" + eventKey);
    }

    /** Records an underpayment notice without closing the order, which stays payable until expiry. */
    public void notifySubscriptionPaymentAmountMismatch(Long userId, Long paymentId, String orderCode,
                                                         String packageName, long expectedAmount,
                                                         long receivedAmount, String providerEventId) {
        String message = "SePay ghi nhận " + String.format("%,d", receivedAmount).replace(',', '.') +
                " đ cho đơn " + orderCode + ", thấp hơn số tiền cần thanh toán " +
                String.format("%,d", expectedAmount).replace(',', '.') + " đ của " + packageName +
                ". Đơn vẫn đang chờ trong thời hạn QR; vui lòng kiểm tra lại giao dịch. Gói chưa được áp dụng.";
        String safeProviderEventId = providerEventId == null
                ? "unknown"
                : providerEventId.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeProviderEventId.isBlank()) safeProviderEventId = "unknown";
        if (safeProviderEventId.length() > 64) safeProviderEventId = safeProviderEventId.substring(0, 64);
        String eventKey = "underpaid:" + safeProviderEventId;
        insertBillingNotification(userId, "Chưa xác nhận đủ tiền thanh toán", message,
                "subscription-payment:" + paymentId + ":" + eventKey);
    }

    /** Records a user's cancellation of an unpaid order or a future paid subscription. */
    public void notifySubscriptionManagementCancelled(Long userId, Long subscriptionId, String message) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO notifications " +
                        "(user_id, title, message, type, link_url, created_at, dedupe_key) " +
                        "VALUES (?, 'Đã cập nhật gói dịch vụ', ?, 'BILLING', '/dashboard/premium?tab=manage', NOW(), ?)",
                userId, message, "subscription-management:" + subscriptionId + ":cancelled");
    }

    private void insertBillingNotification(Long userId, String title, String message, String dedupeKey) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO notifications " +
                        "(user_id, title, message, type, link_url, created_at, dedupe_key) " +
                        "VALUES (?, ?, ?, 'BILLING', '/dashboard/premium', NOW(), ?)",
                userId, title, message, dedupeKey);
    }

    /** Sends an individual inbox row to each active user in the selected audience. */
    @Transactional
    public Map<String, Object> broadcast(String title, String message, String type,
                                         String targetRole, String linkUrl, Long createdBy) {
        String safeTitle = title == null ? "" : title.trim();
        String safeMessage = message == null ? "" : message.trim();
        if (safeTitle.isBlank() || safeMessage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề và nội dung thông báo là bắt buộc.");
        }

        String normalizedType = (type != null && !type.isBlank()) ? type.toUpperCase().trim() : "SYSTEM";
        if (!VALID_TYPES.contains(normalizedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Loại thông báo không hợp lệ. Cho phép: " + VALID_TYPES);
        }

        String roleFilter = null;
        if (targetRole != null && !targetRole.isBlank() && !"ALL".equalsIgnoreCase(targetRole)) {
            roleFilter = targetRole.toUpperCase().trim();
            if (!VALID_ROLES.contains(roleFilter)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Vai trò đích không hợp lệ. Cho phép: " + VALID_ROLES + " hoặc ALL");
            }
        }

        String recipientSql = "SELECT id FROM users WHERE status = 'ACTIVE'";
        List<Object> recipientParams = new ArrayList<>();
        if (roleFilter != null) {
            recipientSql += " AND role = ?";
            recipientParams.add(roleFilter);
        }
        List<Long> recipientIds = jdbcTemplate.queryForList(recipientSql, Long.class, recipientParams.toArray());
        if (recipientIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có người nhận nào phù hợp.");
        }

        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> notificationRows = new ArrayList<>(recipientIds.size());
        for (Long userId : recipientIds) {
            notificationRows.add(new Object[]{
                    userId,
                    safeTitle,
                    safeMessage,
                    normalizedType,
                    (linkUrl != null && !linkUrl.isBlank()) ? linkUrl.trim() : null,
                    createdBy,
                    createdAt
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO notifications (user_id, title, message, type, link_url, created_by, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                notificationRows);

        String historyRole = roleFilter != null ? roleFilter : "ALL";
        jdbcTemplate.update(
                "INSERT INTO notification_broadcasts " +
                        "(title, message, type, target_role, recipients_count, created_by, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                safeTitle, safeMessage, normalizedType, historyRole, recipientIds.size(), createdBy, createdAt);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("recipients", recipientIds.size());
        result.put("type", normalizedType);
        result.put("targetRole", historyRole);
        result.put("message", "Đã gửi thông báo tới " + recipientIds.size() + " người dùng.");
        return result;
    }

    /** Returns the signed-in user's newest notifications and unread count. */
    public Map<String, Object> getMyNotifications(String principal, int limit) {
        Long userId = resolveUserId(principal);
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        List<Map<String, Object>> items = jdbcTemplate.query(
                "SELECT CAST(id AS CHAR) AS id, title, message, type, link_url AS linkUrl, " +
                        "DATE_FORMAT(read_at, '%Y-%m-%dT%H:%i:%s') AS readAt, " +
                        "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS createdAt " +
                        "FROM notifications WHERE user_id = ? " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("message", rs.getString("message"));
                    item.put("type", rs.getString("type"));
                    item.put("linkUrl", rs.getString("linkUrl"));
                    item.put("readAt", rs.getString("readAt"));
                    item.put("createdAt", rs.getString("createdAt"));
                    return item;
                },
                userId, safeLimit);

        Long unreadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("unreadCount", unreadCount != null ? unreadCount : 0L);
        return result;
    }

    public void markRead(String principal, String notificationId) {
        Long userId = resolveUserId(principal);
        long id = parseNotificationId(notificationId);
        int changed = jdbcTemplate.update(
                "UPDATE notifications SET read_at = COALESCE(read_at, NOW()) WHERE id = ? AND user_id = ?",
                id, userId);
        if (changed == 0 && !notificationExistsForUser(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo.");
        }
    }

    public void markAllRead(String principal) {
        Long userId = resolveUserId(principal);
        jdbcTemplate.update(
                "UPDATE notifications SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL",
                userId);
    }

    /** Admin-visible history of notification broadcasts. */
    public List<Map<String, Object>> getBroadcastHistory() {
        return jdbcTemplate.query(
                "SELECT CAST(id AS CHAR) AS id, title, message, type, target_role AS targetRole, " +
                        "recipients_count AS recipients, " +
                        "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS lastSentAt " +
                        "FROM notification_broadcasts ORDER BY created_at DESC, id DESC LIMIT 50",
                (rs, rowNum) -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("message", rs.getString("message"));
                    item.put("type", rs.getString("type"));
                    item.put("targetRole", rs.getString("targetRole"));
                    item.put("recipients", rs.getInt("recipients"));
                    item.put("lastSentAt", rs.getString("lastSentAt"));
                    return item;
                });
    }

    private Long resolveUserId(String principal) {
        User user = userRepository.findByEmailOrUsername(principal, principal)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản người dùng."));
        return user.getId();
    }

    private long parseNotificationId(String notificationId) {
        try {
            return Long.parseLong(notificationId);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã thông báo không hợp lệ.");
        }
    }

    private boolean notificationExistsForUser(long notificationId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE id = ? AND user_id = ?",
                Integer.class, notificationId, userId);
        return count != null && count > 0;
    }
}
