package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hệ thống thông báo (notification) của Moodify.
 *
 * Admin gửi thông báo (broadcast) tới toàn bộ người dùng đang hoạt động
 * hoặc tới một vai trò cụ thể (USER / CONTENT_LEAD / MODERATOR / ADMIN).
 * Mỗi người nhận có một bản ghi riêng nên trạng thái đã đọc là per-user.
 *
 * Dữ liệu lưu trong MongoDB (collections "notifications" và
 * "notification_broadcasts", tự tạo khi chạy) — KHÔNG thay đổi schema MySQL.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate; // chỉ đọc danh sách user từ MySQL
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    private static final String COLLECTION = "notifications";
    private static final String BROADCAST_COLLECTION = "notification_broadcasts";

    private static final List<String> VALID_TYPES = List.of(
            "SYSTEM", "PROMO", "BILLING", "MODERATION", "ANNOUNCEMENT");
    private static final List<String> VALID_ROLES = List.of(
            "USER", "CONTENT_LEAD", "MODERATOR", "ADMIN");

    public NotificationService(JdbcTemplate jdbcTemplate,
                               UserRepository userRepository,
                               MongoTemplate mongoTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /** Gửi thông báo tới tất cả user đang ACTIVE (hoặc lọc theo vai trò). */
    @Transactional
    public Map<String, Object> broadcast(String title, String message, String type,
                                         String targetRole, String linkUrl, Long createdBy) {
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

        // Danh sách người nhận đọc từ MySQL (bảng users có sẵn, không đổi schema)
        String recipientSql = "SELECT id FROM users WHERE status = 'ACTIVE'";
        List<Object> params = new ArrayList<>();
        if (roleFilter != null) {
            recipientSql += " AND role = ?";
            params.add(roleFilter);
        }
        List<Long> recipientIds = jdbcTemplate.queryForList(recipientSql, Long.class, params.toArray());
        if (recipientIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có người nhận nào phù hợp.");
        }

        Date now = new Date();
        List<Document> docs = new ArrayList<>();
        for (Long userId : recipientIds) {
            Document doc = new Document();
            doc.put("userId", userId);
            doc.put("title", title.trim());
            doc.put("message", message.trim());
            doc.put("type", normalizedType);
            doc.put("linkUrl", (linkUrl != null && !linkUrl.isBlank()) ? linkUrl.trim() : null);
            doc.put("createdBy", createdBy);
            doc.put("readAt", null);
            doc.put("createdAt", now);
            docs.add(doc);
        }
        mongoTemplate.getCollection(COLLECTION).insertMany(docs);

        // Ghi lịch sử đợt gửi để admin tra cứu
        Document broadcastLog = new Document();
        broadcastLog.put("title", title.trim());
        broadcastLog.put("message", message.trim());
        broadcastLog.put("type", normalizedType);
        broadcastLog.put("targetRole", roleFilter != null ? roleFilter : "ALL");
        broadcastLog.put("recipients", recipientIds.size());
        broadcastLog.put("createdBy", createdBy);
        broadcastLog.put("createdAt", now);
        mongoTemplate.save(broadcastLog, BROADCAST_COLLECTION);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("recipients", recipientIds.size());
        result.put("type", normalizedType);
        result.put("targetRole", roleFilter != null ? roleFilter : "ALL");
        result.put("message", "Đã gửi thông báo tới " + recipientIds.size() + " người dùng.");
        return result;
    }

    /** Danh sách thông báo của user hiện tại (mới nhất trước). */
    public Map<String, Object> getMyNotifications(String principal, int limit) {
        Long userId = resolveUserId(principal);
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        Query query = Query.query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(safeLimit);
        List<Document> docs = mongoTemplate.find(query, Document.class, COLLECTION);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> n = new HashMap<>();
            n.put("id", doc.getObjectId("_id").toHexString());
            n.put("title", doc.getString("title"));
            n.put("message", doc.getString("message"));
            n.put("type", doc.getString("type"));
            n.put("linkUrl", doc.getString("linkUrl"));
            n.put("readAt", doc.getDate("readAt") != null ? doc.getDate("readAt").toString() : null);
            n.put("createdAt", doc.getDate("createdAt") != null ? doc.getDate("createdAt").toString() : null);
            items.add(n);
        }

        long unread = mongoTemplate.count(
                Query.query(Criteria.where("userId").is(userId).and("readAt").is(null)), COLLECTION);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("unreadCount", unread);
        return result;
    }

    public void markRead(String principal, String notificationId) {
        Long userId = resolveUserId(principal);
        Query query = Query.query(Criteria.where("_id").is(notificationId).and("userId").is(userId));
        mongoTemplate.updateFirst(query, Update.update("readAt", new Date()), COLLECTION);
    }

    public void markAllRead(String principal) {
        Long userId = resolveUserId(principal);
        Query query = Query.query(Criteria.where("userId").is(userId).and("readAt").is(null));
        mongoTemplate.updateMulti(query, Update.update("readAt", new Date()), COLLECTION);
    }

    /** Lịch sử các đợt gửi thông báo. */
    public List<Map<String, Object>> getBroadcastHistory() {
        Query query = Query.query(new Criteria())
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(50);
        List<Document> docs = mongoTemplate.find(query, Document.class, BROADCAST_COLLECTION);

        List<Map<String, Object>> history = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> h = new HashMap<>();
            h.put("id", doc.getObjectId("_id").toHexString());
            h.put("title", doc.getString("title"));
            h.put("message", doc.getString("message"));
            h.put("type", doc.getString("type"));
            h.put("targetRole", doc.getString("targetRole"));
            h.put("recipients", doc.getInteger("recipients"));
            h.put("lastSentAt", doc.getDate("createdAt") != null ? doc.getDate("createdAt").toString() : null);
            history.add(h);
        }
        return history;
    }

    private Long resolveUserId(String principal) {
        User user = userRepository.findByEmailOrUsername(principal, principal)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản người dùng."));
        return user.getId();
    }
}
