package com.laphuth.moodify.services;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class TrackSyncService {

    private static final Logger log = LoggerFactory.getLogger(TrackSyncService.class);

    private final TrackRepository trackRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String oracleBaseUrl;
    private final boolean syncEnabled;

    private Instant lastSyncTime;
    private String lastSyncStatus = "NEVER_RUN";
    private Map<String, Object> lastSyncResult = new HashMap<>();

    public TrackSyncService(
            TrackRepository trackRepository,
            MongoTemplate mongoTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper springObjectMapper,
            @Value("${oracle.audio.base-url:}") String oracleBaseUrl,
            @Value("${sync.scheduler.enabled:true}") boolean syncEnabled
    ) {
        this.trackRepository = trackRepository;
        this.mongoTemplate = mongoTemplate;
        this.oracleBaseUrl = oracleBaseUrl != null ? oracleBaseUrl.trim() : "";
        this.syncEnabled = syncEnabled;

        if (springObjectMapper != null) {
            this.objectMapper = springObjectMapper.copy();
        } else {
            this.objectMapper = new ObjectMapper();
        }

        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                if (node == null || node.isNull()) {
                    return null;
                }
                if (node.isTextual()) {
                    try {
                        return Instant.parse(node.asText());
                    } catch (Exception e) {
                        return null;
                    }
                }
                if (node.isNumber()) {
                    long val = node.asLong();
                    return val > 100_000_000_000L ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
                }
                if (node.isObject() && node.has("$date")) {
                    JsonNode dateNode = node.get("$date");
                    if (dateNode.isTextual()) {
                        try {
                            return Instant.parse(dateNode.asText());
                        } catch (Exception e) {
                            return null;
                        }
                    } else if (dateNode.isNumber()) {
                        long val = dateNode.asLong();
                        return val > 100_000_000_000L ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
                    }
                }
                return null;
            }
        });
        this.objectMapper.registerModule(javaTimeModule);
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private String getNormalizedBaseUrl() {
        if (oracleBaseUrl.isBlank()) {
            return "";
        }
        return oracleBaseUrl.endsWith("/") ? oracleBaseUrl : oracleBaseUrl + "/";
    }

    /**
     * Đồng bộ hai chiều (Two-Way Synchronization):
     * 1. Kéo bài mới/cập nhật từ Oracle về Local (Oracle -> Local)
     * 2. Đẩy bài mới/cập nhật từ Local lên Oracle (Local -> Oracle)
     */
    public synchronized Map<String, Object> syncTwoWay() {
        Map<String, Object> report = new LinkedHashMap<>();
        Instant start = Instant.now();
        report.put("startTime", start.toString());

        String baseUrl = getNormalizedBaseUrl();
        if (baseUrl.isBlank()) {
            report.put("status", "SKIPPED");
            report.put("reason", "ORACLE_AUDIO_BASE_URL is not configured in .env");
            this.lastSyncStatus = "SKIPPED";
            this.lastSyncTime = Instant.now();
            this.lastSyncResult = report;
            return report;
        }

        try {
            log.info("[Sync] Bắt đầu đồng bộ 2 chiều với Oracle: {}", baseUrl);

            // Bước 1: Kéo từ Oracle về Local
            Map<String, Object> pullResult = pullFromOracle(baseUrl);
            report.put("pullFromOracle", pullResult);

            // Tập hợp các ID đã có trên Oracle để tránh push trùng
            @SuppressWarnings("unchecked")
            Set<String> oracleSpotifyIds = (Set<String>) pullResult.get("oracleSpotifyIds");

            // Bước 2: Đẩy từ Local lên Oracle
            Map<String, Object> pushResult = pushToOracle(baseUrl, oracleSpotifyIds);
            report.put("pushToOracle", pushResult);

            report.put("status", "SUCCESS");
            report.put("durationMs", Duration.between(start, Instant.now()).toMillis());
            this.lastSyncStatus = "SUCCESS";

            log.info("[Sync] Hoàn tất đồng bộ 2 chiều thành công trong {} ms", report.get("durationMs"));
        } catch (Exception e) {
            log.error("[Sync] Lỗi trong quá trình đồng bộ 2 chiều: {}", e.getMessage(), e);
            report.put("status", "ERROR");
            report.put("error", e.getMessage());
            this.lastSyncStatus = "ERROR";
        }

        this.lastSyncTime = Instant.now();
        this.lastSyncResult = report;
        return report;
    }

    /**
     * Kéo dữ liệu bài hát từ Oracle về MongoDB Local
     */
    private Map<String, Object> pullFromOracle(String baseUrl) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        String pullUrl = baseUrl + "api/music/tracks?page=0&size=500";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pullUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Oracle returned HTTP status: " + response.statusCode() + " when pulling tracks");
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            result.put("pulledTotal", 0);
            result.put("oracleSpotifyIds", Collections.emptySet());
            return result;
        }

        ensureSparseSpotifyIdIndex();

        List<Track> oracleTracks = objectMapper.convertValue(content, new TypeReference<List<Track>>() {});
        Set<String> oracleSpotifyIds = new HashSet<>();

        int newTracks = 0;
        int updatedTracks = 0;

        for (Track oracleTrack : oracleTracks) {
            if (oracleTrack.getSpotifyId() != null && oracleTrack.getSpotifyId().isBlank()) {
                oracleTrack.setSpotifyId(null);
            }

            if (oracleTrack.getSpotifyId() != null) {
                oracleSpotifyIds.add(oracleTrack.getSpotifyId().trim());
            }

            Track localExisting = null;
            if (oracleTrack.getSpotifyId() != null) {
                localExisting = trackRepository.findBySpotifyId(oracleTrack.getSpotifyId().trim()).orElse(null);
            }
            if (localExisting == null && oracleTrack.getId() != null && !oracleTrack.getId().isBlank()) {
                localExisting = trackRepository.findById(oracleTrack.getId().trim()).orElse(null);
            }

            if (localExisting == null) {
                // Bài mới hoàn toàn -> lưu vào local
                if (oracleTrack.getCreatedAt() == null) {
                    oracleTrack.setCreatedAt(Instant.now());
                }
                if (oracleTrack.getUpdatedAt() == null) {
                    oracleTrack.setUpdatedAt(Instant.now());
                }
                trackRepository.save(oracleTrack);
                newTracks++;
            } else {
                // Bài đã có -> cập nhật metadata nếu Oracle có thông tin mới hơn
                boolean modified = false;
                if (oracleTrack.getLyricsSynced() != null && (localExisting.getLyricsSynced() == null || localExisting.getLyricsSynced().isBlank())) {
                    localExisting.setLyricsSynced(oracleTrack.getLyricsSynced());
                    modified = true;
                }
                if (oracleTrack.getLyricsPlain() != null && (localExisting.getLyricsPlain() == null || localExisting.getLyricsPlain().isBlank())) {
                    localExisting.setLyricsPlain(oracleTrack.getLyricsPlain());
                    modified = true;
                }
                if (oracleTrack.getAudioFeatures() != null && localExisting.getAudioFeatures() == null) {
                    localExisting.setAudioFeatures(oracleTrack.getAudioFeatures());
                    modified = true;
                }
                if (oracleTrack.getLocalPath() != null && !oracleTrack.getLocalPath().equals(localExisting.getLocalPath())) {
                    localExisting.setLocalPath(oracleTrack.getLocalPath());
                    modified = true;
                }
                if (modified) {
                    localExisting.setUpdatedAt(Instant.now());
                    trackRepository.save(localExisting);
                    updatedTracks++;
                }
            }
        }

        result.put("oracleTotalFetched", oracleTracks.size());
        result.put("pulledNewToLocal", newTracks);
        result.put("pulledUpdatedInLocal", updatedTracks);
        result.put("oracleSpotifyIds", oracleSpotifyIds);
        return result;
    }

    /**
     * Đẩy các bài hát từ MongoDB Local lên Oracle Music Service
     */
    private Map<String, Object> pushToOracle(String baseUrl, Set<String> oracleSpotifyIds) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Track> allLocal = trackRepository.findAll();

        List<Track> toPush = new ArrayList<>();
        for (Track localTrack : allLocal) {
            String sid = localTrack.getSpotifyId();
            // Nếu bài hát không có spotifyId (do Content Lead upload thủ công)
            // hoặc spotifyId chưa có trên Oracle -> cần push lên Oracle
            if (sid == null || sid.isBlank() || !oracleSpotifyIds.contains(sid.trim())) {
                toPush.add(localTrack);
            }
        }

        if (toPush.isEmpty()) {
            result.put("pushedCount", 0);
            result.put("message", "All local tracks already synchronized with Oracle");
            return result;
        }

        String pushUrl = baseUrl + "api/music/tracks/sync";
        String payloadJson = objectMapper.writeValueAsString(toPush);

        HttpRequest pushRequest = HttpRequest.newBuilder()
                .uri(URI.create(pushUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .build();

        HttpResponse<String> response = httpClient.send(pushRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode respNode = objectMapper.readTree(response.body());
            int syncedCount = respNode.has("syncedCount") ? respNode.get("syncedCount").asInt() : toPush.size();
            result.put("pushedCount", syncedCount);
            result.put("pushedTrackNames", toPush.stream().map(Track::getName).toList());
            result.put("oracleResponse", respNode);
        } else {
            throw new IllegalStateException("Failed to push tracks to Oracle: HTTP " + response.statusCode() + " - " + response.body());
        }

        return result;
    }

    /**
     * Đẩy ngay lập tức 1 track lên Oracle (dùng khi Content Lead vừa upload bài mới)
     */
    public boolean pushSingleTrack(Track track) {
        if (track == null) {
            return false;
        }
        String baseUrl = getNormalizedBaseUrl();
        if (baseUrl.isBlank()) {
            return false;
        }

        try {
            String pushUrl = baseUrl + "api/music/tracks/sync";
            String payloadJson = objectMapper.writeValueAsString(List.of(track));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pushUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("[Sync] Đã đẩy bài hát '{}' lên Oracle thành công", track.getName());
                return true;
            } else {
                log.warn("[Sync] Đẩy bài '{}' lên Oracle thất bại: HTTP {}", track.getName(), response.statusCode());
            }
        } catch (Exception e) {
            log.error("[Sync] Lỗi khi đẩy bài '{}' lên Oracle: {}", track.getName(), e.getMessage());
        }
        return false;
    }

    /**
     * Chạy định kỳ ở background theo cấu hình sync.scheduler.fixed-rate-ms (mặc định 15 phút)
     */
    @Scheduled(fixedRateString = "${sync.scheduler.fixed-rate-ms:900000}", initialDelay = 30000)
    public void scheduledSync() {
        if (!syncEnabled) {
            return;
        }
        log.info("[Sync] Kích hoạt tiến trình đồng bộ định kỳ với Oracle...");
        syncTwoWay();
    }

    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("syncEnabled", syncEnabled);
        status.put("oracleBaseUrlConfigured", !oracleBaseUrl.isBlank());
        status.put("lastSyncTime", lastSyncTime != null ? lastSyncTime.toString() : null);
        status.put("lastSyncStatus", lastSyncStatus);
        status.put("lastSyncResult", lastSyncResult);
        return status;
    }

    private void ensureSparseSpotifyIdIndex() {
        if (mongoTemplate == null) return;
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
            boolean needsRecreation = false;
            for (Document index : collection.listIndexes()) {
                String name = index.getString("name");
                if ("spotify_id_1".equals(name)) {
                    Boolean isSparse = index.getBoolean("sparse", false);
                    if (!Boolean.TRUE.equals(isSparse)) {
                        log.info("[Sync] Phát hiện index spotify_id_1 cũ không có cờ sparse -> Đang drop index...");
                        collection.dropIndex("spotify_id_1");
                        needsRecreation = true;
                    }
                    break;
                }
            }
            if (needsRecreation) {
                log.info("[Sync] Đang tạo lại index spotify_id_1 với { unique: true, sparse: true }...");
                collection.createIndex(
                        new Document("spotify_id", 1),
                        new IndexOptions().unique(true).sparse(true)
                );
                log.info("[Sync] Đã tạo index sparse spotify_id_1 thành công!");
            }
        } catch (Exception e) {
            log.warn("[Sync] Không thể cấu hình index sparse cho spotify_id: {}", e.getMessage());
        }
    }
}
