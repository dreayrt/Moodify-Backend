# Moodify backend

## Bàn giao công cụ `mood_tags`

Công cụ này lấy profile âm thanh từ `Model-Detect_CamXuc/DetectEmotions/music_mapping.py`, so sánh với các chỉ số của collection `tracks`, rồi tạo file để người phụ trách duyệt. **Audit và preview chỉ đọc MongoDB; chỉ `-Mode apply` mới ghi field `mood_tags`.** Backend không tự phê duyệt tag.

### Chuẩn bị

- Cài Java 21, Python 3 và bảo đảm MongoDB, MySQL truy cập được.
- Trong backend, tạo `.env` từ `.env.example` và điền cấu hình đúng môi trường. Kiểm tra `MONGODB_URI` và `MONGODB_DATABASE` trước khi áp dụng. Không đưa `.env` hoặc mật khẩu vào Git.
- Đặt repository model cạnh backend theo cấu trúc mặc định `E:\Model-Detect_CamXuc\DetectEmotions\music_mapping.py`. Nếu đặt chỗ khác, thêm `-ModelProfileSource "<đường dẫn đến music_mapping.py>"` vào cả lệnh preview và apply.
- Không cần chạy API server hay model server riêng: script sẽ dùng Maven wrapper để khởi động một Spring Boot command, rồi thoát.

### 1. Audit database hiện tại (không ghi)

Kết nối `mongosh` vào MongoDB cần kiểm tra, chọn database theo `.env`, sau đó chạy:

```javascript
use music_streaming
db.getName()
load("E:/moodify-backend/scripts/audit-mood-tags.mongosh.js")
```

Đổi đường dẫn và tên database nếu máy hoặc môi trường khác. Báo cáo in ra `totalTracks`, `withMoodTags`, `moodTagFrequencies` và các track mẫu chưa có tag. `withMoodTags` chỉ đếm các mảng chứa tag không rỗng. Nếu database đã có tag đúng, **không chạy apply lại**.

### 2. Preview và duyệt (chưa ghi)

Các lệnh dưới đây dành cho **Windows CMD**; mỗi lệnh là **một dòng**. Không dùng dấu backtick (`` ` ``) để xuống dòng trong CMD.

```bat
cd /d E:\moodify-backend
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mood-tags.ps1 -Mode preview -MaxDistance 0.35 -MinMargin 0.05
```

Preview tạo file mới `docs\mood-tags-preview-<thời gian>.json`. `0.35` và `0.05` chỉ giúp xác định **ứng viên để duyệt**, không tự gán tag. Sao chép file preview sang một file reviewed mới, xem chỉ số và điểm của **từng track**, rồi điền `approvedMoodTags` bằng đúng một nhãn đã duyệt (ví dụ `["sadness"]`) hoặc `[]` khi chưa đủ căn cứ. Giữ nguyên các trường còn lại. Danh sách nhãn hợp lệ: `enjoyment`, `sadness`, `anger`, `fear`, `surprise`, `disgust`, `other`.

### 3. Ghi `mood_tags` sau khi duyệt

Thay `TEN_FILE_REVIEWED.json` bằng tên file vừa lưu. Chọn tên manifest **mới, chưa tồn tại** và giữ file này để rollback.

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mood-tags.ps1 -Mode apply -ReviewedFile .\docs\TEN_FILE_REVIEWED.json -ManifestFile .\docs\mood-tags-rollback-new.json
```

Apply đối chiếu dữ liệu MongoDB, profile model và các chỉ số âm thanh với bản preview trước khi ghi. Nếu catalog hoặc model đã đổi, tạo preview mới rồi duyệt lại; không dùng file reviewed cũ. File `docs\mood-tags-rollback-20261016.json` của lần chạy trước đã có trạng thái `APPLIED`: **không dùng lại tên manifest đó** và không xem nó là bằng chứng database mới đã được cập nhật.

Kiểm tra kết quả trong `mongosh`:

```javascript
db.tracks.countDocuments({ "mood_tags.0": { $exists: true } })
db.tracks.find({ "mood_tags.0": { $exists: true } }, { spotify_id: 1, name: 1, mood_tags: 1 }).limit(5)
```

Chạy lại audit ở bước 1 để xem phân bố tag. Track được duyệt `[]` có thể có field `mood_tags` rỗng nhưng không nằm trong số đếm trên.

### 4. Rollback khi cần

Chỉ dùng **đúng manifest do lần apply đó tạo ra**:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mood-tags.ps1 -Mode rollback -ManifestFile .\docs\mood-tags-rollback-new.json
```

Rollback chỉ khôi phục hoặc bỏ field `mood_tags` về trạng thái trước lần apply đó; lệnh sẽ từ chối nếu tag đã bị sửa tiếp. Xem [hướng dẫn chi tiết](docs/mood-tag-tool.md) và [kết quả audit ban đầu](docs/mood-tag-audit.md).

**Khi bàn giao qua Git:** kiểm tra `git status` và đưa cả script, tài liệu, mã nguồn backend liên quan vào commit. Các file preview/review/rollback là dữ liệu của từng lần chạy; lưu và chuyển giao riêng khi cần audit hoặc rollback. Không commit `.env`.
