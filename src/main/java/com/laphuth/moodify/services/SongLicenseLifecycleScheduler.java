package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class SongLicenseLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SongLicenseLifecycleScheduler.class);

    private final SongLicenseRepository songLicenseRepository;
    private final TrackRepository trackRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SongLicenseLifecycleScheduler(
        SongLicenseRepository songLicenseRepository,
        TrackRepository trackRepository
    ) {
        this.songLicenseRepository = songLicenseRepository;
        this.trackRepository = trackRepository;
    }

    /**
     * Tự động khởi tạo MySQL Event Scheduler và đồng bộ bài hát khi ứng dụng khởi động
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        initDatabaseEventScheduler();
        log.info("[License Lifecycle] Khởi chạy kiểm tra và đồng bộ trạng thái bản quyền bài hát ban đầu...");
        int count = processExpiredLicenses();
        log.info("[License Lifecycle] Hoàn tất kiểm tra ban đầu. Đã xử lý {} bài hát hết hạn hợp đồng.", count);
    }

    /**
     * Khởi tạo MySQL Event Scheduler & Stored Procedure nếu được hỗ trợ
     */
    public void initDatabaseEventScheduler() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.execute("SET GLOBAL event_scheduler = ON");
            jdbcTemplate.execute("""
                CREATE PROCEDURE IF NOT EXISTS sp_expire_outdated_song_licenses()
                BEGIN
                    UPDATE song_licenses
                    SET status = 'EXPIRED'
                    WHERE status = 'ACTIVE'
                      AND expiry_date IS NOT NULL
                      AND expiry_date < CURRENT_DATE();
                END
            """);
            jdbcTemplate.execute("""
                CREATE EVENT IF NOT EXISTS evt_daily_song_license_expiry
                ON SCHEDULE EVERY 1 DAY
                STARTS (TIMESTAMP(CURRENT_DATE) + INTERVAL 1 DAY + INTERVAL 1 MINUTE)
                ON COMPLETION PRESERVE
                ENABLE
                DO CALL sp_expire_outdated_song_licenses()
            """);
            log.info("[License Lifecycle] MySQL Event Scheduler và Stored Procedure đã được kích hoạt thành công tại tầng Database.");
        } catch (Exception e) {
            log.info("[License Lifecycle] Thông báo MySQL Event: {}. Kịch bản SQL độc lập có tại mysql_event_scheduler_license_expiry.sql", e.getMessage());
        }
    }

    /**
     * Chạy định kỳ vào 00:05 mỗi ngày theo múi giờ Việt Nam (Asia/Ho_Chi_Minh)
     */
    @Scheduled(cron = "${license.expiry.cron:0 5 0 * * ?}", zone = "Asia/Ho_Chi_Minh")
    public void scheduledExpiryCheck() {
        log.info("[License Lifecycle] Bắt đầu tiến trình định kỳ kiểm tra các hợp đồng bản quyền hết hạn...");
        int count = processExpiredLicenses();
        log.info("[License Lifecycle] Kết thúc tiến trình định kỳ. Đã vô hiệu hóa {} bài hát hết hạn.", count);
    }

    /**
     * Xử lý đồng bộ các hợp đồng hết hạn sang MongoDB
     * @return Số lượng bản ghi đã được xử lý
     */
    @Transactional
    public int processExpiredLicenses() {
        LocalDate today = LocalDate.now();
        List<SongLicense> expiredLicenses = songLicenseRepository.findExpiredLicenses();
        if (expiredLicenses.isEmpty()) {
            expiredLicenses = songLicenseRepository.findAllExpiredLicenses(today);
        }

        if (expiredLicenses.isEmpty()) {
            log.debug("[License Lifecycle] Không có hợp đồng bản quyền nào hết hạn hôm nay ({}).", today);
            return 0;
        }

        log.warn("[License Lifecycle] Phát hiện {} hợp đồng bản quyền đã hết hạn hoặc quá hạn trước ngày {}.",
            expiredLicenses.size(), today);

        int processed = 0;
        for (SongLicense license : expiredLicenses) {
            try {
                // 1. Cập nhật trạng thái SongLicense sang EXPIRED nếu chưa phải là EXPIRED
                if (license.getStatus() != LicenseStatus.EXPIRED) {
                    license.setStatus(LicenseStatus.EXPIRED);
                    songLicenseRepository.save(license);
                }

                // 2. Cập nhật trạng thái Track trong MongoDB thành archived và ẩn khỏi phát hành công khai
                String trackId = license.getTrackId();
                if (trackId != null && !trackId.isBlank()) {
                    trackRepository.findById(trackId).ifPresent(track -> {
                        boolean needsUpdate = !"archived".equalsIgnoreCase(track.getStatus())
                            || !"private".equalsIgnoreCase(track.getVisibility());
                        if (needsUpdate) {
                            track.setStatus("archived");
                            track.setVisibility("private");
                            track.setUpdatedAt(Instant.now());
                            trackRepository.save(track);
                            log.info("[License Lifecycle] Đã vô hiệu hóa bài hát '{}' (ID: {}) do hợp đồng hết hạn vào ngày {}.",
                                track.getName(), track.getId(), license.getExpiryDate());
                        }
                    });
                }
                processed++;
            } catch (Exception e) {
                log.error("[License Lifecycle] Lỗi khi xử lý hợp đồng ID {} cho trackId {}: {}",
                    license.getId(), license.getTrackId(), e.getMessage(), e);
            }
        }

        return processed;
    }
}
