-- Restore the inbox tables required by NotificationService.
-- Safe to run when one or both tables already exist.
USE moodify;

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
