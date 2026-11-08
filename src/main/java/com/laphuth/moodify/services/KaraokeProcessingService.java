package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Durable, single-worker CPU preparation queue. Published assets remain compatible with KaraokeController. */
@Service
public class KaraokeProcessingService {
    public static final String JOBS = "karaoke_processing_jobs", VERSIONS = "karaoke_asset_versions", ASSETS = "karaoke_assets";
    private static final Map<String, String> FILES = Map.of("instrumental", "instrumental.wav", "vocals", "vocals.wav", "pitchReference", "pitch_reference.json");
    private static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;
    private final MongoTemplate mongo;
    private final Path root, catalog, uploads;
    private final ObjectMapper json = new ObjectMapper();
    private boolean indexed;

    public KaraokeProcessingService(MongoTemplate mongo,
            @Value("${karaoke.processing.root:/srv/moodify/karaoke}") String root,
            @Value("${karaoke.processing.catalog:/opt/music-data-collector/data/audio}") String catalog,
            @Value("${karaoke.processing.uploads:/app/uploads}") String uploads) {
        this.mongo = mongo; this.root = Path.of(root).toAbsolutePath().normalize();
        this.catalog = Path.of(catalog).toAbsolutePath().normalize(); this.uploads = Path.of(uploads).toAbsolutePath().normalize();
    }

    private synchronized void indexes() {
        if (indexed) return;
        mongo.getCollection(JOBS).createIndex(new Document("activeSong", 1), new IndexOptions().unique(true).sparse(true));
        mongo.getCollection(JOBS).createIndex(new Document("kind", 1).append("status", 1).append("createdAt", 1));
        mongo.getCollection(VERSIONS).createIndex(new Document("songId", 1).append("assetVersion", 1), new IndexOptions().unique(true));
        indexed = true;
    }

    public static String id(String value) {
        if (value == null || !value.matches("[a-f0-9]{24}")) throw error(400, "Mã tài nguyên không hợp lệ.");
        return value;
    }
    private static ResponseStatusException error(int status, String message) { return new ResponseStatusException(HttpStatus.valueOf(status), message); }
    private static Date now() { return new Date(); }
    private Document track(String songId) {
        Document t = mongo.findById(new ObjectId(id(songId)), Document.class, "tracks");
        if (t == null) throw error(404, "Không tìm thấy bài hát.");
        return t;
    }
    private Document job(String jobId) {
        Document j = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(id(jobId))).and("kind").is("job")), Document.class, JOBS);
        if (j == null) throw error(404, "Không tìm thấy tác vụ.");
        return j;
    }
    public static Path confined(Path base, Path candidate) {
        try {
            Path realBase = base.toRealPath(), real = candidate.toRealPath();
            if (!real.startsWith(realBase) || !Files.isRegularFile(real)) throw error(400, "Đường dẫn nguồn không hợp lệ.");
            return real;
        } catch (java.io.IOException e) { throw error(404, "File nguồn không tồn tại trên máy chủ."); }
    }
    private Path original(Document t) {
        String raw = t.getString("local_path");
        if (raw == null || raw.isBlank()) throw error(404, "Bài hát chưa có file nhạc nguồn.");
        try {
            String path = raw.startsWith("http://") || raw.startsWith("https://") ? URI.create(raw).getPath() : raw;
            path = path.replace('\\', '/').replaceAll("^/+", "");
            if (Arrays.asList(path.split("/")).contains("..")) throw error(400, "Đường dẫn nguồn không hợp lệ.");
            if (path.startsWith("data/audio/")) return confined(catalog, catalog.resolve(path.substring(11)));
            if (path.startsWith("uploads/")) return confined(uploads, uploads.resolve(path.substring(8)));
        } catch (IllegalArgumentException e) { throw error(400, "Đường dẫn nguồn không hợp lệ."); }
        throw error(404, "Chưa hỗ trợ vị trí file nguồn của bài hát này.");
    }
    private Path source(Document j) {
        Path path = Path.of(j.getString("sourcePath"));
        return "upload".equals(j.getString("sourceType")) ? confined(root.resolve(".sources"), path) : original(track(j.getString("songId")));
    }
    public static String hash(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[1024 * 1024]; int n;
            while ((n = input.read(block)) != -1) digest.update(block, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw error(422, "Không đọc được file để kiểm tra checksum."); }
    }
    private boolean sourceExists(Document t) { try { original(t); return true; } catch (ResponseStatusException e) { return false; } }
    private static Document safe(Document d) {
        Document out = new Document(d);
        out.remove("sourcePath"); out.remove("leaseToken"); out.remove("activeSong"); out.remove("kind");
        out.put("id", String.valueOf(out.remove("_id"))); return out;
    }

    public Map<String, Object> resources(int page, int size, String query, String status) {
        validatePage(page, size);
        Map<String, Document> published = new HashMap<>(), latest = new HashMap<>(), pending = new HashMap<>();
        for (Document a : mongo.findAll(Document.class, ASSETS)) published.put(a.getString("songId"), a);
        for (Document j : mongo.find(Query.query(Criteria.where("kind").is("job")).with(Sort.by(Sort.Direction.DESC, "createdAt")), Document.class, JOBS)) {
            latest.putIfAbsent(j.getString("songId"), j);
            if ("completed".equals(j.getString("status")) && !j.containsKey("publishedAt")) pending.putIfAbsent(j.getString("songId"), j);
        }
        Query tq = new Query();
        if (query != null && !query.isBlank()) {
            if (query.length() > 100) throw error(400, "Từ khóa quá dài.");
            Pattern p = Pattern.compile(Pattern.quote(query.trim()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            tq.addCriteria(new Criteria().orOperator(Criteria.where("name").regex(p), Criteria.where("artist_name").regex(p)));
        }
        tq.with(Sort.by("name"));
        List<Document> rows = new ArrayList<>(); Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : List.of("missing", "running", "review", "ready")) counts.put(key, 0L);
        for (Document t : mongo.find(tq, Document.class, "tracks")) {
            if (!(t.get("_id") instanceof ObjectId oid)) continue;
            String sid = oid.toHexString(); Document a = published.get(sid), j = latest.get(sid), waiting = pending.get(sid);
            String s = j != null && Set.of("queued", "running").contains(j.getString("status")) ? "running"
                : waiting != null && !Objects.equals(a == null ? null : a.getString("assetVersion"), waiting.getString("assetVersion")) ? "review"
                : a != null && "ready".equals(a.getString("status")) ? "ready" : "missing";
            counts.merge(s, 1L, Long::sum);
            if (status != null && !status.isBlank() && !"all".equals(status) && !s.equals(status)) continue;
            rows.add(new Document("songId", sid).append("title", t.getString("name")).append("artist", t.getString("artist_name"))
                .append("imageUrl", t.getString("image_url")).append("durationMs", t.get("duration_ms"))
                .append("sourceAvailable", sourceExists(t)).append("status", s)
                .append("published", a == null ? null : safe(a)).append("latestJob", j == null ? null : safe(j)));
        }
        return page(rows, page, size, Map.of("counts", counts, "worker", workerState()));
    }
    private static void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 50) throw error(400, "Phân trang không hợp lệ."); }
    private static Map<String, Object> page(List<Document> rows, int page, int size, Map<String, Object> extra) {
        int from = (int)Math.min((long)page * size, rows.size());
        Map<String, Object> result = new LinkedHashMap<>(extra); result.put("items", rows.subList(from, Math.min(from + size, rows.size())));
        result.put("total", rows.size()); result.put("page", page); result.put("size", size); return result;
    }
    public Map<String, Object> detail(String songId) {
        Document t = track(songId); Document a = mongo.findOne(Query.query(Criteria.where("songId").is(songId)), Document.class, ASSETS);
        List<Document> versions = mongo.find(Query.query(Criteria.where("songId").is(songId)).with(Sort.by(Sort.Direction.DESC, "createdAt")), Document.class, VERSIONS);
        // Existing local-prepared assets can be reviewed and restored alongside new web versions.
        if (a != null && versions.stream().noneMatch(v -> Objects.equals(v.get("assetVersion"), a.get("assetVersion")))) versions.add(a);
        return Map.of("songId", songId, "title", Objects.toString(t.get("name"), ""), "artist", Objects.toString(t.get("artist_name"), ""),
            "sourceAvailable", sourceExists(t), "versions", versions.stream().map(KaraokeProcessingService::safe).toList(),
            "publishedVersion", a == null ? "" : Objects.toString(a.get("assetVersion"), ""),
            "jobs", mongo.find(Query.query(Criteria.where("kind").is("job").and("songId").is(songId)).with(Sort.by(Sort.Direction.DESC,"createdAt")).limit(10),Document.class,JOBS).stream().map(KaraokeProcessingService::safe).toList());
    }
    public Map<String, Object> jobs(int page, int size, String status) {
        validatePage(page, size); Criteria c = Criteria.where("kind").is("job");
        if ("active".equals(status)) c = c.and("status").in("queued", "running");
        else if ("history".equals(status)) c = c.and("status").nin("queued", "running");
        Query q = Query.query(c).with(Sort.by(Sort.Direction.DESC, "createdAt")); long total = mongo.count(q, JOBS);
        return Map.of("items", mongo.find(q.skip((long)page * size).limit(size), Document.class, JOBS).stream().map(KaraokeProcessingService::safe).toList(), "total", total);
    }
    public Document create(String songId, MultipartFile upload, String operator, Document retry) {
        indexes(); Document t = track(songId); Path input; String type = "catalog", name;
        if (mongo.exists(Query.query(Criteria.where("activeSong").is(songId)), JOBS)) throw error(409, "Bài hát đang có một tác vụ chờ hoặc đang xử lý.");
        if (upload != null && upload.isEmpty()) throw error(400, "File nguồn tải lên đang rỗng.");
        String jid = new ObjectId().toHexString();
        if (upload != null && !upload.isEmpty()) {
            String filename = Objects.toString(upload.getOriginalFilename(), "source.wav");
            String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!Set.of("mp3", "wav", "flac", "m4a", "ogg", "aac").contains(ext) || upload.getSize() > MAX_SOURCE_BYTES) throw error(400, "Chọn file âm thanh hợp lệ, tối đa 128 MiB.");
            try {
                Path dir = root.resolve(".sources"); Files.createDirectories(dir); input = dir.resolve(jid + "." + ext); upload.transferTo(input);
                try { Files.setPosixFilePermissions(input, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")); }
                catch (UnsupportedOperationException ignored) { /* Windows development filesystem. */ }
            }
            catch (Exception e) { throw error(500, "Không lưu được file nguồn."); }
            name = Path.of(filename.replace('\\','/')).getFileName().toString(); type = "upload";
        } else if (retry != null && "upload".equals(retry.get("sourceType"))) {
            input = source(retry); name = retry.getString("sourceName"); type = "upload";
        } else { input = original(t); name = input.getFileName().toString(); }
        try {
            if (Files.size(input) > MAX_SOURCE_BYTES || Files.size(input) < 1) throw error(400, "File nguồn rỗng hoặc vượt 128 MiB.");
            Document j = new Document("_id", new ObjectId(jid)).append("kind", "job").append("songId", songId).append("activeSong", songId)
                .append("title", t.getString("name")).append("artist", t.getString("artist_name"))
                .append("status", "queued").append("stage", "queued").append("createdAt", now()).append("createdBy", operator)
                .append("assetVersion", new ObjectId().toHexString()).append("sourcePath", input.toString()).append("sourceName", name)
                .append("sourceType", type).append("sourceHash", hash(input)).append("sourceMapping", t.get("local_path"))
                .append("cancelRequested", false).append("updatedAt", now())
                .append("events", List.of(new Document("stage","queued").append("at",now())));
            mongo.insert(j, JOBS); return safe(j);
        } catch (DuplicateKeyException e) {
            if (upload != null && !upload.isEmpty()) try { Files.deleteIfExists(input); } catch (java.io.IOException ignored) { }
            throw error(409, "Bài hát đang có một tác vụ chờ hoặc đang xử lý.");
        }
        catch (java.io.IOException e) { throw error(422, "Không đọc được file nguồn."); }
    }
    public Document retry(String jobId, String operator) {
        Document j = job(jobId);
        if (!Set.of("failed", "cancelled", "interrupted").contains(j.getString("status"))) throw error(409, "Chỉ thử lại tác vụ lỗi, bị hủy hoặc gián đoạn.");
        return create(j.getString("songId"), null, operator, j);
    }
    public void cancel(String jobId, String operator) {
        Document j = job(jobId); String status = j.getString("status");
        if (!Set.of("queued", "running").contains(status)) throw error(409, "Tác vụ đã kết thúc.");
        Update update = new Update().set("cancelRequested", true).set("cancelledBy", operator).set("updatedAt", now());
        if ("queued".equals(status)) update.set("status", "cancelled").set("finishedAt", now()).unset("activeSong").push("events",new Document("stage","cancelled").append("at",now()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(jobId)).and("status").is(status)), update, JOBS);
    }
    public Document workerState() {
        Document state = mongo.findById("__worker__", Document.class, JOBS);
        if (state == null) return new Document("online", false);
        Date beat = state.getDate("heartbeatAt");
        return new Document("online", beat != null && beat.after(Date.from(Instant.now().minusSeconds(45))))
            .append("heartbeatAt", beat).append("capacity", state.get("capacity"));
    }
    public Document claim(String token, Document capacity) {
        indexes(); Date time = now();
        mongo.upsert(Query.query(Criteria.where("_id").is("__worker__")), new Update().setOnInsert("kind", "worker"), JOBS);
        Query lock = Query.query(Criteria.where("_id").is("__worker__").orOperator(Criteria.where("leaseUntil").lt(time), Criteria.where("leaseUntil").exists(false), Criteria.where("leaseToken").is(token)));
        Document lease = mongo.findAndModify(lock, new Update().set("leaseToken", token).set("leaseUntil", Date.from(Instant.now().plusSeconds(120)))
            .set("heartbeatAt", time).set("capacity", capacity), FindAndModifyOptions.options().returnNew(true), Document.class, JOBS);
        if (lease == null) return null;
        mongo.updateMulti(Query.query(Criteria.where("kind").is("job").and("status").is("running").and("heartbeatAt").lt(Date.from(Instant.now().minusSeconds(120)))),
            new Update().set("status", "interrupted").set("error", "Máy xử lý bị gián đoạn. Có thể thử lại.").set("finishedAt", time).unset("activeSong").push("events",new Document("stage","interrupted").append("at",time)), JOBS);
        if (!Boolean.TRUE.equals(capacity.getBoolean("available"))) return null;
        // Never take a second job while another process is still heartbeating.
        if (mongo.exists(Query.query(Criteria.where("kind").is("job").and("status").is("running")), JOBS)) return null;
        Query q = Query.query(Criteria.where("kind").is("job").and("status").is("queued")).with(Sort.by("createdAt"));
        Document j = mongo.findAndModify(q, new Update().set("status", "running").set("stage", "preparing").set("leaseToken", token)
            .set("startedAt", time).set("heartbeatAt", time).set("updatedAt", time).push("events",new Document("stage","preparing").append("at",time)), FindAndModifyOptions.options().returnNew(true), Document.class, JOBS);
        if (j != null) { j.put("id", String.valueOf(j.get("_id"))); j.put("outputPath", root.resolve(j.getString("songId")).resolve(j.getString("assetVersion")).toString()); }
        return j;
    }
    private Document leased(String jobId, String token) {
        Document j = job(jobId);
        if (!"running".equals(j.getString("status")) || !token.equals(j.getString("leaseToken"))) throw error(409, "Tác vụ không thuộc phiên worker hiện tại.");
        Document state = mongo.findById("__worker__", Document.class, JOBS);
        if (state == null || !token.equals(state.getString("leaseToken"))) throw error(409, "Worker đã mất quyền xử lý.");
        return j;
    }
    public Document heartbeat(String jobId, String token, String stage) {
        Document j = leased(jobId, token);
        if (!Set.of("preparing", "separating", "reference", "validating").contains(stage)) stage = "preparing";
        Update beat = new Update().set("heartbeatAt", now()).set("updatedAt", now()).set("stage", stage);
        if (!stage.equals(j.getString("stage"))) beat.push("events",new Document("stage",stage).append("at",now()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(jobId)).and("leaseToken").is(token).and("status").is("running")), beat, JOBS);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("__worker__").and("leaseToken").is(token)), new Update().set("heartbeatAt", now()).set("leaseUntil", Date.from(Instant.now().plusSeconds(120))), JOBS);
        return new Document("cancelRequested", j.getBoolean("cancelRequested", false));
    }
    public void finish(String jobId, String token, String status, String message) {
        leased(jobId, token);
        if (!Set.of("failed", "cancelled").contains(status)) throw error(400, "Trạng thái không hợp lệ.");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(jobId)).and("leaseToken").is(token).and("status").is("running")),
            new Update().set("status", status).set("finishedAt", now()).set("updatedAt", now()).set("error", message == null ? "" : message.substring(0, Math.min(500, message.length()))).unset("activeSong").push("events",new Document("stage",status).append("at",now())), JOBS);
    }
    private Document verified(Document j) {
        Path dir = root.resolve(id(j.getString("songId"))).resolve(id(j.getString("assetVersion")));
        try {
            Document manifest = Document.parse(Files.readString(confined(root, dir.resolve("karaoke_asset_manifest.json"))));
            if (!j.getString("songId").equals(manifest.getString("songId")) || !"prepared_local".equals(manifest.getString("status"))) throw error(422, "Manifest không đúng tác vụ.");
            Document src = manifest.get("source", Document.class), assets = manifest.get("assets", Document.class);
            if (src == null || assets == null || !j.getString("sourceHash").equals(src.getString("sha256")) || !j.getString("sourceHash").equals(hash(source(j)))) throw error(409, "Nguồn âm thanh đã thay đổi.");
            double duration = ((Number)src.get("durationSeconds")).doubleValue();
            if (!Double.isFinite(duration) || duration <= 0 || duration > 1800) throw error(422, "Thời lượng nguồn không hợp lệ.");
            Document files = new Document();
            for (var item : FILES.entrySet()) {
                Document metadata = assets.get(item.getKey(), Document.class);
                if (metadata == null || !item.getValue().equals(metadata.getString("file"))) throw error(422, "Thiếu tài nguyên đầu ra.");
                Path file = confined(root, dir.resolve(item.getValue()));
                if (!hash(file).equals(metadata.getString("sha256")) || Files.size(file) != ((Number)metadata.get("bytes")).longValue()) throw error(422, "Checksum hoặc dung lượng đầu ra không khớp.");
                if (!"pitchReference".equals(item.getKey()) && Math.abs(((Number)metadata.get("durationSeconds")).doubleValue() - duration) > 0.01) throw error(422, "Thời lượng đầu ra không khớp.");
                Document m = new Document(metadata); m.remove("file"); m.put("storageKey", "karaoke/" + j.getString("songId") + "/" + j.getString("assetVersion") + "/" + item.getValue()); files.put(item.getKey(), m);
            }
            Document scoring = manifest.get("scoringReference", Document.class);
            if (scoring == null) throw error(422,"Thiếu thông tin chất lượng tham chiếu.");
            if (duration > 600) {
                scoring.put("status","unsupported_duration");
                List<String> warnings = new ArrayList<>(scoring.getList("warnings",String.class,List.of()));
                warnings.add("Bài dài hơn 10 phút: chưa hỗ trợ chấm điểm."); scoring.put("warnings",warnings);
            }
            return new Document("songId", j.getString("songId")).append("assetVersion", j.getString("assetVersion"))
                .append("status", "draft").append("source", src.append("type", j.getString("sourceType")).append("localPath", j.getString("sourcePath")))
                .append("assets", files).append("processing", manifest.get("processing")).append("scoringReference", scoring)
                .append("storage", new Document("provider", "gcp-vm").append("root", root.toString()).append("baseUrl", "https://moodify.click/"))
                .append("jobId", String.valueOf(j.get("_id"))).append("createdBy", j.getString("createdBy")).append("createdAt", now());
        } catch (ResponseStatusException e) { throw e; } catch (Exception e) { throw error(422, "Bộ tài nguyên đầu ra không hợp lệ hoặc chưa hoàn tất."); }
    }
    public Document complete(String jobId, String token) {
        Document j = leased(jobId, token);
        if (j.getBoolean("cancelRequested", false)) { finish(jobId, token, "cancelled", "Đã hủy theo yêu cầu quản trị viên."); return new Document("status", "cancelled"); }
        Document version = verified(j);
        mongo.upsert(Query.query(Criteria.where("songId").is(j.get("songId")).and("assetVersion").is(j.get("assetVersion"))), Update.fromDocument(new Document("$setOnInsert", version)), VERSIONS);
        // Guard against cancellation racing checksum verification.
        Document done = mongo.findAndModify(Query.query(Criteria.where("_id").is(new ObjectId(jobId)).and("status").is("running").and("leaseToken").is(token).and("cancelRequested").is(false)),
            new Update().set("status", "completed").set("stage", "completed").set("finishedAt", now()).set("updatedAt", now()).unset("activeSong").push("events",new Document("stage","completed").append("at",now())), FindAndModifyOptions.options().returnNew(true), Document.class, JOBS);
        if (done == null) { finish(jobId, token, "cancelled", "Đã hủy theo yêu cầu quản trị viên."); return new Document("status", "cancelled"); }
        return safe(done);
    }
    public Document publish(String songId, String versionId, String operator) {
        track(songId); id(versionId);
        Document v = mongo.findOne(Query.query(Criteria.where("songId").is(songId).and("assetVersion").is(versionId)), Document.class, VERSIONS);
        Document current = mongo.findOne(Query.query(Criteria.where("songId").is(songId)), Document.class, ASSETS);
        if (v == null) throw error(404, "Không tìm thấy phiên bản.");
        if (v.getString("jobId") != null && !"completed".equals(job(v.getString("jobId")).getString("status"))) throw error(409, "Tác vụ chưa hoàn tất.");
        // Revalidate stored bytes even when restoring an old version; catalog mapping may legitimately have changed since creation.
        Document assets = v.get("assets", Document.class);
        for (var item : FILES.entrySet()) {
            Document m = assets.get(item.getKey(), Document.class);
            Path file = confined(root, root.resolve(songId).resolve(versionId).resolve(item.getValue()));
            if (!hash(file).equals(m.getString("sha256"))) throw error(422, "File của phiên bản không còn hợp lệ.");
        }
        if (current != null) {
            Document backup = new Document(current); backup.remove("_id"); backup.putIfAbsent("createdAt", now());
            mongo.upsert(Query.query(Criteria.where("songId").is(songId).and("assetVersion").is(current.get("assetVersion"))), Update.fromDocument(new Document("$setOnInsert", backup)), VERSIONS);
        }
        Document active = new Document(v); active.remove("_id"); active.put("status", "ready"); active.put("publishedAt", now()); active.put("publishedBy", operator); active.put("updatedAt", now());
        // Preserve lyrics only when the version stays the same; a new vocal requires a new alignment.
        if (current != null && Objects.equals(current.get("assetVersion"), versionId)) for (String key : List.of("lyrics", "lyricTimeline")) if (current.containsKey(key)) active.put(key, current.get(key));
        Document update = new Document("$set", active);
        if (current != null && !Objects.equals(current.get("assetVersion"), versionId) && !active.containsKey("lyricTimeline")) update.put("$unset", new Document("lyricTimeline", ""));
        mongo.upsert(Query.query(Criteria.where("songId").is(songId)), Update.fromDocument(update), ASSETS);
        mongo.updateFirst(Query.query(Criteria.where("songId").is(songId).and("assetVersion").is(versionId)), new Update().set("publishedAt", now()).set("publishedBy", operator), VERSIONS);
        if (v.getString("jobId") != null) mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(v.getString("jobId")))),new Update().set("publishedAt",now()).set("publishedBy",operator),JOBS);
        return safe(active);
    }
    public Path sourceFile(String songId, String jobId) {
        if (jobId == null || jobId.isBlank()) return original(track(songId));
        Document j = job(jobId); if (!songId.equals(j.getString("songId"))) throw error(404, "Không tìm thấy nguồn."); return source(j);
    }
}
