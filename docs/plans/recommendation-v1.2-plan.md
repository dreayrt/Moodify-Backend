# Recommendation v1.2 — bảo vệ đường phát và chuẩn bị pilot

Ngày 28/10/2026. Tiếp nối v1.1; không thay UI, không DDL MySQL hoặc thêm collection Mongo, không deploy hay tự cấp quyền nội dung. Giữ các thay đổi đang có của người dùng trong cấu hình/deploy.

1. [x] Tách ghi PLAY_START sau commit qua executor RAM một worker, hàng đợi hữu hạn, Mongo operation timeout, circuit cooldown và bộ đếm mất log. Không thêm outbox/broker; công khai giới hạn crash/restart.
2. [x] Xác minh queue playback bằng lookup đúng track/license/owner/block, bỏ full-catalog scan khỏi heartbeat; giữ fail-closed khi không xác minh được quyền. Không đổi công thức qualified stream/thanh toán.
3. [x] Thêm cờ enabled/CF/campaign cấu hình môi trường và điều khiển ADMIN theo revision trên process hiện tại. Tắt generation giữ đọc/queue bản mix đã có, giữ preference/negative feedback; không fail-open dữ liệu.
4. [x] Quan sát lỗi/latency/empty, log drop/duplicate/circuit và trạng thái cờ bằng API ADMIN, metric cardinality cố định, RAM hữu hạn, không PII. Không thêm monitoring DB hoặc dashboard UI.
5. [x] Tạo preview chỉ đọc nội dung cần đối soát trước pilot; không đặt trạng thái public/approved hoặc sửa license tự động.
6. [x] Test queue saturation/timeout/circuit/rollback, flags/owner/revocation/lookup scope và regression HTTP trên fixture riêng; cập nhật runbook + audit read-only local, ghi rõ những P0 còn mở.

Hoàn tất 29/10/2026: **79 backend tests ngoài EmotionServiceTest, 15 Python tests, 37 tình huống HTTP/MySQL/Mongo fixture đạt**, package thành công. Test còn phân biệt response rỗng thành công với lỗi persistence. Fixture/server/socket thử được dọn; không deploy hay train/publish vào database ứng dụng. [Runbook và giới hạn còn lại](../RECOMMENDATION_V1_2_OPERATIONS.md). Controls/metrics là per-process, không phải rollout phân tán; đọc catalog/quyền vẫn cần Mongo/SQL. Audit local vẫn 0/153 bài đủ policy.
