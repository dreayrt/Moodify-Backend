# Quản lý tài nguyên karaoke trên production

Tab Admin **Tài Nguyên Karaoke** tạo tác vụ tách nhạc bằng CPU trên Google Cloud.
Admin chọn bài trong catalog, sử dụng nguồn hiện có hoặc tải file thay thế, theo
dõi hàng đợi, nghe thử và xuất bản tài nguyên. Tách lại tạo phiên bản mới; bản
đang phục vụ người hát chỉ đổi khi admin duyệt. Có thể khôi phục phiên bản cũ.

## Luồng và dữ liệu

1. `POST /api/admin/karaoke/jobs` nhận multipart `songId` và `file` tùy chọn, trả
   HTTP 202. File tối đa 128 MiB; pipeline kiểm tra khả năng giải mã và giới hạn
   thời lượng 30 phút. Nguồn tải thay thế không ghi đè file nhạc gốc trong catalog.
2. MongoDB giữ tác vụ bền vững. Worker CPU lấy một tác vụ, tách instrumental/vocal,
   trích pitch reference, kiểm tra file và báo hoàn tất. Đóng trình duyệt không
   dừng công việc. API không chờ inference trong một request dài.
3. Backend xác minh checksum nguồn/đầu ra và thời lượng; kết quả thành bản nháp.
4. Admin nghe thử, tải file và xuất bản. Backend cập nhật document đang dùng trong
   `karaoke_assets` sau khi kiểm tra lại byte của các file.

Database `music_streaming`:

| Collection | Nội dung |
| --- | --- |
| `karaoke_assets` | Bản đang xuất bản, tương thích catalog karaoke hiện hữu |
| `karaoke_processing_jobs` | Tác vụ, người tạo, nguồn, sự kiện, lỗi, thời gian; document `__worker__` giữ lease và sức chứa worker |
| `karaoke_asset_versions` | Bản nháp và lịch sử các bộ tài nguyên đã xuất bản |
| `karaoke_room_asset_pins` | Ghim phiên bản theo phòng/lượt phát để xuất bản không đổi tài nguyên của lượt hát đang diễn ra |

File lưu trong `/srv/moodify/karaoke/{songId}/{assetVersion}/`, gồm
`instrumental.wav`, `vocals.wav`, `pitch_reference.json`,
`karaoke_asset_manifest.json`. Các thư mục ẩn `.sources` và `.jobs` giữ nguồn
upload/nhật ký; không được phục vụ bằng Nginx. Manifest cũng không được public.
Thư mục gốc là bind mount, không mất khi thay container.

## API quản trị

Tất cả `/api/admin/**` yêu cầu JWT của ADMIN theo cấu hình bảo mật đang dùng.

| API | Mục đích |
| --- | --- |
| `GET /api/admin/karaoke/resources?page=0&size=20&query=&status=all` | Thư viện, thống kê, trạng thái worker |
| `GET /api/admin/karaoke/resources/{songId}` | Các phiên bản và nhật ký tác vụ gần đây |
| `GET /api/admin/karaoke/resources/{songId}/source?jobId=...` | Nguồn gốc hoặc nguồn của tác vụ, có kiểm tra quyền |
| `GET /api/admin/karaoke/jobs?page=0&size=20&status=active` | Hàng đợi; `status=history` để xem lịch sử |
| `POST /api/admin/karaoke/jobs` | Tạo tác vụ, multipart |
| `POST /api/admin/karaoke/jobs/{id}/cancel` | Hủy queued hoặc yêu cầu dừng running |
| `POST /api/admin/karaoke/jobs/{id}/retry` | Tạo tác vụ mới từ tác vụ lỗi/hủy/gián đoạn |
| `POST /api/admin/karaoke/resources/{songId}/versions/{version}/publish` | Xuất bản hoặc khôi phục phiên bản |

Worker gọi `/api/internal/karaoke/processing/claim`,
`/jobs/{id}/heartbeat`, `/complete`, `/finish` bằng `X-Processing-Key` riêng.
Controller dùng so sánh key constant-time. Worker không có MongoDB credential
hoặc Docker socket. Chuỗi bảo mật riêng chỉ khớp các endpoint nội bộ này.

## Vận hành

Image worker được build từ `E:/python-audio-separator/Dockerfile.processing`,
dựa trên bundle CPU có digest cố định; không dùng image CUDA. Compose service
ở `supporting-services/karaoke-processing/compose.production.yaml`.

- Một bài đồng thời, một luồng CPU, Docker quota 1 CPU, ưu tiên tiến trình thấp.
- Worker cần `PROCESSING_BACKEND_URL` và `PROCESSING_KEY` trong env file riêng.
- Backend cần `KARAOKE_PROCESSING_KEY` trùng key; mount kho karaoke đọc/ghi và
  nguồn catalog chỉ đọc. Backend hiện hữu đã mount uploads.
- Worker mount catalog và uploads chỉ đọc; kho karaoke đọc/ghi, UID/GID 10001.
  Các thư mục cha của song phải cho phép UID/GID này tạo thư mục phiên bản.
- Trước khi nhận tác vụ: tối thiểu 3 GiB RAM available và 5 GiB đĩa trống;
  có thể cấu hình `PROCESSING_MIN_AVAILABLE_MEMORY`, `PROCESSING_MIN_FREE_DISK`.
- Lease 120 giây, heartbeat khoảng 3 giây. Mất liên lạc quá 60 giây, worker dừng
  cây tiến trình trước khi lease có thể được cấp lại. Lần polling sau đánh dấu
  tác vụ chạy quá hạn là interrupted; admin có thể retry.
- Hủy hoặc shutdown dừng cả cây tiến trình FFmpeg/Python. Chỉ dọn thư mục staging
  của đúng phiên bản; không xóa tài nguyên đã xuất bản.
- `PROCESSING_JOB_TIMEOUT_SECONDS` mặc định 43200 (12 giờ), có thể điều chỉnh.
- Không hiển thị phần trăm giả. Giao diện báo stage và thời gian đã chạy.
- Nginx và Spring multipart cần cho phép 130 MiB để chứa file 128 MiB và multipart.

Dữ liệu cao độ không đạt vẫn cho phép xuất bản phần hát, nhưng không bật chấm
điểm. Giới hạn chấm điểm hiện tại 10 phút; bài dài hơn được gắn trạng thái
`unsupported_duration`. Tách vocal không tự sinh lời hoặc đồng bộ lời. Đổi
phiên bản vocal sẽ gỡ timeline cũ không còn phù hợp; giữ dữ liệu lời hiện hữu.

## Kiểm thử

Java: `KaraokeProcessingServiceTest`, `KaraokeProcessingWorkerApiTest`, các test
catalog/diagnostics hiện hữu. Python: `services/karaoke/tests/test_processing_worker.py`
và test API/scoring hiện hữu. Frontend: TypeScript và ESLint trên các file mới.
Nghiệm thu API dùng database kiểm thử riêng, cùng các controller và worker thật;
không cần tài khoản giả, không chạy scheduler SQL của production.
