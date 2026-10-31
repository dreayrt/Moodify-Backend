-- Moodify: lược đồ MySQL tham khảo, chỉ tạo cấu trúc; không chứa seed hoặc lệnh thử nghiệm.
-- Dành cho cơ sở dữ liệu trống. Với cơ sở dữ liệu đang có dữ liệu, dùng migration riêng.
-- Track/artist/album lưu tại MongoDB: các cột *_id tương ứng là tham chiếu logic,
-- không thể khai báo FOREIGN KEY MySQL sang MongoDB.
-- Bản này giữ cả revenue_playback_sessions và qualified_streams theo schema bạn cung cấp.
-- DATETIME(3) của hai bảng doanh thu dùng UTC; các cột thời gian cũ giữ nguyên quy ước hiện tại.
DROP DATABASE IF EXISTS moodify;

CREATE DATABASE moodify
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE moodify;

-- ============================================================
-- 1. NGƯỜI DÙNG
-- Ánh xạ lớp cha User và các lớp con phân biệt theo role trong sơ đồ lớp.
-- Admin / Listener (User) / Content Lead (Phụ trách nội dung) / ContentModerator được biểu diễn thông qua field role.
-- ============================================================
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NULL,
    avatar_url VARCHAR(500) NULL,
    role ENUM('USER','CONTENT_LEAD','MODERATOR','ADMIN') NOT NULL DEFAULT 'USER',
    artist_spotify_id VARCHAR(80) NULL UNIQUE,  -- Cột liên kết mã catalog / Spotify artist ID do Content Lead phụ trách
    status ENUM('ACTIVE','INACTIVE','BANNED') NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_users_role_status (role, status),
    INDEX idx_users_artist_spotify (artist_spotify_id),
    INDEX idx_users_created_at (created_at)
);

-- ============================================================
-- 2. THƯ VIỆN BÀI HÁT CÁ NHÂN
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
-- 3. BÀI HÁT YÊU THÍCH
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
-- 4. NGHỆ SĨ YÊU THÍCH
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
-- 5. ALBUM YÊU THÍCH
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
-- 6. LỊCH SỬ NGHE NHẠC
-- Ánh xạ lớp ListeningHistory trong sơ đồ lớp.
-- Lưu lịch sử phát để người dùng xem lại; không phải sổ doanh thu.
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

CREATE TABLE artist_profiles (
    artist_id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NULL,
    bio TEXT NULL,
    banner_url VARCHAR(500) NULL,
    facebook_url VARCHAR(255) NULL,
    instagram_url VARCHAR(255) NULL,
    youtube_url VARCHAR(255) NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_artist_profile_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE SET NULL,

    CONSTRAINT uk_artist_profile_user UNIQUE (user_id)
);

-- ============================================================
-- 6. ĐÁNH GIÁ & NHẬN XÉT NGHỆ SĨ (ARTIST REVIEWS)
-- Khán giả đánh giá 1-5 sao và để lại nhận xét; nghệ sĩ có thể phản hồi
-- ============================================================
CREATE TABLE artist_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    artist_id VARCHAR(80) NOT NULL,
    rating TINYINT NOT NULL,
    comment TEXT NULL,
    status ENUM('VISIBLE', 'HIDDEN') NOT NULL DEFAULT 'VISIBLE',
    artist_reply TEXT NULL,
    artist_replied_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_artist_review_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_artist_review_artist
        FOREIGN KEY (artist_id)
        REFERENCES artist_profiles(artist_id)
        ON DELETE CASCADE,

    CONSTRAINT uk_user_artist_review
        UNIQUE (user_id, artist_id),

    CONSTRAINT chk_review_rating
        CHECK (rating >= 1 AND rating <= 5),

    INDEX idx_artist_reviews_artist_status (artist_id, status),
    INDEX idx_artist_reviews_user (user_id)
);

-- ============================================================
-- 5. ALBUMS (NGƯỜI DÙNG / CONTENT LEAD TẠO)
-- Quản lý Album phát hành tự tạo trong hệ thống Moodify.
-- ============================================================
CREATE TABLE albums (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,                           -- Người tạo (User hoặc Content Lead)
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    cover_url VARCHAR(500) NULL,
    release_date DATE NULL,
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    status ENUM('ACTIVE', 'INACTIVE', 'ARCHIVED', 'DRAFT', 'PENDING', 'PUBLISHED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_album_creator
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    INDEX idx_albums_user (user_id),
    INDEX idx_albums_title (title),
    INDEX idx_albums_public_status (is_public, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 5.1 BÀI HÁT TRONG ALBUM
-- Liên kết Track (MongoDB) vào Album (MySQL)
-- ============================================================
CREATE TABLE album_tracks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    album_id BIGINT NOT NULL,
    track_id VARCHAR(80) NOT NULL,                     -- Mã track trong MongoDB
    track_order INT NOT NULL DEFAULT 1,
    added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_album_tracks_album
        FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    CONSTRAINT uk_album_track UNIQUE (album_id, track_id),

    INDEX idx_album_tracks_order (album_id, track_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================
-- 7. SỰ KIỆN PHÁT NHẠC
-- Ghi nhận Play / Pause / Resume / Seek / Next / Previous để quản lý phát nhạc và phục vụ thống kê.
-- Bản ghi phụ thuộc listening_history và sẽ bị xóa khi lịch sử nghe bị xóa.
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
-- 8. LỊCH SỬ TÌM KIẾM
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
-- 9. BẬC QUYỀN LỢI THUÊ BAO (SUBSCRIPTION TIERS)
-- Chuẩn hóa dữ liệu 3NF: Lưu trữ độc lập chính sách quảng cáo,
-- hạn ngạch chuyển bài, tải offline và số thiết bị.
-- ============================================================
CREATE TABLE subscription_tiers (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    ad_policy VARCHAR(20) NOT NULL DEFAULT 'NO_ADS',
    ad_free_daily_limit INT NOT NULL DEFAULT 0,
    skip_policy VARCHAR(20) NOT NULL DEFAULT 'UNLIMITED',
    skip_daily_limit INT NOT NULL DEFAULT 0,
    offline_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    offline_max_tracks INT NOT NULL DEFAULT 100,
    max_devices INT NOT NULL DEFAULT 1,
    synced_lyrics BOOLEAN NOT NULL DEFAULT TRUE,
    vip_badge BOOLEAN NOT NULL DEFAULT TRUE,
    family_sharing BOOLEAN NOT NULL DEFAULT FALSE,
    family_members INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    tier_id VARCHAR(50) NOT NULL DEFAULT 'INDIVIDUAL_BASIC',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT chk_service_package_price
        CHECK (price >= 0),
    CONSTRAINT chk_service_package_duration
        CHECK (duration_days > 0),
    CONSTRAINT fk_service_package_tier
        FOREIGN KEY (tier_id) REFERENCES subscription_tiers(id) ON UPDATE CASCADE,

    INDEX idx_service_package_status (status),
    INDEX idx_service_package_display (display_order),
    INDEX idx_service_package_tier (tier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 11. ĐĂNG KÝ/GIA HẠN GÓI
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
-- 12. GIAO DỊCH THANH TOÁN
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
-- MOODIFY OFFLINE DOWNLOAD SYSTEM MIGRATION
-- ============================================================

-- 1. Đảm bảo bảng offline_downloads tồn tại với đầy đủ cột và index
CREATE TABLE IF NOT EXISTS offline_downloads (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Bảng lưu trữ token cấp phép tải xuống một lần (one-time license token)
CREATE TABLE IF NOT EXISTS download_license_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(128) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    offline_download_id BIGINT NULL,
    encryption_key_hash VARCHAR(128) NOT NULL,
    status ENUM('ISSUED','USED','EXPIRED','REVOKED') NOT NULL DEFAULT 'ISSUED',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,

    INDEX idx_dlt_user (user_id),
    INDEX idx_dlt_token (token),
    INDEX idx_dlt_status_expiry (status, expires_at),
    CONSTRAINT fk_dlt_device FOREIGN KEY (device_id) REFERENCES user_devices(id) ON DELETE CASCADE,
    CONSTRAINT fk_dlt_download FOREIGN KEY (offline_download_id) REFERENCES offline_downloads(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Bảng audit log theo dõi và chống lạm dụng tải nhạc ngoại tuyến
CREATE TABLE IF NOT EXISTS offline_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    track_id VARCHAR(64) NULL,
    metadata JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_oal_user_action (user_id, action),
    INDEX idx_oal_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;



-- ============================================================
-- 15. YÊU CẦU KIỂM DUYỆT NỘI DUNG
-- Hỗ trợ Artist.submitForReview() và quy trình kiểm duyệt TRACK/ALBUM của Moderator.
-- Nội dung Track/Album thực tế vẫn được lưu trong MongoDB.
-- ============================================================
CREATE TABLE content_review_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artist_user_id BIGINT NOT NULL,
    content_type ENUM('TRACK','ALBUM') NOT NULL,
    content_id VARCHAR(64) NOT NULL,
    content_title VARCHAR(500) NULL,
    artist_name VARCHAR(255) NULL,
    content_genre VARCHAR(255) NULL,
    content_cover_url VARCHAR(2048) NULL,
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
-- 16. KẾT QUẢ/XỬ LÝ KIỂM DUYỆT
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
-- 17. NHÀ PHÂN PHỐI
-- Thông tin bên phân phối được chọn ở bước khai báo giấy phép bài hát.
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
-- 18. HỢP ĐỒNG PHÂN PHỐI
-- Hợp đồng thuộc một nhà phân phối; revenue_share là tỷ lệ hợp đồng,
-- không phải tỷ lệ chia 75/25 của thống kê doanh thu lượt nghe.
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
-- 19. GIẤY PHÉP BÀI HÁT
-- Ánh xạ SongLicense; track_id tham chiếu logic đến tracks trong MongoDB.
-- acquisition_cost_vnd là chi phí mua quyền cho bài hát do OCR trích xuất
-- và người phụ trách xác nhận. NULL = chưa xác định; 0 = không phát sinh chi phí.
-- Hoàn vốn được so với phần doanh thu Content Lead đã được duyệt, không phải doanh thu gộp.
-- ============================================================
CREATE TABLE song_licenses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id VARCHAR(64) NOT NULL,
    distributor_id BIGINT NULL,
    distribution_contract_id BIGINT NULL,
    distribution_contract_code VARCHAR(80) NULL,
    license_type VARCHAR(80) NOT NULL,
    copyright_owner VARCHAR(200) NULL,
    issue_date DATE NULL,
    expiry_date DATE NULL,
    status ENUM('ACTIVE','EXPIRED','REVOKED','PENDING') NOT NULL DEFAULT 'ACTIVE',
    document_songlicenses_url VARCHAR(1000) NULL,
    acquisition_cost_vnd DECIMAL(18,0) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_song_license_distributor
        FOREIGN KEY (distributor_id) REFERENCES distributors(id),
    CONSTRAINT fk_song_license_contract
        FOREIGN KEY (distribution_contract_id)
        REFERENCES distribution_contracts(id) ON DELETE SET NULL,
    CONSTRAINT chk_song_license_owner
        CHECK (distributor_id IS NOT NULL OR copyright_owner IS NOT NULL),
    CONSTRAINT chk_song_license_dates
        CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date),
    CONSTRAINT chk_song_license_acquisition_cost
        CHECK (acquisition_cost_vnd IS NULL OR acquisition_cost_vnd >= 0),

    INDEX idx_song_license_track (track_id),
    INDEX idx_song_license_status (status),
    INDEX idx_song_license_distributor (distributor_id),
    INDEX idx_song_license_contract (distribution_contract_id)
);
-- ============================================================
-- 20. LƯU LƯỢNG TRUY CẬP NỀN TẢNG
-- Ghi lượt truy cập vào track, artist hoặc trang chung theo nguồn giới thiệu.
-- Đây là lượt truy cập trang, không phải lượt nghe hợp lệ có trả tiền.
-- ============================================================
CREATE TABLE platform_traffic_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type ENUM('TRACK', 'ARTIST', 'GENERAL') NOT NULL DEFAULT 'TRACK',
    target_id VARCHAR(64) NOT NULL,
    platform ENUM('WEB', 'ANDROID', 'IOS', 'OTHER') NOT NULL DEFAULT 'WEB',
    referrer_type ENUM(
        'DIRECT', 'SEARCH', 'TIKTOK', 'FACEBOOK', 'AI_RECOMMEND', 'OTHER'
    ) NOT NULL DEFAULT 'DIRECT',
    user_id BIGINT NULL,
    session_id VARCHAR(64) NULL
        COMMENT 'Phiên truy cập; không phải session_key của lượt nghe trả tiền',
    visited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_platform_traffic_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_traffic_target_time (target_id, visited_at),
    INDEX idx_traffic_platform (platform),
    INDEX idx_traffic_visited_at (visited_at)
);

-- ============================================================
-- 21. THÔNG BÁO CÁ NHÂN
-- Một dòng cho một người nhận; read_at NULL nghĩa là chưa đọc.
-- dedupe_key/source_mongo_id chống tạo trùng khi tác vụ chạy lại hoặc di trú MongoDB.
-- ============================================================
CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    link_url VARCHAR(1000) NULL,
    created_by BIGINT NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dedupe_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_mongo_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_dedupe_key (dedupe_key),
    UNIQUE KEY uk_notifications_source_mongo_id (source_mongo_id),
    KEY idx_notifications_user_created (user_id, created_at, id),
    KEY idx_notifications_user_read (user_id, read_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
);
-- ============================================================
-- 22. LỊCH SỬ GỬI THÔNG BÁO HÀNG LOẠT
-- Lưu thông điệp, vai trò đích, người gửi và số người nhận của một lần phát.
-- ============================================================
CREATE TABLE notification_broadcasts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    target_role VARCHAR(32) NOT NULL DEFAULT 'ALL',
    recipients_count INT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_mongo_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_broadcast_source_mongo_id (source_mongo_id),
    KEY idx_notification_broadcasts_created (created_at, id),
    CONSTRAINT fk_notification_broadcasts_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL
) ;


-- ============================================================
-- 23. PHIÊN PHÁT PHỤC VỤ XÉT LƯỢT NGHE
-- Trạng thái tạm thời: vị trí phát, tổng thời gian và số mili giây liên tục.
-- Một session_key chỉ thuộc một lượt phát; heartbeat không tự tạo doanh thu.
-- Các cột DATETIME(3) trong phần doanh thu được ghi theo UTC.
-- Nếu chuyển trạng thái này sang Redis sau này, cần migration riêng.
-- ============================================================
CREATE TABLE revenue_playback_sessions (
    session_key CHAR(36) PRIMARY KEY
        COMMENT 'UUID duy nhất cho một lần phát',
    listening_history_id BIGINT NOT NULL UNIQUE
        COMMENT 'Khóa ngoại tới lịch sử nghe',
    last_seen_at DATETIME(3) NOT NULL
        COMMENT 'Thời điểm backend nhận sự kiện gần nhất, UTC',
    continuous_ms BIGINT NOT NULL DEFAULT 0
        COMMENT 'Thời gian nghe liên tục hiện tại, mili giây',
    playing BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Phiên đang ở trạng thái phát',
    closed BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Phiên đã kết thúc',

    CONSTRAINT fk_revenue_session_history
        FOREIGN KEY (listening_history_id)
        REFERENCES listening_history(id) ON DELETE CASCADE,
    INDEX idx_revenue_session_active (playing, closed, last_seen_at)
);

-- ============================================================
-- 24. LƯỢT NGHE HỢP LỆ VÀ SỔ DOANH THU
-- Một session_key chỉ sinh tối đa một dòng doanh thu. Sau 60 giây nghe liên tục,
-- phần gộp mặc định là 1.000 VND: Content Lead 750, nền tảng 250.
-- Chỉ APPROVED được tính vào báo cáo/hoàn vốn; PENDING_REVIEW chờ kiểm duyệt.
-- Không lưu listening_history_id: người dùng có thể xóa lịch sử nghe,
-- còn bản ghi doanh thu phải tồn tại độc lập để đối soát.
-- listener_user_id, content_lead_user_id, track_id và số tiền là ảnh chụp
-- phục vụ đối soát, không truy vấn lại từ lịch sử nghe/giấy phép hiện tại.
-- Giá trị tiền và policy_version là ảnh chụp chính sách tại lúc ghi nhận,
-- không được tính lại khi chính sách thay đổi trong tương lai.
-- ============================================================
CREATE TABLE qualified_streams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36) NOT NULL UNIQUE
        COMMENT 'Chống ghi doanh thu trùng cho cùng phiên',
    listener_user_id BIGINT NOT NULL,
    content_lead_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    qualified_at DATETIME(3) NOT NULL
        COMMENT 'Thời điểm đủ điều kiện, UTC',
    gross_vnd DECIMAL(18,0) NOT NULL DEFAULT 1000
        COMMENT 'Doanh thu gộp của lượt nghe, VND',
    content_lead_vnd DECIMAL(18,0) NOT NULL DEFAULT 750
        COMMENT 'Phần Content Lead, VND',
    platform_vnd DECIMAL(18,0) NOT NULL DEFAULT 250
        COMMENT 'Phần nền tảng, VND',
    policy_version VARCHAR(32) NOT NULL DEFAULT 'V1_60S_75_25'
        COMMENT 'Phiên bản quy tắc tính tiền tại lúc ghi nhận',
    status ENUM('APPROVED','PENDING_REVIEW','REJECTED') NOT NULL DEFAULT 'APPROVED'
        COMMENT 'Trạng thái xét duyệt doanh thu',
    review_reason VARCHAR(255) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,

    CONSTRAINT chk_qualified_stream_split
        CHECK (gross_vnd = content_lead_vnd + platform_vnd),
    INDEX idx_stream_lead_time (content_lead_user_id, status, qualified_at),
    INDEX idx_stream_track_time (track_id, status, qualified_at),
    INDEX idx_stream_listener_track_time (listener_user_id, track_id, qualified_at)
);

CREATE TABLE karaoke_rooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(40) NOT NULL,
    host_user_id BIGINT NOT NULL,
    max_members INT NOT NULL DEFAULT 6,
    visibility VARCHAR(16) NOT NULL,
    invite_code_hash VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at DATETIME NULL,

    -- Trạng thái phát chung của phòng
    current_track_id VARCHAR(64) NULL
        COMMENT 'Logical reference to MongoDB tracks._id',
    playback_status VARCHAR(12) NOT NULL DEFAULT 'IDLE',
    playback_position_ms BIGINT NOT NULL DEFAULT 0,
    playback_run_id VARCHAR(36) NULL,
    playback_started_at DATETIME NULL,
    playback_version BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    KEY idx_karaoke_rooms_public (
        visibility,
        closed_at,
        created_at,
        id
    ),
    KEY idx_karaoke_rooms_playback (
        playback_status,
        closed_at
    ),

    CONSTRAINT fk_karaoke_rooms_host
        FOREIGN KEY (host_user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_karaoke_rooms_visibility
        CHECK (visibility IN ('PUBLIC', 'PRIVATE')),

    CONSTRAINT chk_karaoke_rooms_capacity
        CHECK (max_members BETWEEN 1 AND 6)
);


CREATE TABLE karaoke_room_members (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at DATETIME NULL,

    PRIMARY KEY (room_id, user_id),

    KEY idx_karaoke_room_members_user (user_id),

    CONSTRAINT fk_karaoke_room_members_room
        FOREIGN KEY (room_id)
        REFERENCES karaoke_rooms (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_karaoke_room_members_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
);


CREATE TABLE karaoke_room_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL
        COMMENT 'Logical reference to MongoDB tracks._id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    KEY idx_karaoke_room_queue_fifo (
        room_id,
        created_at,
        id
    ),
    KEY idx_karaoke_room_queue_requester (
        requester_user_id
    ),

    UNIQUE KEY uk_karaoke_room_queue_requester (
        room_id,
        requester_user_id
    ),

    CONSTRAINT fk_karaoke_room_queue_room
        FOREIGN KEY (room_id)
        REFERENCES karaoke_rooms (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_karaoke_room_queue_requester
        FOREIGN KEY (requester_user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
);


INSERT INTO users (
    id, full_name, phone, email, username, password, avatar_url,
    role, artist_spotify_id, status, last_login_at, created_at, updated_at
) VALUES
(1, 'Nguyễn Văn Listener', '0901000001', 'listener@moodify.local', 'listener01',
 '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=240',
 'USER', NULL, 'ACTIVE', '2026-09-15 08:10:00', '2026-01-01 08:00:00', '2026-09-15 08:10:00'),
(2, 'Trần Minh Content Lead', '0901000002', 'contentlead@moodify.local', 'contentlead01',
 '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=240',
 'CONTENT_LEAD', '4OCl7UfKRXLcYouOYa3Bwc', 'ACTIVE', '2026-09-15 08:00:00', '2026-02-01 08:00:00', '2026-09-15 08:00:00'),
(3, 'Lê Hoàng Moderator', '0901000003', 'moderator@moodify.local', 'moderator01',
 '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
 NULL, 'MODERATOR', NULL, 'ACTIVE', '2026-09-15 07:45:00', '2026-03-01 08:00:00', '2026-09-15 07:45:00'),
(4, 'Phạm Quốc Admin', '0901000004', 'admin@moodify.local', 'admin01',
 '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
 NULL, 'ADMIN', NULL, 'INACTIVE', NULL, '2026-01-15 08:00:00', '2026-08-30 09:00:00'),
(5, 'Hoàng Anh Banned', '0901000005', 'banned@moodify.local', 'banned01',
 '$2a$10$1732WbZGs1pwdRxZTYGWtewCkRmYYPBY2GFFGn4t8HpL9hJMuAFJq',
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
-- 8. BẬC QUYỀN LỢI THUÊ BAO (SUBSCRIPTION TIERS)
-- ============================================================
INSERT INTO subscription_tiers (
    id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit,
    offline_allowed, offline_max_tracks, max_devices, synced_lyrics,
    vip_badge, family_sharing, family_members, created_at, updated_at
) VALUES
('FREE', 'Tài khoản Miễn Phí', 'Dành cho người nghe nhạc tiêu chuẩn có kèm quảng cáo tài trợ.', 'FULL_ADS', 0, 'LIMITED', 6, FALSE, 0, 1, FALSE, FALSE, FALSE, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
('INDIVIDUAL_BASIC', 'Gói Tiết Kiệm (Basic)', 'Tiết kiệm chi phí: 15 bài không quảng cáo mỗi ngày, 30 lượt skip/ngày, tải 50 bài offline.', 'DAILY_QUOTA', 15, 'LIMITED', 30, TRUE, 50, 1, TRUE, TRUE, FALSE, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
('INDIVIDUAL_FULL', 'Cá Nhân VIP FULL', 'Đặc quyền tối thượng: 100% không quảng cáo 24/7, chuyển bài vô hạn, tải nhạc offline vô hạn.', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 1, TRUE, TRUE, FALSE, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
('FAMILY', 'Gói Gia Đình VIP', 'Trọn bộ đặc quyền FULL chia sẻ cho tối đa 6 tài khoản/thiết bị đồng thời.', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 6, TRUE, TRUE, TRUE, 6, '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- ============================================================
-- 9. SERVICE_PACKAGES
-- Bao phủ: miễn phí/trả phí, nhiều thời hạn, ACTIVE/INACTIVE.
-- ============================================================
INSERT INTO service_packages (
    id, name, description, price, duration_days, display_order, status, tier_id, created_at, updated_at
) VALUES
(1, 'Gói VIP Tiết Kiệm (30 Ngày)', 'Dành cho 1 người: 15 bài hát/ngày không quảng cáo, 30 lượt skip/ngày, tải 50 bài offline.', 29000, 30, 1, 'ACTIVE', 'INDIVIDUAL_BASIC', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(2, 'Gói VIP Tiết Kiệm (1 Năm)', 'Tiết kiệm 20%: Trọn gói 365 ngày nghe nhạc tiết kiệm.', 279000, 365, 2, 'ACTIVE', 'INDIVIDUAL_BASIC', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(3, 'Gói Cá Nhân FULL (30 Ngày)', 'Dành cho 1 người: 100% không quảng cáo vô hạn, chuyển bài và tải nhạc offline vô hạn.', 49000, 30, 3, 'ACTIVE', 'INDIVIDUAL_FULL', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(4, 'Gói Cá Nhân FULL (90 Ngày)', 'Tiết kiệm 12%: 3 tháng âm nhạc không quảng cáo vô hạn.', 129000, 90, 4, 'ACTIVE', 'INDIVIDUAL_FULL', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(5, 'Gói Cá Nhân FULL (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng, trọn bộ đặc quyền cá nhân không giới hạn.', 469000, 365, 5, 'ACTIVE', 'INDIVIDUAL_FULL', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(6, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản/thiết bị đồng thời: Trọn bộ đặc quyền FULL chia sẻ cả gia đình.', 79000, 30, 6, 'ACTIVE', 'FAMILY', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(7, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 790000, 365, 7, 'ACTIVE', 'FAMILY', '2026-01-01 00:00:00', '2026-01-01 00:00:00');

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
INSERT INTO offline_downloads (id, device_id, track_id, status, local_path, downloaded_at, expires_at, created_at, updated_at) VALUES
(1, 1, '11dFghVXANMlKmJXsNCbNl', 'DOWNLOADED', '/storage/offline/11dFghVXANMlKmJXsNCbNl.enc', '2026-09-02 10:00:00', '2026-10-02 10:00:00', '2026-09-02 10:00:00', '2026-09-02 10:00:00'),
(2, 2, '22eFghVXANMlKmJXsNCbNm', 'DOWNLOADED', '/storage/offline/22eFghVXANMlKmJXsNCbNm.enc', '2026-09-03 11:30:00', '2026-10-03 11:30:00', '2026-09-03 11:30:00', '2026-09-03 11:30:00');

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
-- Script Khởi Tạo Toàn Bộ (Event Scheduler + Stored Procedure + Event Hàng Ngày)--
-- 1. Bật tính năng Event Scheduler của MySQL Server
SET GLOBAL event_scheduler = ON;

-- 2. Tạo Stored Procedure quét và cập nhật hợp đồng hết hạn
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_expire_outdated_song_licenses $$

CREATE PROCEDURE sp_expire_outdated_song_licenses()
BEGIN
    -- Cập nhật tất cả các hợp đồng đang ACTIVE nhưng expiry_date đã qua ngày hiện tại
    UPDATE song_licenses
    SET status = 'EXPIRED'
    WHERE status = 'ACTIVE'
      AND expiry_date IS NOT NULL
      AND expiry_date < CURRENT_DATE();
      
    SELECT ROW_COUNT() AS expired_licenses_count;
END $$

DELIMITER ;
-- 3. Tạo Event Scheduler tự động chạy mỗi ngày lúc 00:01:00
DROP EVENT IF EXISTS evt_daily_song_license_expiry;

CREATE EVENT evt_daily_song_license_expiry
ON SCHEDULE EVERY 1 DAY
STARTS (TIMESTAMP(CURRENT_DATE) + INTERVAL 1 DAY + INTERVAL 1 MINUTE)
ON COMPLETION PRESERVE
ENABLE
COMMENT 'Moodify: Tu dong quet va cap nhat trang thai EXPIRED cho hop dong ban quyen het han'
DO CALL sp_expire_outdated_song_licenses();

--  Lệnh Kiểm Tra Trạng Thái Sau Khi Dán --
-- 1. Kiểm tra Event Scheduler đã BẬT chưa (kết quả Value phải là ON)
SHOW VARIABLES LIKE 'event_scheduler';
-- 2. Xem Event đã được lập lịch trong cơ sở dữ liệu chưa (Status: ENABLED)
SHOW EVENTS FROM moodify;
-- 3. Chạy thử Stored Procedure thủ công ngay lập tức để test
CALL sp_expire_outdated_song_licenses();


-- Giả sử bạn muốn test với track_id cụ thể (đổi track_id thành ID bài hát của bạn):
UPDATE song_licenses 
SET expiry_date = DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY)
WHERE track_id = 'YOUR_TRACK_ID_HERE';
-- Chạy procedure để kích hoạt cập nhật ngay lập tức:
CALL sp_expire_outdated_song_licenses();
-- Kiểm tra lại trạng thái bài hát đó trong DB (status sẽ đổi sang EXPIRED):
SELECT id, track_id, status, expiry_date 
FROM song_licenses 
WHERE track_id = 'YOUR_TRACK_ID_HERE';




USE moodify;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_create_subscription_expiry_notifications$$

CREATE PROCEDURE sp_create_subscription_expiry_notifications()
BEGIN
    -- Nhắc trước 7 ngày
    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ',
            DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT(
            'subscription-expiry:',
            s.user_id, ':',
            DATE_FORMAT(s.end_at, '%Y%m%d'), ':7'
        )
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 7 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (
                later_sub.end_at > s.end_at
                OR (
                    later_sub.end_at = s.end_at
                    AND later_sub.id > s.id
                )
            )
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub
              ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package
              ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '')
                NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );

    -- Nhắc trước 1 ngày
    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ',
            DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT(
            'subscription-expiry:',
            s.user_id, ':',
            DATE_FORMAT(s.end_at, '%Y%m%d'), ':1'
        )
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 1 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (
                later_sub.end_at > s.end_at
                OR (
                    later_sub.end_at = s.end_at
                    AND later_sub.id > s.id
                )
            )
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub
              ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package
              ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '')
                NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );
END$$

DELIMITER ;
    
SET time_zone = '+07:00';
SET GLOBAL event_scheduler = ON;
DROP EVENT IF EXISTS evt_daily_subscription_expiry_reminders;
CREATE EVENT evt_daily_subscription_expiry_reminders
    ON SCHEDULE EVERY 1 DAY
    STARTS (TIMESTAMP(CURRENT_DATE) + INTERVAL 1 DAY + INTERVAL 9 HOUR)
    ON COMPLETION PRESERVE
    ENABLE
    COMMENT 'Moodify: nhắc hết hạn gói trước 7 ngày và 1 ngày'
    DO CALL sp_create_subscription_expiry_notifications();