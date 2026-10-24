package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.analytics.TrackVisitRequest;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.services.PlatformTrafficService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/analytics")
public class TrafficAnalyticsApi {

    private final PlatformTrafficService platformTrafficService;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;

    public TrafficAnalyticsApi(
        PlatformTrafficService platformTrafficService,
        UserRepository userRepository,
        TrackRepository trackRepository
    ) {
        this.platformTrafficService = platformTrafficService;
        this.userRepository = userRepository;
        this.trackRepository = trackRepository;
    }

    @PostMapping("/track-visit")
    public ResponseEntity<Map<String, Object>> trackVisit(
        @RequestBody TrackVisitRequest request,
        Authentication authentication
    ) {
        Long userId = null;
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getName())) {
            Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
            if (u.isPresent()) {
                userId = u.get().getId();
            }
        }

        boolean success = platformTrafficService.recordVisit(request, userId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Visit recorded" : "Failed to record visit"
        ));
    }

    @PostMapping("/listening-session")
    public ResponseEntity<Map<String, Object>> recordListeningSession(
        @RequestBody Map<String, Object> body,
        Authentication authentication
    ) {
        Long userId = null;
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getName())) {
            Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
            if (u.isPresent()) {
                userId = u.get().getId();
            }
        }

        Long historyId = body.get("historyId") != null ? ((Number) body.get("historyId")).longValue() : null;
        String trackId = (String) body.get("trackId");
        Long durationMs = body.get("durationMs") != null ? ((Number) body.get("durationMs")).longValue() : 0L;
        Long positionMs = body.get("positionMs") != null ? ((Number) body.get("positionMs")).longValue() : durationMs;
        Long targetPositionMs = body.get("targetPositionMs") != null ? ((Number) body.get("targetPositionMs")).longValue() : positionMs;
        String source = (String) body.get("source");
        String sourceId = (String) body.get("sourceId");
        String deviceType = (String) body.get("deviceType");
        String eventType = (String) body.get("eventType");

        Map<String, Object> result = platformTrafficService.recordListeningSession(
            historyId, userId, trackId, durationMs, positionMs, targetPositionMs, source, sourceId, deviceType, eventType
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/my-history")
    public ResponseEntity<Map<String, Object>> getMyListeningHistory(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search
    ) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(401).body(Map.of("message", "Vui lòng đăng nhập để xem lịch sử nghe nhạc."));
        }
        Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
        if (u.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Không tìm thấy người dùng."));
        }
        Map<String, Object> history = platformTrafficService.getUserListeningHistory(u.get().getId(), page, size, search);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/my-history/{historyId}/events")
    public ResponseEntity<Map<String, Object>> getMyHistoryEvents(
        Authentication authentication,
        @PathVariable Long historyId
    ) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(401).body(Map.of("message", "Vui lòng đăng nhập."));
        }
        Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
        if (u.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Không tìm thấy người dùng."));
        }
        Map<String, Object> events = platformTrafficService.getUserHistoryEvents(u.get().getId(), historyId);
        return ResponseEntity.ok(events);
    }

    @DeleteMapping("/my-history/{historyId}")
    public ResponseEntity<Map<String, Object>> deleteMyHistoryItem(
        Authentication authentication,
        @PathVariable Long historyId
    ) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(401).body(Map.of("message", "Vui lòng đăng nhập."));
        }
        Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
        if (u.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Không tìm thấy người dùng."));
        }
        boolean deleted = platformTrafficService.deleteUserHistoryItem(u.get().getId(), historyId);
        return ResponseEntity.ok(Map.of(
            "success", deleted,
            "message", deleted ? "Đã xóa bài hát khỏi lịch sử nghe." : "Không tìm thấy bài hát cần xóa."
        ));
    }

    @DeleteMapping("/my-history")
    public ResponseEntity<Map<String, Object>> clearMyHistory(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(401).body(Map.of("message", "Vui lòng đăng nhập."));
        }
        Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
        if (u.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Không tìm thấy người dùng."));
        }
        int count = platformTrafficService.clearUserHistory(u.get().getId());
        return ResponseEntity.ok(Map.of(
            "success", true,
            "deletedCount", count,
            "message", "Đã xóa toàn bộ lịch sử nghe nhạc của bạn."
        ));
    }

    @PostMapping("/seed-traffic")
    public ResponseEntity<Map<String, Object>> seedTraffic(
        Authentication authentication,
        @RequestParam(defaultValue = "150") int count
    ) {
        List<String> targetIds = new ArrayList<>();

        if (authentication != null && authentication.isAuthenticated()) {
            Optional<User> userOpt = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                String artistSpotifyId = user.getArtistSpotifyId();
                if (artistSpotifyId != null && !artistSpotifyId.isBlank()) {
                    List<Track> tracks = trackRepository.findByArtistSpotifyId(artistSpotifyId.trim());
                    for (Track t : tracks) {
                        if (t.getId() != null) targetIds.add(t.getId());
                        if (t.getSpotifyId() != null) targetIds.add(t.getSpotifyId());
                    }
                }
            }
        }

        if (targetIds.isEmpty()) {
            List<Track> sampleTracks = trackRepository.findByModerationStatus("approved");
            for (int i = 0; i < Math.min(5, sampleTracks.size()); i++) {
                Track t = sampleTracks.get(i);
                if (t.getId() != null) targetIds.add(t.getId());
            }
        }

        int inserted = platformTrafficService.seedTrafficData(targetIds, count);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "inserted", inserted,
            "targetCount", targetIds.size(),
            "message", "Successfully seeded " + inserted + " traffic events"
        ));
    }
}
