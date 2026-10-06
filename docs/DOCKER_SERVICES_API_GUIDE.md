# HƯỚNG DẪN KIỂM TRA PORT & API CỦA CÁC CONTAINER DOCKER (MOODIFY)

Tài liệu này tổng hợp danh sách cổng mạng (Port), đường dẫn URL (Endpoint), mẫu dữ liệu Request/Response và các câu lệnh kiểm tra (cURL, Postman, PowerShell) cho các container dịch vụ AI & OCR trong hệ thống Moodify.

---

## 1. Bảng Tổng Hợp Cổng Mạng (Port Mapping)

Theo cấu hình trong [`compose.yaml`](../compose.yaml) và [`.env.example`](../.env.example):

| Tên Dịch Vụ | Tên Container | Cổng Host (Máy bạn) | Cổng Trong Container | URL Trực Tiếp Dịch Vụ | URL Swagger / Docs |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Detect Emotions** | `moodify-detect-emotions` | **`8000`** | `8000` | `http://localhost:8000` | `http://localhost:8000/docs` |
| **OCR Service** | `moodify-ocr-service` | **`8001`** | `8000` | `http://localhost:8001` | `http://localhost:8001/docs` |
| **MongoDB** | *(auto)* | `27017` | `27017` | `mongodb://localhost:27017` | *N/A* |
| **MySQL** | *(auto)* | `3306` | `3306` | `localhost:3306` | *N/A* |
| **Spring Boot Backend** | *(Host App)* | **`8080`** | *N/A* | `http://localhost:8080` | `http://localhost:8080/api/...` |

---

## 2. Container 1: Dịch vụ Nhận diện Cảm xúc (`detect-emotions`)

- **Image:** `dreayrt/detect-emotions:latest`
- **Host Port:** `8000`
- **Base URL trực tiếp:** `http://localhost:8000`
- **Tài liệu trực quan (FastAPI Swagger UI):** `http://localhost:8000/docs` hoặc `http://localhost:8000/redoc`

### 2.1. Kiểm tra trực tiếp qua Container (Port 8000)

#### Endpoint: `POST /predict`
- **URL đầy đủ:** `http://localhost:8000/predict`
- **Headers:** `Content-Type: application/json`
- **Request Body (JSON):**
```json
{
  "text": "Hôm nay tôi hoàn thành dự án xuất sắc, tôi cảm thấy rất tự hào và hưng phấn!",
  "strategy": "empathy"
}
```
> *Ghi chú:* Tham số `strategy` có thể là `"empathy"` (đồng cảm), `"energy_boost"`, `"catharsis"`, hoặc để trống (mặc định Backend sẽ truyền `"empathy"`).

#### Kết quả trả về mẫu (Response JSON - HTTP 200):
```json
{
  "text": "Hôm nay tôi hoàn thành dự án xuất sắc, tôi cảm thấy rất tự hào và hưng phấn!",
  "label": "joy",
  "emoji": "😄",
  "confidence": 0.9654,
  "probabilities": {
    "joy": 0.9654,
    "sadness": 0.0051,
    "anger": 0.0032,
    "fear": 0.0043,
    "neutral": 0.0220
  },
  "music_recommendation": {
    "strategy": "empathy",
    "mood_analysis": "Người dùng thể hiện niềm vui và phấn khích cao độ.",
    "target_valence": 0.85,
    "min_valence": 0.65,
    "max_valence": 1.0,
    "target_energy": 0.8,
    "min_energy": 0.6,
    "max_energy": 1.0,
    "target_danceability": 0.75,
    "target_acousticness": 0.2,
    "target_mode": "major",
    "target_tempo": "fast",
    "seed_genres": ["pop", "dance", "electro"]
  }
}
```

#### Câu lệnh kiểm tra nhanh:
**cURL:**
```bash
curl -X POST "http://localhost:8000/predict" \
     -H "Content-Type: application/json" \
     -d "{\"text\": \"Toi dang cam thay rat vui va yeu doi\", \"strategy\": \"empathy\"}"
```

**PowerShell:**
```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/predict" `
  -ContentType "application/json" `
  -Body '{"text": "Toi dang cam thay rat vui va yeu doi", "strategy": "empathy"}' | ConvertTo-Json -Depth 5
```

---

### 2.2. Kiểm tra qua Backend Spring Boot (Port 8080)

Khi ứng dụng Backend chạy, bạn có thể gọi qua API của Spring Boot (Backend gọi trung gian sang container AI và xử lý truy vấn bài hát trong Database):

1. **Dự đoán cảm xúc (Không cần đăng nhập):**
   - **URL:** `POST http://localhost:8080/api/emotions/predict`
   - **Body:** `{"text": "hom nay buon qua", "strategy": "empathy"}`
2. **Gợi ý bài hát dựa theo cảm xúc (Tích hợp MongoDB):**
   - **URL:** `POST http://localhost:8080/api/emotions/recommend`
   - **Body:** `{"text": "hom nay buon qua", "strategy": "empathy", "limit": 10}`
   - **Kết quả:** Trả về nhãn cảm xúc kèm danh sách các bài hát có `valence` và `energy` tương ứng từ MongoDB.

---

## 3. Container 2: Dịch vụ Trích xuất Hợp đồng & OCR (`ocr-service`)

- **Image:** `dreayrt/ocr-service:latest`
- **Host Port:** `8001` (chú ý: bên ngoài map vào cổng 8000 bên trong container)
- **Base URL trực tiếp:** `http://localhost:8001`
- **Tài liệu trực quan (FastAPI Swagger UI):** `http://localhost:8001/docs` hoặc `http://localhost:8001/redoc`

### 3.1. Kiểm tra trực tiếp qua Container (Port 8001)

#### Endpoint: `POST /api/v1/ocr/pdf`
- **URL đầy đủ:** `http://localhost:8001/api/v1/ocr/pdf`
- **Content-Type:** `multipart/form-data`
- **Form Data Parameters:**
  - `file`: File tài liệu (PDF hoặc hình ảnh scan hợp đồng như PNG/JPG).
  - `target_fields`: `license_type,copyright_owner,distributor,contract_id,issue_date,expiry_date`

#### Kết quả trả về mẫu từ container (OcrRawResponse - HTTP 200):
```json
{
  "success": true,
  "document_uuid": "c9a1841e-355b-49dc-8a4e-1282ecb72ef3",
  "processing_status": "COMPLETED",
  "ocr_engine": "paddleocr-v4",
  "confidence": 0.94,
  "raw_text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM\nHỢP ĐỒNG ỦY QUYỀN PHÂN PHỐI ÂM NHẠC\nSố hợp đồng: HD-2026-MD88\nBên A (Chủ sở hữu bản quyền): NGUYEN VAN A\nBên B (Đơn vị phân phối): MOODIFY\nNgày cấp: 15/01/2026\nThời hạn: Vô thời hạn...",
  "extracted_fields": {
    "license_type": "EXCLUSIVE_LICENSE",
    "copyright_owner": "NGUYEN VAN A",
    "distributor": "Moodify",
    "contract_id": "HD-2026-MD88",
    "issue_date": "15/01/2026",
    "expiry_date": "Vô thời hạn"
  }
}
```

#### Câu lệnh kiểm tra nhanh:
**cURL (Sử dụng file có sẵn trong project `hop_dong_doc_quyen.pdf`):**
```bash
curl -X POST "http://localhost:8001/api/v1/ocr/pdf" \
     -F "file=@hop_dong_doc_quyen.pdf" \
     -F "target_fields=license_type,copyright_owner,distributor,contract_id,issue_date,expiry_date"
```

**PowerShell:**
```powershell
$form = @{
    file = Get-Item -Path ".\hop_dong_doc_quyen.pdf"
    target_fields = "license_type,copyright_owner,distributor,contract_id,issue_date,expiry_date"
}
Invoke-RestMethod -Method Post -Uri "http://localhost:8001/api/v1/ocr/pdf" -Form $form | ConvertTo-Json -Depth 5
```

---

### 3.2. Kiểm tra qua Backend Spring Boot (Port 8080)

Endpoint backend đã được chuẩn hóa ngày tháng (ISO `yyyy-MM-dd`), chuẩn hóa nhà phân phối (`distributorId`), và cờ vô thời hạn (`perpetual`):

- **Endpoint:** `POST http://localhost:8080/api/content-lead/ocr/extract-license`
- **Yêu cầu:** Token người dùng có quyền `CONTENT_LEAD` hoặc `ADMIN`.
- **Headers:** 
  - `Authorization: Bearer <ACCESS_TOKEN>`
- **Form Data:**
  - `file`: File PDF/ảnh tải lên.

#### Kết quả chuẩn hóa nhận được từ Backend (OcrExtractResponse):
```json
{
  "success": true,
  "message": "Bóc tách thông tin thành công",
  "confidence": 0.94,
  "licenseType": "EXCLUSIVE_LICENSE",
  "copyrightOwner": "NGUYEN VAN A",
  "distributorId": 1,
  "distributorName": "Moodify Direct Distribution (Nội bộ)",
  "contractId": "HD-2026-MD88",
  "issueDate": "2026-01-15",
  "expiryDate": null,
  "isPerpetual": true
}
```

---

## 4. Các Lệnh Quản Trị Docker Hữu Ích

### 4.1. Khởi động 2 container AI & OCR
```powershell
# Chạy cả 2 container ngầm trong background
docker compose up -d detect-emotions ocr-service

# Hoặc khởi động tất cả (gồm cả MongoDB, MySQL)
docker compose up -d
```

### 4.2. Kiểm tra trạng thái đang chạy
```powershell
docker ps
```
Cột `PORTS` sẽ hiển thị rõ ràng:
- `0.0.0.0:8000->8000/tcp` (`moodify-detect-emotions`)
- `0.0.0.0:8001->8000/tcp` (`moodify-ocr-service`)

### 4.3. Theo dõi Logs kiểm tra lỗi
```powershell
# Xem log nhận diện cảm xúc
docker logs -f moodify-detect-emotions

# Xem log OCR bóc tách tài liệu
docker logs -f moodify-ocr-service
```

---

## 5. Xử Lý Các Sự Cố Thường Gặp (Troubleshooting)

1. **Lỗi `Connection Refused` khi gọi `localhost:8000` hoặc `localhost:8001`:**
   - Kiểm tra xem Docker Desktop đã được bật hay chưa (`docker ps`).
   - Kiểm tra xem container có bị tắt do hết RAM không (`docker inspect moodify-detect-emotions`). Hai container này được cấp phát tối đa `4GB RAM` mỗi container trong file `compose.yaml`.
2. **Container `moodify-detect-emotions` khởi động chậm trong lần đầu:**
   - Image AI cần từ 15-45 giây để tải mô hình học sâu (NLP Transformers / PhoBERT) vào RAM trước khi mở cổng nhận request. Hãy đợi log hiển thị `Application startup complete` hoặc `Uvicorn running on http://0.0.0.0:8000`.
3. **Trùng cổng (Port Conflict):**
   - Đảm bảo máy bạn không có tiến trình nào khác đang chiếm dụng cổng `8000` hoặc `8001`. Có thể kiểm tra bằng lệnh:
     ```powershell
     netstat -ano | findstr :8000
     netstat -ano | findstr :8001
     ```
