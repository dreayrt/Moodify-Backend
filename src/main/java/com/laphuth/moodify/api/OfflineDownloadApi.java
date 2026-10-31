package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.offline.OfflineConfirmRequest;
import com.laphuth.moodify.dto.offline.OfflineDownloadRequest;
import com.laphuth.moodify.dto.offline.OfflineVerifyRequest;
import com.laphuth.moodify.services.OfflineDownloadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/offline")
public class OfflineDownloadApi {

    private final OfflineDownloadService offlineDownloadService;

    public OfflineDownloadApi(OfflineDownloadService offlineDownloadService) {
        this.offlineDownloadService = offlineDownloadService;
    }

    /**
     * Yêu cầu cấp phép tải nhạc ngoại tuyến (Trả về Download Token, Audio URL, Encryption Key, Expiration)
     */
    @PostMapping("/request-download")
    public ResponseEntity<Map<String, Object>> requestDownload(
            Authentication authentication,
            @RequestBody OfflineDownloadRequest request,
            HttpServletRequest httpServletRequest
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        String clientIp = extractClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");

        Map<String, Object> response = offlineDownloadService.requestDownload(
                authentication.getName(), request, clientIp, userAgent
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Xác nhận đã tải và mã hóa thành công trên thiết bị
     */
    @PostMapping("/confirm-download")
    public ResponseEntity<Map<String, Object>> confirmDownload(
            Authentication authentication,
            @RequestBody OfflineConfirmRequest request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        Map<String, Object> response = offlineDownloadService.confirmDownload(
                authentication.getName(), request
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Xác minh danh sách bài hát tải ngoại tuyến khi thiết bị có mạng (License Heartbeat)
     */
    @PostMapping("/verify-license")
    public ResponseEntity<Map<String, Object>> verifyLicense(
            Authentication authentication,
            @RequestBody OfflineVerifyRequest request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        Map<String, Object> response = offlineDownloadService.verifyLicenses(
                authentication.getName(), request
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Xóa bài hát khỏi danh sách ngoại tuyến
     */
    @DeleteMapping("/remove")
    public ResponseEntity<Map<String, Object>> removeDownload(
            Authentication authentication,
            @RequestParam String deviceUuid,
            @RequestParam String trackId
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        Map<String, Object> response = offlineDownloadService.removeDownload(
                authentication.getName(), deviceUuid, trackId
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Lấy danh sách bài hát đã tải trên thiết bị kèm thông tin hạn mức (Quota)
     */
    @GetMapping("/my-downloads")
    public ResponseEntity<Map<String, Object>> getMyDownloads(
            Authentication authentication,
            @RequestParam String deviceUuid
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        Map<String, Object> response = offlineDownloadService.getMyDownloads(
                authentication.getName(), deviceUuid
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Gia hạn toàn bộ bài hát offline khi tài khoản gia hạn VIP
     */
    @PostMapping("/renew-all")
    public ResponseEntity<Map<String, Object>> renewAll(
            Authentication authentication,
            @RequestParam String deviceUuid
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }

        Map<String, Object> response = offlineDownloadService.renewAll(
                authentication.getName(), deviceUuid
        );
        return ResponseEntity.ok(response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
