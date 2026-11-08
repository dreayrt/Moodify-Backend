# Bàn giao quản lý tài nguyên karaoke — 07/11/2026

Đã triển khai release `karaoke-admin-20261107-01` trên `https://moodify.click`.
Admin mở `/dashboard/admin`, chọn **Tài Nguyên Karaoke** trong sidebar.

## Đã triển khai

- Thư viện tài nguyên có tìm kiếm, lọc, phân trang và thống kê.
- Tạo tác vụ bằng nguồn catalog hoặc file upload thay thế, theo dõi hàng đợi
  và nhật ký từng bước. Tác vụ tiếp tục độc lập với trình duyệt.
- Tách nhạc bằng container CPU trên instance Google Cloud; không dùng máy local
  của admin. Đầu ra instrumental, vocals, pitch reference và manifest.
- Nghe thử bản gốc/nhạc nền/vocal; tải các file đầu ra.
- Duyệt xuất bản vào `music_streaming.karaoke_assets`, quản lý phiên bản và
  khôi phục. Các phiên hát đang diễn ra đọc đúng tài nguyên đã được ghim.
- Hủy queued/running, chống tác vụ trùng, retry và phục hồi trạng thái interrupted
  khi worker bị khởi động lại.

Image xử lý đã push lên Docker Hub:

`dreayrt/audio_separator_for_karaoke:0.5.0-processing-cpu`

Digest triển khai:

`sha256:6d71372185539400274abc6f42f7b26e67b9cbe9f448528696f27c580e5a83ec`

Frontend/backend dùng image `moodify-frontend:karaoke-admin-20261107-01` và
`moodify-backend:karaoke-admin-20261107-01`. Backend được ghép trên JAR production
thực tế; ba class karaoke được đối chiếu bytecode với baseline trước khi thay.
Cấu hình bảo mật production hiện hành được giữ, thêm chuỗi bảo mật riêng cho
API worker. Frontend được build trên source production hiện hành, giữ các cập
nhật recommendation/home đã triển khai trước đó.

Service mới: `moodify-karaoke-processing`, CPU-only, quota 1 CPU, một tác vụ đồng
thời. Kho file `/srv/moodify/karaoke` là bind mount; nguồn catalog/uploads mount
chỉ đọc vào worker. Nginx nhận upload multipart 130 MiB cho route quản trị mới
và chặn truy cập public vào thư mục ẩn chứa nguồn/nhật ký. Header audio của
endpoint nghe bản gốc đã kiểm chứng là `audio/x-wav` cho nguồn WAV.

## Kết quả kiểm chứng

- Java: các test mới về quyền worker, kiểu BSON ID, hàng đợi, bộ lọc lịch sử,
  lease, checksum và phiên bản đã qua; các test catalog/diagnostics liên quan
  cũng đã qua. Có 14 test Java liên quan được kiểm tra qua các lượt chạy.
- Python: 29 test API/scoring và 4 test worker qua.
- Frontend: TypeScript, ESLint trên file mới; TypeScript và production build
  trong Docker qua. Chunk quản trị mới tải được qua HTTPS production.
- API end-to-end chạy với controller, MongoDB và worker thật trong database riêng
  `karaoke_admin_verification_20261107`: phân quyền admin/nội bộ, upload, chống
  trùng, hủy queued, retry, tách CPU, bản nháp, từ chối checksum sai, xuất bản,
  khôi phục, tải nguồn và hủy tiến trình running đều qua.
- Khởi động lại worker khi đang xử lý: tiến trình con dừng; hết lease, job thành
  interrupted, khóa bài được giải phóng và retry được. Tác vụ CPU trước đó cũng
  hoàn tất sau khi API kiểm thử khởi động lại trong thời gian xử lý.
- Production worker online trong đúng database `music_streaming`; tại thời điểm
  kiểm tra có 111 bộ tài nguyên ready hiện hữu và chưa có job xử lý production
  nào do đợt kiểm thử này tạo. Thử nghiệm xuất bản chỉ tác động database cách ly.
- File karaoke hiện hữu trả HTTP Range 206. Trong các container có trước release,
  chỉ frontend/backend đổi ID; các dịch vụ khác tiếp tục chạy với ID cũ.
- Ba container kiểm thử đã được dừng sau nghiệm thu; giữ báo cáo để đối chiếu.

## Số đo và giới hạn

Instance đã xác minh có 2 vCPU và khoảng 8 GB RAM. Mẫu nhạc 20 giây trên CPU 1
luồng mất 579,7 giây ở lượt benchmark, khi có build ứng viên đồng thời. Lượt
end-to-end xử lý cùng độ dài mất khoảng 8 phút 22 giây tính từ lúc nhận tác vụ
đến khi backend xác nhận hoàn tất. Trong benchmark quan sát mức RAM khoảng
1,6–1,7 GiB; đây không phải số đo đỉnh bộ nhớ chính thức.

Mẫu là đoạn mở đầu, ít giọng hát nên pitch reference được đánh dấu
`reference_unreliable`. Hệ thống xử lý đúng trường hợp này: vẫn cho xuất bản
nhạc nền để hát, không bật chấm điểm. Chưa đo thời gian một bài hát đầy đủ trong
đợt này; không dùng số đo mẫu làm cam kết thời gian cho mọi bài.

Kiểm tra giao diện trực tiếp trong trình duyệt chưa hoàn tất: công cụ computer
use dừng vì không xác định chắc chắn URL hiện tại trên Windows. Không thao tác
tiếp hoặc dùng công cụ UI khác để vượt qua giới hạn này. Đã kiểm tra bản build,
chunk được phục vụ và API end-to-end độc lập.

## Bằng chứng và rollback

- Báo cáo local: `E:/python-audio-separator/artifacts/karaoke/admin-release/evidence/`.
- Thư mục release trên VM: `/home/kenresfx/karaoke-admin-20261107-01`.
- Compose override: `production.override.json` trong thư mục release.
- Key/env được giữ trên VM, không có trong bộ evidence hoặc tài liệu này.
- Rollback khi các container vẫn thuộc release này:

```bash
python3 /home/kenresfx/karaoke-admin-20261107-01/deploy.py --rollback
```

Rollback dừng worker mới, khôi phục frontend/backend, Nginx và source/JAR host
đã sao lưu. Không xóa bộ karaoke hiện hữu hoặc lịch sử tài nguyên.

Chi tiết API/vận hành: `docs/karaoke-resource-management.md`.
