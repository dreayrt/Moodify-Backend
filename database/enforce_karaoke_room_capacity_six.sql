-- Before running: confirm this migration is against the Moodify schema and
-- inspect the preflight result below. It never removes room memberships.
SELECT r.id, r.name, r.max_members, COUNT(m.user_id) AS active_members
FROM karaoke_rooms r
LEFT JOIN karaoke_room_members m ON m.room_id = r.id AND m.left_at IS NULL
WHERE r.max_members > 6
GROUP BY r.id, r.name, r.max_members
HAVING COUNT(m.user_id) > 6;

-- If the query above returns any rows, stop here and resolve the membership
-- count with the product owner before retrying. Otherwise, lower only the
-- stored capacity limit; do not delete/kick any member.
UPDATE karaoke_rooms AS r
SET max_members = 6
WHERE r.max_members > 6
  AND NOT EXISTS (
      SELECT 1
      FROM karaoke_room_members m
      WHERE m.room_id = r.id AND m.left_at IS NULL
      GROUP BY m.room_id
      HAVING COUNT(*) > 6
  );

-- This constraint name matches database/add_karaoke_room_management.sql.
-- If the production schema uses another name, inspect information_schema first.
ALTER TABLE karaoke_rooms
    DROP CHECK chk_karaoke_rooms_capacity,
    ADD CONSTRAINT chk_karaoke_rooms_capacity CHECK (max_members BETWEEN 1 AND 6);
