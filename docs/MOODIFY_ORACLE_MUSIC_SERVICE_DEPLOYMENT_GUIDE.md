# MOODIFY — TÀI LIỆU TRIỂN KHAI VÀ VẬN HÀNH ORACLE MUSIC SERVICE
### Kiến Trúc MongoDB Metadata + Nginx Audio Streaming + Đồng Bộ Hai Chiều

> **Tài liệu ghi lại toàn bộ quy trình thiết kế, triển khai, cấu hình bảo mật IP và đồng bộ dữ liệu giữa máy ảo Oracle Cloud VM và Moodify Backend.**

---

## 1. TỔNG QUAN HỆ THỐNG

| Hạng mục | Thông tin chi tiết |
|---|---|
| **Hệ thống** | **Moodify** — Nền tảng nghe nhạc trực tuyến |
| **Máy chủ Cloud** | Oracle Cloud VM (Ubuntu 22.04 LTS) |
| **Public IP** | `158.178.247.33` (Giấu trong file `.env` phía Backend/Client) |
| **Domain (tham chiếu cũ)** | `musiccollector.kandes.io.vn` (đã chuyển sang Public IP cổng 80) |
| **Music Service** | Spring Boot 4.x (Java 23), lắng nghe cổng `8081` nội bộ |
| **MongoDB Oracle** | MongoDB 7.0 (Docker container `mongodb`), cổng `27017` nội bộ |
| **MongoDB Local** | MongoDB Server cục bộ trên máy tính cá nhân (`127.0.0.1:27017`) |
| **Nginx Reverse Proxy** | Cổng `80` (HTTP) — Reverse Proxy API sang `:8081` và phân phối file MP3 tĩnh |
| **Thư mục file MP3 audio** | `/opt/music-data-collector/data/audio/` |
| **Thư mục source code** | `/opt/moodify/music-service/` |
| **Quản lý tiến trình** | Systemd daemon (`music-service.service`) tự động chạy nền 24/7 |

---

## 2. MỤC TIÊU VÀ PHẠM VI TRIỂN KHAI

### 2.1. Mục tiêu
Tách biệt hoàn toàn tầng dữ liệu âm nhạc nặng (file nhị phân MP3) và metadata bài hát ra khỏi Backend chính, đặt lên máy chủ Oracle Cloud để:
1. **Giảm tải cho Backend chính và máy chủ web:** Không xử lý stream file âm thanh nặng qua Java/Tomcat heap.
2. **Tối ưu tốc độ phát nhạc (High Performance Streaming):** Tận dụng Nginx xử lý file tĩnh theo cơ chế bất đồng bộ non-blocking (epoll) và tính năng HTTP Range Request (`Accept-Ranges: bytes`) cho phép người dùng tua nhạc (seek) tức thì.
3. **Độc lập và bảo mật:** Không expose port MongoDB ra ngoài internet; toàn bộ thông tin IP máy chủ được cô lập trong `.env`.

### 2.2. Phạm vi đã hoàn thành
1. Kiểm tra hạ tầng Oracle VM: Docker, MongoDB container, Nginx, Java 23, Maven.
2. Xây dựng Spring Boot `music-service` độc lập trên Oracle VM đọc metadata MongoDB `music_streaming`.
3. Cấu hình Nginx reverse proxy đường dẫn `/api/music/` sang cổng `8081` và alias đường dẫn `/data/audio/` tới thư mục chứa file MP3.
4. Chuyển đổi toàn diện từ tên miền (Domain) sang **Public IP cổng 80**, khắc phục triệt để lỗi DNS resolver timeout `127.0.0.53` trên máy ảo.
5. Cài đặt **Systemd Service** giúp `music-service` chạy nền 24/7 và tự khởi động lại khi reboot hoặc gặp sự cố.
6. Mở cổng mạng tường lửa **iptables** và **Oracle VCN Security List Ingress Rules** cho TCP port 80.
7. Triển khai component [`AudioUrlResolver.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/AudioUrlResolver.java) trên Backend chính để ẩn IP trong `.env` và tự động sinh link phát nhạc.
8. Sửa lỗi MongoDB Unique Index `spotify_id_1` thành **Sparse Unique Index** trên Oracle VM.
9. Triển khai hoàn chỉnh **Cơ chế đồng bộ hai chiều (Two-Way Sync)** giữa MongoDB Local và MongoDB Oracle qua [`TrackSyncService.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/TrackSyncService.java) và [`TrackSyncApi.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/api/TrackSyncApi.java).

---

## 3. KIẾN TRÚC TỔNG THỂ HỆ THỐNG

### 3.1. Luồng dữ liệu (Data Flow)
```text
[ Người dùng / Frontend Next.js (localhost:3000) ]
        |
        | 1. GET /api/tracks (Yêu cầu metadata)
        v
[ Backend Moodify (localhost:8080) ]
        |
        | 2. Đọc MongoDB Local (~1ms) -> Tạo audioUrl trỏ về Oracle IP
        v
[ Frontend nhận JSON kèm audioUrl: http://158.178.247.33/data/audio/... ]
        |
        | 3. Bấm Play -> Trình duyệt gửi GET trực tiếp tới Oracle
        v
[ Nginx trên Oracle Cloud VM (Port 80) ]
        |
        +---> /data/audio/  --> alias /opt/music-data-collector/data/audio/ --> MP3 Files (HTTP 200 / 206)
        |
        +---> /api/music/   --> proxy_pass :8081 --> Spring Boot Music Service --> MongoDB :27017
```

### 3.2. Ý nghĩa thiết kế
- **Spring Boot xử lý nghiệp vụ/metadata:** Query dữ liệu bài hát từ MongoDB với tốc độ cực nhanh trên localhost (~1ms).
- **Nginx phục vụ file âm thanh:** Trình duyệt tải nhạc và tua nhạc trực tiếp từ Nginx trên Oracle, **hoàn toàn không đi qua Spring Boot của Backend chính hay Music Service**, giúp tiết kiệm tối đa RAM và CPU.

---

## 4. CHI TIẾT CÁC BƯỚC TRIỂN KHAI TRÊN ORACLE CLOUD VM

### Bước 1: Kiểm tra hạ tầng Docker và MongoDB
Mục đích: Xác nhận container MongoDB đang hoạt động và port 27017 được ánh xạ chính xác.

```bash
# Kiểm tra Docker
docker --version
sudo docker ps -a

# Kiểm tra dữ liệu trong MongoDB container
sudo docker exec -it mongodb mongosh
use music_streaming
show collections
db.tracks.countDocuments()
```
*Kết quả:* Collection `tracks` có sẵn **147 documents** ban đầu từ bộ dữ liệu crawler.

### Bước 2: Chuẩn bị runtime Java 23 và Maven
Mục đích: Cài đặt công cụ biên dịch và chạy file JAR cho Spring Boot.

```bash
java --version    # Eclipse Temurin / OpenJDK 23
javac --version
mvn --version     # Apache Maven 3.6.3
```

### Bước 3: Xây dựng Music Service trên Oracle
Thư mục dự án: `/opt/moodify/music-service/`

**Cấu trúc thư mục:**
```text
/opt/moodify/music-service/
├── pom.xml
└── src/
    ├── main/resources/
    │   └── application.properties
    └── main/java/com/moodify/musicservice/
        ├── MusicServiceApplication.java
        ├── api/TrackApi.java
        ├── model/Track.java
        ├── repository/TrackRepository.java
        └── service/TrackService.java
```

**File `application.properties` trên Oracle:**
```properties
spring.application.name=music-service
server.port=8081
spring.mongodb.uri=mongodb://127.0.0.1:27017/music_streaming
```

### Bước 4: Cấu hình `Track.java` sinh link audio theo Public IP
Trong file `/opt/moodify/music-service/src/main/java/com/moodify/musicservice/model/Track.java`, phương thức `getAudioUrl()` được chuẩn hóa:

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

### Bước 5: Cấu hình Nginx phục vụ qua IP cổng 80
File cấu hình: `/etc/nginx/sites-available/music-collector`

```nginx
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name 158.178.247.33 _;

    client_max_body_size 100M;

    # 1. Reverse proxy sang Spring Boot Music Service (Port 8081)
    location /api/music/ {
        proxy_pass http://127.0.0.1:8081/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 2. Phục vụ trực tiếp file MP3 với HTTP Range & CORS
    location /data/audio/ {
        alias /opt/music-data-collector/data/audio/;
        add_header 'Access-Control-Allow-Origin' '*' always;
        add_header 'Access-Control-Allow-Methods' 'GET, HEAD, OPTIONS' always;
        add_header 'Access-Control-Allow-Headers' '*' always;
        expires 30d;
    }
}
```

Kiểm tra cú pháp và nạp lại cấu hình:
```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Bước 6: Cấu hình tường lửa Ubuntu (iptables)
Mặc định Ubuntu trên Oracle Cloud chặn traffic vào cổng 80. Cần mở cổng và lưu lại cấu hình:

```bash
sudo iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
```

### Bước 7: Mở Ingress Rules trên Oracle Cloud Console
1. Truy cập Oracle Cloud Console -> **Compute** -> **Instances** -> `music-collector-server`.
2. Vào **Attached VNICs** -> Nhấp vào **Subnet** -> **Default Security List**.
3. Thêm Ingress Rule:
   - **Source CIDR**: `0.0.0.0/0`
   - **IP Protocol**: `TCP`
   - **Destination Port**: `80`
   - **Description**: `Allow HTTP access to Music Service and MP3 Audio`

### Bước 8: Cài đặt Systemd Service chạy nền 24/7
Tạo file `/etc/systemd/system/music-service.service`:

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

Kích hoạt và khởi chạy:
```bash
sudo systemctl daemon-reload
sudo systemctl enable music-service
sudo systemctl restart music-service
sudo systemctl status music-service
```

---

## 5. TÍCH HỢP BẢO MẬT IP TRÊN BACKEND MOODIFY (MÁY CÁ NHÂN)

Toàn bộ địa chỉ IP `158.178.247.33` được cô lập trong file `.env` và tuyệt đối không bao giờ commit lên GitHub.

### 5.1. Chuỗi truyền cấu hình 3 tầng:
1. **File [`.env`](file:///d:/moodify-backend/.env):**
   ```properties
   ORACLE_AUDIO_BASE_URL=http://158.178.247.33/
   ```
2. **File [`application.properties`](file:///d:/moodify-backend/src/main/resources/application.properties):**
   ```properties
   oracle.audio.base-url=${ORACLE_AUDIO_BASE_URL:}
   ```
3. **Class [`AudioUrlResolver.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/AudioUrlResolver.java):**
   Thành phần trung tâm nạp `oracle.audio.base-url` qua `@Value` và cung cấp hàm tĩnh `AudioUrlResolver.resolve(localPath)` để chuyển đổi bất kỳ đường dẫn tương đối nào thành URL hoàn chỉnh.

### 5.2. Các file Backend đã cập nhật:
- [`TrackResponse.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/dto/track/TrackResponse.java): Tự động bổ sung trường `audioUrl` vào JSON trả về cho Frontend.
- [`TrackApi.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/api/TrackApi.java): Phương thức `streamTrack()` tự động trả mã `302 Found (Redirect)` trỏ sang Oracle Nginx nếu không tìm thấy file MP3 cục bộ.
- [`AdminService.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/AdminService.java): Thay thế toàn bộ link domain cũ trong Admin Dashboard bằng `AudioUrlResolver`.
- [`SubscriptionApi.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/api/SubscriptionApi.java): Xử lý download nhạc Premium trỏ về Oracle Nginx.
- [`ContentLeadTrackResponse.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/dto/contentlead/ContentLeadTrackResponse.java) & [`ModerationQueueTrackResponse.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/dto/moderator/ModerationQueueTrackResponse.java): Trả link phát nhạc chuẩn cho Content Lead và Moderator.

---

## 6. SO SÁNH VAI TRÒ MONGODB LOCAL VÀ MONGODB ORACLE

| Tiêu chí so sánh | MongoDB Local (Máy cá nhân) | MongoDB Oracle (Máy ảo Cloud) |
|---|---|---|
| **Vị trí chạy** | Windows Service (`127.0.0.1:27017`) | Docker container `mongodb` (`10.0.0.202:27017`) |
| **Vai trò chính** | Database chính phục vụ toàn bộ nghiệp vụ ứng dụng Moodify | Nguồn lưu trữ metadata bài hát gốc cho crawler và Music Service |
| **Các chức năng phụ thuộc** | Browsing, Search, Playlists, User, Admin, Content Lead, Moderator | Trả API metadata độc lập tại `/api/music/tracks` |
| **Tốc độ truy vấn** | **Cực nhanh (~1ms)** do query trên localhost | Không truy vấn trực tiếp từ Backend chính (bảo mật tuyệt đối) |
| **Lưu file MP3?** | Không (tiết kiệm ổ cứng máy cá nhân) | Có (nằm trong `/opt/music-data-collector/data/audio/`) |

---

## 7. CƠ CHẾ ĐỒNG BỘ HAI CHIỀU (PHƯƠNG ÁN 2 — API SYNC SERVICE)

Nhằm đảm bảo cả 2 hệ thống luôn có dữ liệu bài hát mới nhất của nhau, hệ thống đã triển khai cơ chế đồng bộ hai chiều tự động qua HTTP API.

### 7.1. Khắc phục lỗi Sparse Unique Index trên Oracle MongoDB
- **Vấn đề phát hiện:** Khi đồng bộ các bài do Content Lead upload (có `spotify_id = null`), MongoDB Oracle xảy ra lỗi `E11000 duplicate key error` do index `spotify_id_1` là `unique` nhưng thiếu cờ `sparse`.
- **Giải pháp đã thực thi trên Oracle:**
  ```javascript
  db.tracks.dropIndex("spotify_id_1");
  db.tracks.createIndex({ spotify_id: 1 }, { unique: true, sparse: true });
  ```
  Nhờ có `sparse: true`, các bài hát không có Spotify ID có thể được thêm vào database không giới hạn mà không bị xung đột index.

### 7.2. Cập nhật Music Service trên Oracle tiếp nhận bài mới
- **File:** `/opt/moodify/music-service/src/main/java/com/moodify/musicservice/api/TrackApi.java`
- **Endpoint thêm mới:** `POST /api/tracks/sync` (Nginx map thành `http://158.178.247.33/api/music/tracks/sync`).
- **Hàm xử lý:** `upsertTracks(List<Track> incomingTracks)` trong `TrackService.java` tự động đối soát theo `spotifyId`, `id` hoặc tên bài + nghệ sĩ để quyết định INSERT bài mới hoặc UPDATE bài đã có.

### 7.3. Triển khai Service và API đồng bộ trên Backend Moodify
1. **[`TrackSyncService.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/TrackSyncService.java):**
   - **Chiều Oracle → Local (`pullFromOracle`):** Gọi `GET /api/music/tracks?size=500` để lấy danh sách bài trên Oracle, so sánh và lưu các bài mới về MongoDB Local, đồng thời cập nhật lyrics/audioFeatures mới nhất.
   - **Chiều Local → Oracle (`pushToOracle`):** Lọc các bài hát ở Local mà Oracle chưa có (như bài hát Content Lead upload), gửi qua `POST /api/music/tracks/sync`.
   - **Đồng bộ định kỳ (`@Scheduled`):** Tự động kích hoạt chạy nền định kỳ mỗi **15 phút** (cấu hình qua `sync.scheduler.fixed-rate-ms=900000`).
   - **Đẩy bài tức thì (`pushSingleTrack`):** Được tích hợp vào [`ContentLeadCatalogService.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/services/ContentLeadCatalogService.java). Ngay khi Content Lead tạo bài mới (`createTrack`) hoặc cập nhật bài (`updateTrack`), bài hát sẽ được gửi ngay lên Oracle trong tích tắc mà không cần chờ 15 phút.
2. **[`TrackSyncApi.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/api/TrackSyncApi.java):**
   - `POST /api/sync/tracks`: Kích hoạt đồng bộ 2 chiều thủ công ngay lập tức.
   - `GET /api/sync/tracks/status`: Xem chi tiết kết quả, số lượng bài và thời gian lần sync gần nhất.
3. **Cấu hình bổ sung:**
   - Kích hoạt `@EnableScheduling` trong [`MoodifyApplication.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/MoodifyApplication.java).
   - Cho phép gọi endpoint `/api/sync/**` trong [`SecurityConfig.java`](file:///d:/moodify-backend/src/main/java/com/laphuth/moodify/config/SecurityConfig.java).
   - Thêm tham số điều khiển scheduler trong [`application.properties`](file:///d:/moodify-backend/src/main/resources/application.properties).

---

## 8. SỰ CỐ ĐÃ GẶP VÀ CÁCH KHẮC PHỤC

| Sự cố gặp phải | Nguyên nhân | Cách khắc phục đã thực hiện |
|---|---|---|
| **DNS timeout `127.0.0.53` trên Oracle** | Hệ thống phân giải DNS của Ubuntu VM bị treo khiến domain không thể phân giải | Chuyển toàn bộ cấu hình sang dùng **Public IP `158.178.247.33`** trên cổng 80, giấu IP vào file `.env` |
| **API trả 0 bài hát ban đầu** | Cấu hình property MongoDB của Spring Boot 4.x chưa đúng format database | Sửa thành `spring.mongodb.uri=mongodb://127.0.0.1:27017/music_streaming` trong `application.properties` của Oracle |
| **Không nghe được nhạc từ bên ngoài** | Bộ quy tắc iptables trên Ubuntu chặn cổng 80 và Oracle VCN chưa cho phép Ingress | Thêm rule `iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT`, lưu bằng `netfilter-persistent save` và mở Ingress Rule cổng 80 trên Oracle VCN |
| **Music Service tắt khi đóng Termius** | Khởi chạy ứng dụng bằng `java -jar` trực tiếp trên phiên SSH | Tạo daemon `music-service.service` với Systemd, cài đặt `Restart=always` |
| **Lỗi `E11000 duplicate key` khi sync bài Content Lead** | Index `spotify_id_1` là unique nhưng không có cờ `sparse`, khiến các bài có `spotify_id: null` bị xung đột | Chạy lệnh `dropIndex("spotify_id_1")` và tạo lại với `{ unique: true, sparse: true }` trên MongoDB Oracle |

---

## 9. PHỤ LỤC: BỘ LỆNH KIỂM TRA NHANH HỆ THỐNG

### Kiểm tra từ máy tính cá nhân (PowerShell / Terminal):
```bash
# 1. Kiểm tra API metadata trên Oracle
curl.exe -s "http://158.178.247.33/api/music/tracks?page=0&size=2"

# 2. Kiểm tra phát và tua file MP3 trực tiếp (HTTP 200 / Accept-Ranges)
curl.exe -I "http://158.178.247.33/data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3"

# 3. Kích hoạt đồng bộ 2 chiều thủ công
curl.exe -X POST "http://localhost:8080/api/sync/tracks"

# 4. Kiểm tra trạng thái đồng bộ
curl.exe -s "http://localhost:8080/api/sync/tracks/status"
```

### Kiểm tra trên Oracle Cloud VM (SSH Termius):
```bash
# 1. Kiểm tra trạng thái Music Service 24/7
sudo systemctl status music-service

# 2. Xem logs hoạt động thời gian thực
sudo journalctl -u music-service -f

# 3. Đếm số lượng tracks trong MongoDB Oracle
sudo docker exec mongodb mongosh --quiet mongodb://127.0.0.1:27017/music_streaming --eval 'db.tracks.countDocuments()'

# 4. Kiểm tra Nginx
sudo nginx -t
sudo systemctl status nginx
```
