package com.laphuth.moodify.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/content-lead/me/analytics")
public class ContentLeadRevenueApi {
    // Transitional response for older clients. SecurityConfig still restricts this route.
    @GetMapping("/revenue")
    public ResponseEntity<Map<String, Object>> report() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "success", false,
            "error", "STREAM_REVENUE_REMOVED",
            "message", "Chức năng doanh thu từ lượt nghe đã ngừng hoạt động."
        ));
    }
}
