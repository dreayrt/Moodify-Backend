# Cải thiện recommendation sau khảo sát tín hiệu — 27/10/2026

Phạm vi được yêu cầu: lập kế hoạch và triển khai cải thiện backend/worker. Giữ nguyên frontend/UI; không thêm bảng/cột/index MySQL hoặc collection Mongo; không sửa metadata, quyền phát, ID legacy trên database ứng dụng. Model cảm xúc tiếp tục ngoài recommendation.

1. [x] Chuẩn hóa resolver artist/album, nguồn preference và diagnostics. Dùng library làm nguồn chuẩn saved-track, không cộng lại favorite_songs legacy.
2. [x] Đọc favorite artist/album thành prior có giới hạn, không sinh played/save giả cho ALS; thêm API follow/unfollow dùng favorite_artists và nối feedback FOLLOW vào cùng nguồn chuẩn.
3. [x] Sửa resolver/validation favorite album cho SQL/Mongo; scope private/owner, namespace conflict, idempotency; không đoán ID legacy.
4. [x] Đưa selected search hợp lệ vào context ngắn hạn có dedupe với actual play; hỗ trợ genre request ở API, không tự chỉnh giao diện; fingerprint preference để mix thay đổi đúng khi preference đổi.
5. [x] Kết thúc transaction đọc giữa các batch worker, chỉ lấy USER ACTIVE; hợp nhất alias khi tính unique trend; giữ đúng 2 collection. Bổ sung ownership theo ID bất biến cho playlist mới.
6. [x] Kiểm thử hành vi unit + HTTP/SQL/Mongo trên fixture riêng: follow/unfollow ảnh hưởng score, block thắng follow, album không nhân label, dedupe, preference refresh, context, worker freshness và thiếu bảng SQL legacy; cập nhật tài liệu kết quả thực tế.

Các hệ số prior mới là heuristic khởi đầu, không phải trọng số đã chứng minh qua A/B. Không sửa dữ liệu legacy không nối được, không thêm telemetry queue/download giả, không tự bật production hoặc train/publish model trên database ứng dụng. Các hạng mục production lớn khác như circuit breaker/async outcome/global rollout sẽ được ghi rõ nếu chưa thuộc đợt này.

Kết quả: 66 backend tests ngoài EmotionServiceTest đạt; package thành công; 15 Python tests và 31 tình huống tích hợp fixture đạt. Sửa thêm library unlike theo Mongo/Spotify alias để preference không tồn dư. Audit read-only sau cải thiện vẫn có 153 track, 0 đủ policy, ma trận 2 user × 3 bài; không có 8 bảng SQL / 4 collection legacy. Chi tiết và các hạng mục còn mở: [triển khai v1.1](../RECOMMENDATION_V1_1_IMPLEMENTATION.md).
