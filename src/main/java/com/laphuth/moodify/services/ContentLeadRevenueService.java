package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ContentLeadRevenueService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final TrackRepository tracks;
    private final SongLicenseRepository licenses;

    public ContentLeadRevenueService(JdbcTemplate jdbc, UserRepository users, TrackRepository tracks, SongLicenseRepository licenses) {
        this.jdbc = jdbc;
        this.users = users;
        this.tracks = tracks;
        this.licenses = licenses;
    }

    public Map<String, Object> report(String principal, String period) {
        User lead = users.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (lead.getArtistSpotifyId() == null || lead.getArtistSpotifyId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Content Lead catalog is not linked");
        }
        String selected = period != null && List.of("today", "week", "month", "year").contains(period) ? period : "month";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDate fromDate = switch (selected) {
            case "today" -> today;
            case "week" -> today.minusDays(6);
            case "year" -> today.minusDays(364);
            default -> today.minusDays(29);
        };
        Timestamp fromUtc = Timestamp.valueOf(fromDate.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        Timestamp toUtc = Timestamp.valueOf(today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        List<Track> ownTracks = tracks.findByArtistSpotifyId(lead.getArtistSpotifyId());
        Map<String, Track> ownById = new HashMap<>();
        ownTracks.forEach(track -> ownById.put(track.getId(), track));

        String totalsSql = "SELECT track_id, COUNT(*) AS plays, COALESCE(SUM(gross_vnd),0) AS gross, COALESCE(SUM(content_lead_vnd),0) AS content_lead_total, COALESCE(SUM(platform_vnd),0) AS platform FROM qualified_streams WHERE content_lead_user_id=? AND status='APPROVED' AND qualified_at>=? AND qualified_at<? GROUP BY track_id";
        Map<String, Map<String, Object>> periodTotals = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(totalsSql, lead.getId(), fromUtc, toUtc)) {
            periodTotals.put((String) row.get("track_id"), row);
        }
        Map<String, BigDecimal> lifetimeLead = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT track_id,COALESCE(SUM(content_lead_vnd),0) AS lifetime_content_lead_total FROM qualified_streams WHERE content_lead_user_id=? AND status='APPROVED' GROUP BY track_id", lead.getId())) {
            lifetimeLead.put((String) row.get("track_id"), decimal(row.get("lifetime_content_lead_total")));
        }
        Map<String, Long> pending = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT track_id,COUNT(*) AS plays FROM qualified_streams WHERE content_lead_user_id=? AND status='PENDING_REVIEW' AND qualified_at>=? AND qualified_at<? GROUP BY track_id", lead.getId(), fromUtc, toUtc)) {
            pending.put((String) row.get("track_id"), ((Number) row.get("plays")).longValue());
        }
        BigDecimal gross = BigDecimal.ZERO, leadShare = BigDecimal.ZERO, platformShare = BigDecimal.ZERO;
        long plays = 0, pendingPlays = 0;
        List<Map<String, Object>> trackRows = new ArrayList<>();
        var trackIds = new LinkedHashSet<>(ownById.keySet());
        trackIds.addAll(periodTotals.keySet());
        trackIds.addAll(lifetimeLead.keySet());
        trackIds.addAll(pending.keySet());
        for (String trackId : trackIds) {
            Track track = ownById.get(trackId);
            Map<String, Object> sums = periodTotals.getOrDefault(trackId, Map.of());
            long trackPlays = sums.containsKey("plays") ? ((Number) sums.get("plays")).longValue() : 0L;
            long trackPending = pending.getOrDefault(trackId, 0L);
            BigDecimal trackGross = decimal(sums.get("gross"));
            BigDecimal trackLead = decimal(sums.get("content_lead_total"));
            BigDecimal trackPlatform = decimal(sums.get("platform"));
            BigDecimal cost = licenses.findByTrackId(trackId).map(license -> license.getAcquisitionCostVnd()).orElse(null);
            BigDecimal lifetime = lifetimeLead.getOrDefault(trackId, BigDecimal.ZERO);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", trackId);
            item.put("title", track == null ? "Bài hát đã gỡ (" + trackId + ")" : track.getName());
            item.put("genre", track == null || track.getGenres() == null || track.getGenres().isEmpty() ? "" : track.getGenres().get(0));
            item.put("coverUrl", track == null ? null : track.getImageUrl());
            item.put("qualifiedPlays", trackPlays);
            item.put("pendingPlays", trackPending);
            item.put("grossVnd", trackGross);
            item.put("contentLeadVnd", trackLead);
            item.put("platformVnd", trackPlatform);
            item.put("acquisitionCostVnd", cost);
            item.put("lifetimeContentLeadVnd", lifetime);
            item.put("recoupBalanceVnd", cost == null ? null : lifetime.subtract(cost));
            trackRows.add(item);
            gross = gross.add(trackGross);
            leadShare = leadShare.add(trackLead);
            platformShare = platformShare.add(trackPlatform);
            plays += trackPlays;
            pendingPlays += trackPending;
        }
        List<Map<String, Object>> trend = jdbc.queryForList("SELECT DATE(qualified_at + INTERVAL 7 HOUR) AS day,COALESCE(SUM(content_lead_vnd),0) AS contentLeadVnd FROM qualified_streams WHERE content_lead_user_id=? AND status='APPROVED' AND qualified_at>=? AND qualified_at<? GROUP BY DATE(qualified_at + INTERVAL 7 HOUR) ORDER BY day", lead.getId(), fromUtc, toUtc);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", selected);
        result.put("fromDate", fromDate);
        result.put("toDate", today);
        result.put("qualifiedPlays", plays);
        result.put("pendingPlays", pendingPlays);
        result.put("grossVnd", gross);
        result.put("contentLeadVnd", leadShare);
        result.put("platformVnd", platformShare);
        result.put("trend", trend);
        result.put("tracks", trackRows);
        return result;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
