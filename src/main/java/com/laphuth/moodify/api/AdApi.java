package com.laphuth.moodify.api;

import com.laphuth.moodify.services.AdService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API public phục vụ player: lấy danh sách quảng cáo đang hiệu lực
 * và ghi nhận lượt hiển thị (impression).
 */
@RestController
@RequestMapping("/api/ads")
public class AdApi {

    private final AdService adService;

    public AdApi(AdService adService) {
        this.adService = adService;
    }

    @GetMapping("/active")
    public ResponseEntity<List<Map<String, Object>>> getActiveAds() {
        return ResponseEntity.ok(adService.getActiveAds());
    }

    @PostMapping("/{id}/impression")
    public ResponseEntity<Map<String, Object>> recordImpression(@PathVariable String id) {
        adService.recordImpression(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
