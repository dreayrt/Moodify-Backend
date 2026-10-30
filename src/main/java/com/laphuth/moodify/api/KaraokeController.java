package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.karaoke.KaraokePitchFeatureRequest;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/karaoke")
public class KaraokeController {
    private static final String ASSETS = "karaoke_assets";
    private static final String SESSIONS = "karaoke_sessions";
    private static final long MAX_RECORDING_BYTES = 80L * 1024 * 1024;
    private static final int MAX_REFERENCE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 1024 * 1024;
    private static final double MAX_RECORDING_SECONDS = 600;
    private static final int SCORE_SAMPLE_RATE = 22_050;
    private static final int SCORE_HOP_LENGTH = 512;
    private static final int MAX_SCORE_FRAMES = (int) Math.ceil(MAX_RECORDING_SECONDS * SCORE_SAMPLE_RATE / SCORE_HOP_LENGTH);

    private final MongoTemplate mongo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String scoringUrl;
    private final String scoringKey;

    public KaraokeController(MongoTemplate mongo,
                             @Value("${karaoke.scoring-url:http://karaoke-score-production:8000}") String scoringUrl,
                             @Value("${karaoke.scoring-key:}") String scoringKey) {
        this.mongo = mongo;
        this.scoringUrl = scoringUrl.replaceAll("/$", "");
        this.scoringKey = scoringKey;
    }

    @GetMapping("/tracks")
    public Map<String, Object> getTracks(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String query) {
        if (page < 0 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater and size must be positive");
        }
        if (query != null && query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is too long");
        }
        size = Math.min(size, 50);

        List<Document> readyAssets = mongo.find(Query.query(Criteria.where("status").is("ready")), Document.class, ASSETS);
        Map<String, Document> assetBySongId = new LinkedHashMap<>();
        List<ObjectId> trackIds = new ArrayList<>();
        for (Document asset : readyAssets) {
            String songId = asset.getString("songId");
            Document assetFiles = asset.get("assets", Document.class);
            if (!ObjectId.isValid(songId) || assetFiles == null
                    || !hasStorageKey(assetFiles, "instrumental") || !hasStorageKey(assetFiles, "vocals")) {
                continue;
            }
            if (assetBySongId.putIfAbsent(songId, asset) == null) trackIds.add(new ObjectId(songId));
        }

        List<Document> tracks = trackIds.isEmpty() ? List.of()
                : mongo.find(Query.query(Criteria.where("_id").in(trackIds)), Document.class, "tracks");
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matching = new ArrayList<>();
        for (Document track : tracks) {
            String songId = track.getObjectId("_id").toHexString();
            Document asset = assetBySongId.get(songId);
            if (asset == null) continue;

            String title = track.getString("name");
            String artist = track.getString("artist_name");
            title = title == null || title.isBlank() ? "Bài hát không tên" : title;
            artist = artist == null ? "" : artist;
            if (!normalizedQuery.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    && !artist.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }

            Document assetFiles = asset.get("assets", Document.class);
            Document scoringReference = asset.get("scoringReference", Document.class);
            boolean scoringAvailable = scoringReference != null
                    && "sufficient".equals(scoringReference.getString("status"))
                    && hasStorageKey(assetFiles, "pitchReference");
            Object rawDuration = track.get("duration_ms");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("songId", songId);
            item.put("title", title);
            item.put("artist", artist);
            item.put("imageUrl", track.getString("image_url"));
            item.put("durationMs", rawDuration instanceof Number number ? number.longValue() : null);
            item.put("assetVersion", asset.getString("assetVersion"));
            item.put("lyricsPlain", track.get("lyrics_plain"));
            item.put("lyricsSynced", track.get("lyrics_synced"));
            item.put("lyricTimeline", asset.get("lyricTimeline", Document.class));
            Document instrumental = assetFiles.get("instrumental", Document.class);
            Document storage = asset.get("storage", Document.class);
            String baseUrl = storage == null ? "https://moodify.click/" : storage.getString("baseUrl");
            item.put("instrumentalUrl", urlFor(baseUrl, instrumental.getString("storageKey")));
            item.put("scoringAvailable", scoringAvailable);
            item.put("scoringReferenceStatus", scoringReference == null ? "unavailable" : scoringReference.getString("status"));
            matching.add(item);
        }

        matching.sort(Comparator.comparing(item -> String.valueOf(item.get("title")), String.CASE_INSENSITIVE_ORDER));
        long totalElements = matching.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);
        long fromLong = (long) page * size;
        int from = fromLong >= totalElements ? matching.size() : (int) fromLong;
        int to = Math.min(matching.size(), from + size);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", matching.subList(from, to));
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        return response;
    }

    @GetMapping("/tracks/{songId}")
    public Map<String, Object> getTrack(@PathVariable String songId) {
        Document track = findTrack(songId);
        Document asset = readyAsset(songId);
        Document assets = asset.get("assets", Document.class);
        Document storage = asset.get("storage", Document.class);
        String baseUrl = storage == null ? "https://moodify.click/" : storage.getString("baseUrl");
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Karaoke asset storage is unavailable");
        }
        Document instrumental = assets.get("instrumental", Document.class);
        Document vocals = assets.get("vocals", Document.class);
        Document reference = assets.get("pitchReference", Document.class);
        Document scoringReference = asset.get("scoringReference", Document.class);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("songId", songId);
        response.put("title", track.getString("name"));
        response.put("artist", track.getString("artist_name"));
        response.put("imageUrl", track.getString("image_url"));
        response.put("durationMs", track.get("duration_ms"));
        response.put("lyricsPlain", track.get("lyrics_plain"));
        response.put("lyricsSynced", track.get("lyrics_synced"));
        response.put("lyricTimeline", asset.get("lyricTimeline", Document.class));
        response.put("status", "ready");
        response.put("assetVersion", asset.getString("assetVersion"));
        response.put("instrumentalUrl", urlFor(baseUrl, instrumental.getString("storageKey")));
        response.put("vocalsUrl", urlFor(baseUrl, vocals.getString("storageKey")));
        response.put("scoringAvailable", scoringReference != null && "sufficient".equals(scoringReference.getString("status")));
        response.put("scoringReferenceStatus", scoringReference == null ? "unavailable" : scoringReference.getString("status"));
        response.put("referenceVersion", reference.getString("referenceVersion"));
        response.put("warnings", scoringReference == null ? java.util.List.of() : scoringReference.get("warnings"));
        return response;
    }

    @PostMapping("/tracks/{songId}/sessions")
    public ResponseEntity<Map<String, Object>> startSession(@PathVariable String songId, Authentication authentication) {
        findTrack(songId);
        Document asset = readyAsset(songId);
        Document assets = asset.get("assets", Document.class);
        Document reference = assets.get("pitchReference", Document.class);
        Document scoringReference = asset.get("scoringReference", Document.class);
        if (reference == null || scoringReference == null || !"sufficient".equals(scoringReference.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This track has no usable scoring reference");
        }

        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        Document session = new Document("_id", sessionId)
                .append("sessionId", sessionId)
                .append("userId", requireUser(authentication))
                .append("songId", songId)
                .append("assetVersion", asset.getString("assetVersion"))
                .append("referenceVersion", reference.getString("referenceVersion"))
                .append("referenceSha256", reference.getString("sha256"))
                .append("referenceStorageKey", reference.getString("storageKey"))
                .append("status", "started")
                .append("createdAt", Date.from(now))
                .append("expiresAt", Date.from(now.plusSeconds(2 * 60 * 60)));
        mongo.insert(session, SESSIONS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("songId", songId);
        body.put("assetVersion", asset.getString("assetVersion"));
        body.put("referenceVersion", reference.getString("referenceVersion"));
        body.put("expiresAt", now.plusSeconds(2 * 60 * 60).toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId, Authentication authentication) {
        Document session = ownedSession(sessionId, authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("songId", session.getString("songId"));
        body.put("assetVersion", session.getString("assetVersion"));
        body.put("status", session.getString("status"));
        body.put("score", publicScoreResult(session.get("scoreResult")));
        body.put("createdAt", session.get("createdAt"));
        body.put("completedAt", session.get("completedAt"));
        return body;
    }

    @PostMapping(value = "/sessions/{sessionId}/score", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> score(@PathVariable String sessionId,
                                                      Authentication authentication,
                                                      @RequestParam("recording") MultipartFile recording,
                                                      @RequestParam(value = "recordingStartOffsetMs", defaultValue = "0") double recordingStartOffsetMs,
                                                      @RequestParam(value = "captureLatencyMs", defaultValue = "0") double captureLatencyMs,
                                                      @RequestParam(value = "allowOctaveShift", defaultValue = "true") boolean allowOctaveShift) {
        if (recording.isEmpty() || recording.getSize() > MAX_RECORDING_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Recording must be between 1 byte and 80 MiB");
        }
        if (!Double.isFinite(recordingStartOffsetMs) || recordingStartOffsetMs < -30_000 || recordingStartOffsetMs > 30_000
                || !Double.isFinite(captureLatencyMs) || captureLatencyMs < 0 || captureLatencyMs > 2_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording timing values are outside the supported range");
        }
        if (scoringKey.length() < 24) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Scoring service credentials are not configured");
        }

        String userId = requireUser(authentication);
        Document session = ownedSession(sessionId, authentication);
        String recordingHash = sha256(recording);
        String timingSignature = recordingStartOffsetMs + ":" + captureLatencyMs + ":" + allowOctaveShift;
        if ("completed".equals(session.getString("status"))) {
            if (recordingHash.equals(session.getString("recordingSha256"))
                    && timingSignature.equals(session.getString("timingSignature"))) {
                return ResponseEntity.ok(publicScoreResult(session.get("scoreResult")));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session already has a different completed recording");
        }
        if (!"started".equals(session.getString("status")) || expired(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is expired or already being scored");
        }

        Query claim = Query.query(Criteria.where("sessionId").is(sessionId)
                .and("userId").is(userId).and("status").is("started")
                .and("expiresAt").gt(new Date()));
        Document claimed = mongo.findAndModify(claim, new Update().set("status", "scoring").set("scoringStartedAt", new Date()),
                FindAndModifyOptions.options().returnNew(true), Document.class, SESSIONS);
        if (claimed == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is expired or already being scored");
        }

        try {
            Document asset = readyAsset(claimed.getString("songId"));
            if (!claimed.getString("assetVersion").equals(asset.getString("assetVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The prepared song version changed after this session started");
            }
            Document assetFiles = asset.get("assets", Document.class);
            Document referenceAsset = assetFiles.get("pitchReference", Document.class);
            if (referenceAsset == null || !claimed.getString("referenceVersion").equals(referenceAsset.getString("referenceVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The scoring reference no longer matches this session");
            }
            byte[] reference = fetchReference(asset, referenceAsset, claimed.getString("referenceSha256"));
            String timing = "{\"recording_start_offset_ms\":" + recordingStartOffsetMs
                    + ",\"capture_latency_ms\":" + captureLatencyMs
                    + ",\"allow_octave_shift\":" + allowOctaveShift
                    + ",\"expected_reference_version\":\"" + claimed.getString("referenceVersion") + "\"}";
            Document upstreamScore = sendScore(recording, reference, timing);
            Document score = publicScoreDocument(upstreamScore);
            mongo.updateFirst(Query.query(Criteria.where("sessionId").is(sessionId).and("userId").is(userId)
                            .and("status").is("scoring")),
                    new Update().set("status", "completed").set("scoreResult", score)
                            .set("recordingSha256", recordingHash).set("timingSignature", timingSignature)
                            .set("completedAt", new Date()).set("scoringVersion", upstreamScore.get("scoring_version")), SESSIONS);
            return ResponseEntity.ok(castMap(score));
        } catch (ResponseStatusException error) {
            resetSession(sessionId, userId, error.getStatusCode().value());
            throw error;
        } catch (Exception error) {
            resetSession(sessionId, userId, 0);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Scoring service could not complete this recording");
        }
    }

    @PostMapping(value = "/sessions/{sessionId}/score/features", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> scoreFeatures(@PathVariable String sessionId,
                                                              Authentication authentication,
                                                              @RequestBody KaraokePitchFeatureRequest features) {
        validatePitchFeatures(features);
        if (scoringKey.length() < 24) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Scoring service credentials are not configured");
        }

        String userId = requireUser(authentication);
        Document session = ownedSession(sessionId, authentication);
        String featuresHash = sha256(featureSignature(features).getBytes(StandardCharsets.UTF_8));
        String timingSignature = features.recordingStartOffsetMs() + ":" + features.captureLatencyMs() + ":" + features.allowOctaveShift();
        if ("completed".equals(session.getString("status"))) {
            if (featuresHash.equals(session.getString("recordingSha256"))
                    && timingSignature.equals(session.getString("timingSignature"))) {
                return ResponseEntity.ok(publicScoreResult(session.get("scoreResult")));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session already has a different completed recording");
        }
        if (!"started".equals(session.getString("status")) || expired(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is expired or already being scored");
        }

        Query claim = Query.query(Criteria.where("sessionId").is(sessionId)
                .and("userId").is(userId).and("status").is("started")
                .and("expiresAt").gt(new Date()));
        Document claimed = mongo.findAndModify(claim, new Update().set("status", "scoring").set("scoringStartedAt", new Date()),
                FindAndModifyOptions.options().returnNew(true), Document.class, SESSIONS);
        if (claimed == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is expired or already being scored");
        }

        try {
            Document asset = readyAsset(claimed.getString("songId"));
            if (!claimed.getString("assetVersion").equals(asset.getString("assetVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The prepared song version changed after this session started");
            }
            Document assetFiles = asset.get("assets", Document.class);
            Document referenceAsset = assetFiles.get("pitchReference", Document.class);
            if (referenceAsset == null || !claimed.getString("referenceVersion").equals(referenceAsset.getString("referenceVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The scoring reference no longer matches this session");
            }
            byte[] reference = fetchReference(asset, referenceAsset, claimed.getString("referenceSha256"));
            String timing = "{\"recording_start_offset_ms\":" + features.recordingStartOffsetMs()
                    + ",\"capture_latency_ms\":" + features.captureLatencyMs()
                    + ",\"allow_octave_shift\":" + features.allowOctaveShift()
                    + ",\"expected_reference_version\":\"" + claimed.getString("referenceVersion") + "\"}";
            Document upstreamScore = sendScoreFeatures(features, reference, timing);
            Document score = publicScoreDocument(upstreamScore);
            mongo.updateFirst(Query.query(Criteria.where("sessionId").is(sessionId).and("userId").is(userId)
                            .and("status").is("scoring")),
                    new Update().set("status", "completed").set("scoreResult", score)
                            .set("recordingSha256", featuresHash).set("timingSignature", timingSignature)
                            .set("completedAt", new Date()).set("scoringVersion", upstreamScore.get("scoring_version")), SESSIONS);
            return ResponseEntity.ok(castMap(score));
        } catch (ResponseStatusException error) {
            resetSession(sessionId, userId, error.getStatusCode().value());
            throw error;
        } catch (Exception error) {
            resetSession(sessionId, userId, 0);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Scoring service could not complete this recording");
        }
    }

    public static void validatePitchFeatures(KaraokePitchFeatureRequest features) {
        if (features == null || !Double.isFinite(features.durationSeconds()) || features.durationSeconds() <= 0
                || features.durationSeconds() > MAX_RECORDING_SECONDS
                || !Double.isFinite(features.rmsDbfs()) || features.rmsDbfs() < -240 || features.rmsDbfs() > 6
                || !Double.isFinite(features.clippedFraction()) || features.clippedFraction() < 0 || features.clippedFraction() > 1
                || !Double.isFinite(features.recordingStartOffsetMs()) || features.recordingStartOffsetMs() < -30_000
                || features.recordingStartOffsetMs() > 30_000
                || !Double.isFinite(features.captureLatencyMs()) || features.captureLatencyMs() < 0
                || features.captureLatencyMs() > 2_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording feature metadata is outside the supported range");
        }
        List<Double> pitches = features.f0Hz();
        List<Double> probabilities = features.voicedProbability();
        int expected = (int) Math.ceil(features.durationSeconds() * SCORE_SAMPLE_RATE / SCORE_HOP_LENGTH - 1e-9);
        if (pitches == null || probabilities == null || pitches.isEmpty() || pitches.size() != probabilities.size()
                || pitches.size() != expected || pitches.size() > MAX_SCORE_FRAMES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording feature frame count does not match its timeline");
        }
        for (int i = 0; i < pitches.size(); i++) {
            Double pitch = pitches.get(i);
            Double probability = probabilities.get(i);
            if ((pitch != null && (!Double.isFinite(pitch) || pitch < 60 || pitch > 1300))
                    || probability == null || !Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording pitch features contain invalid values");
            }
        }
    }

    /** Scores one room-wide pitch timeline after the room service has merged every singer's contribution. */
    public Map<String, Object> scoreMergedRoomFeatures(String songId, KaraokePitchFeatureRequest features,
                                                       String expectedAssetVersion, String expectedReferenceVersion,
                                                       String expectedReferenceSha256) {
        validatePitchFeatures(features);
        if (scoringKey.length() < 24) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Scoring service credentials are not configured");
        }
        try {
            Document asset = readyAsset(songId);
            if (!expectedAssetVersion.equals(asset.getString("assetVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The prepared song version changed during room scoring");
            }
            Document assetFiles = asset.get("assets", Document.class);
            Document referenceAsset = assetFiles == null ? null : assetFiles.get("pitchReference", Document.class);
            if (referenceAsset == null || !expectedReferenceVersion.equals(referenceAsset.getString("referenceVersion"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The scoring reference changed during room scoring");
            }
            byte[] reference = fetchReference(asset, referenceAsset, expectedReferenceSha256);
            String timing = "{\"recording_start_offset_ms\":0,\"capture_latency_ms\":0"
                    + ",\"allow_octave_shift\":true,\"expected_reference_version\":\""
                    + expectedReferenceVersion + "\"}";
            return castMap(publicScoreDocument(sendScoreFeatures(features, reference, timing)));
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Scoring service could not complete the room performance");
        }
    }

    private static String featureSignature(KaraokePitchFeatureRequest features) {
        return features.durationSeconds() + ":" + features.f0Hz() + ":" + features.voicedProbability()
                + ":" + features.rmsDbfs() + ":" + features.clippedFraction();
    }

    private Document findTrack(String songId) {
        if (songId == null || !ObjectId.isValid(songId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track was not found");
        }
        Document track = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(songId))), Document.class, "tracks");
        if (track == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track was not found");
        return track;
    }

    private Document readyAsset(String songId) {
        Document asset = mongo.findOne(Query.query(Criteria.where("songId").is(songId).and("status").is("ready")), Document.class, ASSETS);
        if (asset == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Karaoke assets are not prepared for this track");
        return asset;
    }

    private static boolean hasStorageKey(Document assets, String role) {
        if (assets == null) return false;
        Document item = assets.get(role, Document.class);
        String storageKey = item == null ? null : item.getString("storageKey");
        return storageKey != null && !storageKey.isBlank();
    }

    private Document ownedSession(String sessionId, Authentication authentication) {
        if (sessionId == null || sessionId.length() > 64) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session was not found");
        Document session = mongo.findOne(Query.query(Criteria.where("sessionId").is(sessionId)
                .and("userId").is(requireUser(authentication))), Document.class, SESSIONS);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session was not found");
        return session;
    }

    private String requireUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return authentication.getName();
    }

    private boolean expired(Document session) {
        Date expiry = session.getDate("expiresAt");
        return expiry == null || expiry.before(new Date());
    }

    private void resetSession(String sessionId, String userId, int status) {
        mongo.updateFirst(Query.query(Criteria.where("sessionId").is(sessionId).and("userId").is(userId).and("status").is("scoring")),
                new Update().set("status", "started").set("lastScoringHttpStatus", status).unset("scoringStartedAt"), SESSIONS);
    }

    private byte[] fetchReference(Document asset, Document referenceAsset, String expectedHash) throws IOException {
        Document storage = asset.get("storage", Document.class);
        String base = storage == null ? "https://moodify.click/" : storage.getString("baseUrl");
        String url = urlFor(base, referenceAsset.getString("storageKey"));
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("reference download failed");
            long declared = connection.getContentLengthLong();
            if (declared < 1 || declared > MAX_REFERENCE_BYTES) throw new IOException("reference size is invalid");
            byte[] bytes = readBounded(connection.getInputStream(), MAX_REFERENCE_BYTES);
            if (!sha256(bytes).equals(expectedHash)) throw new IOException("reference checksum mismatch");
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private Document sendScore(MultipartFile recording, byte[] reference, String timing) throws IOException {
        String boundary = "----MoodifyKaraoke" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = (HttpURLConnection) URI.create(scoringUrl + "/v1/score").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(600_000);
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Authorization", "Bearer " + scoringKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream output = connection.getOutputStream()) {
            writeTextPart(output, boundary, "timing", timing);
            String fileName = cleanFilename(recording.getOriginalFilename());
            writeFileHeader(output, boundary, "recording", fileName, safeMime(recording.getContentType()));
            try (InputStream input = recording.getInputStream()) { input.transferTo(output); }
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFileHeader(output, boundary, "reference", "pitch_reference.json", "application/json");
            output.write(reference);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        try {
            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = body == null ? new byte[0] : readBounded(body, MAX_RESULT_BYTES);
            if (status < 200 || status >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Scoring service rejected the recording (HTTP " + status + ")");
            }
            return Document.parse(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private Document sendScoreFeatures(KaraokePitchFeatureRequest features, byte[] reference, String timing) throws IOException {
        String boundary = "----MoodifyKaraoke" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = (HttpURLConnection) URI.create(scoringUrl + "/v1/score/features").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Authorization", "Bearer " + scoringKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        Map<String, Object> featurePayload = new LinkedHashMap<>();
        featurePayload.put("duration_seconds", features.durationSeconds());
        featurePayload.put("f0_hz", features.f0Hz());
        featurePayload.put("voiced_probability", features.voicedProbability());
        featurePayload.put("rms_dbfs", features.rmsDbfs());
        featurePayload.put("clipped_fraction", features.clippedFraction());
        byte[] featureBytes = objectMapper.writeValueAsBytes(featurePayload);

        try (OutputStream output = connection.getOutputStream()) {
            writeTextPart(output, boundary, "timing", timing);
            writeFileHeader(output, boundary, "features", "pitch_features.json", "application/json");
            output.write(featureBytes);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFileHeader(output, boundary, "reference", "pitch_reference.json", "application/json");
            output.write(reference);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        try {
            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = body == null ? new byte[0] : readBounded(body, MAX_RESULT_BYTES);
            if (status < 200 || status >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Scoring service rejected the recording features (HTTP " + status + ")");
            }
            return Document.parse(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static void writeTextPart(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileHeader(OutputStream out, String boundary, String name, String file, String mime) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > limit) throw new IOException("response exceeds allowed size");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the recording");
        }
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static Document publicScoreDocument(Document upstream) {
        Object rawScore = upstream.get("score");
        Object rawOverall = rawScore instanceof Map<?, ?> map ? map.get("overall") : null;
        Object score = rawOverall instanceof Number number
                ? new Document("overall", number.doubleValue())
                : null;
        return new Document("status", upstream.getString("status")).append("score", score);
    }

    private static Map<String, Object> publicScoreResult(Object stored) {
        if (!(stored instanceof Map<?, ?> result)) return null;
        Object rawScore = result.get("score");
        Object rawOverall = rawScore instanceof Map<?, ?> map ? map.get("overall") : null;
        Object score = rawOverall instanceof Number number
                ? Map.of("overall", number.doubleValue())
                : null;
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("status", result.get("status"));
        safe.put("score", score);
        return safe;
    }

    private static String urlFor(String base, String key) {
        if (base == null || key == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Karaoke asset URL is invalid");
        }
        URI origin;
        try { origin = URI.create(base); }
        catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Karaoke asset URL is invalid");
        }
        String[] parts = key.split("/", -1);
        boolean validKey = parts.length == 4 && "karaoke".equals(parts[0])
                && parts[1].matches("[a-f0-9]{24}") && parts[2].matches("[a-f0-9]{24}")
                && java.util.Set.of("instrumental.wav", "vocals.wav", "pitch_reference.json").contains(parts[3]);
        if (!"https".equalsIgnoreCase(origin.getScheme()) || !"moodify.click".equalsIgnoreCase(origin.getHost())
                || (origin.getPort() != -1 && origin.getPort() != 443)
                || (origin.getPath() != null && !origin.getPath().isEmpty() && !"/".equals(origin.getPath()))
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !validKey) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Karaoke asset URL is invalid");
        }
        String normalized = base.endsWith("/") ? base : base + "/";
        return normalized + key;
    }

    private static String cleanFilename(String name) {
        if (name == null || name.isBlank()) return "recording.wav";
        return name.replaceAll("[\\r\\n\\\"]", "_");
    }

    private static String safeMime(String mime) {
        return mime != null && mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ? mime : "application/octet-stream";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
