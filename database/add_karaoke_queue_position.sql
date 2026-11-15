-- Apply once to existing databases before deploying the queue ordering backend.
-- Pause queue writes during migration. Existing rows retain FIFO order through
-- the created_at/id fallback; the first move normalizes their queue positions.
-- Skip ADD COLUMN if Hibernate ddl-auto=update has already created this column.
ALTER TABLE karaoke_room_queue
    ADD COLUMN queue_position BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_karaoke_room_queue_position
    ON karaoke_room_queue (room_id, queue_position, created_at, id);

-- Existing installations must also apply allow_multiple_karaoke_queue_items.sql
-- if uk_karaoke_room_queue_requester is still present. Hibernate does not drop
-- the old unique index when its annotation is removed.
SHOW INDEX FROM karaoke_room_queue;
