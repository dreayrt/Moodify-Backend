package com.laphuth.moodify.api;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.SubscriptionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SubscriptionApi {

    private final SubscriptionService subscriptionService;
    private final TrackRepository trackRepository;

    private static final String AUDIO_SERVER_BASE_URL = "https://musiccollector.kandes.io.vn/";

    public SubscriptionApi(SubscriptionService subscriptionService, TrackRepository trackRepository) {
        this.subscriptionService = subscriptionService;
        this.trackRepository = trackRepository;
    }

    /**
     * Lấy danh sách các gói dịch vụ đang mở bán (Public API).
     */
    @GetMapping("/packages")
    public ResponseEntity<List<Map<String, Object>>> getPackages() {
        return ResponseEntity.ok(subscriptionService.getActivePackages());
    }

    /**
     * Lấy thông tin bản quyền và trạng thái gói cước của người dùng hiện tại.
     */
    @GetMapping("/subscriptions/me")
    public ResponseEntity<Map<String, Object>> getMySubscription(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(subscriptionService.getCurrentUserSubscription(authentication.getName()));
    }

    /**
     * Đăng ký mua gói dịch vụ (Sandbox Payment).
     */
    @PostMapping("/subscriptions/subscribe")
    public ResponseEntity<Map<String, Object>> subscribe(
        Authentication authentication,
        @RequestBody Map<String, Object> request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Object pkgIdObj = request.get("packageId");
        if (pkgIdObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packageId là bắt buộc.");
        }
        Long packageId = Long.valueOf(pkgIdObj.toString());
        String paymentMethod = (String) request.getOrDefault("paymentMethod", "QR_TRANSFER");

        return ResponseEntity.ok(subscriptionService.subscribePackage(authentication.getName(), packageId, paymentMethod));
    }

    /**
     * Endpoint dành riêng cho DEV/TEST: Bật / Tắt Premium tức thì để demo và kiểm thử.
     */
    @PostMapping("/subscriptions/dev-toggle")
    public ResponseEntity<Map<String, Object>> devToggle(
        Authentication authentication,
        @RequestBody Map<String, Object> request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean enable = Boolean.TRUE.equals(request.get("enable"));
        Integer days = request.get("days") != null ? Integer.valueOf(request.get("days").toString()) : 30;

        return ResponseEntity.ok(subscriptionService.devTogglePremium(authentication.getName(), enable, days));
    }

    /**
     * Tải nhạc ngoại tuyến (Offline Download): Yêu cầu tài khoản có gói Premium.
     */
    @GetMapping("/subscriptions/tracks/{trackSpotifyId}/download")
    public ResponseEntity<?> downloadTrack(
        Authentication authentication,
        @PathVariable String trackSpotifyId
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "UNAUTHORIZED",
                "message", "Vui lòng đăng nhập để sử dụng tính năng này."
            ));
        }

        // Kiểm tra quyền Premium
        Map<String, Object> sub = subscriptionService.getCurrentUserSubscription(authentication.getName());
        boolean isPremium = Boolean.TRUE.equals(sub.get("isPremium"));
        if (!isPremium) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "PREMIUM_REQUIRED",
                "message", "Tính năng tải nhạc ngoại tuyến chỉ dành riêng cho tài khoản Moodify Premium."
            ));
        }

        Track track = trackRepository.findBySpotifyId(trackSpotifyId)
            .or(() -> trackRepository.findById(trackSpotifyId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài hát."));

        String localPath = track.getLocalPath();
        String downloadUrl;
        if (localPath != null && !localPath.isBlank()) {
            String clean = localPath.trim().replace("\\", "/");
            if (clean.startsWith("/")) clean = clean.substring(1);
            downloadUrl = clean.startsWith("http") ? clean : AUDIO_SERVER_BASE_URL + clean;
        } else {
            downloadUrl = "https://musiccollector.kandes.io.vn/data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3";
        }

        // Redirect hoặc trả về URL download kèm file name
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(downloadUrl))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + (track.getName() != null ? track.getName() : "track") + ".mp3\"")
            .build();
    }
}
