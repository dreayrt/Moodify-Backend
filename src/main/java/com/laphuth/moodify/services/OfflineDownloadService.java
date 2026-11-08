package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.dto.offline.OfflineConfirmRequest;
import com.laphuth.moodify.dto.offline.OfflineDownloadRequest;
import com.laphuth.moodify.dto.offline.OfflineVerifyRequest;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class OfflineDownloadService {

    private static final Logger log = LoggerFactory.getLogger(OfflineDownloadService.class);

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final TrackService trackService;
    private final SubscriptionService subscriptionService;
    private final UserDeviceService userDeviceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${security.jwt.secret:${jwt.secret:moodify-super-secret-key-for-offline-encryption-2026}}")
    private String serverSecret;

    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public OfflineDownloadService(
            JdbcTemplate jdbcTemplate,
            UserRepository userRepository,
            TrackService trackService,
            SubscriptionService subscriptionService,
            UserDeviceService userDeviceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.trackService = trackService;
        this.subscriptionService = subscriptionService;
        this.userDeviceService = userDeviceService;
    }

    @PostConstruct
    public void initOfflineTables() {
        if (!maintenanceEnabled) return;
        try {
            // Đảm bảo bảng offline_downloads (vốn có sẵn trong schema) được kiểm tra an toàn
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS offline_downloads (" +
                "    id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "    device_id BIGINT NOT NULL," +
                "    track_id VARCHAR(64) NOT NULL," +
                "    status ENUM('QUEUED','DOWNLOADING','DOWNLOADED','FAILED','EXPIRED','REMOVED') NOT NULL DEFAULT 'QUEUED'," +
                "    local_path VARCHAR(1000) NULL," +
                "    downloaded_at DATETIME NULL," +
                "    expires_at DATETIME NULL," +
                "    last_verified_at DATETIME NULL," +
                "    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "    CONSTRAINT fk_offline_device FOREIGN KEY (device_id) REFERENCES user_devices(id) ON DELETE CASCADE," +
                "    CONSTRAINT uk_offline_device_track UNIQUE (device_id, track_id)," +
                "    INDEX idx_offline_device_status (device_id, status)," +
                "    INDEX idx_offline_track (track_id)," +
                "    INDEX idx_offline_expiry (expires_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
        } catch (Exception e) {
            log.warn("[OfflineDownloadService] Check offline_downloads schema: {}", e.getMessage());
        }
    }

    /**
     * Yêu cầu cấp phép tải nhạc ngoại tuyến (Request Stateless HMAC Download Ticket + Encryption Key + Audio URL)
     */
    @Transactional
    public Map<String, Object> requestDownload(String principal, OfflineDownloadRequest req, String clientIp, String userAgent) {
        User user = findUserByPrincipal(principal);

        if (req.getTrackId() == null || req.getTrackId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu mã bài hát (trackId).");
        }
        if (req.getDeviceUuid() == null || req.getDeviceUuid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu định danh thiết bị (deviceUuid).");
        }

        // 1. Kiểm tra quyền của gói dịch vụ
        Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(user.getUsername());
        boolean isPremium = Boolean.TRUE.equals(subInfo.get("isPremium"));
        Map<?, ?> entitlements = (Map<?, ?>) subInfo.get("entitlements");

        boolean offlineAllowed = entitlements != null && Boolean.TRUE.equals(entitlements.get("offlineAllowed"));
        int maxTracks = entitlements != null && entitlements.get("offlineMaxTracks") instanceof Number num
                ? num.intValue() : 0;

        if (!isPremium || !offlineAllowed || maxTracks <= 0) {
            logAudit(user.getId(), null, "QUOTA_EXCEEDED_BLOCKED", req.getTrackId(),
                    Map.of("reason", "PREMIUM_REQUIRED", "ip", clientIp != null ? clientIp : ""));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tính năng nghe offline chỉ dành cho thành viên Moodify VIP. Vui lòng nâng cấp gói!");
        }

        // 2. Kiểm tra thiết bị và trạng thái ACTIVE
        Long deviceId = getOrCreateDevice(user.getId(), user.getUsername(), req.getDeviceUuid(), req.getDeviceName(), req.getPlatform());
        if (!userDeviceService.isDeviceActive(user.getId(), req.getDeviceUuid())) {
            logAudit(user.getId(), deviceId, "SUSPICIOUS_ACTIVITY", req.getTrackId(),
                    Map.of("reason", "DEVICE_REVOKED", "deviceUuid", req.getDeviceUuid()));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Thiết bị này đã bị thu hồi quyền do vượt quá số lượng thiết bị cho phép.");
        }

        // 3. Kiểm tra hạn mức bài hát tải về trên thiết bị này
        Integer currentDownloadedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offline_downloads WHERE device_id = ? AND status = 'DOWNLOADED'",
                Integer.class,
                deviceId
        );
        if (currentDownloadedCount == null) currentDownloadedCount = 0;

        if (currentDownloadedCount >= maxTracks) {
            logAudit(user.getId(), deviceId, "QUOTA_EXCEEDED_BLOCKED", req.getTrackId(),
                    Map.of("currentCount", currentDownloadedCount, "maxTracks", maxTracks));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã tải tối đa " + maxTracks + " bài hát cho phép của gói cước. Vui lòng xóa bớt bài cũ!");
        }

        // 4. Lấy thông tin bài hát từ MongoDB
        Track track = trackService.getTrackEntity(req.getTrackId());
        if (!trackService.isTrackPubliclyAvailable(track)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bài hát này không còn khả dụng trên hệ thống.");
        }

        String rawAudioUrl = AudioUrlResolver.resolve(track.getLocalPath());
        if (rawAudioUrl == null || rawAudioUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy file nguồn âm thanh của bài hát.");
        }

        // 5. Xác định thời hạn hết hạn bản quyền offline (theo hạn gói VIP)
        String expiresAtStr = (String) subInfo.get("expiresAt");
        LocalDateTime expiresAt = expiresAtStr != null && !expiresAtStr.isBlank()
                ? LocalDateTime.parse(expiresAtStr.replace(" ", "T"))
                : LocalDateTime.now().plusDays(30);

        // 6. Sinh khóa mã hóa AES-256 (256-bit Hex) riêng biệt cho bộ (user, device, track)
        String encryptionKey = generateTrackKey(user.getId(), deviceId, track.getId());

        // 7. Sinh Stateless HMAC-SHA256 Signed Ticket (15 phút TTL) - Không cần lưu bảng DB
        LocalDateTime tokenExpiresAt = LocalDateTime.now().plusMinutes(15);
        String downloadToken = createStatelessDownloadTicket(user.getId(), deviceId, track.getId(), tokenExpiresAt);

        // 8. Cập nhật bản ghi trong bảng có sẵn offline_downloads thành QUEUED
        jdbcTemplate.update(
            "INSERT INTO offline_downloads (device_id, track_id, status, expires_at, created_at, updated_at) " +
            "VALUES (?, ?, 'QUEUED', ?, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE status = 'QUEUED', expires_at = VALUES(expires_at), updated_at = NOW()",
            deviceId, track.getId(), expiresAt
        );

        logAudit(user.getId(), deviceId, "DOWNLOAD_REQUESTED", track.getId(),
                Map.of("ip", clientIp != null ? clientIp : ""));

        // 9. Trả dữ liệu về Mobile Client
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("downloadToken", downloadToken);
        result.put("downloadUrl", rawAudioUrl);
        result.put("encryptionKey", encryptionKey);
        result.put("expiresAt", expiresAt.format(DATE_TIME_FORMATTER));
        result.put("tokenExpiresAt", tokenExpiresAt.format(DATE_TIME_FORMATTER));
        result.put("track", TrackResponse.from(track));
        result.put("quota", Map.of(
            "used", currentDownloadedCount,
            "max", maxTracks
        ));

        return result;
    }

    /**
     * Xác nhận tải xuống và mã hóa thành công từ thiết bị Mobile
     * (Sử dụng Stateless HMAC Token Verification - 0 bảng DB phụ trợ)
     */
    @Transactional
    public Map<String, Object> confirmDownload(String principal, OfflineConfirmRequest req) {
        User user = findUserByPrincipal(principal);

        if (req.getDownloadToken() == null || req.getDownloadToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu downloadToken.");
        }

        // 1. Xác thực chữ ký số HMAC-SHA256 & thời hạn của Stateless Ticket
        ParsedDownloadTicket ticket = verifyStatelessDownloadTicket(req.getDownloadToken().trim());

        if (!ticket.userId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ticket không thuộc tài khoản của bạn.");
        }

        Long deviceId = ticket.deviceId();
        String trackId = ticket.trackId();

        // 2. Kiểm tra bản ghi trong bảng có sẵn offline_downloads
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, status FROM offline_downloads WHERE device_id = ? AND track_id = ? LIMIT 1 FOR UPDATE",
            deviceId, trackId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tải tương ứng trên thiết bị.");
        }

        // 3. Cập nhật trạng thái sang DOWNLOADED
        jdbcTemplate.update(
            "UPDATE offline_downloads SET status = 'DOWNLOADED', downloaded_at = NOW(), last_verified_at = NOW(), local_path = ?, updated_at = NOW() " +
            "WHERE device_id = ? AND track_id = ?",
            req.getLocalPath(), deviceId, trackId
        );

        logAudit(user.getId(), deviceId, "DOWNLOAD_COMPLETED", trackId,
                Map.of("fileHash", req.getFileHash() != null ? req.getFileHash() : "", "fileSize", req.getFileSize() != null ? req.getFileSize() : 0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Đã lưu bản quyền nghe ngoại tuyến thành công!");
        result.put("trackId", trackId);
        return result;
    }

    /**
     * Xác minh danh sách bản quyền bài hát offline khi thiết bị có mạng (License Heartbeat)
     */
    public Map<String, Object> verifyLicenses(String principal, OfflineVerifyRequest req) {
        User user = findUserByPrincipal(principal);

        if (req.getDeviceUuid() == null || req.getDeviceUuid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu deviceUuid.");
        }

        // Lấy thông tin thiết bị
        List<Map<String, Object>> devices = jdbcTemplate.queryForList(
            "SELECT id, status FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            user.getId(), req.getDeviceUuid().trim()
        );

        if (devices.isEmpty() || !"ACTIVE".equalsIgnoreCase((String) devices.get(0).get("status"))) {
            return Map.of(
                "success", false,
                "deviceActive", false,
                "message", "Thiết bị không còn quyền nghe nhạc offline.",
                "validTrackIds", Collections.emptyList(),
                "revokedTrackIds", req.getTrackIds() != null ? req.getTrackIds() : Collections.emptyList()
            );
        }

        Long deviceId = ((Number) devices.get(0).get("id")).longValue();

        // Kiểm tra subscription hiện tại
        Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(user.getUsername());
        boolean isPremium = Boolean.TRUE.equals(subInfo.get("isPremium"));
        String subExpiresAtStr = (String) subInfo.get("expiresAt");
        LocalDateTime now = LocalDateTime.now();

        List<String> validTracks = new ArrayList<>();
        List<String> expiredTracks = new ArrayList<>();
        List<String> revokedTracks = new ArrayList<>();

        if (req.getTrackIds() != null && !req.getTrackIds().isEmpty()) {
            for (String trackId : req.getTrackIds()) {
                if (trackId == null || trackId.isBlank()) continue;

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, expires_at FROM offline_downloads WHERE device_id = ? AND track_id = ? LIMIT 1",
                    deviceId, trackId.trim()
                );

                if (rows.isEmpty()) {
                    revokedTracks.add(trackId);
                    continue;
                }

                String status = (String) rows.get(0).get("status");
                LocalDateTime expiresAt = toLocalDateTime(rows.get(0).get("expires_at"));

                if (!isPremium || "EXPIRED".equalsIgnoreCase(status) || (expiresAt != null && expiresAt.isBefore(now))) {
                    expiredTracks.add(trackId);
                } else if ("REMOVED".equalsIgnoreCase(status)) {
                    revokedTracks.add(trackId);
                } else {
                    validTracks.add(trackId);
                }
            }
        }

        // Cập nhật last_verified_at cho thiết bị này
        jdbcTemplate.update(
            "UPDATE offline_downloads SET last_verified_at = NOW() WHERE device_id = ? AND status = 'DOWNLOADED'",
            deviceId
        );

        logAudit(user.getId(), deviceId, "LICENSE_VERIFIED", null,
                Map.of("validCount", validTracks.size(), "expiredCount", expiredTracks.size(), "revokedCount", revokedTracks.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deviceActive", true);
        result.put("isPremium", isPremium);
        result.put("subscriptionExpiresAt", subExpiresAtStr);
        result.put("validTrackIds", validTracks);
        result.put("expiredTrackIds", expiredTracks);
        result.put("revokedTrackIds", revokedTracks);
        result.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));

        return result;
    }

    /**
     * Xóa bài hát khỏi danh sách ngoại tuyến trên thiết bị
     */
    @Transactional
    public Map<String, Object> removeDownload(String principal, String deviceUuid, String trackId) {
        User user = findUserByPrincipal(principal);

        if (deviceUuid == null || deviceUuid.isBlank() || trackId == null || trackId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu deviceUuid hoặc trackId.");
        }

        List<Long> deviceIds = jdbcTemplate.query(
            "SELECT id FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            user.getId(), deviceUuid.trim()
        );

        if (deviceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thiết bị.");
        }

        Long deviceId = deviceIds.get(0);
        jdbcTemplate.update(
            "UPDATE offline_downloads SET status = 'REMOVED', updated_at = NOW() WHERE device_id = ? AND track_id = ?",
            deviceId, trackId.trim()
        );

        logAudit(user.getId(), deviceId, "FILE_DELETED", trackId.trim(), null);

        return Map.of("success", true, "message", "Đã xóa bài hát khỏi danh sách offline.");
    }

    /**
     * Lấy danh sách toàn bộ bài hát đã tải trên thiết bị này kèm hạn mức quota
     */
    public Map<String, Object> getMyDownloads(String principal, String deviceUuid) {
        User user = findUserByPrincipal(principal);

        if (deviceUuid == null || deviceUuid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu deviceUuid.");
        }

        Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(user.getUsername());
        Map<?, ?> entitlements = (Map<?, ?>) subInfo.get("entitlements");
        int maxTracks = entitlements != null && entitlements.get("offlineMaxTracks") instanceof Number num
                ? num.intValue() : 0;

        List<Long> deviceIds = jdbcTemplate.query(
            "SELECT id FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            user.getId(), deviceUuid.trim()
        );

        if (deviceIds.isEmpty()) {
            return Map.of(
                "tracks", Collections.emptyList(),
                "quota", Map.of("used", 0, "max", maxTracks),
                "isPremium", subInfo.get("isPremium")
            );
        }

        Long deviceId = deviceIds.get(0);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT track_id, status, local_path, downloaded_at, expires_at " +
            "FROM offline_downloads WHERE device_id = ? AND status = 'DOWNLOADED' " +
            "ORDER BY downloaded_at DESC",
            deviceId
        );

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String trackId = (String) row.get("track_id");
            try {
                Track track = trackService.getTrackEntity(trackId);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("trackId", trackId);
                item.put("status", row.get("status"));
                item.put("localPath", row.get("local_path"));
                item.put("downloadedAt", row.get("downloaded_at") != null ? row.get("downloaded_at").toString() : null);
                item.put("expiresAt", row.get("expires_at") != null ? row.get("expires_at").toString() : null);
                item.put("track", TrackResponse.from(track));
                items.add(item);
            } catch (Exception ignored) {}
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tracks", items);
        res.put("quota", Map.of("used", items.size(), "max", maxTracks));
        res.put("isPremium", subInfo.get("isPremium"));
        res.put("subscriptionExpiresAt", subInfo.get("expiresAt"));
        return res;
    }

    /**
     * Gia hạn toàn bộ bài hát đã tải khi người dùng nạp VIP thành công
     */
    @Transactional
    public Map<String, Object> renewAll(String principal, String deviceUuid) {
        User user = findUserByPrincipal(principal);
        Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(user.getUsername());
        boolean isPremium = Boolean.TRUE.equals(subInfo.get("isPremium"));

        if (!isPremium) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn cần có gói VIP đang hoạt động để gia hạn nhạc offline.");
        }

        String expiresAtStr = (String) subInfo.get("expiresAt");
        LocalDateTime expiresAt = expiresAtStr != null && !expiresAtStr.isBlank()
                ? LocalDateTime.parse(expiresAtStr.replace(" ", "T"))
                : LocalDateTime.now().plusDays(30);

        List<Long> deviceIds = jdbcTemplate.query(
            "SELECT id FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            user.getId(), deviceUuid != null ? deviceUuid.trim() : ""
        );

        int updatedCount = 0;
        if (!deviceIds.isEmpty()) {
            Long deviceId = deviceIds.get(0);
            updatedCount = jdbcTemplate.update(
                "UPDATE offline_downloads SET status = 'DOWNLOADED', expires_at = ?, updated_at = NOW() " +
                "WHERE device_id = ? AND status IN ('DOWNLOADED', 'EXPIRED')",
                expiresAt, deviceId
            );
        }

        return Map.of(
            "success", true,
            "renewedCount", updatedCount,
            "newExpiresAt", expiresAt.format(DATE_TIME_FORMATTER),
            "message", "Đã gia hạn thành công " + updatedCount + " bài hát offline!"
        );
    }

    // ======================================================================
    // STATELESS CRYPTOGRAPHIC TOKEN & SECURITY HELPERS
    // ======================================================================

    public record ParsedDownloadTicket(Long userId, Long deviceId, String trackId, long expEpochMilli) {}

    /**
     * Tạo Stateless HMAC-SHA256 Signed Ticket không cần lưu bảng DB.
     * Cấu trúc: DLT.<base64Payload>.<hmacSignature>
     */
    private String createStatelessDownloadTicket(Long userId, Long deviceId, String trackId, LocalDateTime tokenExpiresAt) {
        long expEpochMilli = tokenExpiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = userId + ":" + deviceId + ":" + trackId + ":" + expEpochMilli + ":" + nonce;
        String signature = computeHmacSha256Hex(payload, serverSecret);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "DLT." + encodedPayload + "." + signature;
    }

    /**
     * Giải mã và kiểm tra tính toàn vẹn của Stateless Ticket
     */
    private ParsedDownloadTicket verifyStatelessDownloadTicket(String token) {
        if (token == null || !token.startsWith("DLT.")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token cấp phép tải xuống không hợp lệ.");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token cấp phép tải xuống bị phân mảnh hoặc không hợp lệ.");
        }
        String encodedPayload = parts[1];
        String providedSignature = parts[2];
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể giải mã nội dung token.");
        }

        String expectedSignature = computeHmacSha256Hex(payload, serverSecret);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), providedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chữ ký số của token không hợp lệ (Token có dấu hiệu bị can thiệp).");
        }

        String[] elements = payload.split(":");
        if (elements.length != 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cấu trúc payload của token không hợp lệ.");
        }

        try {
            Long userId = Long.parseLong(elements[0]);
            Long deviceId = Long.parseLong(elements[1]);
            String trackId = elements[2];
            long expEpochMilli = Long.parseLong(elements[3]);

            if (System.currentTimeMillis() > expEpochMilli) {
                throw new ResponseStatusException(HttpStatus.GONE, "Token cấp phép tải xuống đã hết hạn (quá 15 phút). Vui lòng thử lại!");
            }

            return new ParsedDownloadTicket(userId, deviceId, trackId, expEpochMilli);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung số trong token không hợp lệ.");
        }
    }

    private User findUserByPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        return userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng."));
    }

    private Long getOrCreateDevice(Long userId, String username, String deviceUuid, String deviceName, String platform) {
        String cleanUuid = deviceUuid.trim();
        List<Long> ids = jdbcTemplate.query(
            "SELECT id FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            userId, cleanUuid
        );

        if (!ids.isEmpty()) {
            return ids.get(0);
        }

        userDeviceService.syncCurrentDevice(userId, username, cleanUuid, deviceName, platform);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1",
            Long.class,
            userId, cleanUuid
        );
    }

    /**
     * Sinh khóa AES-256 (32 bytes = 64 hex characters) riêng cho bộ (userId + deviceId + trackId)
     */
    private String generateTrackKey(Long userId, Long deviceId, String trackId) {
        String payload = userId + ":" + deviceId + ":" + trackId;
        return computeHmacSha256Hex(payload, serverSecret);
    }

    private String computeHmacSha256Hex(String data, String key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] hash = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi sinh chữ ký HMAC: " + e.getMessage(), e);
        }
    }

    /**
     * Ghi log kiểm toán hành vi tải ngoại tuyến chuẩn công nghiệp qua SLF4J (0 bảng database rác)
     */
    private void logAudit(Long userId, Long deviceId, String action, String trackId, Map<String, Object> meta) {
        try {
            String json = meta != null ? objectMapper.writeValueAsString(meta) : "{}";
            log.info("[OFFLINE_AUDIT] action={} userId={} deviceId={} trackId={} meta={}",
                    action, userId, deviceId, trackId, json);
        } catch (Exception ignored) {
            log.info("[OFFLINE_AUDIT] action={} userId={} deviceId={} trackId={}", action, userId, deviceId, trackId);
        }
    }

    private LocalDateTime toLocalDateTime(Object dateObj) {
        if (dateObj == null) return null;
        if (dateObj instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (dateObj instanceof LocalDateTime ldt) {
            return ldt;
        }
        try {
            return LocalDateTime.parse(dateObj.toString().replace(" ", "T"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
