# BÁO CÁO KIỂM TRA HẠ TẦNG GOOGLE CLOUD (GCL / GCP) - DỰ ÁN MOODIFY

- **Thời gian kiểm tra:** 28/10/2026 20:05 (ICT)
- **Người thực hiện kiểm tra:** Antigravity AI Assistant
- **Hệ thống mục tiêu:** Google Cloud Platform (GCP) & Compute Instance `moodify-server`

---

## 1. TỔNG QUAN KẾT QUẢ KIỂM TRA (EXECUTIVE SUMMARY)

| Mục kiểm tra | Trạng thái | Ghi chú |
| :--- | :---: | :--- |
| **Kết nối Google Cloud SDK (gcloud CLI)** | ✅ THÀNH CÔNG | SDK v591.0.0 hoạt động bình thường |
| **Tài khoản xác thực (Authentication)** | ✅ HỢP LỆ | `kenresfx@gmail.com` (Active) |
| **Project hoạt động** | ✅ CHÍNH XÁC | `project-5b3e817f-ff1d-4771-be7` (Tên: **Moodify**) |
| **Máy chủ ảo (Compute Engine VM)** | ✅ ĐANG CHẠY | `moodify-server` (Ubuntu 24.04 LTS, 2 vCPU, 8GB RAM) |
| **Kết nối mạng & Tường lửa** | ✅ THÔNG SUỐT | Đã mở các port quan trọng (22, 80, 8000, 8001) |
| **Emotion Detection Service (AI)** | ✅ HOẠT ĐỘNG | Port 8000 phản hồi tốt (`model_ready: true`) |
| **OCR Service (Trích xuất hợp đồng/văn bản)** | ✅ HOẠT ĐỘNG | Port 8001 phản hồi tốt (`status: UP`) |
| **Dịch vụ Nginx Web Server** | ✅ HOẠT ĐỘNG | Port 80 trả về banner máy chủ Singapore |

---

## 2. CẤU HÌNH GOOGLE CLOUD CLI (GCL / CLOUD SDK)

- **Phiên bản Google Cloud SDK:**
  - `Google Cloud SDK`: 591.0.0
  - `bq` (BigQuery CLI): 2.1.41
  - `core`: 2026.10.26
  - `gsutil` (Cloud Storage CLI): 5.38
- **Tài khoản đang đăng nhập (Credentialed Account):**
  - `kenresfx@gmail.com` (Được đánh dấu `ACTIVE: *`)
- **Active Configuration Profile:**
  - Tên profile: `default`
  - Project ID: `project-5b3e817f-ff1d-4771-be7`
- **Danh sách Project quản lý bởi tài khoản:**
  1. `project-5b3e817f-ff1d-4771-be7` | Tên: **Moodify** | Project Number: `710162086991` *(Đang sử dụng)*
  2. `studyproject-f6582` | Tên: **StudyProject** | Project Number: `1043203014253`

---

## 3. THÔNG SỐ CHI TIẾT MÁY CHỦ ẢO (COMPUTE ENGINE - VM)

- **Tên phiên bản VM:** `moodify-server`
- **Khu vực (Zone):** `asia-southeast1-b` (Singapore - độ trễ thấp về Việt Nam)
- **Loại máy (Machine Type):** `e2-standard-2`
  - **vCPU:** 2 vCPUs
  - **Bộ nhớ RAM:** 8.00 GB RAM
- **Hệ điều hành:** Ubuntu 24.04 LTS Noble Numbat (`ubuntu-2404-noble-amd64-v20261024`)
- **Dung lượng đĩa cứng:**
  - Ổ đĩa: `moodify-server` (Boot disk)
  - Loại đĩa: `pd-balanced` (Persistent Disk Balanced SSD)
  - Dung lượng: **35 GB**
  - Trạng thái đĩa: `READY`
- **Địa chỉ IP:**
  - **Internal IP (Nội bộ GCP):** `10.148.0.2`
  - **External IP (Công cộng):** `136.85.101.19`
- **Network Tags:** `http-server`, `https-server`, `moodify-server`
- **Trạng thái:** `RUNNING`

---

## 4. CẤU HÌNH TƯỜNG LỬA (VPC FIREWALL RULES)

Hệ thống mạng VPC `default` đang cấu hình quy tắc tường lửa tùy chỉnh phục vụ dự án Moodify:

- **Tên quy tắc:** `allow-moodify-all`
  - **Độ ưu tiên (Priority):** 1000
  - **Hướng lưu lượng:** `INGRESS` (Inbound)
  - **Các cổng cho phép (Allowed Ports):**
    - `tcp:22` (SSH quản trị từ xa)
    - `tcp:80` (HTTP Web server Nginx)
    - `tcp:443` (HTTPS bảo mật SSL/TLS)
    - `tcp:8000-8002` (Các microservice AI: Emotion Detection, OCR Service, Essentia Audio Analysis)
    - `tcp:8080-8081` (Spring Boot API Backend)
  - **Trạng thái:** Kích hoạt (`Disabled: False`)

---

## 5. KẾT QUẢ KIỂM TRA THỰC TẾ CÁC DỊCH VỤ TRÊN CLOUD SERVER (`136.85.101.19`)

| Port | Giao thức / Dịch vụ | Trạng thái Port | Chi tiết phản hồi kiểm tra |
| :---: | :--- | :---: | :--- |
| **22** | SSH Administration | **MỞ (Open)** | Kết nối TCP thành công, cho phép SSH quản trị. |
| **80** | Nginx HTTP Reverse Proxy | **MỞ (Open)** | `HTTP 200 OK` (Nginx 1.24.0 Ubuntu)<br>Nội dung: *"Moodify Google Cloud Server (Singapore) Online - 8GB RAM AI & Audio"* |
| **443** | HTTPS SSL/TLS | **ĐÓNG (Closed)** | Chưa cấu hình chứng chỉ SSL/TLS trên Nginx. |
| **8000** | Emotion Detection Service | **MỞ (Open)** | `HTTP 200 OK` (Uvicorn / FastAPI v1.0.0)<br>Endpoints: `/health`, `/predict`, `/docs`<br>Health check: `{"status":"ok","model_ready":true}` |
| **8001** | Contract OCR Service | **MỞ (Open)** | `HTTP 200 OK` (Uvicorn / FastAPI v1.0.0)<br>Endpoints: `/health`, `/api/v1/ocr/pdf`, `/docs`<br>Health check: `{"status":"UP"}` |
| **8002** | Essentia Audio Analysis | **ĐÓNG (Closed)** | Tiến trình Essentia chưa khởi chạy hoặc chưa bind vào cổng này. |
| **8080** | Moodify Backend (Spring Boot) | **ĐÓNG (Closed)** | Backend hiện đang chạy độc lập ở môi trường Local dev. |

---

## 6. KHẢO SÁT CÁC TÀI NGUYÊN KHÁC TRÊN GCP

1. **Google Cloud Storage (GCS):**
   - Hiện tại project chưa khởi tạo bucket nào (`0 buckets`). File nhạc tĩnh đang được cấu hình trỏ sang máy chủ lưu trữ Oracle (`http://158.178.247.33/`).
2. **Cloud Run / App Engine:**
   - Chưa triển khai dịch vụ serverless (`0 items`). Toàn bộ tải xử lý AI đang được gom vào máy chủ ảo Compute Engine `moodify-server`.
3. **Cloud SQL:**
   - Dịch vụ Cloud SQL chưa bật (`sqladmin.googleapis.com` disabled). Dự án hiện sử dụng cơ sở dữ liệu MySQL và MongoDB riêng.

---

## 7. ĐÁNH GIÁ & HƯỚNG DẪN TÍCH HỢP CHO DỰ ÁN BACKEND

### 7.1. Chuyển đổi gọi Service AI từ Local sang Google Cloud
Hiện tại trong file `.env` của dự án Moodify Backend đang để:
```properties
EMOTION_SERVICE_BASE_URL=http://localhost:8000
OCR_SERVICE_BASE_URL=http://localhost:8001
```
Do hai dịch vụ này đã chạy ổn định và sẵn sàng trên Google Cloud, bạn có thể chuyển cấu hình sang IP cloud bất cứ lúc nào khi không muốn chạy nặng máy local:
```properties
EMOTION_SERVICE_BASE_URL=http://136.85.101.19:8000
OCR_SERVICE_BASE_URL=http://136.85.101.19:8001
```

### 7.2. Các việc khuyến nghị triển khai tiếp theo (Next Steps)
1. **Khởi chạy Audio Analysis Service (Port 8002):** Nếu dự án cần phân tích âm nhạc (bpm, mood, genre, timbre) bằng Essentia, hãy khởi động service trên cổng 8002 trên VM.
2. **Cấu hình chứng chỉ SSL (Let's Encrypt / Certbot):** Bật cổng 443 và cài HTTPS cho Nginx để đảm bảo an toàn dữ liệu khi gọi API từ Frontend/Backend.
3. **Bảo mật Microservice:** Cân nhắc chỉ cho phép truy cập port 8000 và 8001 thông qua Nginx reverse proxy với API Key hoặc giới hạn IP nguồn.
