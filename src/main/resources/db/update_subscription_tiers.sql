USE moodify;

-- ============================================================
-- 1. Tạo bảng quản lý bậc quyền lợi (subscription_tiers)
-- Chỉ lưu các chính sách thực tế mà Moodify hỗ trợ:
-- - Quảng cáo: ad_policy, ad_free_daily_limit
-- - Giới hạn skip bài: skip_policy, skip_daily_limit
-- - Nghe offline: offline_allowed, offline_max_tracks
-- - Thiết bị & gia đình: max_devices, family_sharing, family_members
-- - Tiện ích VIP: synced_lyrics, vip_badge
-- ============================================================
CREATE TABLE IF NOT EXISTS subscription_tiers (
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
    family_members INT NOT NULL DEFAULT 0
);

-- ============================================================
-- 2. Thêm dữ liệu 4 bậc dịch vụ chuẩn (nếu đã có thì cập nhật)
-- ============================================================
INSERT INTO subscription_tiers (
    id, name, description, ad_policy, ad_free_daily_limit, skip_policy, skip_daily_limit,
    offline_allowed, offline_max_tracks, max_devices, synced_lyrics,
    vip_badge, family_sharing, family_members
) VALUES
('FREE', 'Tài Khoản Miễn Phí', 'Dành cho người nghe thông thường kèm quảng cáo', 'FULL_ADS', 0, 'LIMITED', 6, FALSE, 0, 1, FALSE, FALSE, FALSE, 0),
('INDIVIDUAL_BASIC', 'VIP Tiết Kiệm', '15 bài không quảng cáo mỗi ngày, 30 lượt skip/ngày, tải 50 bài offline', 'DAILY_QUOTA', 15, 'LIMITED', 30, TRUE, 50, 1, TRUE, TRUE, FALSE, 0),
('INDIVIDUAL_FULL', 'VIP Cá Nhân FULL', '100% không quảng cáo 24/7, chuyển bài và tải nhạc offline vô hạn', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 1, TRUE, TRUE, FALSE, 0),
('FAMILY', 'VIP Gia Đình', 'Tối đa 6 tài khoản/thiết bị đồng thời, chia sẻ cả gia đình', 'NO_ADS', 0, 'UNLIMITED', 0, TRUE, 9999, 6, TRUE, TRUE, TRUE, 6)
ON DUPLICATE KEY UPDATE 
    name = VALUES(name), 
    description = VALUES(description),
    ad_policy = VALUES(ad_policy),
    ad_free_daily_limit = VALUES(ad_free_daily_limit),
    skip_policy = VALUES(skip_policy),
    skip_daily_limit = VALUES(skip_daily_limit),
    offline_allowed = VALUES(offline_allowed),
    offline_max_tracks = VALUES(offline_max_tracks),
    max_devices = VALUES(max_devices),
    synced_lyrics = VALUES(synced_lyrics),
    vip_badge = VALUES(vip_badge),
    family_sharing = VALUES(family_sharing),
    family_members = VALUES(family_members);

-- ============================================================
-- 3. Cập nhật bảng service_packages: Thêm cột tier_id và xóa cột features_json cũ
-- ============================================================
ALTER TABLE service_packages ADD COLUMN IF NOT EXISTS tier_id VARCHAR(50) NOT NULL DEFAULT 'INDIVIDUAL_BASIC';
ALTER TABLE service_packages DROP COLUMN IF EXISTS features_json;

-- ============================================================
-- 4. Thêm các gói dịch vụ mẫu bán hàng (service_packages) gắn liền với từng bậc
-- Nếu chưa có gói nào thì câu lệnh này sẽ tự động nạp đủ 7 gói cước tiêu chuẩn.
-- ============================================================
INSERT INTO service_packages (
    id, name, description, price, duration_days, display_order, status, tier_id
) VALUES
(1, 'Gói VIP Tiết Kiệm (30 Ngày)', 'Dành cho 1 người: 15 bài hát/ngày không quảng cáo, 30 lượt skip/ngày, tải 50 bài offline.', 29000, 30, 1, 'ACTIVE', 'INDIVIDUAL_BASIC'),
(2, 'Gói VIP Tiết Kiệm (1 Năm)', 'Tiết kiệm 20%: Trọn gói 365 ngày nghe nhạc tiết kiệm.', 279000, 365, 2, 'ACTIVE', 'INDIVIDUAL_BASIC'),
(3, 'Gói Cá Nhân FULL (30 Ngày)', 'Dành cho 1 người: 100% không quảng cáo vô hạn, chuyển bài và tải nhạc offline vô hạn.', 49000, 30, 3, 'ACTIVE', 'INDIVIDUAL_FULL'),
(4, 'Gói Cá Nhân FULL (90 Ngày)', 'Tiết kiệm 12%: 3 tháng âm nhạc không quảng cáo vô hạn.', 129000, 90, 4, 'ACTIVE', 'INDIVIDUAL_FULL'),
(5, 'Gói Cá Nhân FULL (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng, trọn bộ đặc quyền cá nhân không giới hạn.', 469000, 365, 5, 'ACTIVE', 'INDIVIDUAL_FULL'),
(6, 'Gói Gia Đình (30 Ngày)', 'Tối đa 6 tài khoản/thiết bị đồng thời: Trọn bộ đặc quyền FULL chia sẻ cả gia đình.', 79000, 30, 6, 'ACTIVE', 'FAMILY'),
(7, 'Gói Gia Đình (1 Năm)', 'Tiết kiệm tối đa: Tặng 2 tháng cho cả 6 thành viên gia đình.', 790000, 365, 7, 'ACTIVE', 'FAMILY')
ON DUPLICATE KEY UPDATE 
    name = VALUES(name), 
    description = VALUES(description), 
    price = VALUES(price), 
    duration_days = VALUES(duration_days), 
    display_order = VALUES(display_order), 
    status = VALUES(status), 
    tier_id = VALUES(tier_id);

-- Gắn tier_id dự phòng cho các gói có sẵn nếu ID khác 1..7
UPDATE service_packages SET tier_id = 'INDIVIDUAL_BASIC' WHERE tier_id IS NULL OR tier_id = '';

-- ============================================================
-- 5. Tạo khóa ngoại liên kết giữa service_packages và subscription_tiers
-- ============================================================
ALTER TABLE service_packages DROP FOREIGN KEY IF EXISTS fk_service_packages_tier;
ALTER TABLE service_packages 
ADD CONSTRAINT fk_service_packages_tier 
FOREIGN KEY (tier_id) REFERENCES subscription_tiers(id) ON UPDATE CASCADE;
