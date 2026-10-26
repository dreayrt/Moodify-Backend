-- Moodify: lược đồ MySQL tham khảo, chỉ tạo cấu trúc; không chứa seed hoặc lệnh thử nghiệm.
-- Dành cho cơ sở dữ liệu trống. Với cơ sở dữ liệu đang có dữ liệu, dùng migration riêng.
-- Track/artist/album lưu tại MongoDB: các cột *_id tương ứng là tham chiếu logic,
-- không thể khai báo FOREIGN KEY MySQL sang MongoDB.
-- Bản này giữ cả revenue_playback_sessions và qualified_streams theo schema bạn cung cấp.
-- DATETIME(3) của hai bảng doanh thu dùng UTC; các cột thời gian cũ giữ nguyên quy ước hiện tại.
CREATE DATABASE IF NOT EXISTS moodify
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

    INDEX idx_payment_subscription_time (subscription_id, created_at),
    INDEX idx_payment_status_time (status, created_at)
) ;

-- ============================================================
-- 13. THIẾT BỊ NGƯỜI DÙNG
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
-- 14. TẢI NHẠC OFFLINE
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
-- 15. YÊU CẦU KIỂM DUYỆT NỘI DUNG
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
-- distributor_id là FK cần cho giấy phép phân phối chưa gắn hợp đồng;
-- nếu đã gắn hợp đồng, backend kiểm tra hợp đồng thuộc nhà phân phối đó.
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
    acquisition_cost_vnd DECIMAL(18,0) NULL COMMENT 'Chi phí mua quyền bài hát (VND); NULL nếu chưa xác định',
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
    CONSTRAINT chk_song_license_acquisition_cost
        CHECK (acquisition_cost_vnd IS NULL OR acquisition_cost_vnd >= 0),

    INDEX idx_song_license_track (track_id),
    INDEX idx_song_license_status (status),
    INDEX idx_song_license_distributor (distributor_id),
    INDEX idx_song_license_contract (distribution_contract_id)
) ;

-- ============================================================
-- 20. LƯU LƯỢNG TRUY CẬP NỀN TẢNG
-- Ghi lượt truy cập vào track, artist hoặc trang chung theo nguồn giới thiệu.
-- Đây là lượt truy cập trang, không phải lượt nghe hợp lệ có trả tiền.
-- user_id là FK tùy chọn: lượt ẩn danh có giá trị NULL; khi xóa tài khoản,
-- lượt truy cập vẫn được giữ lại ở dạng ẩn danh.
-- ============================================================
CREATE TABLE platform_traffic_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type ENUM('TRACK', 'ARTIST', 'GENERAL') NOT NULL DEFAULT 'TRACK',
    target_id VARCHAR(64) NOT NULL,
    platform ENUM('WEB', 'ANDROID', 'IOS', 'OTHER') NOT NULL DEFAULT 'WEB',
    referrer_type ENUM('DIRECT', 'SEARCH', 'TIKTOK', 'FACEBOOK', 'AI_RECOMMEND', 'OTHER') NOT NULL DEFAULT 'DIRECT',
    user_id BIGINT NULL,
    session_id VARCHAR(64) NULL COMMENT 'Phiên truy cập; không phải session_key của lượt nghe trả tiền',
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
-- Trạng thái tạm thời của một phiên nghe: bộ đếm liên tục và trạng thái phát.
-- Một session_key chỉ thuộc một lượt phát; heartbeat không tự tạo doanh thu.
-- user_id, track_id, last_position_ms và tổng thời gian nghe lấy từ
-- listening_history qua listening_history_id, không lưu bản sao tại đây.
-- Khi lịch sử nghe bị xóa, phiên tạm thời tương ứng cũng bị xóa.
-- Các cột DATETIME(3) trong phần doanh thu được ghi theo UTC.
-- Nếu chuyển trạng thái này sang Redis sau này, cần migration riêng.
-- ============================================================
CREATE TABLE revenue_playback_sessions (
    session_key CHAR(36) PRIMARY KEY COMMENT 'UUID duy nhất cho một lần phát',
    listening_history_id BIGINT NOT NULL UNIQUE COMMENT 'Khóa ngoại tới lịch sử nghe',
    last_seen_at DATETIME(3) NOT NULL COMMENT 'Thời điểm backend nhận sự kiện gần nhất, UTC',
    continuous_ms BIGINT NOT NULL DEFAULT 0 COMMENT 'Thời gian nghe liên tục hiện tại, mili giây',
    playing BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Phiên đang ở trạng thái phát',
    closed BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Phiên đã kết thúc',
    CONSTRAINT fk_revenue_session_history
        FOREIGN KEY (listening_history_id) REFERENCES listening_history(id) ON DELETE CASCADE,
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
    session_key CHAR(36) NOT NULL UNIQUE COMMENT 'Chống ghi doanh thu trùng cho cùng phiên',
    listener_user_id BIGINT NOT NULL,
    content_lead_user_id BIGINT NOT NULL,
    track_id VARCHAR(64) NOT NULL,
    qualified_at DATETIME(3) NOT NULL COMMENT 'Thời điểm đủ điều kiện, UTC',
    gross_vnd DECIMAL(18,0) NOT NULL DEFAULT 1000 COMMENT 'Doanh thu gộp của lượt nghe, VND',
    content_lead_vnd DECIMAL(18,0) NOT NULL DEFAULT 750 COMMENT 'Phần Content Lead, VND',
    platform_vnd DECIMAL(18,0) NOT NULL DEFAULT 250 COMMENT 'Phần nền tảng, VND',
    policy_version VARCHAR(32) NOT NULL DEFAULT 'V1_60S_75_25' COMMENT 'Phiên bản quy tắc tính tiền tại lúc ghi nhận',
    status ENUM('APPROVED','PENDING_REVIEW','REJECTED') NOT NULL DEFAULT 'APPROVED' COMMENT 'Trạng thái xét duyệt doanh thu',
    review_reason VARCHAR(255) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    CONSTRAINT chk_qualified_stream_split
        CHECK (gross_vnd = content_lead_vnd + platform_vnd),
    INDEX idx_stream_lead_time (content_lead_user_id, status, qualified_at),
    INDEX idx_stream_track_time (track_id, status, qualified_at),
    INDEX idx_stream_listener_track_time (listener_user_id, track_id, qualified_at)
);

