package com.laphuth.moodify.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OfflineExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OfflineExpiryScheduler.class);
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
                log.info("[OfflineExpiryScheduler] Đã chuyển {} bài hát offline quá hạn sang trạng thái EXPIRED.", expiredCount);
            }
        } catch (Exception e) {
            log.error("[OfflineExpiryScheduler] Lỗi khi quét hạn tải offline: {}", e.getMessage(), e);
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
                log.info("[OfflineExpiryScheduler] Đã đánh dấu REMOVED cho {} bài hát hết hạn quá 7 ngày.", purgedCount);
            }
        } catch (Exception e) {
            log.error("[OfflineExpiryScheduler] Lỗi khi dọn dẹp bài hát cũ: {}", e.getMessage(), e);
        }
    }
}
