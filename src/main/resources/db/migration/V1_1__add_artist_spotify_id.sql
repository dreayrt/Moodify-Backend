-- Migration: Bổ sung liên kết Nghệ sĩ (artist_spotify_id) vào bảng users
-- Phục vụ luồng đăng ký tài khoản Artist và đồng bộ hồ sơ Nghệ sĩ với MongoDB

ALTER TABLE users 
ADD COLUMN IF NOT EXISTS artist_spotify_id VARCHAR(80) NULL UNIQUE AFTER staff_code;

-- Tạo index tìm kiếm nhanh theo artist_spotify_id
CREATE INDEX IF NOT EXISTS idx_users_artist_spotify ON users (artist_spotify_id);
