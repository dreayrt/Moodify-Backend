package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.analytics.TrackVisitRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PlatformTrafficService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTrafficService.class);
    private final JdbcTemplate jdbcTemplate;

    public PlatformTrafficService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initTable() {
        try {
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS platform_traffic_events (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    target_type ENUM('TRACK', 'ARTIST', 'GENERAL') NOT NULL DEFAULT 'TRACK',
                    target_id VARCHAR(64) NOT NULL,
                    platform ENUM('WEB', 'ANDROID', 'IOS') NOT NULL DEFAULT 'WEB',
                    referrer_type ENUM('DIRECT', 'SEARCH', 'TIKTOK', 'FACEBOOK', 'AI_RECOMMEND', 'OTHER') NOT NULL DEFAULT 'DIRECT',
                    user_id BIGINT NULL,
                    session_id VARCHAR(64) NULL,
                    visited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_traffic_target_time (target_id, visited_at),
                    INDEX idx_traffic_platform (platform),
                    INDEX idx_traffic_visited_at (visited_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """;
            jdbcTemplate.execute(createTableSql);
            log.info("Initialized platform_traffic_events table successfully");
        } catch (Exception e) {
            log.warn("Failed to initialize platform_traffic_events table: {}", e.getMessage());
        }
    }

    public boolean recordVisit(TrackVisitRequest req, Long userId) {
        if (req == null || req.targetId() == null || req.targetId().isBlank()) {
            return false;
        }

        String targetType = normalizeTargetType(req.targetType());
        String platform = normalizePlatform(req.platform());
        String referrerType = normalizeReferrer(req.referrerType());

        String sql = """
            INSERT INTO platform_traffic_events 
            (target_type, target_id, platform, referrer_type, user_id, session_id, visited_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
        """;

        try {
            jdbcTemplate.update(
                sql,
                targetType,
                req.targetId().trim(),
                platform,
                referrerType,
                userId,
                req.sessionId() != null ? req.sessionId().trim() : null
            );
            return true;
        } catch (Exception e) {
            log.error("Error recording platform visit: {}", e.getMessage());
            return false;
        }
    }

    public int seedTrafficData(List<String> targetIds, int countPerTrack) {
        if (targetIds == null || targetIds.isEmpty()) return 0;

        // Chỉ 3 nền tảng chính: Web, Android, iOS
        String[] platforms = {"WEB", "ANDROID", "IOS"};
        double[] platformWeights = {0.45, 0.35, 0.20};

        String[] referrers = {"DIRECT", "SEARCH", "TIKTOK", "FACEBOOK", "AI_RECOMMEND"};
        double[] referrerWeights = {0.30, 0.30, 0.20, 0.10, 0.10};

        Random rand = new Random();
        int totalInserted = 0;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (String targetId : targetIds) {
            if (targetId == null || targetId.isBlank()) continue;

            for (int i = 0; i < countPerTrack; i++) {
                String platform = pickWeighted(platforms, platformWeights, rand);
                String referrer = pickWeighted(referrers, referrerWeights, rand);

                // Phân bổ thời gian: rải đều 90 ngày để khi xem 7d, 30d, 90d số liệu khác biệt rõ rệt
                int daysAgo;
                int rVal = rand.nextInt(100);
                if (rVal < 40) {
                    daysAgo = rand.nextInt(7); // 40% trong 7 ngày gần nhất
                } else if (rVal < 75) {
                    daysAgo = 7 + rand.nextInt(23); // 35% từ ngày 7 đến 30
                } else {
                    daysAgo = 30 + rand.nextInt(60); // 25% từ ngày 30 đến 90
                }

                int hoursAgo = rand.nextInt(24);
                int minsAgo = rand.nextInt(60);
                LocalDateTime visitTime = LocalDateTime.now().minusDays(daysAgo).minusHours(hoursAgo).minusMinutes(minsAgo);
                String visitTimeStr = visitTime.format(dtf);

                Long mockUserId = (long) (rand.nextInt(3) + 1);
                String sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 8);

                String sql = """
                    INSERT INTO platform_traffic_events 
                    (target_type, target_id, platform, referrer_type, user_id, session_id, visited_at)
                    VALUES ('TRACK', ?, ?, ?, ?, ?, ?)
                """;

                try {
                    jdbcTemplate.update(sql, targetId, platform, referrer, mockUserId, sessionId, visitTimeStr);
                    totalInserted++;

                    // Xác suất bấm nghe thực tế (tạo bản ghi listening_history tương ứng)
                    // Càng gần đây tỷ lệ bấm nghe càng cao (7 ngày: ~84%, 30 ngày: ~76%, 90 ngày: ~70%)
                    double playProbability = daysAgo < 7 ? 0.84 : daysAgo < 30 ? 0.76 : 0.70;
                    if (rand.nextDouble() < playProbability) {
                        String sourceVal = switch (referrer) {
                            case "AI_RECOMMEND" -> "EMOTION";
                            case "SEARCH" -> "SEARCH";
                            default -> "HOME";
                        };

                        String streamSql = """
                            INSERT INTO listening_history 
                            (user_id, track_id, started_at, listened_duration_ms, last_position_ms, source, device_type)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
                        int durationMs = 120000 + rand.nextInt(120000);
                        jdbcTemplate.update(streamSql, mockUserId, targetId, visitTimeStr, durationMs, durationMs, sourceVal, platform);
                    }
                } catch (Exception ignored) {}
            }
        }

        return totalInserted;
    }

    private String pickWeighted(String[] items, double[] weights, Random rand) {
        double r = rand.nextDouble();
        double sum = 0.0;
        for (int i = 0; i < items.length; i++) {
            sum += weights[i];
            if (r <= sum) return items[i];
        }
        return items[0];
    }

    private String normalizeTargetType(String val) {
        if (val == null) return "TRACK";
        val = val.trim().toUpperCase();
        return switch (val) {
            case "ARTIST" -> "ARTIST";
            case "GENERAL" -> "GENERAL";
            default -> "TRACK";
        };
    }

    private String normalizePlatform(String val) {
        if (val == null) return "WEB";
        val = val.trim().toUpperCase();
        return switch (val) {
            case "ANDROID" -> "ANDROID";
            case "IOS" -> "IOS";
            default -> "WEB";
        };
    }

    private String normalizeReferrer(String val) {
        if (val == null) return "DIRECT";
        val = val.trim().toUpperCase();
        return switch (val) {
            case "SEARCH" -> "SEARCH";
            case "TIKTOK" -> "TIKTOK";
            case "FACEBOOK" -> "FACEBOOK";
            case "AI_RECOMMEND" -> "AI_RECOMMEND";
            default -> "DIRECT";
        };
    }
}
