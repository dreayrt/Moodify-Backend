package com.laphuth.moodify.api;

import com.laphuth.moodify.services.ContentLeadRevenueService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/content-lead/me/analytics")
public class ContentLeadRevenueApi {
    private final ContentLeadRevenueService revenue;

    public ContentLeadRevenueApi(ContentLeadRevenueService revenue) {
        this.revenue = revenue;
    }

    @GetMapping("/revenue")
    public ResponseEntity<Map<String, Object>> report(Authentication authentication,
                                                        @RequestParam(defaultValue = "month") String period) {
        return ResponseEntity.ok(revenue.report(authentication.getName(), period));
    }
}
