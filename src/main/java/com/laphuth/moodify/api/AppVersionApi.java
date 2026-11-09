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

    @Value("${app.update.latest-version:0.1.2}")
    private String latestVersion;

    @Value("${app.update.latest-build-number:3}")
    private int latestBuildNumber;

    @Value("${app.update.download-url:https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.2/moodify-release.apk}")
    private String downloadUrl;

    @Value("${app.update.release-notes:• Bổ sung và đồng bộ giao diện Playlist hợp gu với bản web\n• Cập nhật câu chào âm nhạc trên màn hình khởi động\n• Nâng cấp trải nghiệm phát nhạc và hiệu năng}")
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
