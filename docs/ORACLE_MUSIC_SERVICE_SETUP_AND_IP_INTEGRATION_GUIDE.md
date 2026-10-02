# HƯỚNG DẪN TRIỂN KHAI VÀ VẬN HÀNH MOODIFY ORACLE MUSIC SERVICE BẰNG PUBLIC IP

Tài liệu này tổng hợp toàn bộ các bước tiếp theo để hoàn thiện việc đưa **Music Service** chạy ngầm 24/7 trên Oracle Cloud VM (Ubuntu 22.04), chuyển đổi từ việc dùng domain sang **Public IP (`158.178.247.33`)**, mở cổng mạng Oracle VCN/Firewall và tích hợp cấu hình bảo mật vào file `.env` của Moodify Backend.

---

## 1. Trả lời câu hỏi: Dùng Public IP thay cho Domain có được không?

**HOÀN TOÀN ĐƯỢC VÀ RẤT TIỆN LỢI**.
- **Lý do**: Khi chạy service nội bộ hoặc backend-to-service, dù ng thẳng Public IP giúp bạn không bị phụ thuộc vào tên miền, không lo lỗi phân giải DNS (như sự cố timeout `127.0.0.53` từng gặp trên VM) và không cần gia hạn chứng chỉ SSL domain định kỳ.
- **Bảo mật**: Bạn hoàn toàn có thể giấu IP này trong file `.env` ở cả Backend và Frontend, không commit IP lên GitHub.
- **Giao thức**: Sẽ chạy qua giao thức **HTTP cổng 80** tiêu chuẩn (Nginx reverse proxy quản lý).

---

## 2. Thông tin máy chủ Oracle hiện tại

- **Public IP**: `158.178.247.33`
- **Private IP**: `10.0.0.202`
- **User SSH**: `ubuntu`
- **Thư mục source Music Service**: `/opt/moodify/music-service/`
- **Thư mục file MP3 audio**: `/opt/music-data-collector/data/audio/`
- **Cổng Spring Boot nội bộ**: `8081`
- **Cổng Nginx public**: `80` (HTTP)

---

## 3. Các bước thao tác tiếp theo qua Termius (SSH)

Bạn mở Termius kết nối vào instance `ubuntu@music-collector-server` và thực hiện lần lượt các bước sau:

### Bước 1: Mở cổng Firewall trên Ubuntu (Bắt buộc)
Mặc định hệ điều hành Ubuntu trên Oracle Cloud có bộ quy tắc `iptables` chặn tất cả các cổng ngoài ngoại trừ SSH (port 22). Để máy ngoài có thể gọi IP vào cổng 80, bạn chạy lệnh sau trên Termius:

```bash
# Cho phép lưu lượng cổng 80 (HTTP) đi qua iptables
sudo iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT

# Lưu lại quy tắc để không bị mất khi reboot server
sudo netfilter-persistent save
```

*(Nếu server chưa có `netfilter-persistent`, cài nhanh bằng: `sudo apt install -y iptables-persistent netfilter-persistent` và chọn Yes)*.

---

### Bước 2: Cập nhật cấu hình Nginx sang sử dụng IP trực tiếp

Chuyển Nginx sang lắng nghe trực tiếp trên cổng 80 với IP của máy chủ.

1. Mở file cấu hình Nginx:
```bash
sudo nano /etc/nginx/sites-available/music-collector
```

2. Xóa hoặc sửa nội dung file thành cấu hình sau:
```nginx
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name 158.178.247.33 _;

    # 1. Reverse proxy sang Spring Boot Music Service (Port 8081)
    location /api/music/ {
        proxy_pass http://127.0.0.1:8081/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 2. Phục vụ trực tiếp file MP3 với HTTP Range (tua nhạc mượt mà)
    location /data/audio/ {
        alias /opt/music-data-collector/data/audio/;
        add_header 'Access-Control-Allow-Origin' '*' always;
        add_header 'Access-Control-Allow-Methods' 'GET, HEAD, OPTIONS' always;
        add_header 'Access-Control-Allow-Headers' '*' always;
        expires 30d;
    }
}
```

3. Nhấn `Ctrl + O` -> `Enter` để lưu, sau đó `Ctrl + X` để thoát `nano`.

4. Kiểm tra cú pháp và reload lại Nginx:
```bash
sudo nginx -t
sudo systemctl reload nginx
```
*(Nếu `nginx -t` báo syntax is ok, Nginx đã sẵn sàng nhận request qua IP)*.

---

### Bước 3: Cập nhật Code `Track.java` của Music Service sang IP

Trước đó, `Track.java` đang nối URL phát nhạc với domain cũ `musiccollector.kandes.io.vn`. Ta cần sửa lại để trả về IP `158.178.247.33`.

1. Mở file `Track.java`:
```bash
sudo nano /opt/moodify/music-service/src/main/java/com/moodify/musicservice/model/Track.java
```

2. Tìm đến phương thức `getAudioUrl()` và sửa thành:
```java
    public String getAudioUrl() {
        if (localPath == null || localPath.isBlank()) {
            return null;
        }
        String normalizedPath = localPath.replace("\\", "/");
        if (normalizedPath.startsWith("data/audio/")) {
            normalizedPath = normalizedPath.substring("data/audio/".length());
        }
        return "http://158.178.247.33/data/audio/" + normalizedPath;
    }
```

3. Lưu file (`Ctrl + O`, `Enter`, `Ctrl + X`).

4. Build lại project:
```bash
cd /opt/moodify/music-service
mvn clean package -DskipTests
```

---

### Bước 4: Tạo Systemd Service để Music Service tự chạy ngầm 24/7

Chạy `java -jar` thủ công sẽ làm ứng dụng tắt ngay khi bạn ngắt kết nối Termius. Do đó, cần cài đặt Systemd để service tự chạy ngầm và tự khởi động lại khi máy chủ reboot.

1. Tạo file service:
```bash
sudo nano /etc/systemd/system/music-service.service
```

2. Dán nội dung sau vào:
```ini
[Unit]
Description=Moodify Oracle Music Service (Spring Boot)
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/moodify/music-service
ExecStart=/usr/bin/java -jar /opt/moodify/music-service/target/music-service-0.0.1-SNAPSHOT.jar
SuccessExitStatus=143
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

3. Lưu file (`Ctrl + O`, `Enter`, `Ctrl + X`).

4. Kích hoạt và khởi động service:
```bash
# Reload cấu hình systemd
sudo systemctl daemon-reload

# Bật tự động khởi động cùng hệ thống
sudo systemctl enable music-service

# Khởi động service ngay lập tức
sudo systemctl restart music-service

# Kiểm tra trạng thái hoạt động
sudo systemctl status music-service
```
*(Nếu thấy `Active: active (running)` màu xanh lá là thành công!)*

---

## 4. Mở cổng Ingress Rules trên Oracle Cloud Console (Rất quan trọng!)

Mặc định Oracle Cloud có Virtual Cloud Network (VCN) chặn mọi kết nối ngoài vào máy ảo nếu chưa khai báo Ingress Rules.

1. Truy cập vào **Oracle Cloud Console** (như trong ảnh bạn đã mở).
2. Vào **Compute** -> **Instances** -> Nhấp vào tên instance **`music-collector-server`**.
3. Cuộn xuống phần **Instance Details** -> Chọn tab **Attached VNICs** bên trái -> Nhấp vào **Subnet: ...**.
4. Trong trang Subnet, nhấp vào **Default Security List for ...**.
5. Nhấp vào nút **Add Ingress Rules** và điền:
   - **Source Type**: `CIDR`
   - **Source CIDR**: `0.0.0.0/0`
   - **IP Protocol**: `TCP`
   - **Source Port Range**: Để trống (All)
   - **Destination Port Range**: `80`
   - **Description**: `Allow HTTP access to Music Service and MP3 Audio`
6. Nhấp **Add Ingress Rules**.

---

## 5. Kiểm tra kết nối từ bên ngoài (Test API & Streaming)

Mở Terminal/PowerShell trên máy tính cá nhân của bạn (hoặc trình duyệt web/Postman) để kiểm tra:

1. **Test Metadata API**:
   Truy cập trên trình duyệt:
   ```text
   http://158.178.247.33/api/music/tracks?page=0&size=5
   ```
   *Kết quả mong đợi*: Trả về dữ liệu JSON chứa danh sách bài hát, trong đó trường `audioUrl` có dạng `http://158.178.247.33/data/audio/...`.

2. **Test phát file nhạc MP3 trực tiếp**:
   Truy cập trực tiếp file nhạc mẫu:
   ```text
   http://158.178.247.33/data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3
   ```
   *Kết quả mong đợi*: Trình duyệt hiển thị thanh phát nhạc HTML5 và có thể bấm Play, tua bài hát bình thường.

---

## 6. Cấu hình biến môi trường `.env` trong Moodify Backend

Tại thư mục gốc của project `d:\moodify-backend`, mở file `.env` và thêm/cập nhật các biến sau:

```env
# ===============================
# ORACLE MUSIC SERVICE CONFIG
# ===============================
ORACLE_MUSIC_SERVER_IP=158.178.247.33
ORACLE_MUSIC_API_BASE_URL=http://158.178.247.33/api/music
ORACLE_AUDIO_BASE_URL=http://158.178.247.33/
```

Trong code Backend (`AdminService.java`, `SubscriptionApi.java`), các biến hardcode trước đây sẽ được thay thế bằng `@Value("${oracle.audio.base-url}")` hoặc lấy từ file `.env` để bảo mật hoàn toàn IP.
