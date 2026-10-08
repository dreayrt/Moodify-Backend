package com.laphuth.moodify.api;

import com.laphuth.moodify.services.AdService;
import com.laphuth.moodify.services.AdminService;
import com.laphuth.moodify.services.NotificationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminApi {

    private final AdminService adminService;
    private final NotificationService notificationService;
    private final AdService adService;
    private final JdbcTemplate jdbcTemplate;

    public AdminApi(AdminService adminService,
                    NotificationService notificationService,
                    AdService adService,
                    JdbcTemplate jdbcTemplate) {
        this.adminService = adminService;
        this.notificationService = notificationService;
        this.adService = adService;
        this.jdbcTemplate = jdbcTemplate;
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
            @RequestBody Map<String, String> body,
            Authentication authentication
    ) {
        String status = body.get("status");
        String reason = body.get("reason");
        adminService.updateUserStatus(id, status, reason, authentication.getName());
        return ResponseEntity.ok(Map.of("success", true, "message", "Trạng thái người dùng đã được cập nhật thành công"));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication
    ) {
        String role = body.get("role");
        String staffCode = body.get("staffCode");
        String artistSpotifyId = body.get("artistSpotifyId");
        adminService.updateUserRole(id, role, staffCode, artistSpotifyId, authentication.getName());
        return ResponseEntity.ok(Map.of("success", true, "message", "Vai trò người dùng đã được cập nhật thành công"));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetUserPassword(
            @PathVariable Long id,
            Authentication authentication
    ) {
        String tempPassword = adminService.resetUserPassword(id, authentication.getName());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Mật khẩu đã được đặt lại. Mật khẩu tạm: " + tempPassword,
                "tempPassword", tempPassword
        ));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        adminService.createUser(body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Người dùng mới đã được tạo thành công"));
    }

    @GetMapping("/users/{id}/subscriptions")
    public ResponseEntity<List<Map<String, Object>>> getUserSubscriptions(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getUserSubscriptions(id));
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

    @PatchMapping("/tracks/{id}/genre")
    public ResponseEntity<Map<String, Object>> updateTrackGenre(
            @PathVariable String id,
            @RequestBody Map<String, String> body
    ) {
        String genre = body.get("genre");
        adminService.updateTrackGenre(id, genre);
        return ResponseEntity.ok(Map.of("success", true, "message", "Thể loại bài hát đã được cập nhật thành công"));
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<Map<String, Object>> deleteTrack(@PathVariable String id) {
        adminService.deleteTrack(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Bài hát đã được xóa hoàn toàn khỏi kho nhạc"));
    }

    // ==========================================
    // 4. CONTENT MODERATION
    // ==========================================
    @GetMapping("/moderation")
    public ResponseEntity<List<Map<String, Object>>> getModerationQueue() {
        return ResponseEntity.ok(adminService.getModerationQueue());
    }

    @GetMapping("/moderation/actions")
    public ResponseEntity<List<Map<String, Object>>> getModerationActions() {
        return ResponseEntity.ok(adminService.getModerationActions());
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

    @GetMapping("/subscription-tiers")
    public ResponseEntity<List<Map<String, Object>>> getSubscriptionTiers() {
        return ResponseEntity.ok(adminService.getSubscriptionTiers());
    }

    @PutMapping("/subscription-tiers/{id}")
    public ResponseEntity<Map<String, Object>> updateSubscriptionTier(
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        adminService.updateSubscriptionTier(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Cấu hình tầng quyền lợi đã được cập nhật thành công"));
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Map<String, Object>>> getTransactions() {
        return ResponseEntity.ok(adminService.getTransactions());
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<List<Map<String, Object>>> getSubscriptions() {
        return ResponseEntity.ok(adminService.getSubscriptions());
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSubscription(@PathVariable Long id) {
        adminService.cancelSubscription(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Gói dịch vụ người dùng đã được hủy thành công"));
    }

    // ==========================================
    // 6. LICENSING
    // ==========================================
    @GetMapping("/licensing")
    public ResponseEntity<Map<String, Object>> getLicensing() {
        return ResponseEntity.ok(adminService.getLicensingData());
    }

    @PostMapping("/distributors")
    public ResponseEntity<Map<String, Object>> createDistributor(@RequestBody Map<String, Object> body) {
        adminService.createDistributor(body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã thêm nhà phân phối mới"));
    }

    @PutMapping("/distributors/{id}")
    public ResponseEntity<Map<String, Object>> updateDistributor(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        adminService.updateDistributor(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật nhà phân phối"));
    }

    @PatchMapping("/distributors/{id}/status")
    public ResponseEntity<Map<String, Object>> toggleDistributorStatus(@PathVariable Long id) {
        adminService.toggleDistributorStatus(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật trạng thái nhà phân phối"));
    }

    @PostMapping("/contracts")
    public ResponseEntity<Map<String, Object>> createContract(@RequestBody Map<String, Object> body) {
        adminService.createContract(body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã lập hợp đồng phân phối mới"));
    }

    @PutMapping("/contracts/{id}")
    public ResponseEntity<Map<String, Object>> updateContract(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        adminService.updateContract(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật thông tin hợp đồng"));
    }

    @PatchMapping("/contracts/{id}/status")
    public ResponseEntity<Map<String, Object>> updateContractStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        adminService.updateContractStatus(id, status);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật trạng thái hợp đồng"));
    }


    // ==========================================
    // 8. FAVORITES MANAGEMENT
    // ==========================================
    @GetMapping("/favorites")
    public ResponseEntity<Map<String, Object>> getFavorites(
            @RequestParam(required = false, defaultValue = "SONG") String type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(adminService.getFavorites(type, query, page, size));
    }

    @GetMapping("/favorites/leaderboard")
    public ResponseEntity<List<Map<String, Object>>> getFavoriteLeaderboard() {
        return ResponseEntity.ok(adminService.getFavoriteLeaderboard());
    }

    @DeleteMapping("/favorites/{id}")
    public ResponseEntity<Map<String, Object>> deleteFavorite(@PathVariable Long id) {
        adminService.deleteFavorite(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Lượt yêu thích đã được xóa khỏi hệ thống"));
    }

    // ==========================================
    // 7. NOTIFICATIONS (Thông báo tới người dùng)
    // ==========================================
    @PostMapping("/notifications/broadcast")
    public ResponseEntity<Map<String, Object>> broadcastNotification(
            @RequestBody Map<String, Object> body,
            Authentication authentication
    ) {
        Long createdBy = null;
        try {
            createdBy = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = ? OR username = ? LIMIT 1",
                    Long.class, authentication.getName(), authentication.getName());
        } catch (Exception ignored) {}
        Map<String, Object> result = notificationService.broadcast(
                (String) body.get("title"),
                (String) body.get("message"),
                (String) body.getOrDefault("type", "SYSTEM"),
                (String) body.getOrDefault("targetRole", "ALL"),
                (String) body.get("linkUrl"),
                createdBy);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<Map<String, Object>>> getNotificationHistory() {
        return ResponseEntity.ok(notificationService.getBroadcastHistory());
    }

    // ==========================================
    // 7b. AUDIT LOGS (Nhật ký hành chính - MongoDB)
    // ==========================================
    @GetMapping("/audit-logs")
    public ResponseEntity<List<Map<String, Object>>> getAuditLogs() {
        return ResponseEntity.ok(adminService.getAuditLogs());
    }

    // ==========================================
    // 9. AD MANAGEMENT (Quản lý quảng cáo & danh mục)
    // ==========================================
    @GetMapping("/ads")
    public ResponseEntity<List<Map<String, Object>>> getAdCampaigns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String categoryId
    ) {
        return ResponseEntity.ok(adService.getCampaigns(status, categoryId));
    }

    @PostMapping("/ads")
    public ResponseEntity<Map<String, Object>> createAdCampaign(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(adService.createCampaign(body));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<Map<String, Object>> updateAdCampaign(
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        adService.updateCampaign(id, body);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật chiến dịch quảng cáo."));
    }

    @PatchMapping("/ads/{id}/status")
    public ResponseEntity<Map<String, Object>> toggleAdCampaignStatus(@PathVariable String id) {
        adService.toggleCampaignStatus(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã đổi trạng thái chiến dịch quảng cáo."));
    }

    @DeleteMapping("/ads/{id}")
    public ResponseEntity<Map<String, Object>> deleteAdCampaign(@PathVariable String id) {
        adService.deleteCampaign(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa chiến dịch quảng cáo."));
    }

    @PostMapping(value = "/ads/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAdAudio(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(adService.uploadAdAudio(file));
    }

    @GetMapping("/ads/categories")
    public ResponseEntity<List<Map<String, Object>>> getAdCategories() {
        return ResponseEntity.ok(adService.getCategories());
    }

    @PostMapping("/ads/categories")
    public ResponseEntity<Map<String, Object>> createAdCategory(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(adService.createCategory(
                (String) body.get("name"), (String) body.get("description")));
    }

    @PutMapping("/ads/categories/{id}")
    public ResponseEntity<Map<String, Object>> updateAdCategory(
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        adService.updateCategory(id,
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("status"));
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật danh mục quảng cáo."));
    }

    @DeleteMapping("/ads/categories/{id}")
    public ResponseEntity<Map<String, Object>> deleteAdCategory(@PathVariable String id) {
        adService.deleteCategory(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa danh mục quảng cáo."));
    }

    // ==========================================
    // LISTENING HISTORY & BEHAVIOR TELEMETRY
    // ==========================================
    @GetMapping("/listening-history")
    public ResponseEntity<Map<String, Object>> getListeningHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long userId
    ) {
        return ResponseEntity.ok(adminService.getListeningHistory(page, size, search, deviceType, source, userId));
    }

    @GetMapping("/listening-history/{id}/events")
    public ResponseEntity<List<Map<String, Object>>> getPlaybackEvents(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getPlaybackEventsForSession(id));
    }

    @GetMapping("/listening-history/summary")
    public ResponseEntity<Map<String, Object>> getListeningSummary() {
        return ResponseEntity.ok(adminService.getListeningSummaryMetrics());
    }

    @GetMapping("/playback-events")
    public ResponseEntity<Map<String, Object>> getAllPlaybackEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(adminService.getAllPlaybackEvents(page, size, eventType, search));
    }

    @GetMapping("/tracks/retention-metrics")
    public ResponseEntity<List<Map<String, Object>>> getTrackRetentionMetrics(
            @RequestParam(defaultValue = "15") int limit
    ) {
        return ResponseEntity.ok(adminService.getTrackPerformanceMetrics(limit));
    }

}
