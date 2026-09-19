-- ============================================================
-- SEED DATA: BẢNG USERS
-- Bao gồm đầy đủ các vai trò: USER (Người nghe), ARTIST (Nghệ sĩ có liên kết MongoDB),
-- MODERATOR (Kiểm duyệt viên) và ADMIN (Quản trị viên).
-- 
-- Mật khẩu mặc định cho các tài khoản test bên dưới: 123456
-- Hash BCrypt: $2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS
-- ============================================================

INSERT INTO users (
    id,
    full_name,
    phone,
    email,
    username,
    password,
    avatar_url,
    role,
    artist_spotify_id,
    status,
    last_login_at,
    created_at,
    updated_at
)
VALUES
(
    1,
    'Nguyễn Văn Listener',
    '0901000001',
    'listener@moodify.local',
    'listener01',
    '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=240&auto=format&fit=crop&q=80',
    'USER',
    NULL,
    'ACTIVE',
    '2026-09-14 09:00:00',
    '2026-01-01 08:00:00',
    '2026-09-14 09:00:00'
),
(
    2,
    'Trần Minh Artist',
    '0901000002',
    'artist@moodify.local',
    'artist01',
    '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
    'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=240&auto=format&fit=crop&q=80',
    'ARTIST',
    '44ZNcW1ZSGB9oqX1ALnriH',
    'ACTIVE',
    '2026-09-14 08:30:00',
    '2026-02-01 08:00:00',
    '2026-09-14 08:30:00'
),
(
    3,
    'Lê Hoàng Moderator',
    '0901000003',
    'moderator@moodify.local',
    'moderator01',
    '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
    'https://images.unsplash.com/photo-1517841905240-472988babdf9?w=240&auto=format&fit=crop&q=80',
    'MODERATOR',
    NULL,
    'ACTIVE',
    '2026-09-14 08:00:00',
    '2026-03-01 08:00:00',
    '2026-09-14 08:00:00'
),
(
    4,
    'Phạm Quốc Admin',
    '0901000004',
    'admin@moodify.local',
    'admin01',
    '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
    'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?w=240&auto=format&fit=crop&q=80',
    'ADMIN',
    NULL,
    'ACTIVE',
    '2026-09-01 10:00:00',
    '2026-01-15 08:00:00',
    '2026-09-01 10:00:00'
),
(
    5,
    'Hoàng Anh Banned',
    '0901000005',
    'banned@moodify.local',
    'banned01',
    '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
    NULL,
    'USER',
    NULL,
    'BANNED',
    NULL,
    '2026-04-01 08:00:00',
    '2026-09-10 14:00:00'
);
