# Cập nhật hàng đợi phòng Karaoke

Mỗi thành viên có thể thêm nhiều bài khác nhau và rút riêng từng bài. Một bài
không được đăng ký trùng bởi cùng người khi vẫn đang nằm trong hàng đợi.
Chủ phòng có nút lên/xuống trên từng bài để đổi thứ tự phát, kể cả bài do người
khác đăng ký. Các thành viên nhận danh sách mới qua sự kiện `queue.updated`.
Bài đang phát không bị thay đổi khi sắp xếp những bài đang chờ.

## Cơ sở dữ liệu và triển khai

1. Tạm dừng thao tác ghi hàng đợi và kiểm tra `SHOW INDEX FROM karaoke_room_queue`.
2. Nếu còn index duy nhất `uk_karaoke_room_queue_requester`, chạy
   `database/allow_multiple_karaoke_queue_items.sql` để gỡ giới hạn một bài/người.
   Hibernate không tự xóa index cũ này.
3. Chạy `database/add_karaoke_queue_position.sql`. Nếu Hibernate đã tạo cột
   `queue_position`, bỏ qua lệnh thêm cột; nếu index tương ứng đã có thì bỏ qua
   lệnh tạo index. Không xóa các bài đã đăng ký.
4. Triển khai backend cùng frontend trong `E:/Moodify/frontend`, rồi mở lại
   thao tác ghi hàng đợi. Giao diện mới cần API backend mới để sắp xếp.

Các dòng cũ có vị trí 0 vẫn giữ thứ tự `created_at, id`. Lần sắp xếp đầu tiên
chuẩn hóa vị trí. Bài mới luôn được thêm cuối hàng đợi. Backend khóa bản ghi
phòng trong lúc thêm, rút, sắp xếp hoặc chuyển bài để tránh ghi chồng thứ tự.

## API sắp xếp

`PUT /api/karaoke/rooms/{roomId}/queue/{itemId}/position`

```json
{ "direction": "UP" }
```

`direction` nhận `UP` hoặc `DOWN`. Chỉ chủ phòng đang là thành viên hoạt động
được thực hiện. API trả 204; ở đầu/cuối danh sách, thao tác ra ngoài biên không
thay đổi dữ liệu. Bài đã được phát/rút hoặc thuộc phòng khác trả 404.

## Kiểm tra

- Backend: `./mvnw.cmd -Dtest=KaraokeRoomQueueTest,KaraokeRoomQueueApiTest,KaraokeRoomCatalogVisibilityTest test`
- Frontend: `npx tsc --noEmit --incremental false`
- Giao diện thực với dữ liệu và media giả lập: `node scripts/test-karaoke-room-queue.mjs`
  (cần Playwright; có thể chỉ định `PLAYWRIGHT_MODULE` và
  `PLAYWRIGHT_CHROMIUM_EXECUTABLE` tới bản cài sẵn).

Kiểm tra trên phòng thử: cùng một người thêm hai bài; chủ phòng đổi thứ tự bài
của thành viên; thành viên khác thấy cập nhật; tải lại trang vẫn giữ thứ tự;
bấm bắt đầu/chuyển bài phát đúng bài đứng đầu mới.
