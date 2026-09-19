-- DEVELOPMENT ONLY: this script deletes and recreates the complete Moodify database.
DROP DATABASE IF EXISTS moodify;

CREATE DATABASE moodify
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE moodify;

-- ============================================================
-- 1. NGƯỜI DÙNG
-- Ánh xạ lớp cha User và các lớp con phân biệt theo role trong sơ đồ lớp.
-- Admin / Listener / Artist / ContentModerator được biểu diễn thông qua field role.
-- ============================================================
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(500) NULL,
    role ENUM('USER','ARTIST','MODERATOR','ADMIN') NOT NULL DEFAULT 'USER',
    artist_spotify_id VARCHAR(80) NULL UNIQUE,  -- <--- Cột liên kết sang MongoDB
    status ENUM('ACTIVE','INACTIVE','BANNED') NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_users_role_status (role, status),
    INDEX idx_users_artist_spotify (artist_spotify_id),
    INDEX idx_users_created_at (created_at)
);

-- ============================================================
-- 3. THƯ VIỆN BÀI HÁT CÁ NHÂN
-- Lưu các bài hát người dùng thêm vào thư viện cá nhân; dữ liệu chi tiết track vẫn nằm trong MongoDB.
-- ============================================================
-- sua ten table lai playlist cua user--
CREATE TABLE user_library_tracks (  
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_library_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_library_user_track UNIQUE (user_id, track_id),

    INDEX idx_library_track (track_id),
    INDEX idx_library_user_added (user_id, added_at)
) ;

-- ============================================================
-- 4. BÀI HÁT YÊU THÍCH
-- Ánh xạ trực tiếp lớp FavoriteSong trong sơ đồ lớp mức thiết kế.
-- track_id tham chiếu logic đến MongoDB tracks._id.
-- ============================================================
CREATE TABLE favorite_songs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_favorite_song_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_favorite_song UNIQUE (user_id, track_id),

    INDEX idx_favorite_song_track (track_id),
    INDEX idx_favorite_song_user_time (user_id, created_at)
) ;

-- ============================================================
-- 5. NGHỆ SĨ YÊU THÍCH
-- Ánh xạ trực tiếp lớp FavoriteArtist trong sơ đồ lớp mức thiết kế.
-- artist_id tham chiếu logic đến MongoDB artists._id.
-- ============================================================
CREATE TABLE favorite_artists (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    artist_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_favorite_artist_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_favorite_artist UNIQUE (user_id, artist_id),

    INDEX idx_favorite_artist_artist (artist_id),
    INDEX idx_favorite_artist_user_time (user_id, created_at)
) ;

-- ============================================================
-- 6. ALBUM YÊU THÍCH
-- Tách riêng để đáp ứng yêu cầu người dùng có thể yêu thích album.
-- album_id tham chiếu logic đến MongoDB albums._id.
-- ============================================================
CREATE TABLE favorite_albums (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    album_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_favorite_album_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_favorite_album UNIQUE (user_id, album_id),

    INDEX idx_favorite_album_album (album_id),
    INDEX idx_favorite_album_user_time (user_id, created_at)
) ;

-- ============================================================
-- 7. LỊCH SỬ NGHE NHẠC
-- Ánh xạ lớp ListeningHistory trong sơ đồ lớp.
-- Lịch sử nghe của ARTIST chỉ được tạo sau khi xác minh track thuộc chính Artist đó trong MongoDB.
-- ============================================================
CREATE TABLE listening_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at DATETIME NULL,
    listened_duration_ms INT UNSIGNED NOT NULL DEFAULT 0, 
    last_position_ms INT UNSIGNED NOT NULL DEFAULT 0,
    source ENUM('HOME','SEARCH','PLAYLIST','ALBUM','ARTIST','LIBRARY','FAVORITES','EMOTION','OTHER')
        NOT NULL DEFAULT 'OTHER',
    source_id VARCHAR(64) NULL,
    device_type ENUM('WEB','ANDROID','IOS','OTHER') NOT NULL DEFAULT 'WEB',

    CONSTRAINT fk_listening_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    INDEX idx_listening_user_time (user_id, started_at),
    INDEX idx_listening_track_time (track_id, started_at)
) ;

-- ============================================================
-- 8. SỰ KIỆN PHÁT NHẠC
-- Ghi nhận Play / Pause / Resume / Seek / Next / Previous để quản lý phát nhạc và phục vụ thống kê.
-- ============================================================
CREATE TABLE playback_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    listening_history_id BIGINT NOT NULL,
    event_type ENUM('PLAY','PAUSE','RESUME','SEEK','SKIP_NEXT','SKIP_PREVIOUS','COMPLETE') NOT NULL,
    position_ms INT UNSIGNED NULL,
    target_position_ms INT UNSIGNED NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_playback_history
        FOREIGN KEY (listening_history_id) REFERENCES listening_history(id) ON DELETE CASCADE,

    INDEX idx_playback_history_time (listening_history_id, occurred_at),
    INDEX idx_playback_type_time (event_type, occurred_at)
) ;

-- ============================================================
-- 9. LỊCH SỬ TÌM KIẾM
-- Ánh xạ SearchHistory, hỗ trợ tìm kiếm thông thường và tìm kiếm theo cảm xúc.
-- ============================================================
CREATE TABLE search_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(255) NOT NULL,
    search_type ENUM('ALL','TRACK','ARTIST','ALBUM','PLAYLIST','EMOTION') NOT NULL DEFAULT 'ALL',
    detected_emotion VARCHAR(50) NULL,
    emotion_confidence DECIMAL(5,4) NULL,
    selected_target_type ENUM('TRACK','ARTIST','ALBUM','PLAYLIST') NULL,
    selected_target_id VARCHAR(64) NULL,
    searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_search_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_search_emotion_confidence
        CHECK (emotion_confidence IS NULL OR (emotion_confidence BETWEEN 0 AND 1)),

    INDEX idx_search_user_time (user_id, searched_at),
    INDEX idx_search_keyword (keyword),
    INDEX idx_search_emotion (detected_emotion)
) ;

-- ============================================================
-- 10. GÓI DỊCH VỤ
-- Ánh xạ ServicePackage trong sơ đồ lớp.
-- Quyền lợi của gói được chuẩn hóa thành bảng riêng thay vì hard-code thành nhiều cột.
-- ============================================================
CREATE TABLE service_packages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL, 
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    duration_days INT UNSIGNED NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT chk_service_package_price
    CHECK (price >= 0),

CONSTRAINT chk_service_package_duration
    CHECK (duration_days > 0),

INDEX idx_service_package_status (status),
INDEX idx_service_package_display (display_order)
) ;

-- ============================================================
-- 13. ĐĂNG KÝ/GIA HẠN GÓI
-- Ánh xạ Subscription trong sơ đồ lớp.
-- Dữ liệu thanh toán được tách riêng sang bảng payment_transactions.
-- ============================================================
CREATE TABLE subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    service_package_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    status ENUM('PENDING','ACTIVE','EXPIRED','CANCELLED','SUSPENDED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_subscription_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

	CONSTRAINT fk_subscription_package
		FOREIGN KEY (service_package_id) REFERENCES service_packages(id),
    CONSTRAINT chk_subscription_dates CHECK (end_at > start_at),

    INDEX idx_subscription_user_status (user_id, status),
    INDEX idx_subscription_end (end_at),
    INDEX idx_subscription_package  (service_package_id)
) ;

-- ============================================================
-- 14. GIAO DỊCH THANH TOÁN
-- Ánh xạ PaymentTransaction và hỗ trợ các cổng thanh toán online.
-- ============================================================
CREATE TABLE payment_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    payment_method VARCHAR(50) NULL,
    provider VARCHAR(50) NULL,
    provider_transaction_id VARCHAR(150) NULL,
    status ENUM('PENDING','SUCCESS','FAILED','CANCELLED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    paid_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,
    CONSTRAINT chk_payment_amount CHECK (amount >= 0),

    UNIQUE KEY uk_payment_provider_tx (provider, provider_transaction_id),
    INDEX idx_payment_subscription_time (subscription_id, created_at),
    INDEX idx_payment_status_time (status, created_at)
) ;

-- ============================================================
-- 15. THIẾT BỊ NGƯỜI DÙNG
-- Xác định các thiết bị Mobile được sử dụng cho chức năng nghe nhạc offline.
-- ============================================================
CREATE TABLE user_devices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_uuid VARCHAR(191) NOT NULL,
    platform ENUM('ANDROID','IOS','OTHER') NOT NULL,
    device_name VARCHAR(120) NULL,
    status ENUM('ACTIVE','REVOKED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_device_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_device UNIQUE (user_id, device_uuid),

    INDEX idx_device_user_status (user_id, status)
) ;

-- ============================================================
-- 16. TẢI NHẠC OFFLINE
-- Ánh xạ khái niệm OfflineSong. localPath là đường dẫn cục bộ trên thiết bị và thông thường
-- do ứng dụng Mobile quản lý; server chủ yếu lưu quyền và trạng thái tải xuống.
-- ============================================================
CREATE TABLE offline_downloads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    status ENUM('QUEUED','DOWNLOADING','DOWNLOADED','FAILED','EXPIRED','REMOVED') NOT NULL DEFAULT 'QUEUED',
    local_path VARCHAR(1000) NULL,
    downloaded_at DATETIME NULL,
    expires_at DATETIME NULL,
    last_verified_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_offline_device
        FOREIGN KEY (device_id) REFERENCES user_devices(id) ON DELETE CASCADE,
    CONSTRAINT uk_offline_device_track UNIQUE (device_id, track_id),

    INDEX idx_offline_device_status (device_id, status),
    INDEX idx_offline_track (track_id),
    INDEX idx_offline_expiry (expires_at)
) ;

-- ============================================================
-- 17. YÊU CẦU KIỂM DUYỆT NỘI DUNG
-- Hỗ trợ Artist.submitForReview() và quy trình kiểm duyệt TRACK/ALBUM của Moderator.
-- Nội dung Track/Album thực tế vẫn được lưu trong MongoDB.
-- ============================================================
CREATE TABLE content_review_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artist_user_id BIGINT NOT NULL,
    content_type ENUM('TRACK','ALBUM') NOT NULL,
    content_id VARCHAR(64) NOT NULL,
    request_type ENUM('PUBLISH','UPDATE') NOT NULL DEFAULT 'PUBLISH',
    status ENUM('PENDING','IN_REVIEW','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME NULL,

    CONSTRAINT fk_review_request_artist
        FOREIGN KEY (artist_user_id) REFERENCES users(id) ON DELETE CASCADE,

    INDEX idx_review_queue (status, submitted_at),
    INDEX idx_review_artist (artist_user_id, submitted_at),
    INDEX idx_review_content (content_type, content_id)
) ;

-- ============================================================
-- 18. KẾT QUẢ/XỬ LÝ KIỂM DUYỆT
-- Ánh xạ các thao tác reviewSong/approveSong/rejectSong của ContentModerator.
-- Giữ lịch sử kiểm duyệt để audit; không ghi đè các quyết định cũ.
-- ============================================================
CREATE TABLE content_review_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_request_id BIGINT NOT NULL,
    moderator_user_id BIGINT NOT NULL,
    action ENUM('START_REVIEW','APPROVE','REJECT','RETURN_FOR_EDIT') NOT NULL,
    reason VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_review_action_request
        FOREIGN KEY (review_request_id) REFERENCES content_review_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_action_moderator
        FOREIGN KEY (moderator_user_id) REFERENCES users(id),

    INDEX idx_review_action_request_time (review_request_id, created_at),
    INDEX idx_review_action_moderator_time (moderator_user_id, created_at)
) ;

-- ============================================================
-- 19. NHÀ PHÂN PHỐI
-- Chỉ giữ bảng này nếu nghiệp vụ phân phối thực sự nằm trong phạm vi đồ án.
-- ============================================================
CREATE TABLE distributors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_name VARCHAR(150) NOT NULL,
    country varchar(150) not null,
    contact_name VARCHAR(100) NULL,
    contact_email VARCHAR(150) NULL,
    contact_phone VARCHAR(30) NULL,
    status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_distributor_name (company_name),
    INDEX idx_distributor_status (status)
) ;

-- ============================================================
-- 20. HỢP ĐỒNG PHÂN PHỐI
-- ============================================================
CREATE TABLE distribution_contracts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    distributor_id BIGINT NOT NULL,
    contract_code VARCHAR(80) NOT NULL UNIQUE,
    title VARCHAR(200) NULL,
    signed_date DATE NOT NULL,
    effective_from DATE NULL,
    effective_to DATE NULL,
    revenue_share double,
    status ENUM('DRAFT','ACTIVE','EXPIRED','TERMINATED') NOT NULL DEFAULT 'ACTIVE',
    document_url VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_distribution_contract_distributor
        FOREIGN KEY (distributor_id) REFERENCES distributors(id),
    CONSTRAINT chk_distribution_contract_dates
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),

    INDEX idx_distribution_contract_distributor (distributor_id),
    INDEX idx_distribution_contract_status (status)
) ;

-- ============================================================
-- 21. GIẤY PHÉP BÀI HÁT
-- Ánh xạ SongLicense; track_id tham chiếu logic đến tracks trong MongoDB.
-- ============================================================
CREATE TABLE song_licenses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id VARCHAR(64) NOT NULL,
    distributor_id BIGINT NULL,
    distribution_contract_id BIGINT NULL,
    license_type VARCHAR(80) NOT NULL,
    copyright_owner VARCHAR(200) NULL,
    issue_date DATE NULL,
    expiry_date DATE NULL,
    status ENUM('ACTIVE','EXPIRED','REVOKED','PENDING') NOT NULL DEFAULT 'ACTIVE',
    document_songlicenses_url VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_song_license_distributor
        FOREIGN KEY (distributor_id) REFERENCES distributors(id),
    CONSTRAINT fk_song_license_contract
        FOREIGN KEY (distribution_contract_id) REFERENCES distribution_contracts(id) ON DELETE SET NULL,
    CONSTRAINT chk_song_license_owner
        CHECK (
            (distributor_id IS NOT NULL AND copyright_owner IS NULL)
            OR (distributor_id IS NULL AND copyright_owner IS NOT NULL)
        ),
    CONSTRAINT chk_song_license_dates
        CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date),

    INDEX idx_song_license_track (track_id),
    INDEX idx_song_license_status (status),
    INDEX idx_song_license_distributor (distributor_id),
    INDEX idx_song_license_contract (distribution_contract_id)
) ;

INSERT INTO users (
    id, full_name, phone, email, username, password, avatar_url,
    role, artist_spotify_id, status, last_login_at, created_at, updated_at
) VALUES
(1, 'Nguyễn Văn Listener', '0901000001', 'listener@moodify.local', 'listener01',
 '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=240',
 'USER', NULL, 'ACTIVE', '2026-09-15 08:10:00', '2026-01-01 08:00:00', '2026-09-15 08:10:00'),
(2, 'Trần Minh Artist', '0901000002', 'artist@moodify.local', 'artist01',
 '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=240',
 'ARTIST', '4OCl7UfKRXLcYouOYa3Bwc', 'ACTIVE', '2026-09-15 08:00:00', '2026-02-01 08:00:00', '2026-09-15 08:00:00'),
(3, 'Lê Hoàng Moderator', '0901000003', 'moderator@moodify.local', 'moderator01',
 '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
 NULL, 'MODERATOR', NULL, 'ACTIVE', '2026-09-15 07:45:00', '2026-03-01 08:00:00', '2026-09-15 07:45:00'),
(4, 'Phạm Quốc Admin', '0901000004', 'admin@moodify.local', 'admin01',
 '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
 NULL, 'ADMIN', NULL, 'ACTIVE', NULL, '2026-01-15 08:00:00', '2026-08-30 09:00:00'),
(5, 'Hoàng Anh Banned', '0901000005', 'banned@moodify.local', 'banned01',
 '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS',
 NULL, 'USER', NULL, 'BANNED', NULL, '2026-04-01 08:00:00', '2026-09-10 14:00:00');

-- ============================================================
-- 2. USER_LIBRARY_TRACKS
-- ============================================================
INSERT INTO user_library_tracks (id, user_id, track_id, added_at) VALUES
(1, 1, '6a8230b53cccfc45cd626ced', '2026-09-01 10:00:00'),
(2, 1, '6a8230b53cccfc45cd626cee', '2026-09-02 11:00:00'),
(3, 2, '6a8230b53cccfc45cd626cef', '2026-09-03 12:00:00'),
(4, 2, '6a8230b53cccfc45cd626cf0', '2026-09-04 13:00:00'),
(5, 5, '6a8230b53cccfc45cd626cf1', '2026-09-05 14:00:00');

-- ============================================================
-- 3. FAVORITE_SONGS
-- ============================================================
INSERT INTO favorite_songs (id, user_id, track_id, created_at) VALUES
(1, 1, '6a8230b53cccfc45cd626ced', '2026-09-05 08:00:00'),
(2, 1, '6a8230b53cccfc45cd626cef', '2026-09-06 08:30:00'),
(3, 2, '6a8230b53cccfc45cd626cee', '2026-09-07 09:00:00'),
(4, 2, '6a8230b53cccfc45cd626cf1', '2026-09-08 09:30:00'),
(5, 5, '6a8230b53cccfc45cd626cf0', '2026-09-09 10:00:00');

-- ============================================================
-- 4. FAVORITE_ARTISTS
-- ============================================================
INSERT INTO favorite_artists (id, user_id, artist_id, created_at) VALUES
(1, 1, '7b8230b53cccfc45cd626001', '2026-09-01 08:00:00'),
(2, 1, '7b8230b53cccfc45cd626002', '2026-09-02 08:00:00'),
(3, 2, '7b8230b53cccfc45cd626003', '2026-09-03 08:00:00'),
(4, 2, '7b8230b53cccfc45cd626004', '2026-09-04 08:00:00'),
(5, 5, '7b8230b53cccfc45cd626005', '2026-09-05 08:00:00');

-- ============================================================
-- 5. FAVORITE_ALBUMS
-- ============================================================
INSERT INTO favorite_albums (id, user_id, album_id, created_at) VALUES
(1, 1, '8c8230b53cccfc45cd626001', '2026-09-01 09:00:00'),
(2, 1, '8c8230b53cccfc45cd626002', '2026-09-02 09:00:00'),
(3, 2, '8c8230b53cccfc45cd626003', '2026-09-03 09:00:00'),
(4, 2, '8c8230b53cccfc45cd626004', '2026-09-04 09:00:00'),
(5, 5, '8c8230b53cccfc45cd626005', '2026-09-05 09:00:00');

-- ============================================================
-- 6. LISTENING_HISTORY
-- Bao phủ toàn bộ source: HOME, SEARCH, PLAYLIST, ALBUM, ARTIST,
-- LIBRARY, FAVORITES, EMOTION, OTHER và đủ device_type.
-- ============================================================
INSERT INTO listening_history (
    id, user_id, track_id, started_at, ended_at,
    listened_duration_ms, last_position_ms, source, source_id, device_type
) VALUES
(1, 1, '6a8230b53cccfc45cd626ced', '2026-09-10 08:00:00', '2026-09-10 08:05:38', 338390, 338390, 'HOME', NULL, 'WEB'),
(2, 1, '6a8230b53cccfc45cd626cee', '2026-09-10 09:00:00', '2026-09-10 09:01:30', 90000, 90000, 'SEARCH', NULL, 'ANDROID'),
(3, 2, '6a8230b53cccfc45cd626cef', '2026-09-11 10:00:00', NULL, 60000, 60000, 'PLAYLIST', '9d8230b53cccfc45cd626001', 'IOS'),
(4, 1, '6a8230b53cccfc45cd626cf0', '2026-09-12 20:00:00', '2026-09-12 20:03:40', 220000, 220000, 'ALBUM', '8c8230b53cccfc45cd626001', 'WEB'),
(5, 2, '6a8230b53cccfc45cd626cf1', '2026-09-13 21:00:00', '2026-09-13 21:02:00', 120000, 120000, 'ARTIST', '7b8230b53cccfc45cd626001', 'OTHER'),
(6, 1, '6a8230b53cccfc45cd626ced', '2026-09-14 07:30:00', '2026-09-14 07:32:30', 150000, 150000, 'LIBRARY', NULL, 'ANDROID'),
(7, 1, '6a8230b53cccfc45cd626cee', '2026-09-14 08:00:00', '2026-09-14 08:04:00', 240000, 240000, 'FAVORITES', NULL, 'IOS'),
(8, 1, '6a8230b53cccfc45cd626cf0', '2026-09-14 20:00:00', '2026-09-14 20:03:00', 180000, 180000, 'EMOTION', NULL, 'WEB'),
(9, 2, '6a8230b53cccfc45cd626cf1', '2026-09-15 06:30:00', '2026-09-15 06:31:00', 60000, 60000, 'OTHER', NULL, 'ANDROID');

-- ============================================================
-- 7. PLAYBACK_EVENTS
-- Bao phủ toàn bộ 7 event_type.
-- ============================================================
INSERT INTO playback_events (
    id, listening_history_id, event_type, position_ms, target_position_ms, occurred_at
) VALUES
(1, 1, 'PLAY', 0, NULL, '2026-09-10 08:00:00'),
(2, 2, 'PAUSE', 45000, NULL, '2026-09-10 09:00:45'),
(3, 2, 'RESUME', 45000, NULL, '2026-09-10 09:01:00'),
(4, 3, 'SEEK', 30000, 60000, '2026-09-11 10:00:30'),
(5, 4, 'SKIP_NEXT', 45000, NULL, '2026-09-12 20:00:45'),
(6, 5, 'SKIP_PREVIOUS', 15000, NULL, '2026-09-13 21:00:15'),
(7, 1, 'COMPLETE', 338390, NULL, '2026-09-10 08:05:38');

-- ============================================================
-- 8. SEARCH_HISTORY
-- Bao phủ toàn bộ search_type và các selected_target_type.
-- ============================================================
INSERT INTO search_history (
    id, user_id, keyword, search_type, detected_emotion, emotion_confidence,
    selected_target_type, selected_target_id, searched_at
) VALUES
(1, 1, 'Teeme', 'ALL', NULL, NULL, 'ARTIST', '7b8230b53cccfc45cd626001', '2026-09-10 07:30:00'),
(2, 1, 'TINH VE', 'TRACK', NULL, NULL, 'TRACK', '6a8230b53cccfc45cd626ced', '2026-09-10 08:30:00'),
(3, 2, 'Hoaprox', 'ARTIST', NULL, NULL, 'ARTIST', '7b8230b53cccfc45cd626002', '2026-09-11 09:00:00'),
(4, 1, 'TINH VE Album', 'ALBUM', NULL, NULL, 'ALBUM', '8c8230b53cccfc45cd626001', '2026-09-12 10:00:00'),
(5, 2, 'Nhạc chill học bài', 'PLAYLIST', NULL, NULL, 'PLAYLIST', '9d8230b53cccfc45cd626001', '2026-09-13 20:00:00'),
(6, 1, 'Hôm nay tôi cảm thấy rất buồn và cô đơn', 'EMOTION', 'SADNESS', 0.9234, 'TRACK', '6a8230b53cccfc45cd626cf0', '2026-09-14 19:50:00');

-- ============================================================
-- 9. SERVICE_PACKAGES
-- Bao phủ: miễn phí/trả phí, nhiều thời hạn, ACTIVE/INACTIVE.
-- ============================================================
INSERT INTO service_packages (
    id, name, description, price, duration_days, display_order, status, created_at, updated_at
) VALUES
(1, 'Moodify Trial 7 Days', 'Gói dùng thử miễn phí trong 7 ngày.', 0, 7, 1, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(2, 'Premium 30 Days', 'Gói Premium trong 30 ngày.', 59000, 30, 2, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(3, 'Premium 90 Days', 'Gói Premium trong 90 ngày.', 149000, 90, 3, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(4, 'Artist Pro 365 Days', 'Gói chuyên nghiệp dành cho Artist trong 365 ngày.', 599000, 365, 4, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(5, 'Legacy 45 Days', 'Gói cũ đã ngừng cho đăng ký mới.', 99000, 45, 5, 'INACTIVE', '2026-01-01 00:00:00', '2026-08-01 00:00:00');

-- ============================================================
-- 10. SUBSCRIPTIONS
-- Bao phủ toàn bộ 5 status.
-- ============================================================
INSERT INTO subscriptions (
    id, user_id, service_package_id, start_at, end_at,
    auto_renew, status, created_at, updated_at
) VALUES
(1, 1, 2, '2026-09-15 10:00:00', '2026-10-15 10:00:00', FALSE, 'PENDING', '2026-09-15 09:55:00', '2026-09-15 09:55:00'),
(2, 1, 3, '2026-09-01 00:00:00', '2026-11-30 00:00:00', TRUE, 'ACTIVE', '2026-09-01 00:00:00', '2026-09-01 00:02:00'),
(3, 2, 4, '2025-01-01 00:00:00', '2026-01-01 00:00:00', FALSE, 'EXPIRED', '2025-01-01 00:00:00', '2026-01-01 00:00:00'),
(4, 2, 2, '2026-08-01 00:00:00', '2026-08-31 00:00:00', FALSE, 'CANCELLED', '2026-08-01 00:00:00', '2026-08-15 10:00:00'),
(5, 5, 2, '2026-09-01 00:00:00', '2026-10-01 00:00:00', FALSE, 'SUSPENDED', '2026-09-01 00:00:00', '2026-09-10 14:00:00');

-- ============================================================
-- 11. PAYMENT_TRANSACTIONS
-- Bao phủ toàn bộ 5 status và nhiều phương thức/provider.
-- ============================================================
INSERT INTO payment_transactions (
    id, subscription_id, amount, payment_method, provider,
    provider_transaction_id, status, paid_at, created_at, updated_at
) VALUES
(1, 1, 59000, 'QR', 'VNPAY', NULL, 'PENDING', NULL, '2026-09-15 09:55:00', '2026-09-15 09:55:00'),
(2, 2, 149000, 'EWALLET', 'MOMO', 'MOMO-20260901-00001', 'SUCCESS', '2026-09-01 00:02:00', '2026-09-01 00:00:00', '2026-09-01 00:02:00'),
(3, 3, 599000, 'CARD', 'VNPAY', 'VNPAY-20250101-00003', 'FAILED', NULL, '2025-01-01 10:00:00', '2025-01-01 10:01:00'),
(4, 4, 59000, 'BANK_TRANSFER', 'MOMO', 'MOMO-20260801-00004', 'CANCELLED', NULL, '2026-08-01 10:00:00', '2026-08-01 10:05:00'),
(5, 5, 59000, 'CARD', 'VNPAY', 'VNPAY-20260901-00005', 'REFUNDED', '2026-09-01 00:05:00', '2026-09-01 00:00:00', '2026-09-10 15:00:00');

-- ============================================================
-- 12. USER_DEVICES
-- Bao phủ ANDROID / IOS / OTHER và ACTIVE / REVOKED.
-- ============================================================
INSERT INTO user_devices (
    id, user_id, device_uuid, platform, device_name, status, created_at
) VALUES
(1, 1, 'android-user1-phone-001', 'ANDROID', 'Samsung Galaxy S24', 'ACTIVE', '2026-05-01 08:00:00'),
(2, 1, 'ios-user1-phone-002', 'IOS', 'iPhone 15', 'ACTIVE', '2026-06-01 08:00:00'),
(3, 2, 'android-artist-phone-003', 'ANDROID', 'Google Pixel 9', 'ACTIVE', '2026-07-01 08:00:00'),
(4, 2, 'other-artist-device-004', 'OTHER', 'Android Tablet', 'REVOKED', '2026-05-15 08:00:00'),
(5, 5, 'android-banned-phone-005', 'ANDROID', 'Xiaomi 14', 'ACTIVE', '2026-08-01 08:00:00');

-- ============================================================
-- 13. OFFLINE_DOWNLOADS
-- Bao phủ toàn bộ 6 status.
-- ============================================================
INSERT INTO offline_downloads (
    id, device_id, track_id, status, local_path,
    downloaded_at, expires_at, last_verified_at, created_at, updated_at
) VALUES
(1, 1, '6a8230b53cccfc45cd626ced', 'QUEUED', NULL, NULL, '2026-10-15 00:00:00', NULL, '2026-09-15 08:00:00', '2026-09-15 08:00:00'),
(2, 2, '6a8230b53cccfc45cd626cee', 'DOWNLOADING', '/Documents/Moodify/6a8230b53cccfc45cd626cee.m4a', NULL, '2026-10-15 00:00:00', '2026-09-15 08:30:00', '2026-09-15 08:20:00', '2026-09-15 08:30:00'),
(3, 3, '6a8230b53cccfc45cd626cef', 'DOWNLOADED', '/storage/emulated/0/Moodify/6a8230b53cccfc45cd626cef.mp3', '2026-09-13 10:00:00', '2026-10-13 10:00:00', '2026-09-15 08:00:00', '2026-09-13 09:58:00', '2026-09-15 08:00:00'),
(4, 5, '6a8230b53cccfc45cd626cf0', 'FAILED', NULL, NULL, NULL, '2026-09-10 10:00:00', '2026-09-10 09:55:00', '2026-09-10 10:00:00'),
(5, 1, '6a8230b53cccfc45cd626cf1', 'EXPIRED', '/storage/emulated/0/Moodify/6a8230b53cccfc45cd626cf1.mp3', '2026-07-01 10:00:00', '2026-08-01 10:00:00', '2026-08-02 10:00:00', '2026-07-01 09:55:00', '2026-08-02 10:00:00'),
(6, 4, '6a8230b53cccfc45cd626ced', 'REMOVED', NULL, '2026-07-10 10:00:00', NULL, '2026-07-20 10:00:00', '2026-07-10 09:55:00', '2026-07-20 10:00:00');

-- ============================================================
-- 14. CONTENT_REVIEW_REQUESTS
-- Bao phủ toàn bộ 5 status, TRACK/ALBUM, PUBLISH/UPDATE.
-- ============================================================
INSERT INTO content_review_requests (
    id, artist_user_id, content_type, content_id,
    request_type, status, submitted_at, resolved_at
) VALUES
(1, 2, 'TRACK', '6a8230b53cccfc45cd626d10', 'PUBLISH', 'PENDING', '2026-09-15 08:00:00', NULL),
(2, 2, 'TRACK', '6a8230b53cccfc45cd626d11', 'UPDATE', 'IN_REVIEW', '2026-09-14 08:00:00', NULL),
(3, 2, 'ALBUM', '8c8230b53cccfc45cd626010', 'PUBLISH', 'APPROVED', '2026-09-10 08:00:00', '2026-09-11 10:00:00'),
(4, 2, 'TRACK', '6a8230b53cccfc45cd626d12', 'PUBLISH', 'REJECTED', '2026-09-09 08:00:00', '2026-09-10 15:00:00'),
(5, 2, 'ALBUM', '8c8230b53cccfc45cd626011', 'UPDATE', 'CANCELLED', '2026-09-08 08:00:00', '2026-09-08 12:00:00');

-- ============================================================
-- 15. CONTENT_REVIEW_ACTIONS
-- Bao phủ START_REVIEW / APPROVE / REJECT / RETURN_FOR_EDIT.
-- ============================================================
INSERT INTO content_review_actions (
    id, review_request_id, moderator_user_id, action, reason, created_at
) VALUES
(1, 2, 3, 'START_REVIEW', NULL, '2026-09-14 09:00:00'),
(2, 3, 3, 'START_REVIEW', NULL, '2026-09-10 09:00:00'),
(3, 3, 3, 'APPROVE', 'Nội dung đạt yêu cầu kiểm duyệt.', '2026-09-11 10:00:00'),
(4, 4, 3, 'RETURN_FOR_EDIT', 'Ảnh bìa chưa đáp ứng quy định.', '2026-09-09 12:00:00'),
(5, 4, 3, 'REJECT', 'Nội dung chỉnh sửa vẫn chưa đáp ứng yêu cầu.', '2026-09-10 15:00:00');

-- ============================================================
-- 16. DISTRIBUTORS
-- Bao phủ ACTIVE / INACTIVE, nhiều quốc gia, contact NULL/non-NULL.
-- ============================================================
INSERT INTO distributors (
    id, company_name, country, contact_name, contact_email,
    contact_phone, status, created_at, updated_at
) VALUES
(1, 'Universal Music Vietnam', 'Vietnam', 'Nguyễn Quốc Anh', 'contact1@distributor.local', '02830000001', 'ACTIVE', '2025-01-01 08:00:00', '2026-09-01 08:00:00'),
(2, 'Believe Digital Vietnam', 'Vietnam', 'Trần Minh Đức', 'contact2@distributor.local', '02830000002', 'ACTIVE', '2025-02-01 08:00:00', '2026-09-01 08:00:00'),
(3, 'Independent Distribution Co.', 'Singapore', 'Lê Hoàng Nam', 'contact3@distributor.local', '02830000003', 'INACTIVE', '2025-03-01 08:00:00', '2026-07-01 08:00:00'),
(4, 'Vietnam Music Distribution', 'Vietnam', NULL, 'contact4@distributor.local', NULL, 'ACTIVE', '2025-04-01 08:00:00', '2026-09-01 08:00:00'),
(5, 'Global Sound Distribution', 'United States', 'Phạm Quang Huy', NULL, '02830000005', 'ACTIVE', '2025-05-01 08:00:00', '2026-09-01 08:00:00');

-- ============================================================
-- 17. DISTRIBUTION_CONTRACTS
-- Bao phủ DRAFT / ACTIVE / EXPIRED / TERMINATED,
-- revenue_share NULL/non-NULL, document_url NULL/non-NULL.
-- revenue_share ở đây dùng theo đơn vị phần trăm (ví dụ 70 = 70%).
-- ============================================================
INSERT INTO distribution_contracts (
    id, distributor_id, contract_code, title, signed_date,
    effective_from, effective_to, revenue_share, status,
    document_url, created_at, updated_at
) VALUES
(1, 1, 'DIST-2026-001', 'Hợp đồng phân phối Universal 2026', '2026-01-01', '2026-01-01', '2026-12-31', 70.0, 'ACTIVE', 'https://example.com/contracts/DIST-2026-001.pdf', '2026-01-01 08:00:00', '2026-01-01 08:00:00'),
(2, 2, 'DIST-2026-002', 'Hợp đồng đang soạn thảo', '2026-08-01', NULL, NULL, NULL, 'DRAFT', NULL, '2026-08-01 08:00:00', '2026-08-01 08:00:00'),
(3, 3, 'DIST-2025-003', 'Hợp đồng phân phối năm 2025', '2025-01-01', '2025-01-01', '2025-12-31', 65.0, 'EXPIRED', 'https://example.com/contracts/DIST-2025-003.pdf', '2025-01-01 08:00:00', '2026-01-01 08:00:00'),
(4, 4, 'DIST-2026-004', 'Hợp đồng chấm dứt trước hạn', '2026-01-10', '2026-02-01', '2026-08-01', 60.0, 'TERMINATED', 'https://example.com/contracts/DIST-2026-004.pdf', '2026-01-10 08:00:00', '2026-08-01 08:00:00'),
(5, 5, 'DIST-2026-005', 'Hợp đồng Global Sound', '2026-06-01', '2026-06-01', NULL, 75.0, 'ACTIVE', 'https://example.com/contracts/DIST-2026-005.pdf', '2026-06-01 08:00:00', '2026-06-01 08:00:00');

-- ============================================================
-- 18. SONG_LICENSES
-- Bao phủ ACTIVE / EXPIRED / REVOKED / PENDING,
-- distributor + contract, distributor không contract,
-- copyright_owner độc lập, document URL NULL/non-NULL.
-- ============================================================
INSERT INTO song_licenses (
    id, track_id, distributor_id, distribution_contract_id,
    license_type, copyright_owner, issue_date, expiry_date,
    status, document_songlicenses_url, created_at, updated_at
) VALUES
(1, '6a8230b53cccfc45cd626ced', 1, 1, 'DIGITAL_STREAMING', NULL, '2026-01-01', '2026-12-31', 'ACTIVE', 'https://example.com/licenses/license-001.pdf', '2026-01-01 08:00:00', '2026-01-01 08:00:00'),
(2, '6a8230b53cccfc45cd626cee', 3, 3, 'DIGITAL_STREAMING', NULL, '2025-01-01', '2025-12-31', 'EXPIRED', 'https://example.com/licenses/license-002.pdf', '2025-01-01 08:00:00', '2026-01-01 08:00:00'),
(3, '6a8230b53cccfc45cd626cef', 4, 4, 'MASTER_LICENSE', NULL, '2026-02-01', '2026-08-01', 'REVOKED', 'https://example.com/licenses/license-003.pdf', '2026-02-01 08:00:00', '2026-08-01 08:00:00'),
(4, '6a8230b53cccfc45cd626cf0', 2, NULL, 'STREAMING_PENDING', NULL, NULL, NULL, 'PENDING', NULL, '2026-09-10 08:00:00', '2026-09-10 08:00:00'),
(5, '6a8230b53cccfc45cd626cf1', NULL, NULL, 'DIRECT_LICENSE', 'Independent Artist', '2026-06-01', NULL, 'ACTIVE', NULL, '2026-06-01 08:00:00', '2026-06-01 08:00:00');


USE moodify;
UPDATE users 
SET password = '$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS'
WHERE username IN ('artist01', 'listener01', 'moderator01', 'admin01');
