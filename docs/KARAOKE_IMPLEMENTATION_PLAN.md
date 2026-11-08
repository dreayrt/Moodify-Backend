# Kế hoạch triển khai phòng karaoke trực tuyến cho Moodify

**Trạng thái:** Bản đề xuất để duyệt, chưa triển khai mã nguồn  
**Ngày:** 26/10/2026  
**Phạm vi giả định:** Một phòng karaoke chung; người dùng đăng ký bài và hát lần lượt. Song ca đồng thời là giai đoạn riêng. Một lập trình viên làm toàn thời gian; bản đầu chỉ có âm thanh, không video và không ghi âm buổi hát.

## 1. Kết luận và các quyết định cần duyệt

Hát theo lượt **khả thi** với stack hiện tại nếu bổ sung dịch vụ truyền âm thanh WebRTC. `python-audio-separator` **đủ để tạo bản beat thử nghiệm** từ file bài hát gốc, nhưng không tạo lời dạng chữ và mốc thời gian. Song ca đồng thời cần thử nghiệm riêng vì độ trễ mạng giữa các ca sĩ có thể làm lệch nhịp.

| Quyết định | Đề xuất cho bản đầu | Lý do |
|---|---|---|
| Nguồn bài | Quản trị viên chuẩn bị 5–10 bài đã xác minh quyền sử dụng | Kiểm soát chất lượng beat, lời và chi phí xử lý |
| Thời điểm tách giọng | Xử lý trước khi bài được công bố, bằng tác vụ Python riêng | Tách giọng tốn tài nguyên; không để người dùng chờ khi đến lượt |
| Lời | LRC theo từng dòng, có người duyệt; tận dụng `lyricsSynced` nếu đúng bản ghi | Giao diện hiện tại đọc được LRC theo dòng; chưa hỗ trợ tô sáng từng từ |
| Âm thanh phòng | Dùng LiveKit Cloud để thử nghiệm WebRTC audio; Spring Boot giữ quyền điều khiển | Giảm công việc vận hành SFU/TURN trong giai đoạn đầu |
| Trình tự phát hành | Phòng + hàng đợi + hát đơn trước; song ca sau cổng thử nghiệm độ trễ | Không để bài toán đồng bộ song ca chặn toàn bộ tính năng |
| Quản lý phòng | Một phòng `main` do hệ thống điều khiển, moderator/admin can thiệp | Không phụ thuộc một người giữ vai trò chủ phòng luôn trực tuyến |

**Cần xác nhận trước khi đầu tư triển khai:** phạm vi quyền dùng bài hát/beat/lời trên phòng trực tuyến; số người nghe cùng lúc dự kiến; có GPU khả dụng hay dùng dịch vụ xử lý ngoài; mục tiêu song ca là hai người hay nhiều hơn hai người cùng lúc.

## 2. Hiện trạng Moodify và khoảng trống

- Frontend: Next.js/React; `player-context.tsx` phát nhạc qua `HTMLAudioElement` cho từng người dùng, với hàng đợi cục bộ. Trình này không thể dùng trực tiếp làm đồng hồ chung cho cả phòng.
- `realtime-lyrics.tsx` đọc `lyricsSynced` dạng LRC theo dòng. Nếu chỉ có `lyricsPlain`, mã nguồn phân bổ thời gian theo độ dài dòng; cách ước lượng này không đủ chính xác cho karaoke.
- Backend: Spring Boot/Java 21, JWT, MySQL quản lý người dùng và giấy phép; MongoDB lưu `Track` gồm `lyrics_plain`, `lyrics_synced`, `local_path`. Chưa có phòng, hàng đợi realtime hoặc media server.
- `/api/tracks/{id}/stream` phát bài gốc hoặc chuyển hướng tới máy chủ nhạc Oracle. Giao diện còn có thể dùng URL audio trực tiếp. Asset karaoke cần đường truy cập và kiểm tra quyền riêng; không chỉ tái sử dụng endpoint phát bài gốc đang cho phép GET công khai.
- Clone `E:\python-audio-separator` khai báo phiên bản `0.47.1`. Preset `karaoke` trong `audio_separator/ensemble_presets.json` kết hợp ba mô hình để loại giọng chính. Chất lượng và thời gian xử lý thực tế phải đo trên bài hát và phần cứng của dự án.

## 3. Luồng tạo một bài karaoke

```text
File bài hát gốc + lời đã kiểm tra (nếu có) + thông tin quyền
  → kiểm tra định dạng, thời lượng, checksum, quyền sử dụng
  → tách vocals / instrumental bằng audio-separator (tác vụ nền)
  → tạo hoặc căn thời gian lời trên bản gốc / vocals
  → nghe và sửa beat, lời, độ lệch đầu bài
  → duyệt asset karaoke
  → bài xuất hiện trong danh sách đăng ký hát
```

### 3.1. Đầu vào bắt buộc và tùy chọn

- **Bắt buộc:** file âm thanh gốc đủ chất lượng, gắn đúng `trackId`, tài liệu/quyết định xác nhận phạm vi sử dụng.
- **Khuyến nghị:** lời dạng chữ đã kiểm tra, đúng phiên bản bản ghi; WAV/FLAC nếu có. MP3 vẫn xử lý được nhưng nguồn nén kém dễ làm giảm chất lượng beat.
- **Có thể tận dụng:** `Track.lyricsSynced` nếu thời gian khớp đúng file gốc. `lyricsPlain` chỉ cung cấp chữ, chưa có thời điểm hiển thị.
- Chỉ có file nhạc gốc vẫn tạo được **beat ứng viên**. Muốn có lời đồng bộ phải thêm khâu nhận dạng/căn lời và duyệt thủ công. Đầu ra AI không tự động được coi là bài đã sẵn sàng phát.

### 3.2. Tách beat

1. Thử trên 5–10 bài đại diện: giọng nam/nữ, bè nền, song ca, reverb, đoạn ít nhạc cụ.
2. So sánh preset `karaoke` với một preset instrumental và một mô hình đơn. Chọn theo **nghe thực tế + thời gian xử lý**, không chọn chỉ theo tên preset. Preset `karaoke` chạy ba mô hình nên có thể tốn thời gian/tài nguyên hơn.
3. Lưu bản gốc, beat và vocals tách ra dưới một `assetVersion`; ghi model/preset, phiên bản mã, checksum nguồn, thời lượng và thời gian xử lý. Không ghi đè âm thanh bài gốc.
4. Duyệt: còn sót giọng chính không; nhạc cụ có bị mất/méo không; âm lượng có clipping không; đoạn đầu/cuối và thời lượng có bị dịch không. Bài lỗi được thử mô hình khác hoặc loại khỏi danh sách karaoke.
5. Chạy như batch/worker riêng khỏi Spring Boot. Giai đoạn mẫu có thể chạy bằng CLI và nhập kết quả sau khi duyệt; tự động hóa upload → job → kết quả chỉ làm khi quy trình thủ công đã ổn định.

### 3.3. Lời đồng bộ

1. Nếu đã có `lyricsSynced`, so từng đoạn với bản gốc. Chỉ tái sử dụng khi đúng cùng bản ghi, intro và outro.
2. Nếu có lời dạng chữ nhưng chưa có thời gian, dùng giọng hát tách ra để gợi ý căn thời gian, rồi sửa trong giao diện/biên tập LRC. Với bản đầu, duyệt theo **dòng** là đủ.
3. Nếu chỉ có file nhạc, nhận dạng giọng hát tạo bản nháp lời; người duyệt sửa chữ và thời gian. Có thể thử `karaoke-gen` với local Whisper, nhưng đây là pipeline lớn có thêm tạo video; chỉ nhận phần LRC cần cho Moodify.
4. Lưu LRC gắn với `assetVersion` của beat. Nếu đổi nguồn hoặc beat, kiểm tra lại độ khớp; muốn tô sáng từng từ về sau cần dữ liệu thời gian từng từ và UI mới.

### 3.4. Trạng thái và dữ liệu đề xuất

Tạo collection MongoDB `karaoke_assets` liên kết `trackId` với `version`, `sourceChecksum`, `instrumentalUrl`, `vocalReferenceUrl`, `lyricsLrc`, `durationMs`, `timingOffsetMs`, `modelPreset`, `processingStatus`, `reviewStatus`, `rightsReference`, `createdAt`, `approvedAt`. Trạng thái đi qua `UPLOADED → PROCESSING → REVIEW_REQUIRED → APPROVED` hoặc `REJECTED/FAILED`. Chỉ `APPROVED` và còn quyền sử dụng mới được đăng ký vào hàng đợi.

Không cho người dùng bình thường tự nộp bài gốc trong MVP. Nếu thêm sau này, cần chính sách nguồn bài, kiểm duyệt quyền, giới hạn dung lượng và hàng đợi xử lý.

## 4. Kiến trúc phòng karaoke

```text
Next.js /dashboard/karaoke
  ├─ REST + JWT ───────────────> Spring Boot: phòng, hàng đợi, quyền, snapshot
  ├─ WebRTC audio + data ──────> LiveKit SFU: ca sĩ phát, khán giả nghe
  └─ beat/LRC được duyệt ──────> kho asset được kiểm soát truy cập

Spring Boot ──token ngắn hạn + cập nhật quyền phát──> LiveKit
Spring Boot ──MySQL──> phòng, hàng đợi, lượt hát, nhật ký điều khiển
Spring Boot ──MongoDB──> Track và karaoke_assets
```

### 4.1. Luật phòng và hàng đợi

- Một phòng cố định `main`; người dùng đã đăng nhập có thể vào nghe. Giới hạn ban đầu đề xuất: 10–20 người để thử nghiệm, điều chỉnh sau khi đo tải.
- Mỗi yêu cầu hát có `queueItemId`, `trackId`, `requesterId`, danh sách ca sĩ, thời điểm đăng ký và trạng thái `WAITING/READY/PLAYING/COMPLETED/SKIPPED/CANCELLED`.
- MVP chỉ chấp nhận **một ca sĩ đang hát**. Mỗi người tối đa một yêu cầu chờ trong hàng đợi; server xác nhận thứ tự và chuyển lượt tự động.
- Moderator/admin được bỏ lượt, khóa micro, gỡ bài, đóng phòng tạm thời. Khi ca sĩ mất kết nối quá thời gian chờ, server chuyển lượt. Nếu người điều khiển rời phòng, phòng vẫn tồn tại.
- Dữ liệu phòng/hàng đợi nằm ở backend; frontend không tự quyết định ai được hát. MySQL transaction/khóa hoặc version bảo vệ thao tác nhận lượt đồng thời.

### 4.2. API và sự kiện dự kiến

| API/sự kiện | Mục đích |
|---|---|
| `GET /api/karaoke/room` | Snapshot phòng, bài hiện tại, hàng đợi, version |
| `POST /api/karaoke/room/join-token` | Xác thực Moodify, trả token LiveKit theo quyền |
| `POST /api/karaoke/queue` | Đăng ký bài đã duyệt |
| `DELETE /api/karaoke/queue/{id}` | Rút yêu cầu của chính mình khi chưa hát |
| `POST /api/karaoke/current/finish` | Ca sĩ báo hoàn tất; backend xác minh và chuyển lượt |
| API moderator: skip/mute/close | Điều khiển sự cố |
| Data events `queue.updated`, `performance.started`, `performance.ended` | Cập nhật UI kèm version; client tải lại snapshot khi vào muộn hoặc mất sự kiện |

Khán giả nhận quyền nghe nhưng không phát micro; chỉ ca sĩ trong lượt được backend cấp quyền phát. Token phải ngắn hạn và khóa bí mật LiveKit chỉ nằm ở backend. Không dựa vào lệnh mute của frontend để bảo vệ phòng.

### 4.3. Âm thanh hát đơn

- Ca sĩ tải beat đã duyệt, nghe qua tai nghe, hiển thị lời theo thời gian phát **tại máy ca sĩ**.
- Trình duyệt trộn beat và tín hiệu micro bằng Web Audio rồi phát một luồng âm thanh lên LiveKit. Khán giả nghe cùng một luồng, giảm nguy cơ beat và giọng đến lệch nhau. Asset cần CORS/phương thức phân phối phù hợp để đưa vào bộ trộn Web Audio.
- Không phát lại giọng của chính ca sĩ vào tai nghe theo mặc định, vì giọng tự nghe bị trễ gây khó hát. Có kiểm tra micro/âm lượng trước khi nhận lượt.
- Khi ca sĩ rời mạng, ngừng luồng, hiện trạng thái chờ và chuyển lượt sau thời gian grace. Thời gian lời phía khán giả là phép ước lượng theo sự kiện phát và độ trễ nghe; kiểm thử thực tế trước khi hứa tô sáng từng từ.

### 4.4. Song ca đồng thời: cổng kỹ thuật riêng

Song ca từ xa **không chỉ là cấp quyền cho hai micro**. Hai ca sĩ sẽ có độ trễ nghe/gửi khác nhau; nếu mỗi người đuổi theo giọng người kia, nhịp có thể trôi. Thử nghiệm dùng hai ca sĩ cùng nghe beat được lên lịch theo một mốc chung, mỗi người gửi micro riêng. Khán giả cần nghe bản trộn có bù độ lệch; lựa chọn giữa trộn phía server và căn chỉnh phía người nghe chỉ quyết định sau phép đo trên hai mạng khác nhau.

Điều kiện để triển khai rộng: hai ca sĩ hát theo beat ổn định; khán giả nghe hai giọng khớp nhịp chấp nhận được; kết nối yếu không làm hỏng cả buổi hát. Nếu không đạt, phát hành kiểu hát **luân phiên câu** trước, tiếp tục nghiên cứu bản song ca đồng thời. Bắt đầu với hai người; trên hai người là bước mở rộng tiếp theo.

## 5. Bảo mật, quyền sử dụng và vận hành

- Rà soát hợp đồng theo cách sử dụng thực tế: tạo beat từ bản gốc, lưu/phân phối beat, hiển thị lời và truyền buổi hát trực tuyến. Giấy phép MIT của `audio-separator` chỉ áp dụng cho mã nguồn; không cấp quyền đối với bài hát. Kiểm tra thêm điều kiện sử dụng của mô hình được chọn.
- Không bật ghi âm/bản phát lại trong MVP. Nếu sau này có ghi âm, cần chính sách đồng ý, lưu trữ, xóa và quyền sử dụng phần biểu diễn.
- Karaoke asset chỉ phát khi đã duyệt, quyền còn hiệu lực và người dùng có quyền truy cập. Kiểm tra lại quyền trước khi đưa bài vào hàng đợi và trước khi bắt đầu lượt.
- Đo: thời gian xử lý/bài, tỉ lệ beat được duyệt, lỗi tạo LRC, số người vào phòng, kết nối WebRTC, mất gói, thời gian chuyển lượt, băng thông và chi phí dịch vụ. Ước lượng lưu lượng theo bitrate × số luồng × số người nghe × thời lượng, sau đó bổ sung overhead thực đo.
- Server media phải có HTTPS/TLS và kết nối WebRTC/TURN thích hợp. Kiểm thử trên di động, Wi-Fi và mạng di động khác nhau; không suy ra chất lượng mạng thật từ thử trên một máy.

## 6. Lộ trình, đầu ra và tiêu chí hoàn thành

| Giai đoạn | Thời gian sơ bộ | Đầu ra | Cổng quyết định |
|---|---:|---|---|
| 0. Rà soát đầu vào + thử separator | 3–5 ngày | 5–10 bài mẫu, báo cáo chất lượng/giây xử lý/tài nguyên, phương án quyền | Có ít nhất vài bài beat và lời dùng được; cách chạy phù hợp máy/chi phí |
| 1. Kho asset karaoke | 4–7 ngày | Beat, LRC, trạng thái xử lý/duyệt, catalog chỉ hiện bài approved | Beat nghe đạt, lời đúng bản thu, asset không lộ khi chưa duyệt |
| 2. Phòng + hàng đợi | 5–8 ngày | Vào phòng, đăng ký, tự chuyển lượt, moderator, reconnect | Không có hai ca sĩ cùng chiếm lượt; mất mạng vẫn khôi phục đúng trạng thái |
| 3. Hát đơn realtime | 5–8 ngày | Micro, trộn beat+giọng, WebRTC, UI lời và kiểm tra thiết bị | Thử trên ít nhất 3 thiết bị, 2 mạng; người nghe nhận âm thanh ổn định, không lặp/echo nghiêm trọng |
| 4. Kiểm thử và phát hành giới hạn | 3–5 ngày | Sửa lỗi, giới hạn tải, log/metric, hướng dẫn vận hành | Đạt danh sách tình huống ở mục 7 |
| 5. Song ca POC và triển khai nếu đạt | Thêm 2–4 tuần | Hai ca sĩ cùng bài, kết quả đo đồng bộ, bản trộn cho khán giả | Chất lượng nhịp/âm thanh được nhóm thử chấp nhận trên mạng thật |

**Tổng MVP hát theo lượt:** khoảng **3–5 tuần**, chưa tính xin quyền, chuẩn bị nguồn nhạc lớn hoặc chi phí phát sinh từ hạ tầng. Đây là ước lượng để ra quyết định, sẽ chỉnh sau giai đoạn 0. Nếu thử nghiệm cho thấy phải tự host media hoặc làm hệ thống nhận dạng lời hoàn toàn tự động, cần dự toán lại.

## 7. Danh sách kiểm thử bắt buộc trước khi phát hành

1. Hai người đăng ký cùng lúc: thứ tự hàng đợi rõ, không nhân đôi lượt.
2. Khán giả không thể tự bật micro; ca sĩ hết lượt không thể tiếp tục phát; moderator có thể ngắt.
3. Ca sĩ từ chối quyền micro, đổi thiết bị, rút tai nghe hoặc thoát giữa bài: phòng xử lý được và chuyển lượt.
4. Người nghe vào giữa bài, tải lại trang hoặc chuyển Wi-Fi sang mạng di động: nhận đúng bài và trạng thái.
5. Beat và LRC khớp từ đầu đến cuối, kể cả intro/outro; thử bài có bè và reverb để phát hiện sót giọng.
6. Bài hết hạn/không được duyệt không hiện trong danh sách và không phát dù hàng đợi cũ còn tham chiếu.
7. Thử tải phòng giới hạn ban đầu; ghi băng thông thực tế và chất lượng kết nối.
8. Song ca chỉ được bật khi thử trên hai mạng khác nhau và người nghe xác nhận hai giọng không lệch nhịp khó chịu.

## 8. Tài liệu tham chiếu

- Audio Separator: https://github.com/nomadkaraoke/python-audio-separator
- Preset local: `E:\python-audio-separator\audio_separator\ensemble_presets.json`
- Karaoke Gen (pipeline lời/beat đầy đủ, có bước duyệt): https://github.com/nomadkaraoke/karaoke-gen
- LiveKit token và quyền: https://docs.livekit.io/home/server/generating-tokens
- LiveKit self-hosting ports: https://docs.livekit.io/transport/self-hosting/ports-firewall/
- WebRTC kết nối và TURN: https://webrtc.org/getting-started/peer-connections
- Quyền đối với âm nhạc: https://www.wipo.int/en/web/music/

## 9. Bước tiếp theo sau khi duyệt kế hoạch

1. Chọn 5–10 bài mẫu và xác minh phạm vi quyền.
2. Xác định máy xử lý có GPU hay cần chạy CPU/dịch vụ ngoài; đo trên một bài trước khi dự toán toàn kho.
3. Quyết định phạm vi MVP: một phòng chung, số người nghe ban đầu, chỉ hát đơn và LRC theo dòng.
4. Sau giai đoạn thử nghiệm 0, cập nhật thiết kế chi tiết/ticket triển khai dựa trên kết quả đo thay vì cố định mô hình tách giọng hoặc phương án song ca từ đầu.
