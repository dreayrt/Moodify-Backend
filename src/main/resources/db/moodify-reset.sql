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

    staff_code VARCHAR(50) NULL UNIQUE,
    artist_spotify_id VARCHAR(80) NULL UNIQUE,

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
    completed BOOLEAN NOT NULL DEFAULT FALSE,
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
CREATE TABLE service_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL,
    eligible_role ENUM('ALL','USER','ARTIST') NOT NULL DEFAULT 'ALL',
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    billing_cycle ENUM('FREE','MONTHLY','QUARTERLY','YEARLY','CUSTOM') NOT NULL DEFAULT 'MONTHLY',
    duration_days INT UNSIGNED NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT chk_service_plan_price CHECK (price >= 0),
    CONSTRAINT chk_service_plan_duration CHECK (duration_days > 0),

    INDEX idx_service_plan_role_status (eligible_role, status),
    INDEX idx_service_plan_display (display_order)
) ;

-- ============================================================
-- 11. DANH MỤC QUYỀN LỢI GÓI
-- Danh mục các quyền lợi có thể cấu hình cho gói dịch vụ.
-- ============================================================
CREATE TABLE plan_features (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    value_type ENUM('BOOLEAN','INTEGER','DECIMAL','STRING') NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ;

-- ============================================================
-- 12. QUYỀN LỢI CỦA TỪNG GÓI DỊCH VỤ
-- Bảng trung gian thể hiện quan hệ N:M giữa gói dịch vụ và quyền lợi.
-- ============================================================
CREATE TABLE service_plan_features (
    service_plan_id BIGINT NOT NULL,
    feature_id BIGINT NOT NULL,
    feature_value VARCHAR(255) NOT NULL,

    PRIMARY KEY (service_plan_id, feature_id),

    CONSTRAINT fk_plan_feature_plan
        FOREIGN KEY (service_plan_id) REFERENCES service_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_feature_feature
        FOREIGN KEY (feature_id) REFERENCES plan_features(id) ON DELETE CASCADE
) ;

-- ============================================================
-- 13. ĐĂNG KÝ/GIA HẠN GÓI
-- Ánh xạ Subscription trong sơ đồ lớp.
-- Dữ liệu thanh toán được tách riêng sang bảng payment_transactions.
-- ============================================================
CREATE TABLE subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    service_plan_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    status ENUM('PENDING','ACTIVE','EXPIRED','CANCELLED','SUSPENDED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_subscription_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_subscription_plan
        FOREIGN KEY (service_plan_id) REFERENCES service_plans(id),
    CONSTRAINT chk_subscription_dates CHECK (end_at > start_at),

    INDEX idx_subscription_user_status (user_id, status),
    INDEX idx_subscription_end (end_at),
    INDEX idx_subscription_plan (service_plan_id)
) ;

-- ============================================================
-- 14. GIAO DỊCH THANH TOÁN
-- Ánh xạ PaymentTransaction và hỗ trợ các cổng thanh toán online.
-- ============================================================
CREATE TABLE payment_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    payment_method VARCHAR(50) NULL,
    provider VARCHAR(50) NULL,
    provider_transaction_id VARCHAR(150) NULL,
    status ENUM('PENDING','SUCCESS','FAILED','CANCELLED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(500) NULL,
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
    last_seen_at DATETIME NULL,
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
    failure_reason VARCHAR(500) NULL,
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
    document_url VARCHAR(1000) NULL,
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




-- ============================================================
-- 1. USERS
-- Bao phủ:
-- USER ACTIVE
-- ARTIST ACTIVE
-- MODERATOR ACTIVE
-- ADMIN INACTIVE
-- USER BANNED
-- Password tất cả: 123456 (BCrypt, mỗi tài khoản có salt riêng)
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
    staff_code,
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
    '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
    'https://example.com/avatar/listener01.jpg',
    'USER',
    NULL,
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
    '$2a$10$cfoKF27VfesaUbZAAcgDguuODqQ3zPn4sBU17L1fX5yluWF00ZRLe',
    'https://example.com/avatar/artist01.jpg',
    'ARTIST',
    NULL,
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
    '$2a$10$5jHqWqUyerKgXloyr.nfHeACPVCJV3WIwtNALKGbe6f5VZoeu/R5G',
    'https://example.com/avatar/moderator01.jpg',
    'MODERATOR',
    'MOD001',
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
    '$2a$10$jJE7xv4iexMOyQtuBo8b1.n8VdiO8ACJtP52twsOF7bQR95aqcZxu',
    'https://example.com/avatar/admin01.jpg',
    'ADMIN',
    'ADM001',
    NULL,
    'INACTIVE',
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
    '$2a$10$k1Zd06qhV2HF4/RoW/5jXux6di7OKUiKrce2hJZDaZWpZ.pHy/CO.',
    NULL,
    'USER',
    NULL,
    NULL,
    'BANNED',
    NULL,
    '2026-04-01 08:00:00',
    '2026-09-10 14:00:00'
);


-- ============================================================
-- 2. USER LIBRARY TRACKS
-- ============================================================

INSERT INTO user_library_tracks (
    id,
    user_id,
    track_id,
    added_at
)
VALUES
(1, 1, '66a000000000000000000001', '2026-09-01 10:00:00'),
(2, 1, '66a000000000000000000002', '2026-09-02 11:00:00'),
(3, 2, '66a000000000000000000003', '2026-09-03 12:00:00'),
(4, 2, '66a000000000000000000004', '2026-09-04 13:00:00'),
(5, 5, '66a000000000000000000005', '2026-09-05 14:00:00');


-- ============================================================
-- 3. FAVORITE SONGS
-- ============================================================

INSERT INTO favorite_songs (
    id,
    user_id,
    track_id,
    created_at
)
VALUES
(1, 1, '66a000000000000000000001', '2026-09-05 08:00:00'),
(2, 1, '66a000000000000000000003', '2026-09-06 08:30:00'),
(3, 2, '66a000000000000000000002', '2026-09-07 09:00:00'),
(4, 2, '66a000000000000000000005', '2026-09-08 09:30:00'),
(5, 5, '66a000000000000000000004', '2026-09-09 10:00:00');


-- ============================================================
-- 4. FAVORITE ARTISTS
-- ============================================================

INSERT INTO favorite_artists (
    id,
    user_id,
    artist_id,
    created_at
)
VALUES
(1, 1, '67b000000000000000000001', '2026-09-01 08:00:00'),
(2, 1, '67b000000000000000000002', '2026-09-02 08:00:00'),
(3, 2, '67b000000000000000000003', '2026-09-03 08:00:00'),
(4, 2, '67b000000000000000000004', '2026-09-04 08:00:00'),
(5, 5, '67b000000000000000000005', '2026-09-05 08:00:00');


-- ============================================================
-- 5. FAVORITE ALBUMS
-- ============================================================

INSERT INTO favorite_albums (
    id,
    user_id,
    album_id,
    created_at
)
VALUES
(1, 1, '68c000000000000000000001', '2026-09-01 09:00:00'),
(2, 1, '68c000000000000000000002', '2026-09-02 09:00:00'),
(3, 2, '68c000000000000000000003', '2026-09-03 09:00:00'),
(4, 2, '68c000000000000000000004', '2026-09-04 09:00:00'),
(5, 5, '68c000000000000000000005', '2026-09-05 09:00:00');


-- ============================================================
-- 6. LISTENING HISTORY
--
-- Case:
-- 1. Nghe hoàn tất
-- 2. Nghe dở
-- 3. Đang nghe
-- 4. Nghe từ emotion recommendation
-- 5. Nghe từ library
-- ============================================================

INSERT INTO listening_history (
    id,
    user_id,
    track_id,
    started_at,
    ended_at,
    listened_duration_ms,
    last_position_ms,
    completed,
    source,
    source_id,
    device_type
)
VALUES
(
    1,
    1,
    '66a000000000000000000001',
    '2026-09-10 08:00:00',
    '2026-09-10 08:04:00',
    240000,
    240000,
    TRUE,
    'HOME',
    NULL,
    'WEB'
),
(
    2,
    1,
    '66a000000000000000000002',
    '2026-09-10 09:00:00',
    '2026-09-10 09:01:30',
    90000,
    90000,
    FALSE,
    'SEARCH',
    NULL,
    'ANDROID'
),
(
    3,
    2,
    '66a000000000000000000003',
    '2026-09-11 10:00:00',
    NULL,
    60000,
    60000,
    FALSE,
    'PLAYLIST',
    '69d000000000000000000001',
    'IOS'
),
(
    4,
    1,
    '66a000000000000000000004',
    '2026-09-12 20:00:00',
    '2026-09-12 20:03:40',
    220000,
    220000,
    TRUE,
    'EMOTION',
    NULL,
    'WEB'
),
(
    5,
    5,
    '66a000000000000000000005',
    '2026-09-13 21:00:00',
    '2026-09-13 21:02:00',
    120000,
    120000,
    FALSE,
    'LIBRARY',
    NULL,
    'OTHER'
);


-- ============================================================
-- 7. PLAYBACK EVENTS
--
-- Đại diện 5 event quan trọng:
-- PLAY
-- PAUSE
-- SEEK
-- SKIP_NEXT
-- COMPLETE
-- ============================================================

INSERT INTO playback_events (
    id,
    listening_history_id,
    event_type,
    position_ms,
    target_position_ms,
    occurred_at
)
VALUES
(
    1,
    1,
    'PLAY',
    0,
    NULL,
    '2026-09-10 08:00:00'
),
(
    2,
    2,
    'PAUSE',
    90000,
    NULL,
    '2026-09-10 09:01:30'
),
(
    3,
    3,
    'SEEK',
    30000,
    60000,
    '2026-09-11 10:01:00'
),
(
    4,
    4,
    'SKIP_NEXT',
    45000,
    NULL,
    '2026-09-12 20:00:45'
),
(
    5,
    1,
    'COMPLETE',
    240000,
    NULL,
    '2026-09-10 08:04:00'
);


-- ============================================================
-- 8. SEARCH HISTORY
--
-- Có tìm kiếm thường + tìm kiếm emotion.
-- ============================================================

INSERT INTO search_history (
    id,
    user_id,
    keyword,
    search_type,
    detected_emotion,
    emotion_confidence,
    selected_target_type,
    selected_target_id,
    searched_at
)
VALUES
(
    1,
    1,
    'Sơn Tùng M-TP',
    'ALL',
    NULL,
    NULL,
    'ARTIST',
    '67b000000000000000000001',
    '2026-09-10 07:30:00'
),
(
    2,
    1,
    'Túy Âm',
    'TRACK',
    NULL,
    NULL,
    'TRACK',
    '66a000000000000000000001',
    '2026-09-10 08:30:00'
),
(
    3,
    2,
    'Hoaprox',
    'ARTIST',
    NULL,
    NULL,
    'ARTIST',
    '67b000000000000000000002',
    '2026-09-11 09:00:00'
),
(
    4,
    1,
    'Hôm nay tôi cảm thấy rất buồn và cô đơn',
    'EMOTION',
    'SADNESS',
    0.9234,
    'TRACK',
    '66a000000000000000000004',
    '2026-09-12 19:50:00'
),
(
    5,
    2,
    'Nhạc chill học bài',
    'PLAYLIST',
    NULL,
    NULL,
    'PLAYLIST',
    '69d000000000000000000001',
    '2026-09-13 20:00:00'
);


-- ============================================================
-- 9. SERVICE PLANS
--
-- Bao phủ đủ:
-- FREE
-- MONTHLY
-- QUARTERLY
-- YEARLY
-- CUSTOM
-- ============================================================

INSERT INTO service_plans (
    id,
    code,
    name,
    description,
    eligible_role,
    price,
    currency,
    billing_cycle,
    duration_days,
    display_order,
    status,
    created_at,
    updated_at
)
VALUES
(
    1,
    'FREE',
    'Moodify Free',
    'Gói miễn phí cơ bản dành cho người dùng.',
    'ALL',
    0,
    'VND',
    'FREE',
    36500,
    1,
    'ACTIVE',
    '2026-01-01 00:00:00',
    '2026-01-01 00:00:00'
),
(
    2,
    'PREMIUM_MONTHLY',
    'Premium Monthly',
    'Gói Premium thanh toán theo tháng.',
    'USER',
    59000,
    'VND',
    'MONTHLY',
    30,
    2,
    'ACTIVE',
    '2026-01-01 00:00:00',
    '2026-01-01 00:00:00'
),
(
    3,
    'PREMIUM_QUARTERLY',
    'Premium Quarterly',
    'Gói Premium thanh toán theo quý.',
    'USER',
    149000,
    'VND',
    'QUARTERLY',
    90,
    3,
    'ACTIVE',
    '2026-01-01 00:00:00',
    '2026-01-01 00:00:00'
),
(
    4,
    'ARTIST_YEARLY',
    'Artist Pro Yearly',
    'Gói chuyên nghiệp dành cho Artist.',
    'ARTIST',
    599000,
    'VND',
    'YEARLY',
    365,
    4,
    'ACTIVE',
    '2026-01-01 00:00:00',
    '2026-01-01 00:00:00'
),
(
    5,
    'LEGACY_CUSTOM',
    'Legacy Custom',
    'Gói custom cũ đã ngừng đăng ký.',
    'ALL',
    99000,
    'VND',
    'CUSTOM',
    45,
    5,
    'INACTIVE',
    '2026-01-01 00:00:00',
    '2026-08-01 00:00:00'
);


-- ============================================================
-- 10. PLAN FEATURES
--
-- Bao phủ:
-- BOOLEAN
-- INTEGER
-- DECIMAL
-- STRING
-- ============================================================

INSERT INTO plan_features (
    id,
    code,
    name,
    description,
    value_type,
    created_at
)
VALUES
(
    1,
    'AD_FREE',
    'Không quảng cáo',
    'Cho phép nghe nhạc không bị gián đoạn bởi quảng cáo.',
    'BOOLEAN',
    '2026-01-01 00:00:00'
),
(
    2,
    'OFFLINE_DOWNLOAD_LIMIT',
    'Giới hạn tải offline',
    'Số lượng bài hát được phép tải về thiết bị.',
    'INTEGER',
    '2026-01-01 00:00:00'
),
(
    3,
    'AUDIO_QUALITY',
    'Chất lượng âm thanh',
    'Mức chất lượng âm thanh tối đa.',
    'STRING',
    '2026-01-01 00:00:00'
),
(
    4,
    'ARTIST_UPLOAD_LIMIT',
    'Giới hạn upload Artist',
    'Số bài hát Artist được phép upload.',
    'INTEGER',
    '2026-01-01 00:00:00'
),
(
    5,
    'REVENUE_MULTIPLIER',
    'Hệ số thử nghiệm',
    'Giá trị decimal dùng để kiểm thử feature dạng số thực.',
    'DECIMAL',
    '2026-01-01 00:00:00'
);


-- ============================================================
-- 11. SERVICE PLAN FEATURES
-- Chính xác 5 record N:M
-- ============================================================

INSERT INTO service_plan_features (
    service_plan_id,
    feature_id,
    feature_value
)
VALUES
(1, 1, 'false'),
(2, 1, 'true'),
(2, 2, '100'),
(3, 3, 'HIGH'),
(4, 4, '500');


-- ============================================================
-- 12. SUBSCRIPTIONS
--
-- Bao phủ toàn bộ 5 status:
-- PENDING
-- ACTIVE
-- EXPIRED
-- CANCELLED
-- SUSPENDED
-- ============================================================

INSERT INTO subscriptions (
    id,
    user_id,
    service_plan_id,
    start_at,
    end_at,
    auto_renew,
    status,
    created_at,
    updated_at
)
VALUES
(
    1,
    1,
    2,
    '2026-09-14 10:00:00',
    '2026-10-14 10:00:00',
    FALSE,
    'PENDING',
    '2026-09-14 09:55:00',
    '2026-09-14 09:55:00'
),
(
    2,
    1,
    3,
    '2026-09-01 00:00:00',
    '2026-11-30 23:59:59',
    TRUE,
    'ACTIVE',
    '2026-09-01 00:00:00',
    '2026-09-01 00:00:00'
),
(
    3,
    2,
    4,
    '2025-01-01 00:00:00',
    '2025-12-31 23:59:59',
    FALSE,
    'EXPIRED',
    '2025-01-01 00:00:00',
    '2026-01-01 00:00:00'
),
(
    4,
    2,
    2,
    '2026-08-01 00:00:00',
    '2026-08-31 23:59:59',
    FALSE,
    'CANCELLED',
    '2026-08-01 00:00:00',
    '2026-08-15 10:00:00'
),
(
    5,
    5,
    2,
    '2026-09-01 00:00:00',
    '2026-10-01 00:00:00',
    FALSE,
    'SUSPENDED',
    '2026-09-01 00:00:00',
    '2026-09-10 14:00:00'
);


-- ============================================================
-- 13. PAYMENT TRANSACTIONS
--
-- Bao phủ đủ:
-- PENDING
-- SUCCESS
-- FAILED
-- CANCELLED
-- REFUNDED
-- ============================================================

INSERT INTO payment_transactions (
    id,
    subscription_id,
    amount,
    currency,
    payment_method,
    provider,
    provider_transaction_id,
    status,
    failure_reason,
    paid_at,
    created_at,
    updated_at
)
VALUES
(
    1,
    1,
    59000,
    'VND',
    'QR',
    'VNPAY',
    NULL,
    'PENDING',
    NULL,
    NULL,
    '2026-09-14 09:55:00',
    '2026-09-14 09:55:00'
),
(
    2,
    2,
    149000,
    'VND',
    'QR',
    'MOMO',
    'MOMO-20260901-00001',
    'SUCCESS',
    NULL,
    '2026-09-01 00:02:00',
    '2026-09-01 00:00:00',
    '2026-09-01 00:02:00'
),
(
    3,
    3,
    599000,
    'VND',
    'CARD',
    'VNPAY',
    'VNPAY-20250101-00003',
    'FAILED',
    'Thẻ bị từ chối bởi ngân hàng.',
    NULL,
    '2025-01-01 10:00:00',
    '2025-01-01 10:01:00'
),
(
    4,
    4,
    59000,
    'VND',
    'QR',
    'MOMO',
    'MOMO-20260801-00004',
    'CANCELLED',
    'Người dùng hủy giao dịch.',
    NULL,
    '2026-08-01 10:00:00',
    '2026-08-01 10:05:00'
),
(
    5,
    5,
    59000,
    'VND',
    'CARD',
    'VNPAY',
    'VNPAY-20260901-00005',
    'REFUNDED',
    'Hoàn tiền sau khi subscription bị đình chỉ.',
    '2026-09-01 00:05:00',
    '2026-09-01 00:00:00',
    '2026-09-10 15:00:00'
);


-- ============================================================
-- 14. USER DEVICES
--
-- Bao phủ:
-- ANDROID
-- IOS
-- OTHER
-- ACTIVE
-- REVOKED
-- ============================================================

INSERT INTO user_devices (
    id,
    user_id,
    device_uuid,
    platform,
    device_name,
    status,
    last_seen_at,
    created_at
)
VALUES
(
    1,
    1,
    'android-user1-phone-001',
    'ANDROID',
    'Samsung Galaxy S24',
    'ACTIVE',
    '2026-09-14 09:00:00',
    '2026-05-01 08:00:00'
),
(
    2,
    1,
    'ios-user1-phone-002',
    'IOS',
    'iPhone 15',
    'ACTIVE',
    '2026-09-13 22:00:00',
    '2026-06-01 08:00:00'
),
(
    3,
    2,
    'android-artist-phone-003',
    'ANDROID',
    'Google Pixel 9',
    'ACTIVE',
    '2026-09-14 08:30:00',
    '2026-07-01 08:00:00'
),
(
    4,
    2,
    'other-artist-device-004',
    'OTHER',
    'Android Tablet',
    'REVOKED',
    '2026-08-20 10:00:00',
    '2026-05-15 08:00:00'
),
(
    5,
    5,
    'android-banned-phone-005',
    'ANDROID',
    'Xiaomi 14',
    'ACTIVE',
    '2026-09-10 13:50:00',
    '2026-08-01 08:00:00'
);


-- ============================================================
-- 15. OFFLINE DOWNLOADS
--
-- Có 6 status nhưng yêu cầu 5 record.
-- Sử dụng 5 case:
-- QUEUED
-- DOWNLOADING
-- DOWNLOADED
-- FAILED
-- EXPIRED
--
-- REMOVED chưa thể hiện do giới hạn 5 dòng.
-- ============================================================

INSERT INTO offline_downloads (
    id,
    device_id,
    track_id,
    status,
    local_path,
    downloaded_at,
    expires_at,
    last_verified_at,
    failure_reason,
    created_at,
    updated_at
)
VALUES
(
    1,
    1,
    '66a000000000000000000001',
    'QUEUED',
    NULL,
    NULL,
    '2026-10-14 00:00:00',
    NULL,
    NULL,
    '2026-09-14 08:00:00',
    '2026-09-14 08:00:00'
),
(
    2,
    2,
    '66a000000000000000000002',
    'DOWNLOADING',
    '/Documents/Moodify/66a000000000000000000002.m4a',
    NULL,
    '2026-10-14 00:00:00',
    '2026-09-14 08:30:00',
    NULL,
    '2026-09-14 08:20:00',
    '2026-09-14 08:30:00'
),
(
    3,
    3,
    '66a000000000000000000003',
    'DOWNLOADED',
    '/storage/emulated/0/Moodify/66a000000000000000000003.mp3',
    '2026-09-13 10:00:00',
    '2026-10-13 10:00:00',
    '2026-09-14 08:00:00',
    NULL,
    '2026-09-13 09:58:00',
    '2026-09-14 08:00:00'
),
(
    4,
    5,
    '66a000000000000000000004',
    'FAILED',
    NULL,
    NULL,
    NULL,
    '2026-09-10 10:00:00',
    'Không đủ dung lượng lưu trữ.',
    '2026-09-10 09:55:00',
    '2026-09-10 10:00:00'
),
(
    5,
    1,
    '66a000000000000000000005',
    'EXPIRED',
    '/storage/emulated/0/Moodify/66a000000000000000000005.mp3',
    '2026-07-01 10:00:00',
    '2026-08-01 10:00:00',
    '2026-08-02 10:00:00',
    'Quyền nghe offline đã hết hạn.',
    '2026-07-01 09:55:00',
    '2026-08-02 10:00:00'
);


-- ============================================================
-- 16. CONTENT REVIEW REQUESTS
--
-- Bao phủ toàn bộ:
-- PENDING
-- IN_REVIEW
-- APPROVED
-- REJECTED
-- CANCELLED
--
-- content_type:
-- TRACK
-- ALBUM
--
-- request_type:
-- PUBLISH
-- UPDATE
-- ============================================================

INSERT INTO content_review_requests (
    id,
    artist_user_id,
    content_type,
    content_id,
    request_type,
    status,
    submitted_at,
    resolved_at
)
VALUES
(
    1,
    2,
    'TRACK',
    '66a000000000000000000010',
    'PUBLISH',
    'PENDING',
    '2026-09-14 08:00:00',
    NULL
),
(
    2,
    2,
    'TRACK',
    '66a000000000000000000011',
    'UPDATE',
    'IN_REVIEW',
    '2026-09-13 08:00:00',
    NULL
),
(
    3,
    2,
    'ALBUM',
    '68c000000000000000000010',
    'PUBLISH',
    'APPROVED',
    '2026-09-10 08:00:00',
    '2026-09-11 10:00:00'
),
(
    4,
    2,
    'TRACK',
    '66a000000000000000000012',
    'PUBLISH',
    'REJECTED',
    '2026-09-09 08:00:00',
    '2026-09-10 15:00:00'
),
(
    5,
    2,
    'ALBUM',
    '68c000000000000000000011',
    'UPDATE',
    'CANCELLED',
    '2026-09-08 08:00:00',
    '2026-09-08 12:00:00'
);


-- ============================================================
-- 17. CONTENT REVIEW ACTIONS
--
-- Bao phủ toàn bộ:
-- START_REVIEW
-- APPROVE
-- REJECT
-- RETURN_FOR_EDIT
-- ============================================================

INSERT INTO content_review_actions (
    id,
    review_request_id,
    moderator_user_id,
    action,
    reason,
    created_at
)
VALUES
(
    1,
    2,
    3,
    'START_REVIEW',
    NULL,
    '2026-09-13 09:00:00'
),
(
    2,
    3,
    3,
    'START_REVIEW',
    NULL,
    '2026-09-10 09:00:00'
),
(
    3,
    3,
    3,
    'APPROVE',
    'Nội dung đạt yêu cầu kiểm duyệt.',
    '2026-09-11 10:00:00'
),
(
    4,
    4,
    3,
    'RETURN_FOR_EDIT',
    'Ảnh bìa chưa đáp ứng quy định.',
    '2026-09-09 12:00:00'
),
(
    5,
    4,
    3,
    'REJECT',
    'Nội dung chỉnh sửa vẫn chưa đáp ứng yêu cầu.',
    '2026-09-10 15:00:00'
);


-- ============================================================
-- 18. DISTRIBUTORS
-- ============================================================

INSERT INTO distributors (
    id,
    company_name,
    contact_name,
    contact_email,
    contact_phone,
    status,
    created_at,
    updated_at
)
VALUES
(
    1,
    'Universal Music Vietnam',
    'Nguyễn Quốc Anh',
    'contact1@distributor.local',
    '02830000001',
    'ACTIVE',
    '2025-01-01 08:00:00',
    '2026-09-01 08:00:00'
),
(
    2,
    'Believe Digital Vietnam',
    'Trần Minh Đức',
    'contact2@distributor.local',
    '02830000002',
    'ACTIVE',
    '2025-02-01 08:00:00',
    '2026-09-01 08:00:00'
),
(
    3,
    'Independent Distribution Co.',
    'Lê Hoàng Nam',
    'contact3@distributor.local',
    '02830000003',
    'INACTIVE',
    '2025-03-01 08:00:00',
    '2026-07-01 08:00:00'
),
(
    4,
    'Vietnam Music Distribution',
    NULL,
    'contact4@distributor.local',
    NULL,
    'ACTIVE',
    '2025-04-01 08:00:00',
    '2026-09-01 08:00:00'
),
(
    5,
    'Global Sound Distribution',
    'Phạm Quang Huy',
    NULL,
    '02830000005',
    'ACTIVE',
    '2025-05-01 08:00:00',
    '2026-09-01 08:00:00'
);


-- ============================================================
-- 19. DISTRIBUTION CONTRACTS
--
-- Bao phủ:
-- DRAFT
-- ACTIVE
-- EXPIRED
-- TERMINATED
-- ============================================================

INSERT INTO distribution_contracts (
    id,
    distributor_id,
    contract_code,
    title,
    signed_date,
    effective_from,
    effective_to,
    status,
    document_url,
    created_at,
    updated_at
)
VALUES
(
    1,
    1,
    'DIST-2026-001',
    'Hợp đồng phân phối Universal 2026',
    '2026-01-01',
    '2026-01-01',
    '2026-12-31',
    'ACTIVE',
    'https://example.com/contracts/DIST-2026-001.pdf',
    '2026-01-01 08:00:00',
    '2026-01-01 08:00:00'
),
(
    2,
    2,
    'DIST-2026-002',
    'Hợp đồng đang soạn thảo',
    '2026-08-01',
    NULL,
    NULL,
    'DRAFT',
    NULL,
    '2026-08-01 08:00:00',
    '2026-08-01 08:00:00'
),
(
    3,
    3,
    'DIST-2025-003',
    'Hợp đồng phân phối năm 2025',
    '2025-01-01',
    '2025-01-01',
    '2025-12-31',
    'EXPIRED',
    'https://example.com/contracts/DIST-2025-003.pdf',
    '2025-01-01 08:00:00',
    '2026-01-01 08:00:00'
),
(
    4,
    4,
    'DIST-2026-004',
    'Hợp đồng chấm dứt trước hạn',
    '2026-01-10',
    '2026-02-01',
    '2026-08-01',
    'TERMINATED',
    'https://example.com/contracts/DIST-2026-004.pdf',
    '2026-01-10 08:00:00',
    '2026-08-01 08:00:00'
),
(
    5,
    5,
    'DIST-2026-005',
    'Hợp đồng Global Sound',
    '2026-06-01',
    '2026-06-01',
    NULL,
    'ACTIVE',
    'https://example.com/contracts/DIST-2026-005.pdf',
    '2026-06-01 08:00:00',
    '2026-06-01 08:00:00'
);


-- ============================================================
-- 20. SONG LICENSES
--
-- Bao phủ:
-- ACTIVE
-- EXPIRED
-- REVOKED
-- PENDING
--
-- Có cả trường hợp:
-- distributor + contract
-- distributor nhưng không contract
-- copyright_owner độc lập và không contract
-- ============================================================

INSERT INTO song_licenses (
    id,
    track_id,
    distributor_id,
    distribution_contract_id,
    license_type,
    copyright_owner,
    issue_date,
    expiry_date,
    status,
    document_url,
    created_at,
    updated_at
)
VALUES
(
    1,
    '66a000000000000000000001',
    1,
    1,
    'DIGITAL_STREAMING',
    NULL,
    '2026-01-01',
    '2026-12-31',
    'ACTIVE',
    'https://example.com/licenses/license-001.pdf',
    '2026-01-01 08:00:00',
    '2026-01-01 08:00:00'
),
(
    2,
    '66a000000000000000000002',
    3,
    3,
    'DIGITAL_STREAMING',
    NULL,
    '2025-01-01',
    '2025-12-31',
    'EXPIRED',
    'https://example.com/licenses/license-002.pdf',
    '2025-01-01 08:00:00',
    '2026-01-01 08:00:00'
),
(
    3,
    '66a000000000000000000003',
    4,
    4,
    'MASTER_LICENSE',
    NULL,
    '2026-02-01',
    '2026-08-01',
    'REVOKED',
    'https://example.com/licenses/license-003.pdf',
    '2026-02-01 08:00:00',
    '2026-08-01 08:00:00'
),
(
    4,
    '66a000000000000000000004',
    2,
    NULL,
    'STREAMING_PENDING',
    NULL,
    NULL,
    NULL,
    'PENDING',
    NULL,
    '2026-09-10 08:00:00',
    '2026-09-10 08:00:00'
),
(
    5,
    '66a000000000000000000005',
    NULL,
    NULL,
    'DIRECT_LICENSE',
    'Independent Artist',
    '2026-06-01',
    NULL,
    'ACTIVE',
    NULL,
    '2026-06-01 08:00:00',
    '2026-06-01 08:00:00'
);

SELECT id, username, role, status, created_at, updated_at
FROM users
ORDER BY id;
