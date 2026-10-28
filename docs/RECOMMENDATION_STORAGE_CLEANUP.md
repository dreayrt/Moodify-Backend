# Biên bản dọn persistence recommendation cũ

Thực hiện ngày **27/10/2026**, theo yêu cầu trực tiếp của chủ dự án. Phạm vi: database ứng dụng local đã kiểm kê trước đó. Không deploy production hoặc sửa giao diện.

## Kết quả

- Đã xóa 8 bảng MySQL: `recommendation_items`, `recommendation_requests`, `recommendation_events`, `recommendation_feedback`, `user_track_daily`, `content_metrics_windows`, `editorial_campaigns`, `recommendation_model_versions`.
- Đã xóa 4 collection Mongo cũ: `user_recommendation_profiles`, `playlist_features`, `personalized_playlists`, `personalized_playlist_versions`.
- Đã chuyển 2 hồ sơ người dùng sang `recommendation_state`; không có event/campaign/mix cũ cần chuyển.
- 36 dòng tổng hợp theo ngày được giữ trong backup. Worker tính lại trong RAM, không tạo lại bảng.
- Persistence recommendation hiện chỉ có `recommendation_state` và `recommendation_events` trong Mongo. `recommendation_events` Mongo là collection mới, khác với bảng SQL cùng tên đã xóa.

## Sao lưu

- [Snapshot gồm SQL DDL, dữ liệu cũ và BSON documents](../storage/recommendation-backups/20261027T132448753525Z/snapshot.json).
- [SHA256](../storage/recommendation-backups/20261027T132448753525Z/sha256.txt).
- [Biên bản kiểm chứng máy đọc được](../storage/recommendation-backups/20261027T132448753525Z/cleanup-verification.json).

Checksum đã được kiểm tra trước và sau cleanup. Snapshot được lưu trong thư mục storage bị Git ignore, không bị Maven clean xóa.

## Kiểm chứng

- Review sau cleanup trả `legacy_sql_rows: {}` và `legacy_mongo_documents: {}`.
- Cấu trúc (hash DDL) và số bản ghi của 27 bảng MySQL nghiệp vụ khớp trước/sau.
- Số document của 17 collection Mongo nghiệp vụ khớp trước/sau.
- Worker chạy một vòng thành công sau cleanup: đọc 51 history rows trong phạm vi train hợp lệ, tính 36 aggregate trong RAM, nhận diện 2 user và 3 track trong tương tác. Không yêu cầu train hoặc thay model.
- Backend/worker không chạy tại thời điểm cleanup; các Java process được xác định là IDE và không bị dừng.

Các kiểm tra số bản ghi/DDL là kiểm tra cấu trúc và số lượng, không phải hash toàn bộ nội dung các bảng nghiệp vụ. Công cụ cleanup chỉ nhắm đến danh sách 8 bảng/4 collection nêu trên; không thực hiện mutation lên bảng/collection nghiệp vụ.
