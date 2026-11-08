# Tạo tài khoản nghệ sĩ theo kho nhạc — 29/10/2026

Đã tạo 83 tài khoản local và 84 tài khoản production. Mỗi môi trường có một tài khoản đã liên kết được giữ nguyên, nâng tổng số nghệ sĩ có bài và có tài khoản lần lượt lên 84 và 85. Dữ liệu hai môi trường khác nhau; không sao chép tài khoản hoặc mật khẩu giữa hai nơi.

## Phạm vi

- Chỉ thêm hàng vào bảng `users` đang có, quyền `CONTENT_LEAD`, trạng thái `ACTIVE`.
- Chỉ chọn `artist_spotify_id` có trong bài hát và khớp chính xác, duy nhất với hồ sơ trong MongoDB `artists`.
- Giữ nguyên mọi tài khoản đã có, kể cả mật khẩu, trạng thái và quyền. Không chuyển quyền tài khoản cũ.
- Không tạo hồ sơ cho bài thiếu mã nghệ sĩ: local còn 2 bài, production còn 1 bài.
- Không sửa giao diện, bài hát, giấy phép, dữ liệu duyệt, lịch sử nghe, bảng hoặc collection.
- Mỗi tài khoản mới có mật khẩu ngẫu nhiên riêng, lưu trong MySQL dưới dạng BCrypt. Email đuôi `@artist.moodify.local` là địa chỉ định danh, không phải hộp thư nhận email. Đăng nhập bằng username và mật khẩu trong file bàn giao.
- Đây là tài khoản được quản trị viên tạo từ hồ sơ kho nhạc; thao tác này không xác minh danh tính người nghệ sĩ ngoài đời hay cấp quyền bản quyền cho bài hát.

## Kết quả kiểm tra

- Local: thêm 83, giữ nguyên 6 hàng users trước đó.
- Production: thêm 84, giữ nguyên 6 hàng users trước đó.
- Đối chiếu mật khẩu BCrypt và liên kết duy nhất cho toàn bộ tài khoản mới trước khi commit thành công.
- Chạy lại ở chế độ xem trước: cả hai môi trường đều có 0 tài khoản cần tạo thêm.
- Production: đăng nhập thật và đọc `/api/content-lead/me/catalog` thành công bằng một tài khoản mới.
- Local: yêu cầu đăng nhập qua backend cổng 8080 bị timeout; chưa xác nhận đăng nhập qua HTTP local. Dữ liệu tài khoản và mật khẩu đã kiểm tra trực tiếp thành công.

## Chạy lại

Script `scripts/provision_artist_accounts.py` thay cho việc chạy toàn bộ `scripts/sync_data.py` cũ ở thư mục cha. Script cũ còn xóa/ghi kho nhạc, tạo giấy phép mẫu và thay dữ liệu duyệt nên không phù hợp tác vụ tạo tài khoản đơn lẻ.

Chạy bằng Python trong `recommendation-worker/.venv`, cần `pymysql`, `pymongo`, `python-dotenv`, `bcrypt`:

```powershell
.\recommendation-worker\.venv\Scripts\python.exe scripts/provision_artist_accounts.py --env .env --output storage/artist-provision/<dot-moi>/preview
.\recommendation-worker\.venv\Scripts\python.exe scripts/provision_artist_accounts.py --env .env --output storage/artist-provision/<dot-moi>/apply --apply
```

Mỗi lần chạy dùng thư mục mới vì script không ghi đè file cũ. Mặc định chỉ xem trước. Nếu có xung đột khi thêm hoặc kiểm tra thất bại, transaction được rollback. Không có bước tự động gửi thông tin đăng nhập cho nghệ sĩ.

## File bàn giao và khôi phục

Trong `storage/artist-provision/20261029/{local,production}-applied/` có:

- `accounts.csv`: tên nghệ sĩ, username, email, mật khẩu, mã nghệ sĩ, số bài. File có mật khẩu, chỉ chia sẻ từng tài khoản với người được phép.
- `credentials.json`: thông tin tạo tài khoản, mật khẩu và BCrypt hash.
- `users-before.json`: bản sao users trước khi thêm.
- `inserted-users.json`: ID chính xác của các tài khoản mới, phục vụ đối chiếu/khôi phục có kiểm soát.
- `preview.json`: danh sách dự kiến và các trường hợp bỏ qua.

CSV được xuất sau khi thao tác hoàn tất. Các file chứa thông tin đăng nhập nằm ngoài Git, thư mục được giới hạn quyền cho tài khoản Windows hiện tại. Không đính kèm chúng vào commit. Nếu cần hoàn tác, phải kiểm tra những tài khoản này đã phát sinh dữ liệu liên quan hay chưa trước khi xóa theo ID; không phục hồi đè toàn bộ users.
