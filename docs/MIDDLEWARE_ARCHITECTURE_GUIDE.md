# Kiến Trúc Middleware và Bảo Vệ Route Phân Quyền (RBAC) - Moodify

Tài liệu giải thích lý do thiết kế, cơ chế hoạt động và cách triển khai Middleware trong hệ sinh thái **Moodify (Next.js Frontend + Spring Boot Backend)**.

---

## 1. Bối cảnh & Bản chất vấn đề

### 1.1 Hiện tượng
Khi người dùng đăng nhập tài khoản có role **`MODERATOR`**, sau đó tự ý đổi đường dẫn URL trên trình duyệt thành:
```text
http://localhost:3000/dashboard/user
```
Trình duyệt vẫn tải và hiển thị khung giao diện (UI) của trang User Dashboard, mặc dù ở Backend Spring Boot đã cấu hình chỉ cho phép `USER`.

### 1.2 Nguyên nhân cốt lõi
1. **Frontend và Backend chạy hoàn toàn độc lập:**
   - Cổng `3000` (Next.js Frontend) và Cổng `8080` (Spring Boot API).
   - Khi gõ URL trên trình duyệt, trình duyệt gửi request trực tiếp đến **Server Next.js (port 3000)** để lấy mã HTML/JavaScript của Component. Lúc này **hoàn toàn chưa có bất kỳ request nào gửi sang Spring Boot (port 8080)**.
2. **Khung giao diện tĩnh (UI Shell) được vẽ trước khi API được gọi:**
   - Trong React/Next.js, cấu trúc trang (Header, Sidebar, Typography, Buttons) là mã JSX tĩnh đã đóng gói sẵn ở máy khách.
   - Các hàm gọi API (lấy playlist, hồ sơ) chỉ bắt đầu chạy **sau khi** giao diện đã hiển thị (trong `useEffect` hoặc `Query Client`).
   - Lúc này Spring Boot Security mới nhận request và trả về HTTP `403 Forbidden`. Tuy nhiên, do Frontend không có cơ chế chặn chuyển trang từ trước, người dùng vẫn nhìn thấy giao diện trống / lỗi đỏ trong Console F12.

---

## 2. Vì sao hệ thống bắt buộc phải có Next.js Middleware?

Trong kiến trúc Web hiện đại, hệ thống áp dụng nguyên lý **"Defense-in-Depth" (Phòng thủ đa tầng)**:

| Tiêu chí | Spring Boot Security (Backend) | Next.js Middleware (Frontend) |
| :--- | :--- | :--- |
| **Trách nhiệm** | **Bảo vệ Dữ liệu (Data / API)** | **Bảo vệ Giao diện & Trải nghiệm (UI / UX)** |
| **Vị trí** | Cổng `8080`, tại Spring Security Filter Chain | Cổng `3000`, tại tầng Edge Server trước khi nạp Route |
| **Khi vi phạm** | Trả về mã lỗi HTTP `401` hoặc `403` | Chuyển hướng tức thì (`Redirect`) về đúng Dashboard của role đó |
| **Độ trễ người dùng** | Phải render xong, gọi API xong mới biết lỗi | **0ms:** Chặn ngay tại Server Edge trước khi render bất kỳ pixel nào |

### Lợi ích khi dùng Middleware:
1. **Không bị "chớp" giao diện (No UI Flash):** Người dùng không bao giờ nhìn thấy dù chỉ 1 frame của trang họ không có quyền.
2. **Bảo mật mã nguồn client:** Trình duyệt không tải mã JavaScript / logic nội bộ của trang cấm về máy khách.
3. **Quản trị tập trung (Centralized):** Toàn bộ luật phân quyền (`/dashboard/user`, `/dashboard/moderator`, `/dashboard/artist`, `/dashboard/admin`) được quản lý tại 1 file duy nhất, không cần lặp lại logic ở từng trang.

---

## 3. Middleware hoạt động như thế nào trong Moodify?

### 3.1 Sơ đồ luồng xử lý

```text
[ Người dùng gõ: /dashboard/user ]
               │
               ▼
[ Next.js Middleware (Edge Runtime) ]
   ├── 1. Kiểm tra Cookie (moodify_token):
   │      └── Chưa có token -> Redirect về: /?auth=signin&redirect=/dashboard/user
   │
   ├── 2. Lấy Role (từ cookie moodify_role hoặc decode JWT payload):
   │      └── Role hiện tại = "MODERATOR"
   │
   └── 3. Đối chiếu quyền hạn:
          └── Role "MODERATOR" cố truy cập "/dashboard/user" -> VI PHẠM!
          └── Lập tức trả về HTTP 307 Redirect sang: /dashboard/moderator
               │
               ▼
[ Trình duyệt tự động mở: /dashboard/moderator ]
   └── Giao diện Moderator hiển thị hợp lệ, gọi API backend thành công.
```

### 3.2 Quy tắc định tuyến (Routing Matrix)

- **Chưa đăng nhập:** Mọi route `/dashboard/*` bị đá về `/` kèm form đăng nhập.
- **Truy cập `/dashboard` gốc:** Tự động điều hướng về đúng trang của role đó (`/dashboard/moderator`, `/dashboard/user`, `/dashboard/artist`, `/dashboard/admin`).
- **Truy cập chéo role:**
  - `MODERATOR` vào `/dashboard/user` hoặc `/dashboard/artist` $\rightarrow$ Về `/dashboard/moderator`.
  - `ARTIST` vào `/dashboard/user` hoặc `/dashboard/moderator` $\rightarrow$ Về `/dashboard/artist`.
  - `USER` vào `/dashboard/moderator`, `/dashboard/artist` $\rightarrow$ Về `/dashboard/user`.

---

## 4. Chi tiết triển khai kỹ thuật

### 4.1 Đồng bộ Cookie tại Client (`lib/auth/auth-client.ts`)
Next.js Middleware chạy tại môi trường **Edge Runtime (Server-side)** nên không thể đọc trực tiếp `localStorage`. Do đó, hệ thống đã bổ sung cơ chế đồng bộ token sang Cookie:
- **Khi Đăng nhập (`saveAuthSession`):** Ghi cookie `moodify_token` (Access Token) và `moodify_role` (Role người dùng) với thời hạn tương ứng `expiresIn`.
- **Khi Khởi động (`getStoredAuthSession`):** Tự động kiểm tra và phục hồi Cookie nếu trình duyệt đã có session trong `localStorage`.
- **Khi Đăng xuất (`clearAuthSession`):** Xóa sạch cả `localStorage` và xóa Cookie (`max-age=0`).

### 4.2 Cấu hình Middleware (`middleware.ts`)
- Đặt tại thư mục gốc của frontend: `d:\Moodify\frontend\middleware.ts`.
- `matcher: ["/dashboard/:path*"]` đảm bảo Middleware chỉ kích hoạt khi người dùng truy cập khu vực Dashboard.

---

## 5. Hướng dẫn nghiệm thu

1. **Test chặn chéo quyền:**
   - Bạn đăng nhập tài khoản **`MODERATOR`**.
   - Trên thanh địa chỉ trình duyệt, gõ: `http://localhost:3000/dashboard/user` rồi bấm **Enter**.
   - **Kết quả:** Trình duyệt sẽ ngay lập tức chuyển hướng về `http://localhost:3000/dashboard/moderator`. Bạn hoàn toàn không còn vào được trang của `USER`.
2. **Test khi chưa đăng nhập:**
   - Mở cửa sổ ẩn danh, gõ: `http://localhost:3000/dashboard/moderator`.
   - **Kết quả:** Tự động chuyển hướng về trang chủ `/?auth=signin`.
