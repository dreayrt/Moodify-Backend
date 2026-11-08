-- Only for creating this table in a new/empty database.
-- Existing tables: use allow_multiple_karaoke_queue_items.sql instead.
-- The referenced karaoke_rooms and users tables must already exist.
CREATE TABLE karaoke_room_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL COMMENT 'Logical reference to MongoDB tracks._id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_karaoke_room_queue_fifo (room_id, created_at, id),
    KEY idx_karaoke_room_queue_requester (requester_user_id),
    KEY idx_karaoke_room_queue_room_requester (room_id, requester_user_id),
    CONSTRAINT fk_karaoke_room_queue_room
        FOREIGN KEY (room_id) REFERENCES karaoke_rooms (id) ON DELETE CASCADE,
    CONSTRAINT fk_karaoke_room_queue_requester
        FOREIGN KEY (requester_user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
