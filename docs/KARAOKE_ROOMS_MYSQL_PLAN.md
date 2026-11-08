# Kế hoạch: quản lý phòng Karaoke bằng MySQL

**Trạng thái:** Đã triển khai mã nguồn và áp dụng migration vào database Moodify local; chưa triển khai production
**Phạm vi:** Hai màn hình danh sách phòng và chi tiết phòng trong Moodify  
**Ước lượng sơ bộ:** 7–10 ngày làm việc cho MVP lưu dữ liệu và cập nhật phòng qua WebSocket

## 1. Đề xuất

Triển khai một MVP dùng MySQL làm nguồn dữ liệu chính cho thông tin phòng, thành viên và hàng đợi bài hát. REST xử lý các lệnh tạo/vào/rời phòng và thêm/rút bài; WebSocket đẩy thay đổi đến những client đang mở phòng. Người dùng đăng nhập mới có thể tạo phòng hoặc tham gia phòng. Backend Spring Boot xác thực quyền và cập nhật dữ liệu; frontend Next.js hiển thị snapshot đã lưu và nhận sự kiện realtime.

MVP này làm cho việc tạo phòng, danh sách, thành viên và bài hát chờ được lưu bền vững, dùng được giữa các trình duyệt. Nó chưa phát beat, truyền micro, đồng bộ lời hoặc kết nối âm thanh giữa nhiều người. Ở màn hình chi tiết, sân khấu minh họa hiện tại được thay bằng trạng thái chờ rõ ràng cho đến khi có tính năng realtime.

**Đánh giá khả thi: cao** cho phạm vi quản lý dữ liệu phòng và cập nhật realtime trên kết nối còn hoạt động. Hệ thống đã có Spring Boot, MySQL, JWT, người dùng quan hệ và API catalog Karaoke. Repository hiện chưa khai báo WebSocket hay Redis trong cấu hình được khảo sát; cần thêm WebSocket cho push. Redis chỉ cần nếu cần hiện diện dùng chung hoặc fan-out sự kiện qua nhiều backend instance. Hát trực tuyến vẫn là một dự án kế tiếp riêng với ước lượng lớn hơn.

## 2. Hiện trạng đã khảo sát

- Màn hình danh sách dùng `DEFAULT_KARAOKE_ROOMS` và `localStorage`; dữ liệu mẫu luôn được ghép vào danh sách. Số người, bài đang hát và trạng thái trên thẻ chưa đến từ server.
- Màn hình chi tiết có danh sách bài, thành viên, người đang hát, lời và tiến trình được khai báo cố định trong component. Bản xem trước cũng ghi rõ chưa có phát nhạc, micro hay kết nối phòng trực tuyến.
- Backend đã có `KaraokeController` cho catalog bài Karaoke và phiên chấm điểm; dữ liệu asset/phiên hiện tại đi qua MongoDB. Chưa có API hoặc bảng MySQL quản lý phòng.
- Backend đã có bảng `users` và xác thực JWT, nên chủ phòng và thành viên có thể liên kết bằng `user_id`.
- Cấu hình compose hiện có MySQL và MongoDB, chưa có Redis; chưa thấy backend WebSocket trong cấu hình hiện tại.
- Tài liệu `KARAOKE_IMPLEMENTATION_PLAN.md` hiện có mô tả kiến trúc hát realtime/WebRTC rộng hơn. Kế hoạch này tách riêng việc đưa quản lý phòng đang hiển thị vào MySQL, không coi lưu phòng là đồng nghĩa với đã có âm thanh trực tuyến.

### Đối chiếu schema MySQL bạn gửi

Đã rà soát các khai báo bảng trong file `Pasted text.txt`: có 26 bảng, chưa có bảng karaoke/phòng/thành viên phòng/hàng đợi và không có cột nào đang lưu cùng quan hệ đó. Vì vậy, ba bảng phòng mới không trùng một bảng nghiệp vụ đã có trong schema này.

- Tái sử dụng `users.id`, `users.full_name`, `users.avatar_url`, `users.role` và `users.status` qua join/foreign key; không sao chép tên, avatar, trạng thái tài khoản hay vai trò toàn hệ thống vào bảng phòng.
- `user_library_tracks`, `favorite_songs`, `listening_history` và `offline_downloads` có `user_id`/`track_id`, nhưng mô tả thư viện, yêu thích, phát cá nhân hoặc tải offline. Không thể dùng các dòng đó làm thành viên phòng hay yêu cầu trong hàng đợi vì thiếu quan hệ `room_id` và vòng đời Karaoke.
- `playback_events` và `revenue_playback_sessions` gắn với lịch sử nghe/doanh thu của một người; `user_devices.status` là trạng thái quyền của thiết bị. Những trường này không thay thế presence hoặc sự kiện của phòng.
- Tên cột `user_id`/`track_id` lặp lại giữa bảng không phải dữ liệu trùng: mỗi cột là một quan hệ khác nhau. Dùng FK tới `users.id` trong bảng thành viên/người gửi là cần thiết; `track_id` của hàng đợi là tham chiếu logic riêng tới MongoDB.

File đính kèm bắt đầu bằng `DROP DATABASE IF EXISTS moodify`; tuyệt đối không chạy file này để cập nhật database đang có dữ liệu. Mình đã kiểm tra `information_schema` trên database Moodify local trước khi áp dụng migration: ba bảng karaoke chưa tồn tại; `users.id` là `BIGINT`, bảng `users` dùng InnoDB. Database đang chạy có một số kiểu/độ dài cột `users` khác hai file DDL, nhưng không ảnh hưởng các khóa ngoại mới vì chúng chỉ tham chiếu `users.id`.

Đối chiếu thêm với `database/Moodify.sql` trong repository cho thấy hai bản DDL không đồng nhất: script trong repository có `albums`, `album_tracks`, `payment_webhook_logs` và `payment_idempotency_keys`, còn file đính kèm không có các bảng này. Cả hai bản đều không định nghĩa bảng phòng Karaoke, nhưng việc chọn migration cuối cùng phải dựa trên database đang chạy, không suy ra chỉ từ một trong hai file.

## 3. Phạm vi MVP đề xuất

### Bao gồm

1. Xem danh sách tất cả phòng còn mở; phòng riêng hiển thị thẻ khóa và chỉ vào được bằng mã mời.
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

Chỉ thêm migration SQL mới, không chạy lại file DDL đính kèm hoặc `Moodify.sql` trên database đang có dữ liệu. Thiết kế dưới đây đã loại các cột có thể tính được từ dữ liệu hiện có hoặc truy vấn từ MongoDB.

### `karaoke_rooms`

- `id`: khóa chính tự tăng.
- `name`: tên phòng.
- `host_user_id`: chủ phòng, khóa ngoại đến `users.id`.
- `max_members`: sức chứa được giới hạn theo các mức giao diện cho phép.
- `visibility`: `PUBLIC` hoặc `PRIVATE`.
- `closed_at`: `NULL` nghĩa là còn mở; có thời điểm nghĩa là đã đóng. Không lưu thêm `status` vì hai trạng thái MVP suy ra được từ cột này.
- `invite_code_hash`: mã mời đã băm, chỉ có giá trị với phòng riêng.
- `created_at`: thời điểm tạo phòng.
- Không lưu `last_activity_at`/`updated_at`: MVP không cho sửa phòng sau khi tạo; thời điểm phòng trở nên trống có thể lấy từ `left_at` của thành viên cuối cùng (hoặc `created_at` nếu chưa có lần rời nào) để thực hiện chính sách tự đóng.
- Không lưu `member_count`, `current_song_id` hay `room_version`: số người được tính từ presence; MVP chưa có bài đang phát; WebSocket event chỉ báo client tải lại snapshot mới nhất.

### `karaoke_room_members`

- `room_id`, `user_id`: khóa ngoại; duy nhất một bản ghi cho một cặp phòng/người dùng.
- Không lưu `role`: chủ phòng đã được xác định duy nhất ở `karaoke_rooms.host_user_id`; quyền chủ phòng kiểm tra qua quan hệ đó.
- `joined_at`, `left_at`: thời điểm gia nhập/rời hiện tại được lưu trong MySQL; MVP không giữ lịch sử từng lần reconnect.
- `left_at IS NULL` là ghế thành viên hiện còn hiệu lực; lúc rời phòng hoặc presence hết hạn, backend đặt `left_at`. `joined_at` được cập nhật khi thành viên vào lại; MVP không lưu từng lần reconnect thành lịch sử riêng.
- Presence của socket là trạng thái tạm thời, không phải cột MySQL. Một backend instance có thể theo dõi WebSocket đang kết nối và heartbeat trong bộ nhớ; khi chạy nhiều instance hoặc cần chia sẻ presence, dùng Redis TTL. Nếu socket mất đột ngột, lease/heartbeat hết hạn rồi backend đóng membership và phát event. Không thêm `last_seen_at` vào MySQL trong phương án cơ sở.
- Chủ phòng có thể đồng thời có một dòng membership khi đang ở phòng: `host_user_id` là quan hệ sở hữu, còn dòng membership là quan hệ đang tham gia. Không lưu thêm `role=HOST` lặp lại. Không lưu `member_count`, tên hay avatar trong bảng này.

### `karaoke_room_queue`

- `id`, `room_id`, `requester_user_id`, `track_id`.
- `track_id` là tham chiếu logic đến track trong MongoDB, không khai báo khóa ngoại xuyên database. Backend xác minh bài còn có asset Karaoke dùng được trước khi nhận yêu cầu.
- Mỗi người chỉ có một yêu cầu đang chờ trong một phòng; khóa duy nhất `(room_id, requester_user_id)` khớp hành vi giao diện và ngăn gửi trùng.
- Không sao chép tên bài, nghệ sĩ, ảnh bìa hay thời lượng từ catalog MongoDB; API tải metadata theo lô từ Mongo khi dựng snapshot phòng. Nếu track không còn tồn tại/không còn hợp lệ, API báo mục hàng đợi không khả dụng.
- `created_at` xác định thứ tự FIFO (sắp theo `created_at`, rồi `id`). MVP không cho kéo thả sắp xếp nên không cần `queue_position`; chỉ lưu yêu cầu đang chờ, rút lượt thì xóa dòng nên chưa cần `status`/`cancelled_at`.

Khi nhiều người cùng vào một phòng gần đầy, service khóa bản ghi phòng trong transaction rồi đếm các membership còn hiệu lực (`left_at IS NULL`) trước khi thêm người. Điều này tránh hai request đồng thời cùng vượt giới hạn sức chứa. Unique key và kiểm tra quyền ở backend ngăn đăng ký trùng hoặc rút lượt của người khác. MySQL giữ dữ liệu chuẩn; Redis chỉ giữ presence và/hoặc chuyển tiếp sự kiện, không thay thế ba bảng nghiệp vụ. Chỉ tạo index cần cho khóa ngoại, tra thành viên theo phòng và đọc hàng đợi FIFO; không thêm index dự phòng trước khi có query/tải thực tế chứng minh cần thiết.

## 5. API dự kiến

| API | Tác vụ |
|---|---|
| `GET /api/karaoke/rooms` | Danh sách phòng đang mở (công khai và riêng tư), chỉ gồm thông tin thẻ như host và số người hiện diện; không trả mã mời |
| `POST /api/karaoke/rooms` | Tạo phòng; trả về ID và mã mời nếu là phòng riêng |
| `GET /api/karaoke/rooms/{roomId}` | Snapshot phòng, thành viên và hàng đợi bài chờ |
| `POST /api/karaoke/rooms/{roomId}/join` | Vào phòng; gửi mã mời nếu phòng riêng |
| `POST /api/karaoke/rooms/{roomId}/leave` | Rời phòng |
| `POST /api/karaoke/rooms/{roomId}/queue` | Thêm bài đã chọn vào cuối hàng đợi |
| `DELETE /api/karaoke/rooms/{roomId}/queue/{itemId}` | Người gửi rút lượt chờ của mình hoặc chủ phòng gỡ lượt |
| `POST /api/karaoke/rooms/{roomId}/close` | Chủ phòng đóng phòng |

WebSocket dùng endpoint `/ws/karaoke` (WebSocket thuần, không dùng STOMP). Client gửi thông điệp đầu để xác thực JWT, kiểm tra thiết bị nếu có UUID, rồi đăng ký `subscribeRooms` hoặc `subscribeRoom` kèm `roomId`. Heartbeat mỗi 20 giây giữ presence; backend đóng kết nối im lặng sau timeout và cập nhật membership. Các lệnh thay đổi vẫn đi qua REST; sau khi transaction MySQL commit xong, backend phát event `rooms.updated`, `member.joined`, `member.left` hoặc `queue.updated`. Event chỉ báo thay đổi; client tải snapshot hiện thời từ API đọc MySQL thay vì duy trì một bản trạng thái cạnh tranh ở frontend. Vì vậy không cần thêm cột đếm phiên bản hay bảng event cho MVP.

Khi mất kết nối hoặc vừa vào phòng, client gọi lại API snapshot để đồng bộ; WebSocket là kênh push chứ không phải nguồn dữ liệu duy nhất. Nếu chạy nhiều backend instance, cần Redis Pub/Sub hoặc broker tương đương để event và presence tới các client đang kết nối ở instance khác. Các thao tác thay đổi cần JWT. Backend lấy `user_id` từ người dùng đã xác thực, không nhận `hostUserId` hay `requesterUserId` do frontend tự khai báo.

## 6. Thay đổi giao diện

### Danh sách phòng

- Bỏ `DEFAULT_KARAOKE_ROOMS` và `KARAOKE_ROOM_STORAGE_KEY`.
- Tải danh sách phòng công khai và riêng tư từ API; nút tạo phòng gửi form lên backend rồi điều hướng theo ID trả về. Trang danh sách có thể đăng ký event để cập nhật số phòng và trạng thái mà không cần tải lại.
- Lấy tên phòng từ API chi tiết thay vì dựa vào `?name=` trên URL.
- Card hiển thị host, hiện diện/công suất, quyền truy cập và hàng đợi thực tế. Với phòng chưa có bài, hiển thị trạng thái chưa chọn bài; không điền bài demo.
- Phòng riêng xuất hiện với biểu tượng khóa, nhãn cần mã mời và nút nhập mã; API chỉ trả thông tin thẻ, còn chi tiết phòng và hàng đợi cần membership hợp lệ.

### Chi tiết phòng

- Bỏ `INITIAL_QUEUE`, `MEMBERS`, người đang hát, lời và thanh tiến trình hardcode.
- Hiển thị snapshot thành viên và hàng đợi từ API; thêm/rút bài qua REST; cập nhật thành viên/hàng đợi qua WebSocket.
- Khi WebSocket reconnect, tải lại snapshot để khôi phục sự kiện bị lỡ.
- Khu vực sân khấu giữ phong cách giao diện hiện có nhưng hiển thị trạng thái chờ, ví dụ “Sân khấu chưa phát trực tuyến”, thay vì giả lập người hát và thời gian.
- Nếu hết phòng hoặc người dùng không có quyền, thông báo đúng lỗi và có đường quay lại danh sách.

## 7. Các giai đoạn và thời gian

| Giai đoạn | Ước lượng | Kết quả |
|---|---:|---|
| 0. Chốt quy tắc phòng và migration | 0.5 ngày | Quy tắc mã mời, thời hạn phòng, giới hạn người và API được thống nhất |
| 1. MySQL và backend | 2–3 ngày | Migration cộng thêm, entity/repository/service/API, xác thực quyền và xử lý join đồng thời |
| 2. WebSocket và presence | 2–3 ngày | Push event sau commit; presence heartbeat/TTL; khôi phục snapshot sau reconnect |
| 3. Nối danh sách và chi tiết | 2–3 ngày | REST snapshot + WebSocket events; có trạng thái lỗi/rỗng/tải; bỏ sân khấu giả |
| 4. Kiểm tra luồng và hoàn thiện | 1 ngày | Rà soát tạo/vào/rời/đóng phòng, phòng đầy/riêng tư, reconnect, nhiều tab và nhiều phiên |

**Tổng:** khoảng **7–10 ngày làm việc** cho một người đã quen codebase, tương đương khoảng **1.5–2 tuần**. Đây là cập nhật dữ liệu phòng realtime qua WebSocket, chưa phải truyền âm thanh realtime. Nếu cần nhiều backend instance và Redis Pub/Sub, dự trù thêm khoảng **1–3 ngày** cho cấu hình, fan-out và kiểm tra vận hành. Không bao gồm WebRTC, song ca, chi phí LiveKit/TURN hay rà soát pháp lý quyền bài hát.

### Trạng thái triển khai trong workspace

- Đã thêm ba bảng bằng migration cộng thêm `database/add_karaoke_room_management.sql`, entity/repository/service và REST API.
- Đã nối danh sách, tạo phòng, phòng riêng bằng mã mời đã băm, thành viên, hàng đợi và các thao tác rời/đóng phòng vào backend.
- Đã thêm WebSocket một instance với heartbeat, reconnect và tải lại snapshot. Chưa thêm Redis; nếu triển khai nhiều backend instance thì presence và sự kiện chưa được chia sẻ giữa các instance.
- Đã gỡ dữ liệu giả khỏi hai màn hình; khu vực sân khấu hiện báo chưa phát nhạc/micro.
- Danh sách hiện hiển thị cả phòng riêng đang mở dưới dạng thẻ khóa; người dùng nhập mã ở trang phòng trước khi được vào.
- Đã tạo quy tắc tự đóng sau 24 giờ phòng không còn thành viên và chỉ giữ một yêu cầu chờ trên mỗi người trong mỗi phòng.
- Đã áp dụng migration trên database Moodify local sau khi kiểm tra `information_schema`; xác nhận ba bảng là InnoDB, các khóa/index cần thiết đã có và cả ba bảng đang rỗng, không seed dữ liệu mẫu.
- Chưa triển khai migration lên production; trước khi triển khai cần chạy lại bước preflight trên đúng database production. Không chạy toàn bộ DDL có `DROP DATABASE`.

## 8. Điều kiện nghiệm thu

1. Tạo phòng xong tải lại trang hoặc mở trình duyệt khác vẫn thấy phòng trong trạng thái đúng.
2. Thông tin host, sức chứa, quyền riêng tư và hàng đợi lấy từ server; không hiện ba phòng mẫu sau khi API trả về danh sách rỗng.
3. Phòng đầy từ chối thành viên tiếp theo kể cả khi hai người gửi yêu cầu cùng lúc.
4. Người chưa có mã không vào được phòng riêng; người dùng không thể giả danh host hoặc rút lượt của thành viên khác.
5. Số người đang hiện diện cập nhật khi kết nối phòng thay đổi; presence hết hạn sau ngắt kết nối đột ngột. Trên kết nối khỏe, event được đẩy đến client khác mà không cần reload trang.
6. Người dùng có thể thêm/rút bài chờ và trạng thái được giữ sau khi tải lại.
7. Giao diện không thể hiện ca sĩ, lời hay tiến trình bài đang phát nếu chưa có phát trực tuyến thật.

## 9. Rủi ro và quyết định cần chốt

| Câu hỏi | Khuyến nghị |
|---|---|
| Phòng riêng hoạt động thế nào? | Mã mời ngẫu nhiên; chỉ backend xác nhận quyền vào |
| Phòng tồn tại bao lâu? | Tự đóng sau 24 giờ không có thành viên hiện diện; mốc thời gian lấy từ `left_at`, không thêm cột hoạt động riêng |
| Cần cập nhật thành viên và hàng đợi tức thì không? | Có; REST ghi dữ liệu, WebSocket push thông báo sau commit, tải snapshot mới khi có event hoặc reconnect |
| Có cần Redis ngay không? | Nếu chỉ một backend instance, có thể bắt đầu WebSocket + presence TTL/heartbeat. Nếu cần nhiều instance hoặc presence phân tán, thêm Redis; compose hiện chưa có Redis |
| Có cần hàng đợi bền vững ngay không? | Có; màn hình thứ hai đang có danh sách chờ và cần bỏ dữ liệu minh họa |
| Có cần hát trực tuyến trong cùng task không? | Khuyến nghị không gộp; việc đó cần chọn giải pháp WebRTC, kiểm thử mạng và dự toán riêng |
| Có cần giữ phòng mẫu sau khi bật database? | Khuyến nghị không; dùng migration trống, không tự tạo phòng demo trong production |

**Các quyết định đã dùng khi triển khai:** phạm vi MVP trong mục 3, mã mời cho phòng riêng, tự đóng sau 24 giờ không có thành viên, WebSocket một instance và sân khấu chờ đến khi có tính năng âm thanh. Redis được để ngoài phạm vi hiện tại; cần bổ sung trước khi chạy nhiều backend instance.

## 10. Ranh giới với dự án hát trực tuyến

Tài liệu hiện có `docs/KARAOKE_IMPLEMENTATION_PLAN.md` ước lượng MVP hát theo lượt khoảng 3–5 tuần, do cần asset beat/lời đã duyệt, quyền phát nhạc, âm thanh WebRTC và kiểm thử thiết bị/mạng. Phần MySQL trong kế hoạch này là nền dữ liệu hữu ích cho dự án đó, nhưng không tự tạo ra đồng bộ nhạc hay kết nối micro. Song ca đồng thời vẫn là thử nghiệm kỹ thuật riêng.
