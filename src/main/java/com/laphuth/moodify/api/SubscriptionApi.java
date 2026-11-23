package com.laphuth.moodify.api;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.AudioUrlResolver;
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
     * Lấy cấu hình hạng gói công khai (FREE + các hạng gói hiện có).
     */
    @GetMapping("/packages/tiers")
    public ResponseEntity<List<Map<String, Object>>> getPublicSubscriptionTiers() {
        return ResponseEntity.ok(subscriptionService.getPublicSubscriptionTiers());
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

    /** Danh sách lịch sử gói và các biên nhận thanh toán của tài khoản hiện tại. */
    @GetMapping("/subscriptions/me/management")
    public ResponseEntity<List<Map<String, Object>>> getMySubscriptionManagement(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(subscriptionService.getMySubscriptionManagement(authentication.getName()));
    }

    /** Hủy đơn chờ thanh toán hoặc gói đã thanh toán nhưng chưa tới ngày áp dụng. */
    @PostMapping("/subscriptions/me/{subscriptionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelMySubscription(
        Authentication authentication,
        @PathVariable Long subscriptionId
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(subscriptionService.cancelMyManagedSubscription(
            authentication.getName(), subscriptionId));
    }

    /** Báo giá và thời điểm áp dụng theo chính sách gói của tài khoản hiện tại. */
    @GetMapping("/subscriptions/package-quotes")
    public ResponseEntity<List<Map<String, Object>>> getPackageQuotes(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(subscriptionService.getPackageQuotes(authentication.getName()));
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

        // Kiểm tra quyền tải ngoại tuyến theo gói hiện tại
        Map<String, Object> sub = subscriptionService.getCurrentUserSubscription(authentication.getName());
        boolean isPremium = Boolean.TRUE.equals(sub.get("isPremium"));
        Map<?, ?> entitlements = sub.get("entitlements") instanceof Map<?, ?> ent ? ent : null;
        boolean offlineAllowed = entitlements != null && Boolean.TRUE.equals(entitlements.get("offlineAllowed"));
        int maxTracks = entitlements != null && entitlements.get("offlineMaxTracks") instanceof Number num
                ? num.intValue() : 0;
        if (!isPremium || !offlineAllowed || maxTracks <= 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", isPremium ? "OFFLINE_NOT_ALLOWED" : "PREMIUM_REQUIRED",
                "message", "Gói dịch vụ hiện tại của bạn không bao gồm quyền tải nhạc ngoại tuyến."
            ));
        }

        Track track = trackRepository.findBySpotifyId(trackSpotifyId)
            .or(() -> trackRepository.findById(trackSpotifyId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài hát."));

        String downloadUrl = AudioUrlResolver.resolve(track.getLocalPath());

        // Redirect hoặc trả về URL download kèm file name
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(downloadUrl))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + (track.getName() != null ? track.getName() : "track") + ".mp3\"")
            .build();
    }
}
