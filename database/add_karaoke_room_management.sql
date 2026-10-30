-- Additive migration for persisted Karaoke rooms, memberships, and queue.
-- Never run the user-provided DROP DATABASE script to apply this migration.
-- Run once against the target Moodify schema after checking information_schema.

CREATE TABLE IF NOT EXISTS karaoke_rooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(40) NOT NULL,
    host_user_id BIGINT NOT NULL,
    max_members INT NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    invite_code_hash VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_karaoke_rooms_public (visibility, closed_at, created_at, id),
    CONSTRAINT fk_karaoke_rooms_host
        FOREIGN KEY (host_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_karaoke_rooms_visibility
        CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT chk_karaoke_rooms_capacity
        CHECK (max_members BETWEEN 1 AND 6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS karaoke_room_members (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at DATETIME NULL,
    PRIMARY KEY (room_id, user_id),
    KEY idx_karaoke_room_members_user (user_id),
    CONSTRAINT fk_karaoke_room_members_room
        FOREIGN KEY (room_id) REFERENCES karaoke_rooms (id) ON DELETE CASCADE,
    CONSTRAINT fk_karaoke_room_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS karaoke_room_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL COMMENT 'Logical reference to MongoDB tracks._id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_karaoke_room_queue_fifo (room_id, created_at, id),
    KEY idx_karaoke_room_queue_requester (requester_user_id),
    UNIQUE KEY uk_karaoke_room_queue_requester (room_id, requester_user_id),
    CONSTRAINT fk_karaoke_room_queue_room
        FOREIGN KEY (room_id) REFERENCES karaoke_rooms (id) ON DELETE CASCADE,
    CONSTRAINT fk_karaoke_room_queue_requester
        FOREIGN KEY (requester_user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
