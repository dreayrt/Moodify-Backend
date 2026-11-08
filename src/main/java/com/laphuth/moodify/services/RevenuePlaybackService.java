package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.recommendation.RecommendationTypes;

import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class RevenuePlaybackService {
    private static final long QUALIFY_MS = 60_000;
    private final JdbcTemplate jdbc;
    private final TrackRepository tracks;
    private final SongLicenseRepository licenses;
    private final RecommendationOutcomeDispatcher recommendationOutcomes;

    public RevenuePlaybackService(JdbcTemplate jdbc, TrackRepository tracks, SongLicenseRepository licenses,
        RecommendationOutcomeDispatcher recommendationOutcomes) {
        this.jdbc = jdbc;
        this.tracks = tracks;
        this.licenses = licenses;
        this.recommendationOutcomes = recommendationOutcomes;
    }

    @Transactional
    public Map<String, Object> record(Long userId, String rawTrackId, String rawSessionKey, String rawEvent,
                                      long positionMs, long targetPositionMs) {
        return record(userId, rawTrackId, rawSessionKey, rawEvent, positionMs, targetPositionMs, "OTHER", null, "WEB", null);
    }

    @Transactional
    public Map<String, Object> record(Long userId, String rawTrackId, String rawSessionKey, String rawEvent,
                                      long positionMs, long targetPositionMs, String source, String sourceId, String deviceType,
                                      RecommendationTypes.TokenClaims attribution) {
        if (userId == null || rawTrackId == null || rawSessionKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authenticated user, track and session are required");
        }
        try { UUID.fromString(rawSessionKey); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session key"); }
        Track track = tracks.findById(rawTrackId).orElseGet(() -> tracks.findBySpotifyId(rawTrackId).orElse(null));
        if (track == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found");
        String trackId = track.getId();
        String event = rawEvent == null ? "HEARTBEAT" : rawEvent.toUpperCase();
        if (!java.util.Set.of("PLAY", "RESUME", "PAUSE", "SEEK", "HEARTBEAT", "COMPLETE", "SKIP_NEXT", "SKIP_PREVIOUS", "TRACK_CHANGED", "PLAYBACK_STOPPED").contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid playback event");
        }
        long position = Math.max(0, positionMs);
        if (track.getDurationMs() != null && position > track.getDurationMs() + 2_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playback position exceeds track duration");
        }
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        var rows = jdbc.queryForList("""
            SELECT s.session_key, s.listening_history_id, s.last_seen_at, s.continuous_ms,
                   s.playing, s.closed, h.user_id, h.track_id,
                   h.last_position_ms, h.listened_duration_ms
            FROM revenue_playback_sessions s
            JOIN listening_history h ON h.id = s.listening_history_id
            WHERE s.session_key = ?
            FOR UPDATE
            """, rawSessionKey);
        if (rows.isEmpty()) {
            if (!event.equals("PLAY") && !event.equals("HEARTBEAT")) {
                return Map.of("success", false, "message", "Start playback first");
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                    "INSERT INTO listening_history (user_id,track_id,started_at,listened_duration_ms,last_position_ms,source,source_id,device_type) VALUES (?,?,?,0,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                statement.setString(2, trackId);
                statement.setObject(3, now);
                statement.setLong(4, position);
                statement.setString(5, source);
                statement.setString(6, sourceId);
                statement.setString(7, deviceType);
                return statement;
            }, key);
            long historyId = key.getKey().longValue();
            jdbc.update("INSERT INTO revenue_playback_sessions (session_key,listening_history_id,last_seen_at,playing) VALUES (?,?,?,TRUE)",
                rawSessionKey, historyId, now);
            jdbc.update("INSERT INTO playback_events (listening_history_id,event_type,position_ms,occurred_at) VALUES (?,'PLAY',?,?)",
                historyId, position, now);
            if (attribution != null) {
                String eventId = UUID.nameUUIDFromBytes((userId + ":play:" + rawSessionKey)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                recordRecommendationAfterCommit(userId, eventId, attribution, now.toInstant(ZoneOffset.UTC), rawSessionKey, historyId);
            }
            return Map.of("success", true, "historyId", historyId, "qualified", false, "created", true);
        }
        Map<String, Object> session = rows.get(0);
        if (((Number) session.get("user_id")).longValue() != userId || !trackId.equals(session.get("track_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playback session does not belong to this user and track");
        }
        long historyId = ((Number) session.get("listening_history_id")).longValue();
        if (isTrue(session.get("closed"))) {
            return Map.of("success", true, "historyId", historyId, "qualified", hasQualified(rawSessionKey));
        }
        // MySQL Connector/J returns java.time.LocalDateTime for DATETIME columns, not Timestamp.
        Object lastSeenValue = session.get("last_seen_at");
        LocalDateTime lastSeen = lastSeenValue instanceof Timestamp ts
            ? ts.toLocalDateTime()
            : (LocalDateTime) lastSeenValue;
        long elapsed = Math.max(0, Duration.between(lastSeen, now).toMillis());
        long previousPosition = ((Number) session.get("last_position_ms")).longValue();
        long deltaPosition = position - previousPosition;
        long continuous = ((Number) session.get("continuous_ms")).longValue();
        long totalPlayed = ((Number) session.get("listened_duration_ms")).longValue();
        boolean playing = isTrue(session.get("playing"));
        boolean progressEvent = java.util.Set.of("HEARTBEAT", "PAUSE", "COMPLETE", "SKIP_NEXT", "SKIP_PREVIOUS", "TRACK_CHANGED", "PLAYBACK_STOPPED").contains(event);
        if (playing && progressEvent && elapsed > 0) {
            long advanced = advanceContinuousMs(continuous, elapsed, deltaPosition);
            if (advanced > continuous) totalPlayed += elapsed;
            continuous = advanced;
        }
        boolean closed = java.util.Set.of("COMPLETE", "SKIP_NEXT", "SKIP_PREVIOUS", "TRACK_CHANGED", "PLAYBACK_STOPPED").contains(event);
        if (continuous >= QUALIFY_MS && !hasQualified(rawSessionKey)) {
            qualify(userId, track, rawSessionKey, now);
        }
        if (event.equals("PAUSE") || event.equals("SEEK") || closed) continuous = 0;
        // SEEK resets the continuous counter but the listener is still playing — keep the
        // playing flag (only PAUSE/closed events may clear it), otherwise heartbeats after a
        // seek stop accumulating and the stream can never qualify.
        boolean nextPlaying = event.equals("PLAY") || event.equals("RESUME") || event.equals("SEEK")
            || (event.equals("HEARTBEAT") && playing);
        jdbc.update("UPDATE revenue_playback_sessions SET last_seen_at=?,continuous_ms=?,playing=?,closed=? WHERE session_key=?",
            now, continuous, nextPlaying, closed, rawSessionKey);
        jdbc.update("UPDATE listening_history SET listened_duration_ms=?,last_position_ms=?,ended_at=? WHERE id=?",
            totalPlayed, position, closed ? now : null, historyId);
        String storedEvent = switch (event) {
            case "PLAY", "RESUME", "PAUSE", "SEEK", "COMPLETE", "SKIP_NEXT", "SKIP_PREVIOUS" -> event;
            default -> null;
        };
        if (storedEvent != null) {
            jdbc.update("INSERT INTO playback_events (listening_history_id,event_type,position_ms,target_position_ms,occurred_at) VALUES (?,?,?,?,?)",
                historyId, storedEvent, position, Math.max(0, targetPositionMs), now);
        }
        return Map.of("success", true, "historyId", historyId, "qualified", hasQualified(rawSessionKey));
    }

    private void recordRecommendationAfterCommit(long userId, String eventId,
        RecommendationTypes.TokenClaims claims,
        Instant at, String sessionKey, long historyId) {
        // MySQL is the authoritative listening/financial store. Recommendation analytics are best effort
        // after commit: no distributed transaction or new SQL outbox is required.
        Runnable write = () -> recommendationOutcomes.submit(userId,eventId,claims,at,sessionKey,historyId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { write.run(); }
                });
        } else write.run();
    }

    private boolean hasQualified(String key) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM qualified_streams WHERE session_key=?", Long.class, key);
        return count != null && count > 0;
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }

    static long advanceContinuousMs(long previous, long elapsed, long positionDelta) {
        if (elapsed <= 0 || elapsed > 15_000 || positionDelta < elapsed / 2 ||
            positionDelta > elapsed * 2.25 + 1500) return 0;
        return previous + elapsed;
    }

    private void qualify(Long userId, Track track, String key, LocalDateTime now) {
        if (!"published".equalsIgnoreCase(track.getStatus()) || !"approved".equalsIgnoreCase(track.getModerationStatus())) return;
        if (track.getDurationMs() != null && track.getDurationMs() < QUALIFY_MS) return;
        SongLicense license = licenses.findByTrackId(track.getId()).orElse(null);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        if (license == null || license.getStatus() != LicenseStatus.ACTIVE ||
            (license.getIssueDate() != null && license.getIssueDate().isAfter(today)) ||
            (license.getExpiryDate() != null && license.getExpiryDate().isBefore(today))) return;
        Long leadId = jdbc.query("SELECT id FROM users WHERE role='CONTENT_LEAD' AND artist_spotify_id=? LIMIT 1",
            (rs, index) -> rs.getLong(1), track.getArtistSpotifyId()).stream().findFirst().orElse(null);
        if (leadId == null || leadId.equals(userId)) return;
        String role = jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, userId);
        if (!"USER".equals(role)) return;
        Long overlapping = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM revenue_playback_sessions s
            JOIN listening_history h ON h.id = s.listening_history_id
            WHERE h.user_id=? AND h.track_id=? AND s.session_key<>?
              AND s.playing=TRUE AND s.closed=FALSE AND s.last_seen_at>=?
            """, Long.class, userId, track.getId(), key, now.minusSeconds(20));
        Long rapidRepeat = jdbc.queryForObject("SELECT COUNT(*) FROM qualified_streams WHERE listener_user_id=? AND track_id=? AND qualified_at>=?",
            Long.class, userId, track.getId(), now.minusMinutes(10));
        boolean review = (overlapping != null && overlapping > 0) || (rapidRepeat != null && rapidRepeat >= 3);
        jdbc.update("INSERT INTO qualified_streams (session_key,listener_user_id,content_lead_user_id,track_id,qualified_at,gross_vnd,content_lead_vnd,platform_vnd,policy_version,status,review_reason) VALUES (?,?,?,?,?,1000,750,250,'V1_60S_75_25',?,?)",
            key, userId, leadId, track.getId(), now, review ? "PENDING_REVIEW" : "APPROVED",
            review ? "Overlapping playback or rapid repeated streams" : null);
    }
}
