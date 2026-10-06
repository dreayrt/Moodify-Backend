package com.laphuth.moodify.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UserDeviceService {

    private final JdbcTemplate jdbcTemplate;
    private final SubscriptionService subscriptionService;

    public UserDeviceService(JdbcTemplate jdbcTemplate, SubscriptionService subscriptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Chuẩn hóa platform để khớp chặt chẽ với ENUM('ANDROID','IOS','OTHER') trong DB.
     */
    public String normalizePlatform(String rawPlatform) {
        if (rawPlatform == null || rawPlatform.isBlank()) {
            return "OTHER";
        }
        String upper = rawPlatform.trim().toUpperCase();
        if ("ANDROID".equals(upper) || "IOS".equals(upper)) {
            return upper;
        }
        return "OTHER";
    }

    /**
     * Tự động đăng ký hoặc cập nhật thiết bị khi người dùng đăng nhập.
     * Cưỡng chế giới hạn số lượng thiết bị đồng thời (maxDevices) theo gói dịch vụ.
     */
    @Transactional
    public void registerDeviceOnLogin(Long userId, String username, String deviceUuid, String deviceName, String platform) {
        if (deviceUuid == null || deviceUuid.isBlank()) {
            return;
        }

        String cleanUuid = deviceUuid.trim();
        String cleanPlatform = normalizePlatform(platform);
        String cleanName = (deviceName != null && !deviceName.isBlank()) ? deviceName.trim() : "Thiết bị Moodify";

        // Lấy hạn mức thiết bị tối đa từ gói cước hiện tại của user
        int maxDevices = 1;
        try {
            Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(username);
            if (subInfo != null && subInfo.get("entitlements") instanceof Map<?, ?> ent) {
                Object maxObj = ent.get("maxDevices");
                if (maxObj instanceof Number num) {
                    maxDevices = Math.max(1, num.intValue());
                }
            }
        } catch (Exception ignored) {
            maxDevices = 1;
        }

        // Kiểm tra xem thiết bị này đã từng liên kết với user chưa
        String checkSql = "SELECT id, status FROM user_devices WHERE user_id = ? AND device_uuid = ? LIMIT 1";
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(checkSql, userId, cleanUuid);

        if (!existing.isEmpty()) {
            // Đã tồn tại: cập nhật tên, platform, đưa về ACTIVE
            Long existingId = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update(
                "UPDATE user_devices SET device_name = ?, platform = ?, status = 'ACTIVE', created_at = NOW() WHERE id = ?",
                cleanName, cleanPlatform, existingId
            );
        } else {
            // Thiết bị mới: thêm vào database
            jdbcTemplate.update(
                "INSERT INTO user_devices (user_id, device_uuid, platform, device_name, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', NOW())",
                userId, cleanUuid, cleanPlatform, cleanName
            );
        }

        // Kiểm tra và thực thi hạn mức maxDevices:
        // Nếu số thiết bị ACTIVE hiện tại vượt quá maxDevices, tự động thu hồi (REVOKED) các thiết bị cũ nhất
        enforceMaxDevicesLimit(userId, maxDevices, cleanUuid);
    }

    private void enforceMaxDevicesLimit(Long userId, int maxDevices, String currentDeviceUuid) {
        try {
            String sql = "SELECT id, device_uuid FROM user_devices WHERE user_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC, id DESC";
            List<Map<String, Object>> activeDevices = jdbcTemplate.queryForList(sql, userId);

            if (activeDevices.size() > maxDevices) {
                for (int i = maxDevices; i < activeDevices.size(); i++) {
                    Long devId = ((Number) activeDevices.get(i).get("id")).longValue();
                    String devUuid = (String) activeDevices.get(i).get("device_uuid");
                    // Không thu hồi chính thiết bị vừa đăng nhập
                    if (!cleanEquals(devUuid, currentDeviceUuid)) {
                        jdbcTemplate.update("UPDATE user_devices SET status = 'REVOKED' WHERE id = ?", devId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UserDeviceService] Could not enforce maxDevices limit: " + e.getMessage());
        }
    }

    private boolean cleanEquals(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Lấy danh sách thiết bị của người dùng cá nhân (kèm thông tin hạn mức gói).
     */
    public Map<String, Object> getUserDevicesSummary(Long userId, String username) {
        int maxDevices = 1;
        try {
            Map<String, Object> subInfo = subscriptionService.getCurrentUserSubscription(username);
            if (subInfo != null && subInfo.get("entitlements") instanceof Map<?, ?> ent) {
                Object maxObj = ent.get("maxDevices");
                if (maxObj instanceof Number num) {
                    maxDevices = Math.max(1, num.intValue());
                }
            }
        } catch (Exception ignored) {
            maxDevices = 1;
        }

        String sql = "SELECT id, user_id, device_uuid, platform, device_name, status, created_at FROM user_devices WHERE user_id = ? ORDER BY status ASC, created_at DESC";
        List<Map<String, Object>> devices = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> d = new HashMap<>();
            d.put("id", rs.getLong("id"));
            d.put("userId", rs.getLong("user_id"));
            d.put("deviceUuid", rs.getString("device_uuid"));
            d.put("platform", rs.getString("platform"));
            d.put("deviceName", rs.getString("device_name"));
            d.put("status", rs.getString("status"));
            d.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            return d;
        }, userId);

        long activeCount = devices.stream().filter(d -> "ACTIVE".equals(d.get("status"))).count();

        Map<String, Object> res = new HashMap<>();
        res.put("devices", devices);
        res.put("maxDevices", maxDevices);
        res.put("activeCount", activeCount);
        return res;
    }

    /**
     * Thu hồi quyền của một thiết bị cụ thể thuộc sở hữu của user.
     */
    public boolean revokeUserDevice(Long userId, Long deviceId) {
        int updated = jdbcTemplate.update(
            "UPDATE user_devices SET status = 'REVOKED' WHERE id = ? AND user_id = ?",
            deviceId, userId
        );
        return updated > 0;
    }

    /**
     * Đăng xuất / thu hồi quyền khỏi tất cả các thiết bị khác, chỉ giữ lại thiết bị hiện tại.
     */
    public int revokeOtherDevices(Long userId, String currentDeviceUuid) {
        if (currentDeviceUuid == null || currentDeviceUuid.isBlank()) {
            return jdbcTemplate.update(
                "UPDATE user_devices SET status = 'REVOKED' WHERE user_id = ? AND status = 'ACTIVE'",
                userId
            );
        }
        return jdbcTemplate.update(
            "UPDATE user_devices SET status = 'REVOKED' WHERE user_id = ? AND device_uuid != ? AND status = 'ACTIVE'",
            userId, currentDeviceUuid.trim()
        );
    }
}
