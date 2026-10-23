package com.laphuth.moodify.services;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-time, opt-in importer for the legacy MongoDB notification collections.
 * Enable with --app.notifications.mongo-migration.enabled=true, verify the
 * imported rows, then start the application normally with the property unset.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.mongo-migration.enabled", havingValue = "true")
public class MongoNotificationMigrationRunner implements ApplicationRunner {

    private static final String NOTIFICATIONS_COLLECTION = "notifications";
    private static final String BROADCASTS_COLLECTION = "notification_broadcasts";

    private final MongoTemplate mongoTemplate;
    private final JdbcTemplate jdbcTemplate;

    public MongoNotificationMigrationRunner(MongoTemplate mongoTemplate, JdbcTemplate jdbcTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<Long> userIds = new HashSet<>(jdbcTemplate.queryForList("SELECT id FROM users", Long.class));
        int importedNotifications = importNotifications(userIds);
        int importedBroadcasts = importBroadcasts(userIds);
        System.out.printf("[Notification migration] Imported %d inbox rows and %d broadcast history rows. " +
                "MongoDB source collections were left unchanged.%n", importedNotifications, importedBroadcasts);
    }

    private int importNotifications(Set<Long> userIds) {
        List<Document> documents = mongoTemplate.findAll(Document.class, NOTIFICATIONS_COLLECTION);
        int imported = 0;
        for (Document document : documents) {
            Long userId = asLong(document.get("userId"));
            if (userId == null || !userIds.contains(userId)) {
                continue;
            }

            String sourceId = sourceId(document);
            int inserted = jdbcTemplate.update(
                    "INSERT IGNORE INTO notifications " +
                            "(user_id, title, message, type, link_url, created_by, read_at, created_at, source_mongo_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId,
                    stringValue(document.get("title"), "Thông báo"),
                    stringValue(document.get("message"), ""),
                    stringValue(document.get("type"), "SYSTEM"),
                    nullableString(document.get("linkUrl")),
                    existingUserId(asLong(document.get("createdBy")), userIds),
                    asTimestamp(document.get("readAt")),
                    asTimestamp(document.get("createdAt"), Timestamp.valueOf(LocalDateTime.now())),
                    sourceId);
            imported += inserted;
        }
        return imported;
    }

    private int importBroadcasts(Set<Long> userIds) {
        List<Document> documents = mongoTemplate.findAll(Document.class, BROADCASTS_COLLECTION);
        int imported = 0;
        for (Document document : documents) {
            String sourceId = sourceId(document);
            int inserted = jdbcTemplate.update(
                    "INSERT IGNORE INTO notification_broadcasts " +
                            "(title, message, type, target_role, recipients_count, created_by, created_at, source_mongo_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    stringValue(document.get("title"), "Thông báo"),
                    stringValue(document.get("message"), ""),
                    stringValue(document.get("type"), "SYSTEM"),
                    stringValue(document.get("targetRole"), "ALL"),
                    asInteger(document.get("recipients")),
                    existingUserId(asLong(document.get("createdBy")), userIds),
                    asTimestamp(document.get("createdAt"), Timestamp.valueOf(LocalDateTime.now())),
                    sourceId);
            imported += inserted;
        }
        return imported;
    }

    private static Long existingUserId(Long userId, Set<Long> userIds) {
        return userId != null && userIds.contains(userId) ? userId : null;
    }

    private static String sourceId(Document document) {
        Object id = document.get("_id");
        return id != null ? id.toString() : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }

    private static String nullableString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Timestamp asTimestamp(Object value) {
        return asTimestamp(value, null);
    }

    private static Timestamp asTimestamp(Object value, Timestamp fallback) {
        if (value instanceof Date date) {
            return new Timestamp(date.getTime());
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof LocalDateTime dateTime) {
            return Timestamp.valueOf(dateTime);
        }
        if (value instanceof Number epochMillis) {
            return new Timestamp(epochMillis.longValue());
        }
        return fallback;
    }
}
