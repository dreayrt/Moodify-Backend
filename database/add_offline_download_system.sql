-- ============================================================
-- MOODIFY OFFLINE DOWNLOAD SYSTEM MIGRATION
-- ============================================================

-- 1. Đảm bảo bảng offline_downloads tồn tại với đầy đủ cột và index
CREATE TABLE IF NOT EXISTS offline_downloads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    status ENUM('QUEUED','DOWNLOADING','DOWNLOADED','FAILED','EXPIRED','REMOVED') NOT NULL DEFAULT 'QUEUED',
    local_path VARCHAR(1000) NULL,
    downloaded_at DATETIME NULL,
    expires_at DATETIME NULL,
    last_verified_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_offline_device
        FOREIGN KEY (device_id) REFERENCES user_devices(id) ON DELETE CASCADE,
    CONSTRAINT uk_offline_device_track UNIQUE (device_id, track_id),

    INDEX idx_offline_device_status (device_id, status),
    INDEX idx_offline_track (track_id),
    INDEX idx_offline_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Bảng lưu trữ token cấp phép tải xuống một lần (one-time license token)
CREATE TABLE IF NOT EXISTS download_license_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(128) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    offline_download_id BIGINT NULL,
    encryption_key_hash VARCHAR(128) NOT NULL,
    status ENUM('ISSUED','USED','EXPIRED','REVOKED') NOT NULL DEFAULT 'ISSUED',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,

    INDEX idx_dlt_user (user_id),
    INDEX idx_dlt_token (token),
    INDEX idx_dlt_status_expiry (status, expires_at),
    CONSTRAINT fk_dlt_device FOREIGN KEY (device_id) REFERENCES user_devices(id) ON DELETE CASCADE,
    CONSTRAINT fk_dlt_download FOREIGN KEY (offline_download_id) REFERENCES offline_downloads(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Bảng audit log theo dõi và chống lạm dụng tải nhạc ngoại tuyến
CREATE TABLE IF NOT EXISTS offline_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    track_id VARCHAR(64) NULL,
    metadata JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_oal_user_action (user_id, action),
    INDEX idx_oal_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
