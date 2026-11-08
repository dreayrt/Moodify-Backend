# Kế hoạch v4.1: Karaoke tối đa 6 người/phòng với mediasoup

**Trạng thái:** Người dùng đã yêu cầu triển khai. Phần code cốt lõi đã được thêm vào local checkout; migration MySQL chưa chạy và GCP chưa bị thay đổi.  
**Cập nhật:** 30/10/2026. Chọn mediasoup tự host trên Google Cloud; chỉ chủ phòng điều khiển playback; giới hạn tối đa 6 người/phòng theo yêu cầu mới.  
**Phạm vi:** Beat + lời đồng bộ do chủ phòng điều khiển, micro trò chuyện cho mọi thành viên trong từng phòng hiện có.  
**Giả định để ước lượng:** Một lập trình viên quen codebase; một Spring Boot instance và một media service; tối đa 6 người/phòng, tính cả chủ phòng và người chỉ nghe. VM hiện có: Compute Engine e2-standard-2, 2 vCPU, 8 GB RAM, Ubuntu 24.04, disk 70 GB, zone asia-southeast1-b. Chưa đo tài nguyên còn trống hoặc xác nhận public IP/domain/firewall.

## 0. Trạng thái triển khai hiện tại

- Backend có endpoint playback theo `expectedVersion`, khóa row phòng, tự chuyển bài theo scheduler, chuyển host khi host rời hoặc quá thời hạn reconnect, cấp voice ticket một lần và kiểm tra membership bằng lease 15 giây.
- Frontend có player instrumental và lời LRC đồng bộ theo snapshot server, nút điều khiển chỉ hiện cho host, nút âm thanh để mở autoplay, voice qua mediasoup-client, mic mặc định tắt và trạng thái đang nói.
- Media service nằm trong `supporting-services/karaoke-media/`, dùng Node.js 22, mediasoup 3.32.0, WebSocket signaling, giới hạn 6 peer/phòng, một producer audio/người và coturn có TURN credential ngắn hạn.
- Migration playback: `database/add_karaoke_room_shared_playback.sql`. Migration giới hạn capacity: `database/enforce_karaoke_room_capacity_six.sql`; cần kiểm tra preflight trước khi chạy. Không migration nào đã chạy vào database của người dùng.
- Hướng dẫn môi trường và cổng: `docs/KARAOKE_MEDIASOUP_DEPLOYMENT.md`. Không có SSH, firewall hay thay đổi cấu hình VM GCP nào được áp dụng.
- Ticket dùng HMAC-SHA256 với secret chỉ nằm ở backend/media service. Spring registry tiêu thụ JTI một lần; media renew lease mỗi 5 giây, lease hết hạn sau 15 giây. Đây là triển khai đơn instance; registry chưa chia sẻ giữa nhiều Spring/media replicas.

## 1. Các quyết định đã áp dụng theo yêu cầu

| Nội dung | Quyết định hiện hành |
|---|---|
| Sức chứa | Tối đa 6 tài khoản/phòng, gồm chủ phòng và mọi người nghe/nói; tạo phòng mới với sức chứa 6 |
| Điều khiển nhạc | Chỉ chủ phòng đang có membership hợp lệ được bắt đầu, tạm dừng, tiếp tục và bỏ qua |
| Xử lý thao tác đồng thời | Server quyết định bằng transaction + phiên bản playback; lệnh cũ bị từ chối và không tự chạy lại |
| Thêm bài | Mọi thành viên được đăng ký bài; chỉ chủ phòng bấm Bắt đầu khi phòng đang rảnh |
| Kết thúc bài | Backend tự chuyển bài kế tiếp, chỉ chuyển một lần |
| Micro | Mọi thành viên có thể tự bật/tắt micro; mặc định tắt khi vào phòng |
| Công nghệ truyền tiếng | WebRTC qua mediasoup SFU; Node.js quản lý media; WebSocket Spring Boot đồng bộ playback |
| Hạ tầng thử nghiệm | Self-host media service + coturn trên VM Google Cloud hiện có, sau bước đánh giá tài nguyên và cổng mạng |
| Chủ phòng mất kết nối | Chờ reconnect 30 giây; nếu vẫn vắng thì tạm dừng và chuyển quyền cho thành viên trực tuyến lâu nhất |
| Chủ phòng chủ động rời | Tạm dừng và chuyển quyền ngay nếu còn thành viên; chủ mới chủ động tiếp tục |
| Phạm vi âm thanh | Trò chuyện nhiều người, nghe beat và lời theo trạng thái chung; chất lượng hát từ xa cần đánh giá riêng |

Các quyết định trong bảng là phạm vi đã được người dùng yêu cầu triển khai. Migrations chỉ được chuẩn bị trong repository; chưa kết nối hoặc thay đổi database từ xa. Hạ tầng Google Cloud cũng chưa bị chỉnh sửa.

## 2. Hiện trạng và cách tái sử dụng

Phòng, thành viên và hàng đợi được quản lý trong MySQL bằng `karaoke_rooms`, `karaoke_room_members`, `karaoke_room_queue`. Backend đã thêm trạng thái playback chung; WebSocket thông báo để frontend đồng bộ lại snapshot.

Catalog Karaoke và trang hát cá nhân cung cấp nguồn instrumental, lời và logic audio/lyric được tái sử dụng. Track phải có instrumental asset hợp lệ để phát beat; lời chỉ đồng bộ theo timestamp khi nguồn cung cấp lyric LRC.

Bản v4.1 dùng mediasoup thay LiveKit, giới hạn 6 người/phòng; giữ micro cho mọi thành viên và quyền playback ở chủ phòng. Tài liệu `KARAOKE_IMPLEMENTATION_PLAN.md` cũ mô tả phòng main và hát theo lượt; luật phòng, kiến trúc và ước lượng trong tài liệu này là cơ sở cho task hiện tại.

## 3. Hành vi người dùng

- Thành viên thêm bài vào cuối hàng đợi. Khi rảnh, chỉ chủ phòng thấy nút **Bắt đầu** có thể thao tác.
- Chủ phòng thấy **Tạm dừng**, **Tiếp tục** theo trạng thái và **Bỏ qua**. Thành viên thường xem trạng thái “Chủ phòng đang điều khiển”; không có nút tác động playback chung.
- Dùng lệnh cụ thể, tránh API toggle. Lệnh chỉ có hiệu lực khi backend xác nhận; nếu trạng thái đã đổi, giao diện cập nhật và báo ngắn “Trạng thái phòng vừa thay đổi”.
- Bài đang phát không được rút như bài chờ; chủ phòng dùng Bỏ qua. Quyền rút bài chờ giữ theo requester hiện có.
- Một thành viên có thể bật mic để trò chuyện ngay cả khi không đăng ký bài. Không phải chờ tới lượt hát.
- Khi vào phòng: mic tắt; hiện có nút bật/tắt mic và bật âm thanh sân khấu. Chọn thiết bị, chỉnh âm lượng riêng và local mute từng thành viên chưa được làm.
- Có nút bật âm thanh nếu trình duyệt chặn tự phát. Tự bật mic và cho phép phát âm thanh là hai thao tác/quyền khác nhau.
- Nhiều người có thể nói cùng lúc. Giao diện hiển thị ai đang nói và trạng thái mic.
- Pause nhạc không tắt mic; tắt mic không thay đổi nhạc chung.
- Rời phòng/đóng phòng phải dừng và giải phóng track micro. Mất kết nối hiển thị trạng thái đang kết nối lại; không tự bật mic lần đầu.
- Media hiện từ chối phiên voice thứ hai cùng tài khoản/phòng; chưa có luồng thay thế phiên cũ hoặc thông báo hướng dẫn chuyển tab/thiết bị.
- Khi phòng không còn thành viên trực tuyến sau thời gian chờ reconnect, backend tạm dừng playback. Không dựa riêng vào sự kiện đóng tab hoặc membership chưa được đánh dấu rời.

### 3.1. Ma trận quyền

| Thao tác | Chủ phòng | Thành viên thường | Backend |
|---|---|---|---|
| Bắt đầu / tạm dừng / tiếp tục / bỏ qua | Có | Không, gọi API trực tiếp trả 403 | Kiểm tra quyền và cập nhật state |
| Tự chuyển khi hết bài | Không cần bấm | Không cần bấm | Scheduler thực hiện theo luật phòng |
| Đăng ký bài / rút bài chờ của mình | Có | Có | Giữ luật queue hiện tại |
| Tự bật/tắt mic, chọn thiết bị | Có | Có | Cấp quyền voice theo membership |
| Chỉnh âm lượng nghe, bật âm thanh, local mute | Có, chỉ trên thiết bị mình | Có, chỉ trên thiết bị mình | Không thay playback chung |
| Đóng phòng | Có theo quyền hiện có | Không | Ngắt playback và voice |

Quyền được kiểm tra ở backend bằng `host_user_id` hiện tại, không tin role do frontend hoặc token media khai báo. Một người điều khiển vẫn có thể double-click, mở hai tab hoặc gặp response timeout; version và transaction vẫn cần thiết.

### 3.2. Vòng đời chủ phòng và chuyển quyền

- Phân biệt **chủ động rời** với **mất kết nối tạm thời**. Dùng heartbeat/TTL và grace period, không kết luận rời phòng từ một socket bị đóng; một tài khoản còn tab hợp lệ thì vẫn hiện diện.
- Chủ phòng chủ động rời: trong transaction khóa phòng, lưu vị trí và pause, chọn người kế nhiệm, cập nhật `host_user_id` và tăng `playback_version`. Chọn thành viên còn membership và presence hợp lệ, theo `joined_at` cũ nhất, hòa thì theo `user_id`.
- Chủ phòng mất kết nối: sau khi phát hiện mất presence, giữ quyền trong 30 giây để reconnect. Bài hiện tại có thể tiếp tục trong khoảng chờ; nếu hết bài trước khi chủ quay lại, không tự bắt đầu bài kế tiếp khi chưa có chủ trực tuyến.
- Hết khoảng chờ mà chủ vẫn vắng: backend tạm dừng, chuyển quyền và thông báo cho phòng. Chủ mới có thể bấm Tiếp tục hoặc Bắt đầu nếu bài trước đã kết thúc; không tự phát ngay khi nhận quyền.
- Chủ cũ quay lại sau khi chuyển quyền là thành viên thường. Lệnh đang bay từ chủ cũ phải bị từ chối; không tự lấy lại quyền.
- Không còn thành viên hợp lệ: pause, chưa chọn chủ mới, giữ `host_user_id` hiện tại. Khi có người gia nhập trở lại, backend đánh giá lại tình trạng vắng chủ theo cùng khoảng chờ/quy tắc kế nhiệm; client không tự nhận vai trò chủ.
- Trong cùng transaction, thay chủ luôn tăng playback version kể cả nhạc đang pause, để vô hiệu hóa lệnh từ phiên chủ cũ. Timer chuyển quyền phải đối chiếu host hiện tại và presence generation/last-seen để không xử lý nhầm sau reconnect.
- Đóng phòng kết thúc playback/voice và không chạy nhánh chuyển quyền. Tự động tiếp tục bài kế tiếp chỉ hoạt động khi phòng còn mở, đang trong chuỗi phát và có chủ trực tuyến; nếu chủ vắng thì dừng ở ranh giới bài, giữ queue cho chủ xử lý.

30 giây là giá trị đề xuất có thể cấu hình và cần đo khi thử mạng thực tế. Không đổi quyền sở hữu chỉ vì API media lỗi nếu session ứng dụng của chủ vẫn khỏe.

### 3.3. Giới hạn 6 người và chống vượt sức chứa

- Tối đa **6 tài khoản**, bao gồm chủ phòng, người chỉ nghe và người bật mic. Tắt mic không trả lại chỗ trong phòng. Frontend hiển thị số người thực tế, ví dụ 5/6 và trạng thái Đã đầy khi đủ chỗ.
- Tạo phòng mới với `max_members = 6`; bỏ các lựa chọn lớn hơn 6. Tận dụng cột `max_members` hiện có, không thêm field sức chứa khác. Phòng cũ có giới hạn nhỏ hơn vẫn không được vượt giới hạn đã lưu.
- Spring Boot khóa bản ghi room trong transaction join, kiểm tra membership của user trước, rồi đếm membership chưa rời (`left_at IS NULL`). Chỉ thêm/kích hoạt membership nếu còn chỗ dưới `min(max_members, 6)`; không chỉ kiểm tra bằng số hiển thị ở frontend hoặc cache presence.
- Join lại của cùng tài khoản đang có membership là idempotent, không chiếm thêm chỗ. Hai tài khoản cùng vào khi đang 5/6 chỉ một người thành công, người còn lại nhận lỗi ROOM_FULL. Chủ phòng cũng chiếm một chỗ; không có suất vượt trần dành riêng cho chủ.
- Mất mạng tạm thời vẫn giữ chỗ trong khoảng grace period. Sau khi backend xác nhận hết hạn và cập nhật membership rời, chỗ mới được trả lại; không cho user mới chiếm chỗ khi người cũ vẫn có quyền hợp lệ.
- Media service kiểm tra ticket/membership và tối đa 6 peer tài khoản trong router; mỗi tài khoản có một voice session. Người chỉ nghe vẫn được tính; mở nhiều tab không tạo thêm peer độc lập để vượt giới hạn.
- Trước migration, rà phòng cũ có `max_members > 6` hoặc số membership đang hoạt động >6. Phòng giới hạn >6 nhưng hiện có <=6 người có thể giảm về 6. Nếu đang >6 người, dừng và báo dữ liệu cần xử lý trước khi áp ràng buộc; không tự đẩy người dùng ra hoặc xóa membership.
- Sau khi dữ liệu phù hợp, cập nhật CHECK hiện có để không chấp nhận sức chứa >6, đồng bộ validation API/UI. Rà constraint thực tế và luật giá trị nhỏ hơn 6 khi viết migration, không chỉ thay script tạo bảng mới.

Giới hạn này áp dụng cho từng phòng. Số phòng chạy cùng lúc trên VM phải được đo riêng, không suy ra từ giới hạn 6 người/phòng.

## 4. Kiến trúc mediasoup đề xuất

```text
Next.js room
  ├── REST/JWT + /ws/karaoke ──> Spring Boot ──> MySQL: phòng, membership, queue, playback
  ├── HTTP audio/lyrics ───────> nguồn asset và metadata MongoDB hiện có
  ├── WSS signaling /media/ws ─> Node.js media service (JavaScript)
  └── WebRTC audio ───────────> mediasoup C++ worker / router
                  qua coturn khi cần relay ──┘

Spring Boot <── API nội bộ được xác thực ──> Node.js media service
Node.js quản lý: room/router, peer/session, send/receive transport, producer, consumer
```

Phân trách nhiệm:

| Thành phần | Trách nhiệm |
|---|---|
| Spring Boot | Quyền phòng/mã code, host, queue, playback, clock/snapshot, cấp và xác nhận ticket voice |
| MySQL | Nguồn chuẩn cho phòng, membership, queue và trạng thái phát |
| MongoDB/catalog | Metadata bài hát, URL beat và lời hiện có |
| Media service Node.js | Signaling voice, xác thực phiên, quản lý đối tượng mediasoup, cleanup và reconnect |
| mediasoup worker | SFU chuyển tiếp audio giữa các client; không trộn beat/giọng |
| coturn | STUN/TURN dự phòng cho kết nối khó qua NAT/firewall |
| Next.js | mediasoup-client, audio/micro và UI; playback theo snapshot Spring Boot |

Chọn WebSocket thuần cho signaling media để khớp API WebSocket của trình duyệt. Đây là kết nối riêng với media service; không mặc định dùng Socket.IO hoặc cho rằng Spring WebSocket tự hiểu protocol mediasoup.

Các lệnh bắt đầu/tạm dừng/tiếp tục/bỏ qua chỉ đi tới Spring Boot. Signaling media không được sửa queue/playback. Mỗi máy phát beat cục bộ theo timeline chung; micro đi qua SFU. Giới hạn của mạng không biến chủ phòng thành máy relay cho cả phòng.

Bản đầu một media instance, một worker và một router được tạo lười cho mỗi phòng có voice. Các router không trộn user giữa phòng. Một worker có thể phục vụ nhiều router; worker mediasoup chạy trên một CPU core nên chưa tự bật hai worker trên VM đang chia sẻ 2 vCPU với ứng dụng. Đây là cấu hình thử nghiệm, không phải giới hạn CPU cứng. Tham khảo [mediasoup scalability](https://mediasoup.org/documentation/v3/scalability/).

## 5. Công nghệ, môi trường và phần cần tự xây

| Lớp | Lựa chọn |
|---|---|
| Media server library | mediasoup 3.32.0 (ghim trong package-lock) |
| Media client | mediasoup-client 3.25.3 (ghim trong package-lock) |
| Runtime/service | Node.js 22 trong container Linux; server JS CommonJS để chạy worker mediasoup và khóa dependency bằng package-lock |
| Codec voice | Opus audio; MVP không mở video/screen share |
| Signaling | WebSocket qua WSS, request/response/event có ID và schema kiểm tra |
| Kết nối khó | coturn với credential ngắn hạn, quota và cấu hình relay được thử thực tế |
| Đóng gói | Docker image cho media service; cấu hình Compose media/TURN tách biệt dịch vụ đang chạy |

mediasoup là thư viện media, không cung cấp sẵn bộ quản lý phòng/signaling/xác thực cho Moodify. Cần tự xây protocol, quyền đối tượng, cleanup, speaking indicator và khôi phục phiên. Luồng client/server tham khảo [mediasoup communication](https://mediasoup.org/documentation/v3/communication-between-client-and-server/).

Media service đã được tạo tại `E:\moodify-backend\supporting-services\karaoke-media`, chạy Node.js >=22. Docker image dựng service riêng; cần xác nhận build image trên môi trường Linux trước khi đưa lên VM. Xem [mediasoup installation](https://mediasoup.org/documentation/v3/mediasoup/installation/).

Media service đã được tạo tại `E:\moodify-backend\supporting-services\karaoke-media`, có `server.js`, lockfile, Dockerfile, Compose, TURN và cấu hình mẫu; không tạo repository mới.

Tự host không phát sinh phí thuê LiveKit Cloud. Vẫn có chi phí hạ tầng Google Cloud, băng thông và công vận hành; tính toán ở mục 14.

## 6. Dữ liệu tối thiểu, tránh sao chép field

Migration cộng thêm **5 cột** vào `karaoke_rooms`; không sao chép metadata MongoDB:

| Cột | Vai trò |
|---|---|
| `current_track_id VARCHAR(64) NULL` | ID logic của bài hiện phát trong MongoDB; lượt được lấy ra khỏi queue khi bắt đầu |
| `playback_status VARCHAR(12) NOT NULL DEFAULT 'IDLE'` | Trạng thái rõ ràng: IDLE, PLAYING hoặc PAUSED |
| `playback_position_ms BIGINT NOT NULL DEFAULT 0` | Vị trí đã lưu lúc bắt đầu/tạm dừng |
| `playback_started_at DATETIME(3) NULL` | Mốc UTC của server cho lần phát/tiếp tục hiện tại |
| `playback_version BIGINT NOT NULL DEFAULT 0` | Phiên bản tăng mỗi lần playback thay đổi; chống lệnh và phản hồi cũ |

Cột version dùng để kiểm soát đồng thời; `playback_status` lưu trạng thái trực tiếp; các cột khác chỉ giữ ID bài và vị trí/mốc thời gian cần thiết cho playback.

- MySQL tiếp tục giữ khóa ngoại room/user/queue. `track_id` ở queue giữ vai trò tham chiếu logic tới MongoDB.
- Không lặp lại tên người dùng, tên bài, nghệ sĩ, URL beat, lời hoặc thời lượng trong bảng phòng.
- Presence ứng dụng thuộc Spring Boot; mic/speaking/transport là trạng thái runtime do media service quản lý và phát event. Không ghi liên tục vào database; không dùng presence voice để tự suy ra quyền chủ phòng.
- Dùng ID room/user hiện có để gắn peer. Router/transport/producer/consumer ID, ticket và session generation nằm trong bộ nhớ với TTL; không thêm bảng hoặc collection voice. Sau restart phải dựng lại phiên từ membership hợp lệ.
- Bản đầu không tạo bảng lịch sử lệnh. Log có cấu trúc chứa requestId, actor, version và kết quả đủ cho việc điều tra; nếu cần audit lâu dài sẽ đề xuất riêng.

`current_track_id` không có FK vì bài nằm trong MongoDB. Queue item được xóa trong cùng transaction khi trở thành current; không thêm bảng/field trùng tên bài, nghệ sĩ, URL, lời hoặc thời lượng. Thao tác queue và playback cùng khóa row room trước để serialize cạnh tranh.

## 7. Xử lý bất đồng bộ khi chủ phòng điều khiển

### 7.1. Hợp đồng lệnh

Lệnh gồm `action`, `expectedPlaybackVersion`, `expectedQueueItemId` và `requestId`. Backend lấy user từ JWT, không tin userId do client gửi. Với start, client gửi cả ID bài đầu hàng đợi đang thấy để tránh bắt đầu bài khác ngoài ý định.

Trong một transaction ngắn:

1. Khóa bản ghi room bằng `SELECT ... FOR UPDATE`.
2. Kiểm tra phòng còn mở, membership còn hiệu lực và user hiện tại trùng `host_user_id` sau khi đã khóa; phối hợp cùng luồng chuyển chủ/rời/đóng phòng. Người không có quyền trả 403, chưa thay đổi dữ liệu.
3. So phiên bản và bài hiện tại/đầu queue với điều kiện lệnh.
4. Kiểm tra chuyển trạng thái hợp lệ; tính vị trí từ thời gian server.
5. Cập nhật playback và queue một lần, tăng version; commit.
6. Sau commit mới gửi event kèm version. Không gọi media/catalog network trong khi giữ khóa DB; asset được kiểm tra trước và đối chiếu lại ID khi commit.

Chỉ chủ phòng hợp lệ được gửi lệnh playback. Client nhận `409 Conflict` kèm snapshot mới khi điều kiện cũ, cập nhật giao diện và **không tự phát lại lệnh skip/start**. Khi đã mất quyền chủ, trả 403 và frontend tải lại vai trò; 401 dành cho phiên đăng nhập không còn hợp lệ.

### 7.2. Các tình huống cụ thể

| Tình huống | Kết quả bắt buộc |
|---|---|
| Chủ phòng double-click skip bài A cùng version 10 | Một lệnh chuyển sang B/version 11; lệnh còn lại bị từ chối, B không bị skip |
| Chủ phòng mở hai tab gửi pause và skip cùng version | Lệnh được backend chấp nhận trước thắng; lệnh còn lại phải tải trạng thái mới |
| Thành viên thường tự gọi start/pause/resume/skip | Backend trả 403 dù client tự sửa nút/role; playback không đổi |
| Chủ cũ gửi lệnh đồng thời với chuyển chủ | Nếu chuyển chủ commit trước, lệnh chủ cũ bị từ chối; nếu lệnh chạy trước, chuyển chủ tiếp tục pause từ trạng thái mới nhất |
| Response mất, client retry nguyên lệnh | expectedVersion cũ không được thực thi lần nữa; nhận lại trạng thái để xác nhận |
| Lệnh A tới sau khi bài đã đổi | expectedQueueItemId/version không khớp, không ảnh hưởng bài B |
| Event WebSocket tới sai thứ tự | Bỏ event cũ; tải snapshot mới nhất |
| Fetch/metadata/audio promise cũ hoàn tất trễ | Dùng request sequence/abort và version; không thay audio bằng bài cũ |
| Timer hết bài và chủ phòng skip cùng lúc | Cùng đường transaction và điều kiện phiên bản, chỉ một lần chuyển lượt |

`requestId` dùng để đối chiếu log; **không tự nó đảm bảo idempotency**. Chống thực thi lặp của bản đầu dựa vào điều kiện version + item được cập nhật nguyên tử. Retry phải giữ nguyên điều kiện ban đầu; nếu cần trả chính xác cùng response cũ sau nhiều lần retry thì bổ sung kho receipt là một yêu cầu khác.

Lệnh đặt lại trạng thái đã đúng là no-op; không tăng version không cần thiết. Không hứa thứ tự theo lúc người dùng bấm trên đồng hồ cá nhân; thứ tự hợp lệ là thứ tự server chấp nhận.

### 7.3. Chuyển bài và sự kiện bị mất

- Backend scheduler là nguồn tự chuyển khi hết thời lượng; không phụ thuộc trình duyệt nào còn mở hoặc gửi ended.
- Scheduler là hành động nội bộ của hệ thống, không cấp quyền chuyển bài cho thành viên. Kiểm tra phòng mở và chủ hiện diện trước khi tự bắt đầu bài tiếp theo, theo mục 3.2.
- Nếu bài kết thúc khi chủ vắng, backend hoàn tất/xóa lượt cũ và đưa playback về idle trong cùng transaction, giữ nguyên các bài chờ và tăng version. Chủ quay lại/chủ mới bấm Bắt đầu để tiếp tục; không để mốc started_at cũ khiến client tiếp tục tính vượt thời lượng bài.
- Timer lưu điều kiện item/version và thời điểm kết thúc. Pause/resume/skip làm timer cũ mất hiệu lực; timer mới được tính lại.
- Client có thể báo ended như gợi ý; backend kiểm tra đúng item/version và thời lượng đã trôi qua, không tin việc audio client tự báo kết thúc.
- Backend restart phải khôi phục lịch từ MySQL và tái xác nhận presence của chủ qua reconnect/grace period. Nếu bài đã hết và chủ đang trực tuyến, chuyển một lượt sang bài kế tiếp từ mốc server hiện tại; nếu chưa xác nhận được chủ thì giữ queue, chờ chủ xử lý. Không nhảy cả hàng đợi vì downtime.
- Event sau commit có thể bị mất khi tiến trình lỗi. Bản đầu dùng snapshot khi reconnect/focus và heartbeat đối soát version khoảng 5 giây để tự hồi phục. Nếu cần bảo đảm phân phối event qua nhiều instance, bổ sung transactional outbox + broker như phạm vi hạ tầng tiếp theo.

Khi tiếp tục sau pause/reconnect, giới hạn vị trí theo thời lượng asset hợp lệ. Nếu chưa xác định được thời lượng hoặc asset lỗi, giữ trạng thái chờ/lỗi rõ ràng để chủ quyết định bỏ qua; không tự đoán thời lượng rồi chuyển nhiều bài.

## 8. Đồng bộ beat và lyric

Snapshot trả current item, trạng thái suy ra, position, startedAt, version, `hostUserId`, quyền điều khiển của người gọi và `serverNowEpochMs`. Quyền trong response giúp hiển thị UI; backend vẫn kiểm tra lại cho từng lệnh. Khi đang phát:

`targetPosition = savedPosition + (estimatedServerNow - startedAt)`

Client đo clock offset bằng nhiều mẫu request/response, ưu tiên mẫu RTT thấp và dùng đồng hồ monotonic để tính elapsed giữa các lần hiệu chỉnh. Mốc thời gian server cần đồng bộ khi chạy nhiều instance.

Sau khi tải metadata audio, client tính lại vị trí đích ngay trước lúc seek/play. Lời chạy theo `audio.currentTime` của chính audio đang nghe. Dữ liệu lời thường không được trình bày như lời đã căn thời gian.

Đối chiếu drift khoảng 5 giây, khi focus lại và khi reconnect. Ngưỡng seek ban đầu đề xuất 300–500 ms rồi điều chỉnh qua phép đo; tránh seek liên tục vì gây giật tiếng. Không ghi tiến trình mỗi giây vào MySQL.

Mục tiêu nghiệm thu thử nghiệm: chênh vị trí beat giữa các máy thường dưới 500 ms trên mạng ổn định sau khi đã tải xong. Đây là mục tiêu cần đo, không đảm bảo đồng bộ mẫu âm thanh hoặc song ca cùng nhịp qua Internet.

## 9. Micro, giao diện và giới hạn âm thanh

- Frontend dùng mediasoup-client và xin quyền micro bằng thao tác Bật mic trên HTTPS/localhost. Có xử lý từ chối quyền, không có thiết bị, thiết bị bận và đổi thiết bị.
- Khi vào voice, tạo receive transport để nghe người khác. Chỉ tạo producer khi người dùng bật mic; giới hạn một audio producer/peer. Không tự publish camera hoặc màn hình.
- Tắt mic phải đóng producer và dừng MediaStreamTrack để nhả thiết bị; bật lại tạo producer mới. Đổi mic thay track có cleanup, không phát hai nguồn cùng lúc.
- Client dùng echo cancellation/noise suppression cho trò chuyện và thử chất lượng thực tế; tai nghe giúp giảm beat lọt vào mic. Các bộ lọc cho thoại có thể làm thay đổi chất giọng hát, chưa cam kết chất lượng biểu diễn.
- Mỗi consumer gắn audio người nghe; không phát lại tiếng của chính mình. Local mute và âm lượng voice/beat chỉ có tác dụng trên thiết bị đó.
- Nếu browser chặn autoplay, có nút Bật âm thanh. Quyền mic khác quyền tự phát audio.
- Media service dùng audio level observer/event đã giới hạn tần suất để hiển thị người đang nói; đây là phần phải viết, không phải tính năng UI tự có từ mediasoup.
- Mic mặc định tắt. Khi reconnect kỹ thuật trong cùng phiên, chỉ khôi phục trạng thái bật nếu người dùng trước đó đã bật và track còn hợp lệ; vào lại sau khi rời, phiên bị thay thế hoặc đổi quyền luôn bắt đầu tắt mic.
- Lỗi voice không dừng playback/queue; thông báo trạng thái rõ và cho thử kết nối lại. Không chuyển host chỉ vì SFU mất kết nối.
- Không tự bật mic người khác; bản đầu hỗ trợ tự mute và local mute, chưa thêm quyền ép bật mic.

**Giới hạn hiện tại:** mediasoup chuyển tiếp giọng có độ trễ mạng, trong khi beat phát trên từng máy. Đồng bộ beat/lyric không làm giọng ca sĩ tự khớp beat của khán giả. Mục tiêu là trò chuyện và karaoke cộng đồng cơ bản.

Nếu cần giọng hát và beat khớp như một luồng biểu diễn, phải thử nghiệm pipeline trộn/đồng bộ riêng; mediasoup tự nó không trộn hay căn thời gian giọng với beat. Phần đó không nằm trong ước lượng MVP.

## 10. Xác thực, room riêng và thu hồi quyền voice

### 10.1. Ticket ứng dụng

- Client gọi `POST /api/karaoke/rooms/{roomId}/voice-ticket` bằng JWT Moodify. Spring Boot kiểm tra room mở, active membership và quy trình mã code của phòng riêng.
- Ticket hiện ký HS256 bằng secret dùng chung giữa Spring Boot và media service; chứa `iss`, `aud`, `sub`, `roomId`, `type`, `jti/sid`, `iat`, `exp`; TTL 60 giây. Đây là lựa chọn MVP một instance, không phải ký bất đối xứng; cả hai service phải giữ secret ở server và cấu hình giống nhau.
- Client gửi ticket qua thông điệp authenticate đầu tiên trên WSS, không đặt token ở query URL/log. Giới hạn thời gian socket chưa authenticate.
- Media service kiểm chữ ký/type/expiry và gọi API nội bộ Spring Boot để xác nhận, tiêu thụ ticket một lần rồi tạo lease. Spring giữ registry ticket/lease trong bộ nhớ; media cũng giữ replay marker trong bộ nhớ. Không tái sử dụng ticket đã tiêu thụ; sau Spring restart, người dùng xin ticket mới.
- Spring restart mất registry thì ticket cũ chưa dùng bị từ chối; client phải xin ticket mới. Không dùng timestamp joined_at có độ chính xác giây làm định danh phiên duy nhất.

### 10.2. Lease cho phiên đang kết nối

Token ngắn hạn chỉ kiểm soát bắt tay; hết token không tự ngắt WebRTC đã tồn tại. Media service gia hạn lease quyền mỗi 5 giây; lease hết hạn sau 15 giây nếu không gia hạn được. Đây là TTL kiểm tra quyền, khác grace period host 30 giây.

- Rời/đóng phòng: backend vô hiệu hóa membership; voice session bị thu hồi khi media gọi lần gia hạn kế tiếp hoặc lease hết hạn. Đóng signaling socket sẽ gọi `/release`; lease giới hạn thời gian tồn tại nếu callback bị mất.
- Mỗi lần gia hạn kiểm tra membership/room và session còn hiệu lực. Không gọi MySQL trên từng gói RTP; batch xác nhận lease nếu cần.
- Nếu Spring không truy cập được: từ chối join mới; voice hiện có bị đóng khi lease hết hạn, UI báo lỗi thay vì duy trì quyền vô thời hạn.
- Một room/user hiện chỉ nhận một voice session; server từ chối phiên thứ hai. Cơ chế thay thế phiên cũ theo thứ tự, hiển thị thông báo và kiểm soát callback phiên cũ chưa triển khai.
- API nội bộ Spring ↔ Node chỉ cho mạng nội bộ/loopback và có xác thực service, request timeout, chống replay; không đưa admin credential cho trình duyệt.
- Không chia sẻ kết nối trực tiếp MySQL/MongoDB cho media service; Spring là nơi quyết định quyền.

### 10.3. Quyền đối tượng media

Mỗi request phải kiểm tra đối tượng transport/producer/consumer thuộc session đang gọi và đúng router/room. Không tin roomId/userId do client tự gửi sau authenticate. Consume chỉ cho producer trong cùng phòng, không nhận ID tùy ý từ phòng khác. Hạn chế kind=audio, codec hợp lệ, kích thước và tần suất signaling.

Bảo vệ origin WSS, giới hạn ticket/transport/producer/consumer và cleanup timeout. Các khóa ký, credential service và TURN secret chỉ ở server. Credential TURN ngắn hạn do server cấp sau kiểm tra quyền; TURN không thay thế kiểm tra membership và không mở relay vô danh. Tham khảo [coturn](https://github.com/coturn/coturn).

Mục tiêu thu hồi: đường ngắt chủ động qua WebSocket có hiệu lực ngay; thay đổi membership được thấy ở lần lease tiếp theo; khi mất callback, lease đóng voice trong cửa sổ tối đa 15 giây. Cần đo lại trên môi trường triển khai.

## 11. API và hợp đồng signaling đã triển khai

| Thành phần | Công việc |
|---|---|
| Room snapshot Spring | Trả playback/version, track + lyric/audio metadata, host và membership |
| Playback API Spring | `/playback/start`, `/pause`, `/resume`, `/skip`; host-only và `expectedVersion` |
| WebSocket Spring | Thông báo room/playback thay đổi để clients lấy snapshot mới; không chuyển audio |
| Scheduler/host lifecycle | Tự chuyển bài; xử lý reconnect grace, pause và chuyển host |
| Voice ticket API Spring | Cấp JWT HS256 60 giây sau xác minh room membership |
| API nội bộ | `/consume`, `/lease`, `/release`; service key; single-use ticket và lease 15 giây |
| Signaling Node | Auth, RTP capabilities, transports, audio produce/consume, cleanup và speaking events |
| Media frontend | mediasoup-client, instrumental/lyrics sync, mic mặc định tắt, reconnect và remote audio |
| Vận hành | Docker/Compose, env mẫu, health endpoints và hướng dẫn TLS/firewall/TURN |

Các route được triển khai trong controller `/api/karaoke/rooms/{roomId}` gồm snapshot, lệnh playback và `/voice-ticket`. Scheduler backend là nguồn chuyển bài khi hết thời lượng; trình duyệt không được tự quyết định trạng thái phát.

Signaling Node dùng WebSocket request ID/response cho các thao tác và event riêng cho peer/speaker. Các action được triển khai:

| Action | Mục đích và điều kiện |
|---|---|
| `auth` | Xác minh chữ ký/issuer/audience/expiry, tiêu thụ single-use ticket ở Spring, kiểm tra membership rồi gắn peer vào room |
| `get-rtp-capabilities` | Trả router capabilities và ICE/TURN servers |
| `create-transport` / `connect-transport` | Tạo send/receive WebRTC transport và kết nối DTLS |
| `produce` / `close-producer` | Tạo hoặc đóng một audio producer cho peer |
| `consume` / `resume-consumer` / `close-consumer` | Tạo consumer paused, resume sau khi client dựng track, hoặc dọn ngay khi khởi tạo phía client lỗi |
| `heartbeat` / `leave` | Kiểm tra signaling và giải phóng voice session |

Luồng chuẩn: tải device capabilities → tạo transport nhận/gửi → connect DTLS khi cần → produce mic → server tạo consumer paused → client consume xong báo ready → server resume. Tham khảo [hướng dẫn client/server](https://mediasoup.org/documentation/v3/communication-between-client-and-server/).

Các event producer-added/closed, peer-left, voice-state và speaker-level do ứng dụng phát; phải tự thông báo khi chủ động đóng tài nguyên. Client dùng snapshot để vá event bị mất.

## 12. Trạng thái các bước và đầu ra

| Hạng mục | Trạng thái |
|---|---|
| Rà code hiện có, thêm playback state và API backend | Đã thêm; Maven compile thành công ngày 30/10/2026 |
| MySQL playback migration | Script cộng thêm 5 cột đã chuẩn bị; chưa chạy trên database |
| Capacity tối đa 6 | Backend/UI giới hạn 6; migration preflight và cập nhật constraint đã chuẩn bị, chưa áp dụng |
| Playback UI | Beat/lời dùng clock server hiệu chỉnh theo RTT ước lượng, lấy snapshot làm mốc và hiệu chỉnh drift khi lệch >400 ms; chưa kiểm trên mạng thật |
| mediasoup service + voice client | Luồng mic/remote audio/speaking/reconnect đã viết; chưa build hoặc chạy media service trên Linux, chưa kiểm tra WebRTC thật |
| Điều khiển chủ phòng | Khi chủ rời/vắng quá grace, playback pause; chuyển cho thành viên còn session realtime sớm nhất. Khi không có chủ hợp lệ, thành viên tiếp theo join có thể phục hồi quyền |
| Grace period | Cấu hình mặc định 30 giây từ lúc backend ghi nhận mất WebSocket cuối cùng; mất kết nối im lặng còn cộng heartbeat timeout 30 giây và lịch sweep tối đa 15 giây trước khi bắt đầu grace |
| Voice UX | Chọn mic/loa, đổi mic đang bật bằng replaceTrack, local mute/âm lượng riêng cho từng giọng remote; voice session trùng vẫn bị từ chối, chưa có UX thay thế phiên |
| Voice race/security hardening | Đã tuần tự hóa request theo peer; cache idempotency theo request ID/payload 60 giây; giới hạn 2 transport, 1 producer và tối đa 5 consumer mỗi peer; giới hạn 60 request/10 giây, auth timeout và Origin allowlist |
| TURN cho mạng bị hạn chế | Mẫu hiện chỉ bật coturn UDP/TCP 3478, chưa cấu hình TURN/TLS trên 5349 hoặc relay qua 443; chưa xác nhận khả năng kết nối từ mạng doanh nghiệp |
| Quan sát vận hành | Có health endpoint và Prometheus `/metrics` nội bộ cho rooms/peers/transports/producers/consumers, auth lỗi và rate-limit; RTT/loss/băng thông thật và cảnh báo còn cần triển khai/kiểm chứng |
| Triển khai và chạy thử trên VM | Chưa làm: cần domain/WSS, public IP, secrets, TURN/firewall và thử 2–6 thiết bị/mạng khác nhau |

Đầu ra trong checkout gồm Java/Next.js, `supporting-services/karaoke-media/`, package lock, hai migration và hướng dẫn deploy. Đây là code sẵn để rà soát; không đồng nghĩa đã cập nhật schema thật hay public service.

Mở nhiều Spring/media instance là giai đoạn sau: cần đồng bộ presence/event và định tuyến room tới media instance, thu hồi lease xuyên instance. Không mặc định thêm Redis/Kafka ở MVP một instance.

## 13. Nghiệm thu còn lại trước khi bật dùng thật

1. Chủ phòng double-click hoặc hai tab cùng start/skip cùng version: chỉ một thay đổi, không mất thêm bài.
2. Pause/resume/skip race với ended, rút queue, leave, chuyển chủ và close: state/queue vẫn hợp lệ.
3. Retry sau timeout không chạy lệnh lần hai; client không tự lấy version mới rồi gửi lại skip cũ.
4. Event, fetch và audio metadata đến đảo thứ tự không kéo phòng về bài/trạng thái cũ.
5. Mất event, reconnect và restart backend đều hồi phục đúng current/version; phòng rỗng tạm dừng sau grace period.
6. Chỉ chủ hiện tại điều khiển playback. Thành viên thường gọi trực tiếp cả bốn API đều bị từ chối; vẫn được đăng ký bài và xin voice token khi có membership hợp lệ. Người ngoài/rời phòng không có quyền này.
7. Tăng 2 → 4 → 6 người/phòng trên thiết bị/mạng khác nhau: đo drift beat, RTT/jitter/loss, CPU/RAM host và worker, băng thông và lỗi audio. Kiểm tra người thứ 7, join đồng thời ở 5/6, reconnect giữ chỗ, nhiều tab và nhiều phòng cùng lúc.
8. Mic mặc định tắt; bật/tắt/chọn thiết bị và local mute đúng; rời phòng giải phóng mic.
9. Thử Wi-Fi, mạng di động, UDP trực tiếp, ICE/TCP và TURN fallback cưỡng bức; xác nhận audio hai chiều thực sự truyền được, không chỉ socket kết nối.
10. Phòng riêng: ticket sai phòng/hết hạn/replay, ID transport/producer phòng khác, leave/close khi mất callback và lease hết hạn; nhiều tab không giữ được hai voice session.
11. HTTPS, autoplay bị chặn, quyền mic bị từ chối, trình duyệt background và khóa màn hình được thử; trạng thái hiển thị đúng khi OS tạm ngưng media.
12. Có số đo chất lượng thật để quyết định mở tính năng; tách rõ kết quả trò chuyện và chất lượng giọng hát so với beat.
13. Chủ mất kết nối rồi quay lại trong grace period giữ quyền; timer cũ không chuyển chủ nhầm. Một tab đóng nhưng tab khác còn hoạt động không làm mất quyền.
14. Chủ rời hẳn/vắng quá hạn: pause và chuyển đúng một lần; chủ mới nhận nút điều khiển, chủ cũ trở lại không tự lấy quyền. Lệnh cũ không tác động bài mới.
15. Hết bài lúc chủ vắng không khởi động thêm bài; phòng rỗng/đóng phòng không tự phát. Mất voice riêng không khiến backend chuyển chủ khi session ứng dụng vẫn hoạt động.

## 14. Chi phí và lựa chọn hạ tầng

Dùng mediasoup/coturn tự host, không mua LiveKit Cloud. Chi phí vận hành gồm VM, ổ đĩa/log, IP công khai, băng thông xuống người dùng, TURN và domain nếu chưa có. Nếu giữ nguyên cấu hình VM thì việc cài service không tự tăng đơn giá VM, nhưng lưu lượng hoặc nhu cầu nâng máy vẫn có thể tăng hóa đơn. Tham khảo [Google Cloud network pricing](https://cloud.google.com/vpc/network-pricing).

Cần đo lưu lượng voice riêng với beat/catalog để không quy toàn bộ phí mạng cho mediasoup. Một mic có nhiều người nghe tạo nhiều consumer và nhiều luồng xuống; TURN cũng tiêu thụ băng thông. Với 6 người cùng bật mic, mỗi người nghe 5 người khác: tối đa 6 audio producer và 30 audio consumer cho một phòng, không tính nghe lại tiếng mình. Đây là phép đếm tải, không phải kết quả benchmark; nhiều phòng làm tăng tổng tải.

Không dùng hạn mức participant-minutes của LiveKit Cloud để tính giá mediasoup tự host. Ước tính ngân sách sau phép đo bitrate/lưu lượng và biết mức sử dụng, không gán giá tháng từ ảnh cấu hình.

## 15. Xử lý bất đồng bộ riêng của voice: bảo vệ trong mã nguồn

Quyền playback dùng khóa row và `playback_version`. Signaling voice được tuần tự hóa theo từng peer; mỗi peer có cache request ID, hash payload, TTL và giới hạn kích thước. Các thao tác tạo transport/producer/consumer kiểm tra session còn mở sau các điểm `await`, rồi đóng tài nguyên vừa tạo nếu peer đã rời. Đây là bảo vệ trong một tiến trình; cần chạy thử trên Linux/browser để xác nhận hành vi mediasoup thực tế.

| Tình huống | Cách xử lý đề xuất |
|---|---|
| Hai user join phòng chưa có router cùng lúc | Đã có promise khởi tạo theo room; chỉ lưu một router thành công |
| Double-click bật mic / retry produce | Đã tuần tự hóa theo peer; tối đa một producer/peer; request ID trùng trả lại response cache |
| Ack tạo transport bị mất | Retry cùng request ID/payload trong 60 giây nhận response cũ; tối đa 2 transport/peer |
| Reconnect xong callback cũ tới | Client gắn generation cho mỗi kết nối; callback cũ không gửi request/cập nhật UI của session mới. Cần thử race thực tế |
| Consume trùng / producer đóng khi consume đang chạy | Đã dedupe theo producer ID/peer; tối đa 5 consumer/peer; listener xóa map khi producer/transport đóng |
| User rời khi xác thực/tạo transport đang chờ | Ticket được kiểm tra lại với Spring; sau await kiểm tra socket/session và đóng transport/media object vừa tạo nếu peer đã rời |
| Leave/close callback từ phiên cũ | Lease/release gắn `sessionId`; backend chỉ thu hồi đúng session. User session thứ hai bị từ chối |
| Worker chết / Node restart | Process thoát để supervisor restart; client reconnect xin ticket mới; playback MySQL không bị reset. Chưa xác nhận trên VM |
| Spring restart/mất kết nối nội bộ | Media lease thất bại thì đóng voice khi hết lease; Spring không truy cập được thì từ chối join mới. Cần fault-injection khi nghiệm thu |

Mọi tài nguyên có owner, quota và timeout; request xử lý tuần tự theo peer. Cache request ID chỉ trong runtime, giới hạn TTL 60 giây/kích thước 128 entry và hash payload; cùng ID với payload khác bị từ chối. Cache không tồn tại sau restart. Khi restart, client xin ticket mới thay vì retry mù một ID mediasoup đã mất.

Khi signaling socket đóng, đóng tài nguyên media phiên đó để tránh microphone tiếp tục truyền ngầm; UI có thể kết nối lại và tạo lại phiên. Grace period 30 giây dành cho vai trò chủ phòng không đồng nghĩa giữ transport voice mồ côi trong 30 giây.

Router rỗng được đóng khi peer cuối cùng rời. Có health/readiness và endpoint Prometheus nội bộ cho số room/peer/transport/producer/consumer cùng lỗi auth/rate-limit. Khi media service phục hồi, không thay current song/playback trong MySQL. Speaking/mic state được lấy lại từ snapshot phiên mới. RTT/loss/băng thông WebRTC và cảnh báo vẫn cần instrument/monitor trên môi trường chạy thật.

## 16. Kế hoạch triển khai trên VM Google Cloud hiện có

### 16.1. Khảo sát trước khi thay cấu hình

Thông tin từ ảnh: e2-standard-2, 2 vCPU, 8 GB RAM, Ubuntu 24.04 x86_64, disk balanced 70 GB, zone asia-southeast1-b. Chưa xác định tải ứng dụng thực tế, dung lượng trống, external IP, domain, rule hiệu lực, proxy và port đang dùng.

Khi được phép triển khai: kiểm tra CPU/RAM/disk/process/container, cổng lắng nghe và firewall GCP + firewall OS. Các chỉ báo HTTP/HTTPS Off trên ảnh không thay thế kiểm tra rule thực tế. Không quyết định nâng VM hoặc mua IP chỉ từ cấu hình danh nghĩa.

### 16.2. Network đang được cấu hình trong mẫu triển khai

Các cổng dưới là cấu hình mẫu trong repository; phải rà cổng thật và firewall trước khi áp dụng:

| Đường kết nối | Cấu hình dự kiến |
|---|---|
| HTTPS/WSS | 443 qua reverse proxy hiện có; route media signaling tới Node trên loopback/private port |
| Node signaling | Ví dụ 3001 nội bộ, không mở thẳng ra Internet |
| API quản trị/lease | Chỉ loopback/private network và service authentication; không public |
| mediasoup WebRTC | UDP/TCP 40000–49999; bind `0.0.0.0`, công bố `MEDIASOUP_ANNOUNCED_IP` là external IP thật |
| coturn | UDP/TCP 3478, bật theo compose profile `turn` |
| TURN relay | UDP 49160–49200 trong compose mẫu |

WebRtcServer cho phép nhiều transport dùng chung cổng mỗi worker; tăng worker cần cổng riêng và kiểm tra firewall. Chỉ reverse proxy HTTP/WSS là chưa đủ để chuyển gói media; mở đúng cổng UDP/TCP. Các lựa chọn API dựa trên [mediasoup WebRtcServer/TransportListenInfo](https://mediasoup.org/documentation/v3/mediasoup/api/).

Trong GCP, bind địa chỉ có trên VM và công bố external IP đúng; không công bố IP nội bộ 10.x cho client Internet. Nếu external IP có thể thay đổi, cần phương án ổn định trước khi chạy lâu dài; rà chi phí trước khi đặt thêm tài nguyên.

TURN/TLS 5349 không đảm bảo vượt được mạng chỉ cho outbound 443. Nếu yêu cầu hỗ trợ loại mạng đó, cần đánh giá endpoint TURN 443 với IP riêng hoặc proxy TCP phù hợp và thử thật; không giành cổng 443 của web hiện tại. TURN có quota/credential ngắn hạn và chính sách relay giới hạn đúng nhu cầu SFU.

### 16.3. Cách đưa lên và hoàn tác

- Dựng image mediasoup worker trên môi trường build, ghim phiên bản; không compile nặng trên VM phục vụ app.
- Chọn Docker networking và ánh xạ cổng khớp bind/announcedAddress; ưu tiên thiết kế đơn giản trên Linux, rà port trước khi dùng host networking.
- Thêm service media và coturn với giới hạn tài nguyên/log, healthcheck và restart policy. Không thay Compose đang có bằng cấu hình mẫu mới thiếu các dịch vụ cũ.
- Tạo route WSS/TLS, kiểm tra direct media rồi TURN; sau đó mới bật feature flag cho nhóm thử nghiệm.
- Mở tải tăng dần; đo worker/Node và tác động lên Spring/database. Nếu không đạt, duyệt giảm sức chứa hoặc tách/nâng VM trước khi mở rộng.
- Rollback bằng tắt feature voice, dừng service media mới và hoàn tác route/rule vừa thêm. Playback có thể rollback bằng feature flag; migration cộng thêm giữ nguyên cho tới khi xác nhận ứng dụng cũ hoạt động, không xóa dữ liệu phòng/queue.
- Có log requestId/sessionId/roomId đã che token, metric số peer/transport/producer/consumer, lease failures, worker died, RTT/loss và băng thông. Không ghi âm trong MVP.

## 17. Nghiệm thu mediasoup bổ sung và việc còn lại

Ngoài mục 13 cần thử:

1. Ticket dùng lại/sai issuer/audience/room, consume xuyên phòng và can thiệp tài nguyên peer khác đều bị từ chối.
2. Mỗi user chỉ một voice session trong room; phiên thay thế không bị callback cũ đóng nhầm.
3. Mất callback ngắt voice thì lease thu hồi trong cửa sổ đề xuất 15 giây; Spring lỗi không cấp quyền mới.
4. Tắt mic dừng track/producer; rời phòng không còn audio truyền; không có producer/consumer bị rò sau nhiều vòng join/leave.
5. Thử restart Node, worker và Spring độc lập; UI hồi phục, playback không bị reset, ticket/ID cũ không sống lại.
6. Audio thật đi qua direct UDP và relay cưỡng bức; thống kê candidate/ICE xác nhận đường dùng, thử trên hai mạng khác nhau.
7. Snapshot producer và event đảo thứ tự không làm mất người nghe; consumer được resume sau client ready.
8. Tổng tải trên VM và chất lượng đo đạt mục tiêu 6 người/phòng; user thứ 7 không vào được qua API hoặc signaling. Báo riêng số phòng đồng thời đã kiểm chứng.

**Phạm vi đã triển khai trong mã nguồn:** Tối đa 6 người/phòng; mediasoup + media service Node.js/JavaScript; Spring Boot quản lý quyền và playback; chỉ chủ phòng bắt đầu/tạm dừng/tiếp tục/bỏ qua; mọi thành viên có thể bật mic và mic mặc định tắt. Ticket 60 giây, lease voice 15 giây. Host grace period 30 giây.

**Còn lại trước khi bật trên môi trường dùng thật:** rà schema thực tế và chạy migration theo hướng dẫn; cấu hình secrets, WSS/TLS, public IP/announced address và TURN; mở đúng firewall; build/deploy service trên Linux; rồi thử mạng thật, reconnect, giới hạn 6 người và tải nhiều phòng. Chưa SSH vào máy chủ, chạy migrations từ xa, mở firewall hay tạo tài nguyên GCP. Build/lint/compile kiểm tra mã không thay thế cho nghiệm thu browser/WebRTC thực tế.
