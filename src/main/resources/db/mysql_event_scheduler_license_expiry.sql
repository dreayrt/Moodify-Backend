-- ==============================================================================
-- MOODIFY DATABASE EVENT SCHEDULER: LICENSE EXPIRY AUTOMATION
-- ==============================================================================
-- Kịch bản tự động hóa xử lý hợp đồng bản quyền hết hạn tại tầng MySQL Database.
-- Chạy tự động định kỳ vào lúc 00:01:00 hàng ngày (Múi giờ Asia/Ho_Chi_Minh).
-- ==============================================================================

USE moodify;

-- 1. Bật tính năng Event Scheduler của MySQL Server
SET GLOBAL event_scheduler = ON;

-- 2. Tạo Stored Procedure xử lý các hợp đồng đã hết hạn hiệu lực
DELIMITER $$

DROP PROCEDURE IF EXISTS sp_expire_outdated_song_licenses $$

CREATE PROCEDURE sp_expire_outdated_song_licenses()
BEGIN
    -- Cập nhật tất cả các hợp đồng bản quyền đang ACTIVE nhưng expiry_date đã qua ngày hiện tại
    UPDATE song_licenses
    SET status = 'EXPIRED'
    WHERE status = 'ACTIVE'
      AND expiry_date IS NOT NULL
      AND expiry_date < CURRENT_DATE();
      
    SELECT ROW_COUNT() AS expired_licenses_count;
END $$

DELIMITER ;

-- 3. Tạo Event Scheduler tự động thực thi Stored Procedure mỗi ngày lúc 00:01
DROP EVENT IF EXISTS evt_daily_song_license_expiry;

CREATE EVENT evt_daily_song_license_expiry
ON SCHEDULE EVERY 1 DAY
STARTS (TIMESTAMP(CURRENT_DATE) + INTERVAL 1 DAY + INTERVAL 1 MINUTE)
ON COMPLETION PRESERVE
ENABLE
COMMENT 'Moodify: Tu dong quet va cap nhat trang thai EXPIRED cho hop dong ban quyen het han'
DO CALL sp_expire_outdated_song_licenses();

-- ==============================================================================
-- KIỂM TRA TRẠNG THÁI (VERIFICATION QUERIES)
-- ==============================================================================
-- Kiểm tra Event Scheduler đã bật chưa:
-- SHOW VARIABLES LIKE 'event_scheduler';
--
-- Kiểm tra Event đã được lập lịch:
-- SHOW EVENTS FROM moodify;
--
-- Chạy thử thủ công để kiểm tra Procedure:
-- CALL sp_expire_outdated_song_licenses();
-- ==============================================================================
