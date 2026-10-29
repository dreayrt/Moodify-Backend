# Kế hoạch: quản lý phòng Karaoke bằng MySQL

**Trạng thái:** Đề xuất để chủ dự án quyết định; chưa triển khai mã nguồn  
**Phạm vi:** Hai màn hình danh sách phòng và chi tiết phòng trong Moodify  
**Ước lượng sơ bộ:** 5–7 ngày làm việc cho MVP lưu phòng, thành viên và hàng đợi

## 1. Đề xuất

Triển khai một MVP dùng MySQL làm nguồn dữ liệu chính cho thông tin phòng, thành viên đang tham gia và hàng đợi bài hát. Người dùng đăng nhập mới có thể tạo phòng hoặc tham gia phòng. Backend Spring Boot xác thực quyền và cập nhật dữ liệu; frontend Next.js chỉ hiển thị kết quả từ API.

MVP này làm cho việc tạo phòng, danh sách, thành viên và bài hát chờ được lưu bền vững, dùng được giữa các trình duyệt. Nó chưa phát beat, truyền micro, đồng bộ lời hoặc kết nối âm thanh giữa nhiều người. Ở màn hình chi tiết, sân khấu minh họa hiện tại được thay bằng trạng thái chờ rõ ràng cho đến khi có tính năng realtime.

**Đánh giá khả thi: cao** cho phạm vi quản lý dữ liệu phòng. Hệ thống đã có Spring Boot, MySQL, JWT, người dùng quan hệ và API catalog Karaoke. Phần tốn công chính là thiết kế trạng thái tham gia và hàng đợi, xử lý đồng thời khi phòng gần đầy, cùng việc nối API vào hai màn hình. Hát trực tuyến là một dự án kế tiếp riêng với ước lượng lớn hơn.

## 2. Hiện trạng đã khảo sát

- Màn hình danh sách dùng `DEFAULT_KARAOKE_ROOMS` và `localStorage`; dữ liệu mẫu luôn được ghép vào danh sách. Số người, bài đang hát và trạng thái trên thẻ chưa đến từ server.
- Màn hình chi tiết có danh sách bài, thành viên, người đang hát, lời và tiến trình được khai báo cố định trong component. Bản xem trước cũng ghi rõ chưa có phát nhạc, micro hay kết nối phòng trực tuyến.
- Backend đã có `KaraokeController` cho catalog bài Karaoke và phiên chấm điểm; dữ liệu asset/phiên hiện tại đi qua MongoDB. Chưa có API hoặc bảng MySQL quản lý phòng.
- Backend đã có bảng `users` và xác thực JWT, nên chủ phòng và thành viên có thể liên kết bằng `user_id`.
- Tài liệu `KARAOKE_IMPLEMENTATION_PLAN.md` hiện có mô tả kiến trúc hát realtime/WebRTC rộng hơn. Kế hoạch này tách riêng việc đưa quản lý phòng đang hiển thị vào MySQL, không coi lưu phòng là đồng nghĩa với đã có âm thanh trực tuyến.

## 3. Phạm vi MVP đề xuất

### Bao gồm

1. Xem danh sách phòng công khai còn mở, phân trang đơn giản khi cần.
2. Tạo phòng với tên, số người tối đa và quyền công khai/riêng tư.
3. Vào phòng, rời phòng; kiểm tra giới hạn sức chứa ở backend.
4. Phòng riêng chỉ vào được bằng mã mời ngẫu nhiên.
5. Xem tên chủ phòng, số thành viên còn hiện diện, trạng thái phòng và hàng đợi bài hát.
6. Thêm bài vào hàng đợi từ catalog Karaoke hiện có; người gửi được rút lượt chờ của mình, chủ phòng có thể gỡ lượt.
7. Chủ phòng đóng phòng. Phòng đã đóng không còn xuất hiện trong danh sách công khai.
8. Có trạng thái tải, rỗng, lỗi và thử tải lại; không âm thầm thay lỗi API bằng dữ liệu mẫu.

### Chưa bao gồm

- Phát beat, micro/WebRTC, đồng bộ thời gian bài hát hoặc lời trực tiếp.
- Đánh dấu một bài là đang phát nếu chưa có engine phát chung; không hiển thị ca sĩ/tiến trình giả.
- Chat, song ca đồng thời, lời mời bạn bè, công cụ điều hành/moderator và trang quản trị riêng.
- Thay đổi catalog bài hát hoặc quy trình chuẩn bị asset Karaoke.

## 4. Mô hình dữ liệu đề xuất

Thêm migration SQL mới, không chạy lại `Moodify.sql` trên database đang có dữ liệu vì file đó dựng lại database.

### `karaoke_rooms`

- `id`: khóa chính tự tăng.
- `name`: tên phòng.
- `host_user_id`: chủ phòng, khóa ngoại đến `users.id`.
- `max_members`: sức chứa được giới hạn theo các mức giao diện cho phép.
- `visibility`: `PUBLIC` hoặc `PRIVATE`.
- `status`: `OPEN` hoặc `CLOSED` trong MVP.
- `invite_code_hash`: mã mời đã băm, chỉ có giá trị với phòng riêng.
- `created_at`, `updated_at`, `closed_at`, `last_activity_at`.

### `karaoke_room_members`

- `room_id`, `user_id`: khóa ngoại; duy nhất một bản ghi cho một cặp phòng/người dùng.
- `role`: `HOST` hoặc `MEMBER`.
- `joined_at`, `last_seen_at`, `left_at`.
- Thành viên được tính là đang hiện diện khi `left_at IS NULL` và heartbeat gần nhất còn mới. Frontend gửi heartbeat định kỳ; số người hiển thị là số user duy nhất có mặt, không phải số lần bấm vào phòng.

### `karaoke_room_queue`

- `id`, `room_id`, `requester_user_id`, `track_id`.
- `track_id` là tham chiếu logic đến track trong MongoDB, không khai báo khóa ngoại xuyên database. Backend xác minh bài còn có asset Karaoke dùng được trước khi nhận yêu cầu.
- Lưu thêm snapshot tên bài/nghệ sĩ để hàng đợi vẫn có thể hiển thị nếu catalog thay đổi.
- `queue_position`, `status` (`WAITING`, `CANCELLED`; các trạng thái phát bài để dành cho giai đoạn realtime), `created_at`.

Khi nhiều người cùng vào một phòng gần đầy, service khóa bản ghi phòng trong transaction rồi đếm thành viên trước khi thêm người. Điều này tránh hai request đồng thời cùng vượt giới hạn sức chứa. Unique key và kiểm tra quyền ở backend ngăn đăng ký trùng hoặc rút lượt của người khác.

## 5. API dự kiến

| API | Tác vụ |
|---|---|
| `GET /api/karaoke/rooms` | Danh sách phòng công khai đang mở kèm host, số người và bài đầu hàng đợi nếu có |
| `POST /api/karaoke/rooms` | Tạo phòng; trả về ID và mã mời nếu là phòng riêng |
| `GET /api/karaoke/rooms/{roomId}` | Snapshot phòng, thành viên và hàng đợi bài chờ |
| `POST /api/karaoke/rooms/{roomId}/join` | Vào phòng; gửi mã mời nếu phòng riêng |
| `POST /api/karaoke/rooms/{roomId}/leave` | Rời phòng |
| `POST /api/karaoke/rooms/{roomId}/presence` | Gia hạn hiện diện khi người dùng vẫn ở phòng |
| `POST /api/karaoke/rooms/{roomId}/queue` | Thêm bài đã chọn vào cuối hàng đợi |
| `DELETE /api/karaoke/rooms/{roomId}/queue/{itemId}` | Người gửi rút lượt chờ của mình hoặc chủ phòng gỡ lượt |
| `POST /api/karaoke/rooms/{roomId}/close` | Chủ phòng đóng phòng |

Các thao tác thay đổi cần JWT. Backend lấy `user_id` từ người dùng đã xác thực, không nhận `hostUserId` hay `requesterUserId` do frontend tự khai báo. Giai đoạn đầu dùng polling snapshot khoảng 10–15 giây; nếu sau này cần cập nhật tức thì mới bổ sung WebSocket/SSE.

## 6. Thay đổi giao diện

### Danh sách phòng

- Bỏ `DEFAULT_KARAOKE_ROOMS` và `KARAOKE_ROOM_STORAGE_KEY`.
- Tải danh sách từ API; nút tạo phòng gửi form lên backend rồi điều hướng theo ID trả về.
- Lấy tên phòng từ API chi tiết thay vì dựa vào `?name=` trên URL.
- Card hiển thị host, hiện diện/công suất, quyền truy cập và hàng đợi thực tế. Với phòng chưa có bài, hiển thị trạng thái chưa chọn bài; không điền bài demo.
- Phòng riêng không xuất hiện ở danh sách công khai.

### Chi tiết phòng

- Bỏ `INITIAL_QUEUE`, `MEMBERS`, người đang hát, lời và thanh tiến trình hardcode.
- Hiển thị thành viên và hàng đợi lấy từ API; thêm/rút bài qua API.
- Khu vực sân khấu giữ phong cách giao diện hiện có nhưng hiển thị trạng thái chờ, ví dụ “Sân khấu chưa phát trực tuyến”, thay vì giả lập người hát và thời gian.
- Nếu hết phòng hoặc người dùng không có quyền, thông báo đúng lỗi và có đường quay lại danh sách.

## 7. Các giai đoạn và thời gian

| Giai đoạn | Ước lượng | Kết quả |
|---|---:|---|
| 0. Chốt quy tắc phòng và migration | 0.5 ngày | Quy tắc mã mời, thời hạn phòng, giới hạn người và API được thống nhất |
| 1. MySQL và backend | 2–3 ngày | Migration cộng thêm, entity/repository/service/API, xác thực quyền và xử lý join đồng thời |
| 2. Nối danh sách phòng | 1 ngày | Tải/tạo phòng từ API; có trạng thái lỗi/rỗng/tải |
| 3. Nối chi tiết phòng | 1–2 ngày | Thành viên, hiện diện và hàng đợi dùng dữ liệu backend; bỏ sân khấu giả |
| 4. Kiểm tra luồng và hoàn thiện | 0.5–1 ngày | Rà soát tạo/vào/rời/đóng phòng, phòng đầy/riêng tư, refresh và nhiều phiên |

**Tổng:** khoảng **5–7 ngày làm việc** cho một người đã quen codebase, tương đương khoảng **1–1.5 tuần**. Không bao gồm phát nhạc realtime, song ca, chi phí LiveKit/TURN hay rà soát pháp lý quyền bài hát.

## 8. Điều kiện nghiệm thu

1. Tạo phòng xong tải lại trang hoặc mở trình duyệt khác vẫn thấy phòng trong trạng thái đúng.
2. Thông tin host, sức chứa, quyền riêng tư và hàng đợi lấy từ server; không hiện ba phòng mẫu sau khi API trả về danh sách rỗng.
3. Phòng đầy từ chối thành viên tiếp theo kể cả khi hai người gửi yêu cầu cùng lúc.
4. Người chưa có mã không vào được phòng riêng; người dùng không thể giả danh host hoặc rút lượt của thành viên khác.
5. Số người đang hiện diện cập nhật theo heartbeat và tự hết hạn khi trình duyệt đóng đột ngột.
6. Người dùng có thể thêm/rút bài chờ và trạng thái được giữ sau khi tải lại.
7. Giao diện không thể hiện ca sĩ, lời hay tiến trình bài đang phát nếu chưa có phát trực tuyến thật.

## 9. Rủi ro và quyết định cần chốt

| Câu hỏi | Khuyến nghị |
|---|---|
| Phòng riêng hoạt động thế nào? | Mã mời ngẫu nhiên; chỉ backend xác nhận quyền vào |
| Phòng tồn tại bao lâu? | Tự đóng sau 24 giờ không hoạt động, thời hạn cấu hình được |
| Cần cập nhật thành viên tức thì không? | MVP dùng heartbeat 30 giây và polling 10–15 giây; WebSocket/SSE để sau |
| Có cần hàng đợi bền vững ngay không? | Có; màn hình thứ hai đang có danh sách chờ và cần bỏ dữ liệu minh họa |
| Có cần hát trực tuyến trong cùng task không? | Khuyến nghị không gộp; việc đó cần chọn giải pháp WebRTC, kiểm thử mạng và dự toán riêng |
| Có cần giữ phòng mẫu sau khi bật database? | Khuyến nghị không; dùng migration trống, không tự tạo phòng demo trong production |

**Điểm cần chủ dự án duyệt trước khi triển khai:** phạm vi MVP trong mục 3, phương án mã mời cho phòng riêng, thời hạn tự đóng 24 giờ và chấp nhận màn hình sân khấu ở trạng thái chờ cho đến giai đoạn âm thanh realtime.

## 10. Ranh giới với dự án hát trực tuyến

Tài liệu hiện có `docs/KARAOKE_IMPLEMENTATION_PLAN.md` ước lượng MVP hát theo lượt khoảng 3–5 tuần, do cần asset beat/lời đã duyệt, quyền phát nhạc, âm thanh WebRTC và kiểm thử thiết bị/mạng. Phần MySQL trong kế hoạch này là nền dữ liệu hữu ích cho dự án đó, nhưng không tự tạo ra đồng bộ nhạc hay kết nối micro. Song ca đồng thời vẫn là thử nghiệm kỹ thuật riêng.
