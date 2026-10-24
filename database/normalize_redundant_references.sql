-- Apply ONCE to an existing database after a backup, with playback writes stopped.
-- Deploy the matching backend code before allowing playback writes again.
-- This script never deletes qualified_streams or listening_history rows.
-- MySQL DDL auto-commits; do not treat this file as a single rollbackable transaction.
USE moodify;

-- Abort before changing the schema if old session copies disagree with their source.
-- Resolve any reported rows manually; do not silently overwrite historical data.
DROP PROCEDURE IF EXISTS sp_assert_revenue_normalization_ready;
DELIMITER $$
CREATE PROCEDURE sp_assert_revenue_normalization_ready()
BEGIN
    IF EXISTS (
        SELECT 1 FROM revenue_playback_sessions s
        LEFT JOIN listening_history h ON h.id = s.listening_history_id
        WHERE h.id IS NULL
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Orphan revenue playback sessions exist';
    END IF;

    IF EXISTS (
        SELECT 1 FROM revenue_playback_sessions s
        JOIN listening_history h ON h.id = s.listening_history_id
        WHERE s.user_id <> h.user_id
           OR s.track_id <> h.track_id
           OR s.last_position_ms <> h.last_position_ms
           OR s.total_played_ms <> h.listened_duration_ms
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Revenue session copies differ from listening_history';
    END IF;

    IF EXISTS (
        SELECT 1 FROM platform_traffic_events p
        LEFT JOIN users u ON u.id = p.user_id
        WHERE p.user_id IS NOT NULL AND u.id IS NULL
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Orphan platform traffic user IDs exist';
    END IF;

    IF EXISTS (
        SELECT 1 FROM qualified_streams
        WHERE gross_vnd <> content_lead_vnd + platform_vnd
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Qualified stream money split is inconsistent';
    END IF;
END$$
DELIMITER ;

CALL sp_assert_revenue_normalization_ready();
DROP PROCEDURE sp_assert_revenue_normalization_ready;

-- A live session now refers to its listening_history row. If that row is
-- deleted later, the temporary session disappears with it; the ledger remains.
ALTER TABLE revenue_playback_sessions
    ADD CONSTRAINT fk_revenue_session_history
        FOREIGN KEY (listening_history_id) REFERENCES listening_history(id)
        ON DELETE CASCADE;

ALTER TABLE revenue_playback_sessions
    DROP INDEX idx_revenue_session_user_track,
    ADD INDEX idx_revenue_session_active (playing, closed, last_seen_at),
    DROP COLUMN user_id,
    DROP COLUMN track_id,
    DROP COLUMN last_position_ms,
    DROP COLUMN total_played_ms;

-- Optional user association for traffic; deleting a user anonymizes visits.
ALTER TABLE platform_traffic_events
    ADD CONSTRAINT fk_platform_traffic_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Keep historical money snapshots, while requiring the split to balance.
ALTER TABLE qualified_streams
    ADD CONSTRAINT chk_qualified_stream_split
        CHECK (gross_vnd = content_lead_vnd + platform_vnd);

-- The ledger keeps its own listener, lead, track, time and monetary snapshot.
-- listening_history_id is not a FK and may become dangling when users delete
-- their listening history. session_key already identifies the payable play.
ALTER TABLE qualified_streams
    DROP COLUMN listening_history_id;
