package com.laphuth.moodify.api;

import com.laphuth.moodify.services.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Hộp thư thông báo của người dùng: xem danh sách, đánh dấu đã đọc.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationApi {

    private final NotificationService notificationService;

    public NotificationApi(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyNotifications(
            Authentication authentication,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return ResponseEntity.ok(notificationService.getMyNotifications(authentication.getName(), limit));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(
            Authentication authentication,
            @PathVariable String id
    ) {
        notificationService.markRead(authentication.getName(), id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead(Authentication authentication) {
        notificationService.markAllRead(authentication.getName());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
