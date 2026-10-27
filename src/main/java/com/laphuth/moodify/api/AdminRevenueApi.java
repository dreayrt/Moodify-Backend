package com.laphuth.moodify.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/revenue")
public class AdminRevenueApi {
    private final JdbcTemplate jdbc;

    public AdminRevenueApi(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/pending")
    public List<Map<String, Object>> pending() {
        return jdbc.queryForList("SELECT id,track_id,listener_user_id,content_lead_user_id,qualified_at,review_reason FROM qualified_streams WHERE status='PENDING_REVIEW' ORDER BY qualified_at LIMIT 200");
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable long id, @RequestBody Map<String, String> body, Authentication auth) {
        String decision = body.get("decision");
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED or REJECTED");
        }
        String reason = body.get("reason");
        if ("REJECTED".equals(decision) && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejection reason is required");
        }
        Long reviewer = jdbc.query("SELECT id FROM users WHERE email=? OR username=? LIMIT 1", (rs, row) -> rs.getLong(1), auth.getName(), auth.getName()).stream().findFirst().orElse(null);
        int changed = jdbc.update("UPDATE qualified_streams SET status=?,review_reason=?,reviewed_by=?,reviewed_at=? WHERE id=? AND status='PENDING_REVIEW'",
            decision, reason, reviewer, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)), id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stream is no longer pending");
        return ResponseEntity.ok(Map.of("id", id, "status", decision));
    }
}
