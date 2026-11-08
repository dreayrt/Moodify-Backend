# Hệ đề xuất Moodify — triển khai v1.2

Ngày triển khai và thu gọn persistence: **27/10/2026**, múi giờ người dùng **Asia/Saigon**.

**Cập nhật vận hành 29/10/2026 — v1.2:** [runbook, API điều khiển và bảo vệ outcome](RECOMMENDATION_V1_2_OPERATIONS.md). PLAY_START sau commit được enqueue trên executor RAM hữu hạn, insert EVENT có deadline và circuit cooldown. Heartbeat không đọc toàn catalog để xác minh queue. Có controls enabled/CF/campaign, metrics và preview catalog dành cho ADMIN, scope từng process. Không sửa UI hoặc schema; không deploy. [Audit chỉ đọc mới](recommendation-data-audit-v1.2-20261029.json) vẫn có 0/153 bài đủ policy.

**Kiểm chứng v1.2:** 79 backend tests ngoài EmotionServiceTest đạt, package thành công; 15 Python tests đạt; 37 tình huống HTTP với MySQL/Mongo fixture đạt. Có test socket driver không phản hồi, queue saturation, circuit recovery, transaction rollback, controls/revision, quyền trên token cũ và empty/error counters. Không chứng nhận tải/CDN/production hoặc full suite EmotionServiceTest; các kiểm chứng v1.1 bên dưới là lịch sử.

**Quyết định hiện hành theo yêu cầu chủ dự án: không thêm bảng/cột MySQL; tối đa 2 collection Mongo mới. Lượt thu gọn này chỉ sửa backend/worker/tài liệu và không thay đổi giao diện.**

**Bổ sung đánh giá 27/10/2026:** xem [kiến trúc/công thức hiện hành](RECOMMENDATION_SYSTEM_ARCHITECTURE.md) và [audit dữ liệu, điểm chặn, lộ trình production](RECOMMENDATION_PRODUCTION_REVIEW.md). Kiểm thử chức năng đạt không có nghĩa sẵn sàng production; đánh giá phát hiện các điểm cần xử lý thêm về worker snapshot, schema playlist, ownership và fault isolation. [JSON audit read-only](recommendation-data-audit-20261027.json) là nguồn số liệu hiện tại.

**Cập nhật v1.1:** [chi tiết kiến trúc, công thức, API và kiểm thử v1.1](RECOMMENDATION_V1_1_IMPLEMENTATION.md). Follow artist, favorite album và selected search đã được tích hợp sau [khảo sát nguồn](RECOMMENDATION_SIGNAL_REASSESSMENT.md); library alias được dedupe, worker có snapshot riêng mỗi batch, trend lọc USER ACTIVE và playlist mới dùng owner ID. Không sửa frontend hoặc mở rộng schema. [Audit chỉ đọc sau cải thiện](recommendation-data-audit-v1.1-20261027.json) vẫn có 0/153 bài đủ điều kiện.

Phạm vi mã: recommendation dựa trên hành vi, đặc trưng bài hát, playlist, xu hướng nội bộ và chiến dịch có thời hạn. **Không gọi hoặc sửa model tìm kiếm cảm xúc.** [Thiết kế tổng thể](PERSONALIZED_RECOMMENDATION_ARCHITECTURE.md) và [thiết kế playlist/database](RECOMMENDATION_PLAYLIST_DATABASE_DESIGN.md) cung cấp bối cảnh; tài liệu này mô tả chính xác phiên bản đã hiện thực hóa.

## 1. Các chức năng đã có mã chạy

- Home trả các section track, playlist công khai và bản phối cá nhân; decision log lưu request/item, nguồn, điểm và phiên bản.
- Content-based từ genre, nghệ sĩ và các đặc trưng audio có xử lý thiếu; sở thích nhiều nhóm, lịch sử gần và hành vi lưu/thêm playlist riêng.
- Chỉ tiếp nhận điểm collaborative từ worker đúng user, hữu hạn và chưa quá hạn; khi chưa đủ dữ liệu dùng `content-session-v1`.
- Daily mix và khám phá tuần có stable ID, immutable track versions, generation key chống trùng, CAS khi publish và giới hạn refresh.
- Trend mix/section chỉ xuất hiện khi có candidate trend đủ ngưỡng; không đổi tên catalog popularity thành trend.
- Playlist có sẵn được xếp hạng và giảm các danh sách gần trùng nhau; không tự sắp lại playlist do user tạo.
- Đề xuất phát tiếp/thêm bài; user phải chủ động thêm vào playlist qua API mutation hiện có.
- Queue mang token có chữ ký liên kết user, parent playlist/version và từng track. Sự kiện PLAY_START được enqueue sau khi transaction MySQL của phiên nghe commit và ghi Mongo trên worker RAM riêng; quy tắc tính qualified stream giữ nguyên. Queue đầy/circuit mở/insert lỗi có đếm mất log, không rollback phiên nghe hoặc doanh thu. Không có durable outbox; crash có thể mất log.
- Impression sau khi card hiển thị ít nhất 50% trong 1 giây, chỉ khi tab visible; event ID giữ nguyên khi retry.
- Hide/not interested có hoàn tác; bản phối mới được tính lại, bản phối cũ khi đọc/queue vẫn lọc track bị chặn và quyền phát hiện hành.
- Lưu bản sao playlist cá nhân là thao tác idempotent, mặc định riêng tư, có lineage; giữ độc lập khi bản phối hệ thống cập nhật.
- Admin tạo, bật/tắt và đặt lịch campaign theo bài/playlist; optimistic revision cho cập nhật; campaign thay đổi/hết hạn được áp dụng không cần train lại.
- Trang quản trị có chẩn đoán số bài đủ điều kiện và các nhóm thiếu metadata, audio hoặc giấy phép; số đếm theo điều kiện có thể chồng lấn.
- Xóa lịch sử làm mất hiệu lực hồ sơ học riêng của user và yêu cầu rebuild mix; không còn bảng aggregate riêng. Queue/version cũ vẫn thuộc user và được giữ theo vòng đời hiện tại, không tự xóa mọi playlist user đã lưu.

## 2. Thành phần và dữ liệu

Backend mới ở package `com.laphuth.moodify.recommendation`. Reader recommendation đọc các bảng nghiệp vụ MySQL đã có: `users`, `listening_history`, `playback_events`, `revenue_playback_sessions`, `user_library_tracks`, `favorite_artists`, `favorite_albums`, `albums`, `album_tracks`, `search_history` và giấy phép qua repository. API follow/favorite ghi đúng hai bảng favorite hiện có; library vẫn ghi bảng nghiệp vụ library. Luồng phát nhạc nghiệp vụ vẫn ghi lịch sử/doanh thu như trước. Không còn DDL, schema gate hoặc thao tác ghi các bảng recommendation trong MySQL.

Chỉ dùng **2 collection mới**:

| Collection | Nội dung | Vòng đời |
| --- | --- | --- |
| `recommendation_state` | Document phân biệt bằng `kind`: PROFILE, FEEDBACK, MIX, MIX_VERSION, CAMPAIGN | Profile 3 ngày; version thay thế 7 ngày; mix hiện hành, phản hồi và campaign giữ đến khi cập nhật/xóa |
| `recommendation_events` | DECISION chứa items nhúng trong một document; EVENT chứa impression/open/outcome | Decision 7 ngày, event 30 ngày bằng TTL index |

Các document có ID phân vùng rõ (`profile:<userId>`, `campaign:<id>`, `feedback:<deterministicId>`); mix/version giữ UUID hiện có để không đổi API. Mọi truy vấn dùng `kind` và scope người dùng tương ứng. Có 6 index tổng cộng ngoài index `_id` mặc định: truy vấn user, campaign, refresh, TTL state; truy vấn log và TTL log. TTL chạy nền nên không đảm bảo xóa đúng từng giây; thời hạn token/quyền phát được kiểm tra riêng.

Không sao chép toàn bộ lịch sử nghe sang Mongo. Aggregate user–track/ngày được tính trong RAM khi train. Trend đọc cửa sổ lịch sử có sẵn; playlist features tính trực tiếp từ catalog. Model artifact và manifest/checksum ở filesystem `storage/recommendation-models`, không tạo bảng registry hoặc collection metrics. Điểm collaborative lưu tối đa 500 track/user. Playlist người dùng chủ động lưu vẫn dùng collection `playlists` hiện có.

## 3. Cài và khởi tạo ở môi trường cần triển khai

Chạy từ thư mục backend bằng PowerShell, dùng `.env` của đúng môi trường:

```powershell
.\scripts\recommendations.ps1 -Mode setup
.\scripts\recommendations.ps1 -Mode audit
.\scripts\recommendations.ps1 -Mode init
```

`setup` tạo virtualenv/cài dependencies. `audit` chỉ đọc số đếm. `init` tạo index ở đúng hai collection Mongo và thử đánh giá ALS; không chạy SQL DDL. Lần gọi API đầu cũng có thể khởi tạo index Mongo. Mã cũ `RecommendationSchema`, flag `RECOMMENDATIONS_SCHEMA_INITIALIZE` và migration `recommendation_v1.sql` đã được gỡ.

**Database local đã dọn ngày 27/10/2026 theo yêu cầu chủ dự án:** đã backup có checksum, chuyển 2 hồ sơ người dùng sang `recommendation_state`, xóa đủ 8 bảng SQL và 4 collection Mongo cũ. 36 dòng `user_track_daily` là aggregate dẫn xuất, được giữ trong backup và không tái tạo thành bảng/collection. Kiểm tra lại không còn đối tượng legacy. Hai collection mới là `recommendation_state` và `recommendation_events`. Cấu trúc và số bản ghi của 27 bảng MySQL nghiệp vụ, cùng số document của 17 collection Mongo nghiệp vụ, khớp trước/sau. Worker đã chạy một vòng thành công sau cleanup, chỉ tính 36 aggregate trong RAM, không train model.

Bản sao lưu: `storage/recommendation-backups/20261027T132448753525Z/snapshot.json`; checksum và biên bản kiểm chứng nằm cùng thư mục. Xem [biên bản dọn dữ liệu](RECOMMENDATION_STORAGE_CLEANUP.md). Không có backend/worker đang chạy khi dọn; các tiến trình Java của IDE được giữ nguyên. Không chỉnh giao diện hoặc deploy production.

Công cụ `supporting-services/recommendation-worker/compact_storage.py` đã được kiểm thử trên fixture và áp dụng vào database local theo yêu cầu. Các lệnh sau mô tả cách dùng lại khi cần, không cần chạy thêm trên local đã dọn:

```powershell
# Read-only, không backup/ghi/xóa
.\scripts\recommendations.ps1 -Mode review-storage

# Chỉ chạy sau khi được chủ dự án đồng ý; dừng backend/worker đang ghi trước
# Backup có checksum + import dữ liệu cần giữ; chưa xóa nguồn
.\supporting-services\recommendation-worker\.venv\Scripts\python.exe .\supporting-services\recommendation-worker\compact_storage.py --apply

# Backup + import + kiểm tra nguồn chưa đổi + dọn đúng allowlist 8 bảng/4 collection cũ
.\supporting-services\recommendation-worker\.venv\Scripts\python.exe .\supporting-services\recommendation-worker\compact_storage.py --apply --remove-legacy
```

Backup BSON Extended JSON nằm trong `storage/recommendation-backups/<timestamp>/snapshot.json`, có DDL SQL và dữ liệu nguồn, checksum riêng. SQL DATE được giữ bằng tag `$sqlDate`. Công cụ không đụng bảng/collection users, tracks, playlists, giấy phép, lịch sử hoặc doanh thu. Aggregate cũ và registry cũ nằm trong backup; không tái tạo thành collection riêng. Metadata model đang phục vụ lấy từ profile và manifest artifact. Không deploy production trong lượt này.

## 4. Chạy worker và model

```powershell
# Tổng hợp một lần
.\scripts\recommendations.ps1 -Mode run

# Đánh giá rồi cân nhắc xuất ALS
.\scripts\recommendations.ps1 -Mode train

# Tổng hợp mỗi 15 phút; thử train tối đa một lần mỗi 24 giờ
.\scripts\recommendations.ps1 -Mode watch
```

Worker không ghi MySQL. Worker chỉ lấy tương tác từ tài khoản role USER, status ACTIVE; mỗi extract dùng transaction READ ONLY mới và kết thúc bằng rollback. Chuẩn hóa Spotify ID/Mongo ID và giới hạn đóng góp lặp theo user–track/ngày. Follow/album không trở thành label ALS giả. Artifact và manifest lưu tại `storage/recommendation-models`, được Git ignore.

Mô hình dùng confidence-weighted implicit ALS bằng NumPy dành cho catalog nhỏ. Chia holdout theo timeline toàn cục; feature của ngày train cutoff và các ngày sau đó không được vào train. Chỉ publish nếu đủ dữ liệu và NDCG@10 trên tập holdout vượt baseline popularity theo ngưỡng cấu hình trong mã. Đây là cổng chất lượng ban đầu, không thay cho A/B hoặc khẳng định có ý nghĩa thống kê.

Giới hạn hiện tại: dense matrix tối đa 2 triệu phần tử, tối đa một triệu history rows trong một lần extract. Tăng quy mô cần sparse/incremental pipeline; không nâng giới hạn mà chưa đo RAM/thời gian. Khi dữ liệu ít, thông báo `accepted: false` là kết quả bình thường và hệ tiếp tục dùng content/session.

Scheduler Java refresh các mix đã đến hạn mỗi 15 phút. Lần đọc đầu tạo mix còn thiếu bằng thuật toán chọn bài nhẹ; không train model trong request. Refresh thủ công trả kết quả đồng bộ và cách nhau tối thiểu 5 phút. Đây là khác biệt cụ thể so với phương án job refresh bất đồng bộ trong tài liệu thiết kế.

## 5. Quyền phát và thời gian

Recommendation v1 yêu cầu track có `status=published`, `visibility=public`, `moderation_status=approved`, có đường dẫn audio và giấy phép ACTIVE đúng ngày hiệu lực. Không tự coi metadata cũ thiếu trạng thái là đã xuất bản. `RECOMMENDATIONS_REQUIRE_ACTIVE_LICENSE=false` chỉ bỏ điều kiện bắt buộc có một license; nếu đã có license không hợp lệ thì vẫn bị loại. Mặc định bật yêu cầu license.

Event/revenue mới ghi UTC. Giá trị JDBC cho cột `DATETIME` được truyền bằng `LocalDateTime` UTC để tránh việc JVM giờ Việt Nam và connection UTC tự đổi lệch 7 giờ khi truyền `Timestamp`. Smoke test đối chiếu `started_at` thực lưu với giờ UTC và xác nhận lỗi ghi Mongo outcome không làm mất phiên nghe MySQL đã commit. Dữ liệu analytics/library cũ được coi là giờ địa phương Việt Nam theo triển khai hiện tại. Hai cấu hình phải thống nhất nếu lịch sử môi trường khác:

```properties
# UTC = legacy timestamp + offset minutes
RECOMMENDATIONS_LEGACY_OFFSET_MINUTES=-420
RECOMMENDATIONS_LEGACY_TIMEZONE=Asia/Ho_Chi_Minh
```

Nếu dữ liệu cũ thực sự là UTC, dùng `0` và `UTC`. Không thể tự suy ra chính xác mọi timestamp hỗn hợp không có provenance. Các phiên có `revenue_playback_sessions` được nhận diện riêng là UTC.

## 6. Các trang và API

- `/dashboard`: có recommendation sections, giữ phần khám phá catalog cũ với tên phản ánh đúng nguồn.
- `/dashboard/recommendations`: danh sách gợi ý và bản phối.
- `/dashboard/recommendations/{id}?type=PERSONALIZED_PLAYLIST`: chi tiết mix, phát, lưu bản sao, refresh.
- `/dashboard/recommendations/{id}?type=PLAYLIST`: playlist có sẵn và bài gợi ý tiếp nối.
- `/dashboard/admin/recommendations`: tạo/quản lý các đợt tuyển chọn; có liên kết từ trang admin.

API triển khai:

| Nhóm | Endpoint |
| --- | --- |
| Home/playlist | `GET /api/recommendations/home`, `GET /api/recommendations/playlists` |
| Tiếp nối | `GET /api/recommendations/next?seedTrackId=...`, `GET /api/recommendations/playlist-continuation?playlistId=...` |
| Bản phối | `GET /api/me/personalized-playlists`, `GET /api/me/personalized-playlists/{id}` |
| Phiên bản | `GET /api/me/personalized-playlists/{id}/versions/{versionId}` |
| Refresh/copy | `POST /api/me/personalized-playlists/{id}/refresh`, `POST /api/me/personalized-playlists/{id}/copies` |
| Queue | `POST /api/playback/queues` |
| Phản hồi | `POST /api/recommendations/events`, `PUT /api/recommendations/feedback` |
| Campaign | `GET/POST /api/admin/recommendations/campaigns`, `PUT /api/admin/recommendations/campaigns/{id}` |
| Chẩn đoán | `GET /api/admin/recommendations/readiness` |

API cá nhân hóa yêu cầu authentication; campaign yêu cầu ADMIN theo security rules. Playlist riêng và version riêng luôn kiểm tra chủ sở hữu. Client không được tự ghi PLAY_START/SAVE_COPY dưới dạng outcome đã xác thực.

## 7. Kiểm chứng

```powershell
# Unit/backend tests không phụ thuộc container model cảm xúc
.\mvnw.cmd '-Dtest=*,!EmotionServiceTest' test

# Worker unit tests; pytest là dependency phục vụ kiểm thử, không phải runtime
.\supporting-services\recommendation-worker\.venv\Scripts\python.exe -m pytest .\supporting-services\recommendation-worker -q

# Đóng gói rồi chạy HTTP smoke với MySQL/Mongo thật, database fixture tách biệt
.\mvnw.cmd clean -DskipTests package
.\scripts\recommendations.ps1 -Mode smoke
```

Smoke test kiểm tra file JAR không còn resource SQL hoặc class khởi tạo schema recommendation cũ; khi nâng cấp phải dùng `clean package` để loại resource sót từ build trước. Smoke test chỉ chạy nếu cả hai database server là local. Tạo hai database tạm có tên `moodify_recommendation_test_<random>`, không copy user thật; cleanup chỉ chấp nhận đúng regex tên fixture. Backend fixture tắt scheduler và kết thúc sau kiểm thử. Log nằm trong `target/recommendation-smoke-server.log`, không in token hoặc credentials.

Frontend: chạy `npx tsc --noEmit`, ESLint các file recommendation mới và `npm run build` trong thư mục frontend. Bộ test backend đầy đủ hiện có một test gọi container emotion service bên ngoài; khi container đó không chạy, test cũ `EmotionServiceTest.testPredictEmotionWithRunningContainer` trả 503. Không sửa model hoặc che lỗi đó để báo toàn bộ suite xanh.

Kết quả kiểm chứng v1.1: **66 backend tests** không phụ thuộc `EmotionServiceTest` đạt và package thành công; **15 worker/migration tests** đạt; **31 tình huống HTTP smoke** đạt với MySQL/Mongo thật trên fixture riêng. Smoke xác nhận API/worker chạy khi không tồn tại cả 8 bảng SQL cũ, chỉ tạo 2 collection recommendation; worker thấy commit từ connection khác giữa batch; follow/favorite ảnh hưởng score và mix; alias/namespace/ownership/intent/trend đúng các trường hợp đã kiểm tra; retention và cleanup fixture đạt. TypeScript, ESLint phần recommendation mới và Next production build đã đạt ở lượt triển khai UI trước; không chạy lại hoặc sửa frontend trong v1.1. Fixture dùng audio giả nên không chứng nhận phát audio thật hoặc production latency. Các server preview và database fixture được dọn sau kiểm tra. Chi tiết xem [biên bản v1.1](RECOMMENDATION_V1_1_IMPLEMENTATION.md).

## 8. Trạng thái dữ liệu local và giới hạn

Audit ngày triển khai ghi nhận 153 track, 3 tài khoản listener, 124 history rows và 0 playlist công khai. Phần lớn track legacy thiếu status/visibility; **chưa có track nào đồng thời đạt đủ policy recommendation nghiêm ngặt**. Vì vậy UI trên database local này hiện chưa có gợi ý từ catalog để hiển thị, dù các luồng đã được kiểm chứng trên database fixture độc lập. Hệ không tự tạo playlist công khai hoặc tự phê duyệt/đổi giấy phép để lấp đầy UI. Quản trị cần xác nhận metadata xuất bản và liên kết quyền phát đúng cho nội dung thực; không dùng dữ liệu fixture để thay cho dữ liệu thật.

Lần đánh giá worker có 2 user và 3 track trong ma trận tương tác lịch sử, không đủ holdout để publish ALS, nên model đang phục vụ là content/session baseline. Số bài trong mix phụ thuộc nội dung thực sự đủ quyền phát, có thể ít hơn mục tiêu 30.

Chưa triển khai trong v1: nguồn chart bên ngoài chưa được cấu hình, ranker LambdaMART, session Transformer, contextual bandit, service phục vụ ANN và distributed streaming. Các bước này cần dữ liệu/quy mô hoặc nguồn truy cập phù hợp; không được mô tả như tính năng đã chạy. Retention model artifact/version và unlearning giữa các user cần hoàn thiện trước khi vận hành production ở quy mô lớn; xóa lịch sử hiện vô hiệu hóa projection riêng đang phục vụ và rebuild mix, không huấn luyện lại toàn bộ mô hình cộng đồng ngay lập tức.
