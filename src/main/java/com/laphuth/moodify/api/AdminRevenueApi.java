package com.laphuth.moodify.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/revenue")
public class AdminRevenueApi {
    // Keep tombstones for older admin clients; never read or mutate the historical ledger.
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> pending() {
        return removed();
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<Map<String, Object>> review() {
        return removed();
    }

    private ResponseEntity<Map<String, Object>> removed() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "success", false,
            "error", "STREAM_REVENUE_REMOVED",
            "message", "Chức năng doanh thu từ lượt nghe đã ngừng hoạt động."
        ));
    }
}
