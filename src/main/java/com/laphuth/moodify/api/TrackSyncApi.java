package com.laphuth.moodify.api;

import com.laphuth.moodify.services.TrackSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sync/tracks")
public class TrackSyncApi {

    private final TrackSyncService trackSyncService;

    public TrackSyncApi(TrackSyncService trackSyncService) {
        this.trackSyncService = trackSyncService;
    }

    /**
     * Kích hoạt đồng bộ 2 chiều ngay lập tức (Manual trigger)
     * POST /api/sync/tracks
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerSync() {
        Map<String, Object> result = trackSyncService.syncTwoWay();
        return ResponseEntity.ok(result);
    }

    /**
     * Kiểm tra trạng thái và lịch sử đồng bộ gần nhất
     * GET /api/sync/tracks/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        return ResponseEntity.ok(trackSyncService.getSyncStatus());
    }
}
