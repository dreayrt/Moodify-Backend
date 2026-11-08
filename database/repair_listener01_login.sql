-- One-time, targeted recovery for the listener01 fixture account.
-- This deliberately does not import Moodify.sql, which drops/recreates the database.
-- If email or phone belongs to another account, the INSERT fails and no other row is changed.
START TRANSACTION;

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
    'Nguyễn Văn Listener',
    '0901000001',
    'listener@moodify.local',
    'listener01',
    '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    'USER',
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'listener01'
);

UPDATE users
SET password = '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    updated_at = CURRENT_TIMESTAMP
WHERE username = 'listener01';

SELECT id, username, role, status
FROM users
WHERE username = 'listener01';

COMMIT;
