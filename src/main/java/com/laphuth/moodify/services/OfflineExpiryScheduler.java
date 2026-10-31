package com.laphuth.moodify.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OfflineExpiryScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled;

    public OfflineExpiryScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Quét định kỳ mỗi 1 giờ để chuyển các bản ghi đã quá thời hạn expires_at sang EXPIRED
     */
    @Scheduled(fixedRate = 3600000)
    public void scanAndExpireOfflineDownloads() {
        if (!maintenanceEnabled) return;
        try {
            int expiredCount = jdbcTemplate.update(
                "UPDATE offline_downloads SET status = 'EXPIRED', updated_at = NOW() " +
                "WHERE status = 'DOWNLOADED' AND expires_at IS NOT NULL AND expires_at < NOW()"
            );
            if (expiredCount > 0) {
                System.out.println("[OfflineExpiryScheduler] Đã chuyển " + expiredCount + " bài hát offline quá hạn sang trạng thái EXPIRED.");
            }

            // Hủy các token cấp phép tải xuống một lần đã quá 15 phút chưa được sử dụng
            int expiredTokens = jdbcTemplate.update(
                "UPDATE download_license_tokens SET status = 'EXPIRED' " +
                "WHERE status = 'ISSUED' AND expires_at < NOW()"
            );
            if (expiredTokens > 0) {
                System.out.println("[OfflineExpiryScheduler] Đã hủy " + expiredTokens + " token tải xuống quá hạn.");
            }
        } catch (Exception e) {
            System.err.println("[OfflineExpiryScheduler] Lỗi khi quét hạn tải offline: " + e.getMessage());
        }
    }

    /**
     * Quét định kỳ lúc 03:00 sáng hàng ngày để dọn dẹp các bản ghi EXPIRED quá 7 ngày
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldExpiredDownloads() {
        if (!maintenanceEnabled) return;
        try {
            LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
            int purgedCount = jdbcTemplate.update(
                "UPDATE offline_downloads SET status = 'REMOVED', updated_at = NOW() " +
                "WHERE status = 'EXPIRED' AND expires_at < ?",
                sevenDaysAgo
            );
            if (purgedCount > 0) {
                System.out.println("[OfflineExpiryScheduler] Đã đánh dấu REMOVED cho " + purgedCount + " bài hát hết hạn quá 7 ngày.");
            }
        } catch (Exception e) {
            System.err.println("[OfflineExpiryScheduler] Lỗi khi dọn dẹp bài hát cũ: " + e.getMessage());
        }
    }
}
