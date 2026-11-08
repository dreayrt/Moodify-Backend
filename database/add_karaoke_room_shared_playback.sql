-- Additive migration for synchronized playback state.
-- Inspect the legacy capacity values first. The application now caps all rooms
-- at six active members without forcibly removing anyone from an existing room.
SELECT id, name, max_members
FROM karaoke_rooms
WHERE closed_at IS NULL AND max_members > 6;

ALTER TABLE karaoke_rooms
    ADD COLUMN current_track_id VARCHAR(64) NULL,
    ADD COLUMN playback_status VARCHAR(12) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN playback_position_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN playback_started_at DATETIME NULL,
    ADD COLUMN playback_version BIGINT NOT NULL DEFAULT 0,
    ADD KEY idx_karaoke_rooms_playback (playback_status, closed_at);

-- Intentionally no FK for current_track_id: tracks are stored in MongoDB.
-- Room state remains authoritative in MySQL; the track id is a cross-store reference.
