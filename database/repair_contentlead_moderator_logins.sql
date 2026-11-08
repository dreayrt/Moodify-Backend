-- One-time, targeted recovery for the contentlead01 and moderator01 fixture accounts.
-- This deliberately does not import Moodify.sql, which drops/recreates the database.
-- If an email, phone, or artist ID belongs to another account, the INSERT fails
-- and no unrelated account is updated.
START TRANSACTION;

INSERT INTO users (
    full_name,
    phone,
    email,
    username,
    password,
    artist_spotify_id,
    role,
    status
)
SELECT
    'Trần Minh Content Lead',
    '0901000002',
    'contentlead@moodify.local',
    'contentlead01',
    '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    '4OCl7UfKRXLcYouOYa3Bwc',
    'CONTENT_LEAD',
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'contentlead01'
);

INSERT INTO users (
    full_name,
    phone,
    email,
    username,
    password,
    role,
    status
)
SELECT
    'Lê Hoàng Moderator',
    '0901000003',
    'moderator@moodify.local',
    'moderator01',
    '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    'MODERATOR',
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'moderator01'
);

UPDATE users
SET password = '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    updated_at = CURRENT_TIMESTAMP
WHERE username IN ('contentlead01', 'moderator01');

SELECT id, username, role, status
FROM users
WHERE username IN ('contentlead01', 'moderator01')
ORDER BY username;

COMMIT;
