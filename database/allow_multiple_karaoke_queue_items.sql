-- Existing database only. Pause queue writes while applying this change,
-- then deploy the updated backend and mobile app.
-- This changes indexes only; it does not add/remove columns or delete rows.
-- Run once against the database that still has the old unique index.
ALTER TABLE karaoke_room_queue
    DROP INDEX uk_karaoke_room_queue_requester,
    ADD INDEX idx_karaoke_room_queue_room_requester (room_id, requester_user_id);

-- Verify: Non_unique must be 1 for idx_karaoke_room_queue_room_requester.
SHOW INDEX FROM karaoke_room_queue;

-- Rollback prerequisite: the query below must return no rows.
-- SELECT room_id, requester_user_id, COUNT(*) AS queued_songs
-- FROM karaoke_room_queue
-- GROUP BY room_id, requester_user_id HAVING COUNT(*) > 1;
-- If multiple songs remain, wait for them to be sung/removed by their owners.
-- Never delete queued songs automatically to force a rollback.
-- Then, with queue writes paused and the old application restored:
-- ALTER TABLE karaoke_room_queue
--     DROP INDEX idx_karaoke_room_queue_room_requester,
--     ADD UNIQUE INDEX uk_karaoke_room_queue_requester (room_id, requester_user_id);
