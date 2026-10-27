# Rà soát cột lặp và quan hệ của lược đồ Moodify

Nguồn rà soát là bản SQL người dùng cung cấp cùng các truy vấn hiện tại trong backend. Chỉ thay đổi file và mã nguồn; chưa chạy SQL trên cơ sở dữ liệu đang có.

| Cột/nhóm cột | Kết luận | Xử lý |
| --- | --- | --- |
| `revenue_playback_sessions.user_id`, `track_id` | Lặp `listening_history.user_id`, `track_id`; phiên đã có `listening_history_id`. | Bỏ hai cột; truy vấn `JOIN listening_history`; thêm FK tới lịch sử nghe. |
| `revenue_playback_sessions.last_position_ms`, `total_played_ms` | Lặp `listening_history.last_position_ms`, `listened_duration_ms` và được cập nhật cùng mỗi heartbeat. | Bỏ bản sao; đọc/ghi ở `listening_history`. |
| `qualified_streams.listening_history_id` | Không có FK; có thể thành ID mồ côi khi người dùng xóa lịch sử nghe. Không được báo cáo doanh thu sử dụng. | Bỏ cột; `session_key` giữ tính duy nhất của lượt được trả tiền. |
| `platform_traffic_events.user_id` | Là tham chiếu tới `users` nhưng thiếu FK. | Thêm FK tùy chọn `ON DELETE SET NULL` để giữ lượt truy cập ẩn danh khi tài khoản bị xóa. |
| `qualified_streams.listener_user_id`, `content_lead_user_id`, `track_id`, các số tiền | Bản chụp tại thời điểm phát sinh doanh thu. Lịch sử nghe có thể bị xóa; track ở MongoDB có thể thay đổi; tỷ lệ chia có thể thay đổi. | Giữ để đối soát độc lập. Không cascade doanh thu theo lịch sử nghe hay tài khoản. Thêm CHECK tổng tiền gộp bằng hai phần chia. |
| `payment_transactions.amount` | Giá gói dịch vụ có thể thay đổi sau giao dịch. | Giữ số tiền giao dịch lịch sử. |
| `song_licenses.distributor_id` | Là FK tới `distributors`; còn dùng khi giấy phép được cấp qua nhà phân phối mà chưa gắn hợp đồng. Khi có hợp đồng, hai ID có thể trùng thông tin nhà phân phối. | Giữ để không làm mất trường hợp chưa có hợp đồng; backend kiểm tra hợp đồng thuộc đúng nhà phân phối, thay vì âm thầm chọn hợp đồng khác. |
| Các `track_id`, `artist_id`, `album_id` trỏ tới MongoDB | Không thể khai báo FK MySQL sang MongoDB. | Giữ tham chiếu logic; kiểm tra ở backend. |

Các FK riêng lẻ trên `song_licenses.distributor_id` và `distribution_contract_id` chỉ bảo đảm từng ID tồn tại, chưa bảo đảm chúng thuộc cùng một nhà phân phối. Backend đã được sửa để kiểm tra cặp ID khi upload. Có thể rà soát dữ liệu cũ bằng truy vấn chỉ đọc:

```sql
SELECT l.id, l.distributor_id AS license_distributor_id,
       c.distributor_id AS contract_distributor_id
FROM song_licenses l
JOIN distribution_contracts c ON c.id = l.distribution_contract_id
WHERE l.distributor_id <> c.distributor_id;
```

## Áp dụng với database đã có hai bảng doanh thu

1. Sao lưu database và tạm dừng request ghi phiên nghe.
2. Xem sự sai lệch trước khi đổi cấu trúc. Script migration sẽ tự dừng nếu có phiên mồ côi, bản sao trong phiên khác với `listening_history`, ID người dùng truy cập mồ côi hoặc số tiền chia không cân.
3. Chạy `database/normalize_redundant_references.sql` **một lần**. MySQL DDL tự commit; nếu một bước lỗi, kiểm tra trạng thái từng bảng trước khi chạy lại, không chạy lại cả file một cách mù quáng.
4. Khởi động backend đã cập nhật và cho phép request ghi phiên nghe trở lại.

`database/Moodify.annotated.schema.sql` dành cho database trống. `database/add_content_lead_revenue.sql` chỉ dùng với database cũ **chưa** có cột chi phí và hai bảng doanh thu. Vì database hiện tại đã có chúng, chỉ xem xét migration chuẩn hóa sau bước kiểm tra ở trên. Bản SQL dán kèm có `DROP DATABASE` và các lệnh `UPDATE`/`DELETE` thử nghiệm; các lệnh đó không được đưa vào bản schema đã sửa và chưa được thực thi.
