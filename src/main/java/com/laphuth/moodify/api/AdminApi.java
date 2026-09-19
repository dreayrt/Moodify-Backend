package com.laphuth.moodify.api;

import com.laphuth.moodify.services.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminApi {

    private final AdminService adminService;

    public AdminApi(AdminService adminService) {
        this.adminService = adminService;
    }

    // ==========================================
    // 1. OVERVIEW STATS
    // ==========================================
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        return ResponseEntity.ok(adminService.getOverview());
    }

    // ==========================================
    // 2. USERS MANAGEMENT
    // ==========================================
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(adminService.getUsers(role, status, query));
    }

    @PatchMapping("/users/{id}/status")
    public ResponseEntity<Map<String, Object>> updateUserStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        String status = body.get("status");
        String reason = body.get("reason");
        adminService.updateUserStatus(id, status, reason);
        return ResponseEntity.ok(Map.of("success", true, "message", "Trạng thái người dùng đã được cập nhật thành công"));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        String role = body.get("role");
        String staffCode = body.get("staffCode");
        String artistSpotifyId = body.get("artistSpotifyId");
        adminService.updateUserRole(id, role, staffCode, artistSpotifyId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Vai trò người dùng đã được cập nhật thành công"));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetUserPassword(@PathVariable Long id) {
        adminService.resetUserPassword(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Mật khẩu đã được đặt lại về 123456 thành công"));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        adminService.createUser(body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Người dùng mới đã được tạo thành công"));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Tài khoản người dùng đã được xóa / vô hiệu hóa"));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUserProfile(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        adminService.updateUserProfile(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Thông tin người dùng đã được cập nhật"));
    }

    @GetMapping("/users/{id}/devices")
    public ResponseEntity<List<Map<String, Object>>> getUserDevices(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getUserDevices(id));
    }

    @PatchMapping("/devices/{id}/revoke")
    public ResponseEntity<Map<String, Object>> revokeDevice(@PathVariable Long id) {
        adminService.revokeDevice(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Thiết bị đã bị thu hồi quyền truy cập"));
    }

    // ==========================================
    // 3. CATALOG TRACKS (MongoDB)
    // ==========================================
    @GetMapping("/tracks")
    public ResponseEntity<List<Map<String, Object>>> getCatalogTracks(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(adminService.getCatalogTracks(query, status));
    }

    @PatchMapping("/tracks/{id}/takedown")
    public ResponseEntity<Map<String, Object>> takedownTrack(@PathVariable String id) {
        adminService.takedownTrack(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bài hát đã được gỡ khỏi kho nhạc"));
    }

    @PatchMapping("/tracks/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreTrack(@PathVariable String id) {
        adminService.restoreTrack(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bài hát đã được khôi phục phát sóng thành công"));
    }

    // ==========================================
    // 4. CONTENT MODERATION
    // ==========================================
    @GetMapping("/moderation")
    public ResponseEntity<List<Map<String, Object>>> getModerationQueue() {
        return ResponseEntity.ok(adminService.getModerationQueue());
    }

    @PostMapping("/moderation/{id}/decision")
    public ResponseEntity<Map<String, Object>> submitReviewDecision(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication
    ) {
        String action = body.get("action");
        String reason = body.get("reason");
        String moderator = authentication != null ? authentication.getName() : "admin01";
        adminService.submitReviewDecision(id, action, reason, moderator);
        return ResponseEntity.ok(Map.of("success", true, "message", "Quyết định kiểm duyệt đã được ghi nhận"));
    }

    // ==========================================
    // 5. PACKAGES & MONETIZATION
    // ==========================================
    @GetMapping("/packages")
    public ResponseEntity<List<Map<String, Object>>> getPackages() {
        return ResponseEntity.ok(adminService.getPackages());
    }

    @PutMapping("/packages/{id}/price")
    public ResponseEntity<Map<String, Object>> updatePackagePrice(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        Object priceObj = body.get("price");
        Double newPrice = priceObj instanceof Number ? ((Number) priceObj).doubleValue() : Double.parseDouble(priceObj.toString());
        adminService.updatePackagePrice(id, newPrice);
        return ResponseEntity.ok(Map.of("success", true, "message", "Giá gói cước đã được cập nhật"));
    }

    @PostMapping("/packages")
    public ResponseEntity<Map<String, Object>> createPackage(@RequestBody Map<String, Object> body) {
        adminService.createPackage(body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Gói cước mới đã được tạo thành công"));
    }

    @PutMapping("/packages/{id}")
    public ResponseEntity<Map<String, Object>> updatePackageDetails(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        adminService.updatePackageDetails(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Chi tiết gói cước đã được cập nhật thành công"));
    }

    @PatchMapping("/packages/{id}/status")
    public ResponseEntity<Map<String, Object>> togglePackageStatus(@PathVariable Long id) {
        adminService.togglePackageStatus(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Trạng thái gói cước đã được thay đổi"));
    }

    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Map<String, Object>> deletePackage(@PathVariable Long id) {
        adminService.deletePackage(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Gói cước đã được xử lý xóa / vô hiệu hóa thành công"));
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Map<String, Object>>> getTransactions() {
        return ResponseEntity.ok(adminService.getTransactions());
    }

    @PostMapping("/transactions/{id}/refund")
    public ResponseEntity<Map<String, Object>> refundTransaction(@PathVariable Long id) {
        adminService.refundTransaction(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Giao dịch đã được hoàn tiền thành công"));
    }

    // ==========================================
    // 6. LICENSING
    // ==========================================
    @GetMapping("/licensing")
    public ResponseEntity<Map<String, Object>> getLicensing() {
        return ResponseEntity.ok(adminService.getLicensingData());
    }
}
