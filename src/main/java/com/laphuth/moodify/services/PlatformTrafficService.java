package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.analytics.TrackVisitRequest;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
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
    private final TrackRepository trackRepository;

    public PlatformTrafficService(JdbcTemplate jdbcTemplate, TrackRepository trackRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.trackRepository = trackRepository;
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

    public Map<String, Object> recordListeningSession(
            Long userId,
            String trackId,
            Long durationMs,
            Long positionMs,
            String source,
            String sourceId,
            String deviceType,
            String eventType
    ) {
        return recordListeningSession(null, userId, trackId, durationMs, positionMs, positionMs, source, sourceId, deviceType, eventType);
    }

    public Map<String, Object> recordListeningSession(
            Long historyId,
            Long userId,
            String trackId,
            Long durationMs,
            Long positionMs,
            Long targetPositionMs,
            String source,
            String sourceId,
            String deviceType,
            String eventType
    ) {
        if (trackId == null || trackId.isBlank()) {
            return Map.of("success", false, "message", "trackId is required");
        }

        long durMs = (durationMs != null && durationMs > 0) ? durationMs : 0L;
        long posMs = (positionMs != null && positionMs > 0) ? positionMs : durMs;
        long tgtPosMs = (targetPositionMs != null && targetPositionMs >= 0) ? targetPositionMs : posMs;
        String normEvent = normalizePlaybackEvent(eventType);

        try {
            // Trường hợp 1: Đã có session đang phát dở -> Cập nhật listening_history và ghi tiếp playback_events
            if (historyId != null && historyId > 0) {
                Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM listening_history WHERE id = ?", Integer.class, historyId);
                if (exists != null && exists > 0) {
                    jdbcTemplate.update("""
                        UPDATE listening_history 
                        SET ended_at = NOW(),
                            listened_duration_ms = GREATEST(listened_duration_ms, ?),
                            last_position_ms = ?
                        WHERE id = ?
                    """, durMs, posMs, historyId);

                    String sqlEvent = """
                        INSERT INTO playback_events 
                        (listening_history_id, event_type, position_ms, target_position_ms, occurred_at)
                        VALUES (?, ?, ?, ?, NOW())
                    """;
                    jdbcTemplate.update(sqlEvent, historyId, normEvent, posMs, tgtPosMs);

                    return Map.of("success", true, "historyId", historyId);
                }
            }

            // Trường hợp 2: Khởi tạo phiên nghe mới trong listening_history
            final Long effectiveUserId;
            if (userId != null) {
                effectiveUserId = userId;
            } else {
                Long fallbackId = null;
                try {
                    fallbackId = jdbcTemplate.queryForObject("SELECT id FROM users ORDER BY id ASC LIMIT 1", Long.class);
                } catch (Exception ignored) {}
                effectiveUserId = fallbackId != null ? fallbackId : 1L;
            }

            String normSource = normalizeListeningSource(source);
            String normDevice = normalizePlatform(deviceType);

            org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
            String sqlHistory = """
                INSERT INTO listening_history 
                (user_id, track_id, started_at, ended_at, listened_duration_ms, last_position_ms, source, source_id, device_type)
                VALUES (?, ?, DATE_SUB(NOW(), INTERVAL ? SECOND), NOW(), ?, ?, ?, ?, ?)
            """;
            long secondsAgo = Math.max(1, durMs / 1000);

            jdbcTemplate.update(connection -> {
                java.sql.PreparedStatement ps = connection.prepareStatement(sqlHistory, java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, effectiveUserId);
                ps.setString(2, trackId.trim());
                ps.setLong(3, secondsAgo);
                ps.setLong(4, durMs);
                ps.setLong(5, posMs);
                ps.setString(6, normSource);
                ps.setString(7, sourceId != null && !sourceId.isBlank() ? sourceId.trim() : null);
                ps.setString(8, normDevice);
                return ps;
            }, keyHolder);

            Number generatedKey = keyHolder.getKey();
            Long generatedHistoryId = generatedKey != null ? generatedKey.longValue() : null;

            if (generatedHistoryId != null) {
                String sqlEvent = """
                    INSERT INTO playback_events 
                    (listening_history_id, event_type, position_ms, target_position_ms, occurred_at)
                    VALUES (?, ?, ?, ?, NOW())
                """;
                jdbcTemplate.update(sqlEvent, generatedHistoryId, normEvent, posMs, tgtPosMs);
            }

            return Map.of("success", true, "historyId", generatedHistoryId != null ? generatedHistoryId : 0);
        } catch (Exception e) {
            log.error("Error recording listening session: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private String normalizeListeningSource(String val) {
        if (val == null) return "OTHER";
        val = val.trim().toUpperCase();
        return switch (val) {
            case "HOME" -> "HOME";
            case "SEARCH" -> "SEARCH";
            case "PLAYLIST" -> "PLAYLIST";
            case "ALBUM" -> "ALBUM";
            case "ARTIST" -> "ARTIST";
            case "LIBRARY" -> "LIBRARY";
            case "FAVORITES" -> "FAVORITES";
            case "EMOTION" -> "EMOTION";
            default -> "OTHER";
        };
    }

    private String normalizePlaybackEvent(String val) {
        if (val == null) return "COMPLETE";
        val = val.trim().toUpperCase();
        return switch (val) {
            case "PLAY" -> "PLAY";
            case "PAUSE" -> "PAUSE";
            case "RESUME" -> "RESUME";
            case "SEEK" -> "SEEK";
            case "SKIP_NEXT" -> "SKIP_NEXT";
            case "SKIP_PREVIOUS" -> "SKIP_PREVIOUS";
            default -> "COMPLETE";
        };
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

    public Map<String, Object> getUserListeningHistory(Long userId, int page, int size, String search) {
        if (userId == null) {
            return Map.of("items", List.of(), "totalElements", 0, "totalPages", 0, "summary", Map.of());
        }

        StringBuilder whereClause = new StringBuilder(" WHERE lh.user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (search != null && !search.isBlank()) {
            whereClause.append(" AND lh.track_id LIKE ? ");
            params.add("%" + search.trim() + "%");
        }

        String countSql = "SELECT COUNT(*) FROM listening_history lh " + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);
        if (totalElements == null) totalElements = 0L;

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = safePage * safeSize;

        String selectSql = """
            SELECT lh.id, lh.track_id, lh.started_at, lh.ended_at,
                   lh.listened_duration_ms, lh.last_position_ms,
                   lh.source, lh.source_id, lh.device_type,
                   (SELECT COUNT(*) FROM playback_events pe WHERE pe.listening_history_id = lh.id) as event_count
            FROM listening_history lh
        """ + whereClause + " ORDER BY lh.started_at DESC, lh.id DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safeSize);
        queryParams.add(offset);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Map<String, Object>> items = jdbcTemplate.query(selectSql, queryParams.toArray(), (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("trackId", rs.getString("track_id"));
            java.sql.Timestamp startedAtTs = rs.getTimestamp("started_at");
            java.sql.Timestamp endedAtTs = rs.getTimestamp("ended_at");
            m.put("startedAt", startedAtTs != null ? startedAtTs.toLocalDateTime().format(dtf) : "");
            m.put("endedAt", endedAtTs != null ? endedAtTs.toLocalDateTime().format(dtf) : "");

            long durMs = rs.getLong("listened_duration_ms");
            long posMs = rs.getLong("last_position_ms");
            m.put("listenedDurationMs", durMs);
            m.put("listenedDurationSeconds", Math.round(durMs / 1000.0));
            m.put("listenedDurationFormatted", formatDurationSeconds((int) (durMs / 1000)));
            m.put("lastPositionMs", posMs);
            m.put("lastPositionSeconds", Math.round(posMs / 1000.0));
            m.put("lastPositionFormatted", formatDurationSeconds((int) (posMs / 1000)));
            m.put("source", rs.getString("source"));
            m.put("sourceId", rs.getString("source_id"));
            m.put("deviceType", rs.getString("device_type"));
            m.put("eventCount", rs.getInt("event_count"));
            return m;
        });

        // Enrich with MongoDB Track Metadata
        for (Map<String, Object> item : items) {
            String trackId = (String) item.get("trackId");
            Track t = null;
            if (trackId != null && trackRepository != null) {
                t = trackRepository.findById(trackId).orElse(null);
                if (t == null) {
                    t = trackRepository.findBySpotifyId(trackId).orElse(null);
                }
            }

            long listenedMs = ((Number) item.get("listenedDurationMs")).longValue();

            if (t != null) {
                item.put("title", t.getName());
                item.put("trackTitle", t.getName());
                item.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ");
                item.put("artistName", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ");
                String resolvedCover = AudioUrlResolver.resolveImageUrl(t.getImageUrl());
                item.put("coverUrl", resolvedCover != null ? resolvedCover : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                item.put("audioUrl", AudioUrlResolver.resolve(t.getLocalPath()));
                item.put("spotifyId", t.getSpotifyId());
                long trackDurationMs = t.getDurationMs() != null && t.getDurationMs() > 0 ? t.getDurationMs() : 210000L;
                item.put("durationMs", trackDurationMs);
                item.put("totalDuration", formatDurationSeconds((int) (trackDurationMs / 1000)));
                item.put("genre", t.getGenres() != null && !t.getGenres().isEmpty() ? t.getGenres().get(0) : "V-Pop");
                item.put("lyricsSynced", t.getLyricsSynced());
                item.put("lyricsPlain", t.getLyricsPlain());

                double compPercent = Math.min(100.0, Math.round(((double) listenedMs / trackDurationMs) * 1000.0) / 10.0);
                item.put("completionRatePercent", compPercent);
                item.put("isCompleted", compPercent >= 80.0);
            } else {
                item.put("title", "Bài hát #" + (trackId != null ? trackId.substring(Math.max(0, trackId.length() - 6)) : "track"));
                item.put("trackTitle", "Bài hát #" + (trackId != null ? trackId.substring(Math.max(0, trackId.length() - 6)) : "track"));
                item.put("artist", "Moodify Artist");
                item.put("artistName", "Moodify Artist");
                item.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                item.put("audioUrl", "");
                item.put("spotifyId", trackId);
                item.put("durationMs", 210000L);
                item.put("totalDuration", "3:30");
                item.put("genre", "V-Pop");
                double compPercent = Math.min(100.0, Math.round(((double) listenedMs / 210000.0) * 1000.0) / 10.0);
                item.put("completionRatePercent", compPercent);
                item.put("isCompleted", compPercent >= 80.0);
            }
        }

        // Summary metrics for user
        Long totalDurationMs = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(listened_duration_ms), 0) FROM listening_history WHERE user_id = ?",
            Long.class, userId
        );
        if (totalDurationMs == null) totalDurationMs = 0L;

        Long totalCompleted = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT pe.listening_history_id) 
            FROM playback_events pe 
            JOIN listening_history lh ON pe.listening_history_id = lh.id 
            WHERE lh.user_id = ? AND pe.event_type = 'COMPLETE'
        """, Long.class, userId);
        if (totalCompleted == null) totalCompleted = 0L;

        double overallCompletionRate = totalElements > 0 
            ? Math.min(100.0, Math.round(((double) totalCompleted / totalElements) * 1000.0) / 10.0) 
            : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalListenedTracks", totalElements);
        summary.put("totalListenedMinutes", Math.round(totalDurationMs / 60000.0));
        summary.put("totalListenedHours", Math.round((totalDurationMs / 3600000.0) * 10.0) / 10.0);
        summary.put("completionRatePercent", overallCompletionRate);
        summary.put("totalCompletedSessions", totalCompleted);

        int totalPages = (int) Math.ceil((double) totalElements / safeSize);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages);
        result.put("currentPage", safePage);
        result.put("pageSize", safeSize);
        result.put("summary", summary);
        return result;
    }

    public Map<String, Object> getUserHistoryEvents(Long userId, Long historyId) {
        if (userId == null || historyId == null) {
            return Map.of("items", List.of());
        }

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM listening_history WHERE id = ? AND user_id = ?",
            Integer.class, historyId, userId
        );
        if (count == null || count == 0) {
            return Map.of("items", List.of(), "error", "Phiên nghe không thuộc về người dùng này.");
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        String sql = """
            SELECT id, listening_history_id, event_type, position_ms, target_position_ms, occurred_at
            FROM playback_events
            WHERE listening_history_id = ?
            ORDER BY occurred_at ASC, id ASC
        """;

        List<Map<String, Object>> events = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("listeningHistoryId", rs.getLong("listening_history_id"));
            m.put("eventType", rs.getString("event_type"));
            long posMs = rs.getLong("position_ms");
            long tgtMs = rs.getLong("target_position_ms");
            m.put("positionMs", posMs);
            m.put("positionFormatted", formatDurationSeconds((int) (posMs / 1000)));
            m.put("targetPositionMs", tgtMs);
            m.put("targetPositionFormatted", formatDurationSeconds((int) (tgtMs / 1000)));
            java.sql.Timestamp occurredTs = rs.getTimestamp("occurred_at");
            m.put("occurredAt", occurredTs != null ? occurredTs.toLocalDateTime().format(dtf) : "");
            return m;
        }, historyId);

        return Map.of("historyId", historyId, "items", events, "totalEvents", events.size());
    }

    public boolean deleteUserHistoryItem(Long userId, Long historyId) {
        if (userId == null || historyId == null) return false;
        int rows = jdbcTemplate.update(
            "DELETE FROM listening_history WHERE id = ? AND user_id = ?",
            historyId, userId
        );
        return rows > 0;
    }

    public int clearUserHistory(Long userId) {
        if (userId == null) return 0;
        return jdbcTemplate.update(
            "DELETE FROM listening_history WHERE user_id = ?",
            userId
        );
    }

    private String formatDurationSeconds(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
