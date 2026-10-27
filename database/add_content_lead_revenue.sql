-- Apply once to an existing Moodify database. Never run database/Moodify.sql on live data.
USE moodify;

ALTER TABLE song_licenses
    ADD COLUMN acquisition_cost_vnd DECIMAL(18,0) NULL,
    ADD CONSTRAINT chk_song_license_acquisition_cost CHECK (acquisition_cost_vnd IS NULL OR acquisition_cost_vnd >= 0);

-- Times in the new revenue tables are UTC DATETIME(3).
CREATE TABLE revenue_playback_sessions (
    session_key CHAR(36) PRIMARY KEY,
    listening_history_id BIGINT NOT NULL UNIQUE,
    last_seen_at DATETIME(3) NOT NULL,
    continuous_ms BIGINT NOT NULL DEFAULT 0,
    playing BOOLEAN NOT NULL DEFAULT FALSE,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_revenue_session_history
        FOREIGN KEY (listening_history_id) REFERENCES listening_history(id) ON DELETE CASCADE,
    INDEX idx_revenue_session_active (playing, closed, last_seen_at)
);

-- Ledger survives deletion of listening_history; do not store its disposable ID.
CREATE TABLE qualified_streams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36) NOT NULL UNIQUE,
    listener_user_id BIGINT NOT NULL,
    content_lead_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    qualified_at DATETIME(3) NOT NULL,
    gross_vnd DECIMAL(18,0) NOT NULL DEFAULT 1000,
    content_lead_vnd DECIMAL(18,0) NOT NULL DEFAULT 750,
    platform_vnd DECIMAL(18,0) NOT NULL DEFAULT 250,
    policy_version VARCHAR(32) NOT NULL DEFAULT 'V1_60S_75_25',
    status ENUM('APPROVED','PENDING_REVIEW','REJECTED') NOT NULL DEFAULT 'APPROVED',
    review_reason VARCHAR(255) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    CONSTRAINT chk_qualified_stream_split CHECK (gross_vnd = content_lead_vnd + platform_vnd),
    INDEX idx_stream_lead_time (content_lead_user_id, status, qualified_at),
    INDEX idx_stream_track_time (track_id, status, qualified_at),
    INDEX idx_stream_listener_track_time (listener_user_id, track_id, qualified_at)
);
