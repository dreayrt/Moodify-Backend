-- Moodify: MySQL inbox and subscription-renewal reminders
-- Run with a MySQL account that can create tables, procedures, events, and
-- change the global event_scheduler setting. The Event runs daily at 09:00 UTC+7.

USE moodify;
SET time_zone = '+07:00';

-- License expiry is handled by SongLicenseLifecycleScheduler in Spring because
-- that workflow also updates Track documents in MongoDB. Remove the old DB job
-- to avoid two schedulers competing for the same license records.
DROP EVENT IF EXISTS evt_daily_song_license_expiry;
DROP PROCEDURE IF EXISTS sp_expire_outdated_song_licenses;

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    link_url VARCHAR(1000) NULL,
    created_by BIGINT NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dedupe_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_mongo_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_dedupe_key (dedupe_key),
    UNIQUE KEY uk_notifications_source_mongo_id (source_mongo_id),
    KEY idx_notifications_user_created (user_id, created_at, id),
    KEY idx_notifications_user_read (user_id, read_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_broadcasts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    target_role VARCHAR(32) NOT NULL DEFAULT 'ALL',
    recipients_count INT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_mongo_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_broadcast_source_mongo_id (source_mongo_id),
    KEY idx_notification_broadcasts_created (created_at, id),
    CONSTRAINT fk_notification_broadcasts_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL Events need this server-level setting. Persist it in MySQL's server
-- configuration as well if it must remain ON after a database restart.
SET GLOBAL event_scheduler = ON;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_create_subscription_expiry_notifications$$
CREATE PROCEDURE sp_create_subscription_expiry_notifications()
BEGIN
    -- The furthest active end date is the user's effective subscription expiry.
    -- If multiple rows share that end date, use the newest subscription row.
    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ', DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT('subscription-expiry:', s.user_id, ':', DATE_FORMAT(s.end_at, '%Y%m%d'), ':7')
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 7 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (
                later_sub.end_at > s.end_at
                OR (later_sub.end_at = s.end_at AND later_sub.id > s.id)
            )
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );

    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ', DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT('subscription-expiry:', s.user_id, ':', DATE_FORMAT(s.end_at, '%Y%m%d'), ':1')
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 1 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (
                later_sub.end_at > s.end_at
                OR (later_sub.end_at = s.end_at AND later_sub.id > s.id)
            )
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );
END$$

DELIMITER ;

DROP EVENT IF EXISTS evt_daily_subscription_expiry_reminders;
CREATE EVENT evt_daily_subscription_expiry_reminders
    ON SCHEDULE EVERY 1 DAY
    STARTS (TIMESTAMP(CURRENT_DATE) + INTERVAL 1 DAY + INTERVAL 9 HOUR)
    ON COMPLETION PRESERVE
    ENABLE
    COMMENT 'Moodify: create 7-day and 1-day subscription expiry inbox notifications'
    DO CALL sp_create_subscription_expiry_notifications();

-- Verify after deployment:
-- SHOW VARIABLES LIKE 'event_scheduler';
-- SHOW EVENTS FROM moodify;
-- SELECT COUNT(*) FROM notifications;
-- CALL sp_create_subscription_expiry_notifications(); -- safe to rerun; dedupe_key prevents duplicates.
