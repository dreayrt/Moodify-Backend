# Ngừng doanh thu từ lượt nghe — 11/11/2026

`ListeningPlaybackService` thay `RevenuePlaybackService`: giữ phiên nghe, lịch sử, sự kiện player và recommendation outcome sau commit; bỏ điều kiện phát sinh tiền, tỷ lệ 1.000/750/250 và mọi truy vấn sổ `qualified_streams` ở runtime.

`POST /api/analytics/listening-session` giữ endpoint/payload và response `success`/`historyId`. `qualified` là trường tương thích luôn `false`, kể cả với phiên cũ. Client không cần cập nhật để ngừng phát sinh tiền.

Các API cũ trả **410 Gone**, body `success: false`, `error: STREAM_REVENUE_REMOVED` và thông báo đã ngừng; không truy vấn database:

- `GET /api/content-lead/me/analytics/revenue`
- `GET /api/admin/revenue/pending`
- `POST /api/admin/revenue/{id}/review`

Phân quyền trong `SecurityConfig` giữ nguyên. Giao dịch và doanh thu bán gói Premium, quyền phát nhạc, quản lý giấy phép/chi phí bản quyền và dữ liệu hợp đồng vẫn thuộc các luồng hiện có.

## Dữ liệu

Không chạy migration, đổi schema hoặc xóa dữ liệu. Giữ `qualified_streams` làm sổ lịch sử đã ngừng dùng. Giữ `revenue_playback_sessions` với tên cũ vì `RecommendationStore` còn dựa vào nó để nhận diện lịch sử UTC, xu hướng và playlist tương tác. Các SQL migration lịch sử không phải hướng dẫn kích hoạt lại nghiệp vụ.

## Triển khai

1. Chạy kiểm thử service phiên nghe, API đã ngừng, recommendation và lịch sử; không khởi động toàn ứng dụng chỉ để test vì có đường auto-DDL.
2. Phát hành backend mới, dừng hết instance backend cũ có thể ghi tiền. Backend mới không có đường runtime ghi `qualified_streams`.
3. Phát hành web đã gỡ báo cáo Content Lead; kiểm tra phát nhạc/lịch sử từ web và Flutter hiện tại.
4. Xác nhận không thêm bản ghi sổ doanh thu trong môi trường kiểm thử khi nghe quá 60 giây/nhiều phút. Không dùng production còn instance cũ làm bằng chứng duy nhất.

Rollback bằng bản vá giữ việc ngừng tính tiền; khôi phục nguyên backend cũ sẽ bật lại nghiệp vụ đã bỏ. Đợt này không có rollback schema/dữ liệu.

Kiểm chứng cục bộ: 63/63 test phiên nghe, API đã ngừng, recommendation và lịch sử đạt. Test phiên nghe qua nhiều phút xác nhận lịch sử vẫn cập nhật và không có SQL đọc/ghi `qualified_streams`. Chưa chạy smoke trên DB hoặc backend đang phục vụ người dùng.
