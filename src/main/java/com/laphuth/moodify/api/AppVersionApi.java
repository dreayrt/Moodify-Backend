package com.laphuth.moodify.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Public endpoint cung cấp thông tin phiên bản mới nhất của ứng dụng Android
 * phục vụ cơ chế In-App Auto-Update không qua Google Play Store.
 */
@RestController
@RequestMapping("/api/app")
public class AppVersionApi {

    @Value("${app.update.latest-version:0.1.1}")
    private String latestVersion;

    @Value("${app.update.latest-build-number:2}")
    private int latestBuildNumber;

    @Value("${app.update.download-url:https://api.moodify.click/uploads/apk/moodify-update-test.apk}")
    private String downloadUrl;

    @Value("${app.update.release-notes:• Bản cập nhật mới dành cho Moodify Android\n• Tính năng tự động cập nhật & cài đặt trực tiếp\n• Tối ưu hóa hiệu năng & độ ổn định}")
    private String releaseNotes;

    @Value("${app.update.force-update:false}")
    private boolean forceUpdate;

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getLatestVersion() {
        Map<String, Object> body = new HashMap<>();
        body.put("latestVersion", latestVersion);
        body.put("latestBuildNumber", latestBuildNumber);
        body.put("downloadUrl", downloadUrl);
        body.put("releaseNotes", releaseNotes);
        body.put("forceUpdate", forceUpdate);
        return ResponseEntity.ok(body);
    }
}
