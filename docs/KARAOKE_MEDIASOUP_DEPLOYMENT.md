# Triển khai karaoke với mediasoup

Dịch vụ media chạy tách biệt với Spring Boot. Spring Boot vẫn là nguồn dữ liệu chuẩn cho thành viên phòng và trạng thái phát nhạc. Sau khi xác minh tư cách thành viên, Spring Boot cấp vé giọng nói có chữ ký, chỉ dùng một lần và có hiệu lực trong 60 giây; tiến trình mediasoup xác minh vé rồi tự hết hạn dấu đánh dấu chống phát lại. Gói tin giọng nói được truyền qua SFU mediasoup. Mỗi trình duyệt tự tải nhạc nền và bắt đầu phát theo thời điểm phát do máy chủ xác định, vì vậy SFU không truyền tiếp các bản nhạc đệm có bản quyền.

## Môi trường

Cấu hình cùng một giá trị `KARAOKE_MEDIA_TICKET_SECRET` (ít nhất 32 ký tự ngẫu nhiên) trong môi trường Spring Boot và `supporting-services/karaoke-media/.env`; dùng một secret service khác cho `KARAOKE_MEDIA_SERVICE_SECRET`. Trong Spring Boot, đặt `KARAOKE_MEDIA_WS_URL` thành URL `wss://<your-host>/ws` có thể truy cập từ bên ngoài và `KARAOKE_ROOM_RECONNECT_GRACE_MS=30000`. Trong `supporting-services/karaoke-media/.env`, đặt `MEDIASOUP_ANNOUNCED_IP` thành địa chỉ IP công khai của VM và `MEDIASOUP_ALLOWED_ORIGINS` thành danh sách origin chính xác, phân tách bằng dấu phẩy (ví dụ `https://moodify.click`). Bỏ `localhost` khỏi allowlist production. Không đưa bí mật vé vào bất kỳ biến môi trường frontend `NEXT_PUBLIC_*` nào.

Trên máy ảo, trong thư mục `supporting-services/karaoke-media/`, sao chép `.env.example` thành tệp `.env` không được Git theo dõi, điền bí mật/origin/IP công khai, rồi khởi chạy stack bằng lệnh `docker compose --env-file supporting-services/karaoke-media/.env -f supporting-services/karaoke-media/compose.yaml up -d --build`. Cổng 3001 chỉ được liên kết với giao diện loopback của máy chủ; định tuyến WSS qua reverse proxy TLS có hỗ trợ nâng cấp kết nối WebSocket. Giữ `/health/ready` và `/metrics` ở mạng nội bộ; không mở cổng TCP 3001 ra Internet hoặc đưa đường dẫn metrics qua reverse proxy công khai.

Mở các quy tắc ingress sau trong Google Cloud firewall cho network tag của máy ảo; khi phù hợp, hãy giới hạn nguồn truy cập ở mức cần thiết:

| Giao thức | Cổng | Mục đích |
| --- | ---: | --- |
| TCP | Cổng TLS của proxy (thường là 443) | Tín hiệu WebSocket công khai; proxy chuyển tiếp đến loopback 3001 |
| UDP và TCP | 40000–49999 | Các kết nối truyền tải WebRTC của mediasoup |
| UDP và TCP | 3478 | coturn, chỉ khi bật TURN |
| UDP | 49160–49200 | Chuyển tiếp coturn, chỉ khi bật TURN |

Để bật TURN, cấu hình `TURN_SERVER_URLS` và cùng một `TURN_SHARED_SECRET` cho coturn lẫn dịch vụ media, sau đó khởi chạy compose profile `turn`. Dịch vụ media tạo thông tin xác thực TURN có thời hạn ngắn. Các giá trị bí mật mẫu chỉ là chỗ giữ chỗ; hãy thay chúng trước khi sử dụng. Không bao giờ mở dải UDP của mediasoup nếu chưa đặt đúng IP công khai được khai báo trong `MEDIASOUP_ANNOUNCED_IP`.

## Giới hạn vận hành

Dịch vụ giới hạn mỗi phòng ở sáu kết nối media và mỗi người dùng ở một kết nối trong một phòng. Bộ định tuyến của phòng và dấu đánh dấu chống phát lại được lưu trong bộ nhớ tiến trình, vì vậy giai đoạn thử nghiệm chỉ chạy một bản sao. Khi khởi động lại, các kết nối giọng nói bị ngắt; máy khách sẽ kết nối lại và yêu cầu vé mới. Máy ảo trong ảnh chụp màn hình bảng điều khiển được cung cấp có cấu hình `e2-standard-2` (2 vCPU, 8 GB RAM); riêng thông tin này chưa đủ để khẳng định máy có thể phục vụ sáu kết nối. Hãy đo CPU, bộ nhớ, mất gói và băng thông bằng máy khách thực tế trước khi bật tính năng hoặc thêm phòng hoạt động đồng thời. Trạng thái phát nhạc vẫn được lưu trong MySQL và có thể khôi phục độc lập.
