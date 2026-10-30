package com.laphuth.moodify.services;

import com.laphuth.moodify.api.KaraokeController;
import com.laphuth.moodify.dto.karaoke.KaraokePitchFeatureRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomContributionRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomDetailResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomPitchSegmentRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomScoreSessionResponse;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KaraokeRoomScoringService {
    private static final String COLLECTION = "karaoke_room_score_sessions";
    private static final String ASSETS = "karaoke_assets";
    private static final int SAMPLE_RATE = 22_050;
    private static final int HOP_LENGTH = 512;
    private static final int MAX_DURATION_SECONDS = 600;

    private final MongoTemplate mongo;
    private final KaraokeRoomService roomService;
    private final KaraokeController scoring;
    private final KaraokeRoomRealtimeGateway realtime;

    public KaraokeRoomScoringService(MongoTemplate mongo, KaraokeRoomService roomService,
                                     KaraokeController scoring, KaraokeRoomRealtimeGateway realtime) {
        this.mongo = mongo;
        this.roomService = roomService;
        this.scoring = scoring;
        this.realtime = realtime;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        mongo.indexOps(COLLECTION).ensureIndex(new Index().on("roomId", Sort.Direction.ASC)
                .on("playbackRunId", Sort.Direction.ASC).unique());
        mongo.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
    }

    public KaraokeRoomScoreSessionResponse startCurrent(Long roomId, Authentication authentication) {
        KaraokeRoomDetailResponse room = roomService.getRoom(roomId, authentication);
        var playback = room.playback();
        if (playback == null || playback.track() == null || playback.playbackRunId() == null
                || !(playback.status().name().equals("PLAYING") || playback.status().name().equals("PAUSED"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_PLAYBACK_NOT_SCORABLE");
        }
        Long durationMs = playback.track().durationMs();
        if (durationMs == null || durationMs <= 0 || durationMs > MAX_DURATION_SECONDS * 1000L) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_TRACK_DURATION_UNSUPPORTED");
        }
        Query lookup = Query.query(Criteria.where("roomId").is(roomId)
                .and("playbackRunId").is(playback.playbackRunId()));
        Document existing = mongo.findOne(lookup, Document.class, COLLECTION);
        if (existing == null) {
            Document asset = mongo.findOne(Query.query(Criteria.where("songId").is(playback.track().trackId())
                    .and("status").is("ready")), Document.class, ASSETS);
            Document assetFiles = asset == null ? null : asset.get("assets", Document.class);
            Document reference = assetFiles == null ? null : assetFiles.get("pitchReference", Document.class);
            Document scoringReference = asset == null ? null : asset.get("scoringReference", Document.class);
            if (asset == null || reference == null || scoringReference == null
                    || !"sufficient".equals(scoringReference.getString("status"))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_TRACK_HAS_NO_SCORING_REFERENCE");
            }
            Date now = new Date();
            String sessionId = UUID.randomUUID().toString();
            Document created = new Document("_id", sessionId)
                    .append("sessionId", sessionId)
                    .append("roomId", roomId)
                    .append("playbackRunId", playback.playbackRunId())
                    .append("songId", playback.track().trackId())
                    .append("durationMs", durationMs)
                    .append("assetVersion", asset.getString("assetVersion"))
                    .append("referenceVersion", reference.getString("referenceVersion"))
                    .append("referenceSha256", reference.getString("sha256"))
                    .append("status", "collecting")
                    .append("ended", false)
                    .append("participants", new ArrayList<String>())
                    .append("contributions", new Document())
                    .append("createdAt", now)
                    .append("expiresAt", new Date(now.getTime() + 24 * 60 * 60 * 1000L));
            try {
                mongo.insert(created, COLLECTION);
                existing = created;
            } catch (DuplicateKeyException race) {
                existing = mongo.findOne(lookup, Document.class, COLLECTION);
            }
        }
        return response(existing);
    }

    public KaraokeRoomScoreSessionResponse registerParticipant(Long roomId, String sessionId,
                                                                 Authentication authentication) {
        Document session = ownedRoomSession(roomId, sessionId, authentication);
        if (!"collecting".equals(session.getString("status"))) return response(session);
        Long userId = roomService.authenticatedUserId(authentication);
        mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("collecting")),
                new Update().addToSet("participants", String.valueOf(userId)), COLLECTION);
        return response(requireSession(roomId, sessionId));
    }

    public KaraokeRoomScoreSessionResponse submitContribution(Long roomId, String sessionId,
                                                               KaraokeRoomContributionRequest request,
                                                               Authentication authentication) {
        Document session = ownedRoomSession(roomId, sessionId, authentication);
        if (!"collecting".equals(session.getString("status"))) return response(session);
        Long userId = roomService.authenticatedUserId(authentication);
        String participantId = String.valueOf(userId);
        List<?> participants = session.getList("participants", Object.class, List.of());
        if (!participants.contains(participantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_SCORE_PARTICIPANT_NOT_REGISTERED");
        }
        List<KaraokeRoomPitchSegmentRequest> segments = request == null ? null : request.segments();
        if (segments == null || segments.isEmpty() || segments.size() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROOM_SCORE_SEGMENTS_INVALID");
        }
        List<Document> storedSegments = new ArrayList<>();
        double durationMs = session.get("durationMs", Number.class).doubleValue();
        for (KaraokeRoomPitchSegmentRequest segment : segments) {
            if (segment == null || !Double.isFinite(segment.offsetMs()) || segment.offsetMs() < 0
                    || segment.offsetMs() > durationMs || segment.features() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROOM_SCORE_SEGMENT_OFFSET_INVALID");
            }
            KaraokeController.validatePitchFeatures(segment.features());
            if (segment.offsetMs() + segment.features().durationSeconds() * 1000 > durationMs + 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROOM_SCORE_SEGMENT_EXCEEDS_TRACK");
            }
            storedSegments.add(new Document("offsetMs", segment.offsetMs())
                    .append("features", featureDocument(segment.features())));
        }
        Document contribution = new Document("userId", participantId)
                .append("segments", storedSegments).append("submittedAt", new Date());
        String key = "u" + participantId;
        mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("collecting")),
                new Update().set("contributions." + key, contribution), COLLECTION);
        tryFinalize(roomId, sessionId);
        return response(requireSession(roomId, sessionId));
    }

    public KaraokeRoomScoreSessionResponse finish(Long roomId, String sessionId, Authentication authentication) {
        ownedRoomSession(roomId, sessionId, authentication);
        mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("collecting")),
                new Update().set("ended", true).set("endedAt", new Date()), COLLECTION);
        tryFinalize(roomId, sessionId);
        return response(requireSession(roomId, sessionId));
    }

    public KaraokeRoomScoreSessionResponse get(Long roomId, String sessionId, Authentication authentication) {
        return response(ownedRoomSession(roomId, sessionId, authentication));
    }

    private void tryFinalize(Long roomId, String sessionId) {
        Document session = requireSession(roomId, sessionId);
        if (!Boolean.TRUE.equals(session.getBoolean("ended")) || !"collecting".equals(session.getString("status"))) return;
        List<?> participants = session.getList("participants", Object.class, List.of());
        Document contributions = session.get("contributions", Document.class);
        if (participants.isEmpty()) {
            Document completed = mongo.findAndModify(sessionQuery(roomId, sessionId)
                            .addCriteria(Criteria.where("status").is("collecting").and("ended").is(true)),
                    new Update().set("status", "completed").set("completedAt", new Date())
                            .set("resultMessage", "NO_PARTICIPANTS"),
                    FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
            if (completed != null) realtime.publishRoomScoreUpdated(roomId, sessionId, "completed", null);
            return;
        }
        if (contributions == null) return;
        for (Object participant : participants) {
            if (!contributions.containsKey("u" + participant)) return;
        }
        Document claimed = mongo.findAndModify(sessionQuery(roomId, sessionId)
                        .addCriteria(Criteria.where("status").is("collecting").and("ended").is(true)),
                new Update().set("status", "scoring").set("scoringStartedAt", new Date()),
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (claimed == null) return;
        try {
            KaraokePitchFeatureRequest merged = merge(claimed);
            if (merged == null) {
                mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("scoring")),
                        new Update().set("status", "completed").set("scoreResult", null)
                                .set("completedAt", new Date()).set("resultMessage", "NO_VOICED_AUDIO"), COLLECTION);
                realtime.publishRoomScoreUpdated(roomId, sessionId, "completed", null);
                return;
            }
            Map<String, Object> score = scoring.scoreMergedRoomFeatures(claimed.getString("songId"), merged,
                    claimed.getString("assetVersion"), claimed.getString("referenceVersion"),
                    claimed.getString("referenceSha256"));
            mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("scoring")),
                    new Update().set("status", "completed").set("scoreResult", score)
                            .set("completedAt", new Date()), COLLECTION);
            realtime.publishRoomScoreUpdated(roomId, sessionId, "completed", score);
        } catch (Exception error) {
            mongo.updateFirst(sessionQuery(roomId, sessionId).addCriteria(Criteria.where("status").is("scoring")),
                    new Update().set("status", "failed").set("failedAt", new Date())
                            .set("failure", error instanceof ResponseStatusException status
                                    ? status.getReason() : "ROOM_SCORING_FAILED"), COLLECTION);
            realtime.publishRoomScoreUpdated(roomId, sessionId, "failed", null);
        }
    }

    @Scheduled(fixedDelayString = "${karaoke.rooms.scoring-retry-ms:5000}")
    public void retryEndedSessions() {
        List<Document> ended = mongo.find(Query.query(Criteria.where("status").is("collecting")
                .and("ended").is(true)), Document.class, COLLECTION);
        for (Document session : ended) {
            Number roomId = session.get("roomId", Number.class);
            if (roomId == null) continue;
            Date endedAt = session.getDate("endedAt");
            Document contributions = session.get("contributions", Document.class);
            List<?> participants = session.getList("participants", Object.class, List.of());
            if (endedAt != null && System.currentTimeMillis() - endedAt.getTime() >= 30_000 && contributions != null) {
                List<String> submitted = participants.stream().map(String::valueOf)
                        .filter(id -> contributions.containsKey("u" + id)).toList();
                mongo.updateFirst(sessionQuery(roomId.longValue(), session.getString("sessionId"))
                                .addCriteria(Criteria.where("status").is("collecting")),
                        new Update().set("participants", submitted), COLLECTION);
            }
            tryFinalize(roomId.longValue(), session.getString("sessionId"));
        }
    }

    private KaraokePitchFeatureRequest merge(Document session) {
        long durationMs = session.get("durationMs", Number.class).longValue();
        double durationSeconds = durationMs / 1000d;
        int frameCount = (int) Math.ceil(durationSeconds * SAMPLE_RATE / HOP_LENGTH - 1e-9);
        List<Double> pitches = new ArrayList<>(java.util.Collections.nCopies(frameCount, null));
        List<Double> probabilities = new ArrayList<>(java.util.Collections.nCopies(frameCount, 0d));
        Document contributions = session.get("contributions", Document.class);
        if (contributions == null) return null;
        double rmsDbfs = -240;
        double clipped = 0;
        boolean hasVoice = false;
        for (String key : contributions.keySet()) {
            Document contribution = contributions.get(key, Document.class);
            List<Document> segments = contribution == null ? null : contribution.getList("segments", Document.class);
            if (segments == null) continue;
            for (Document segment : segments) {
                Number offsetRaw = segment.get("offsetMs", Number.class);
                Document features = segment.get("features", Document.class);
                if (offsetRaw == null || features == null) continue;
                List<?> sourcePitches = features.getList("f0_hz", Object.class);
                List<?> sourceProbabilities = features.getList("voiced_probability", Object.class);
                if (sourcePitches == null || sourceProbabilities == null) continue;
                int start = (int) Math.round(offsetRaw.doubleValue() * SAMPLE_RATE / 1000d / HOP_LENGTH);
                int count = Math.min(sourcePitches.size(), sourceProbabilities.size());
                for (int index = 0; index < count; index++) {
                    int target = start + index;
                    if (target < 0 || target >= frameCount) continue;
                    Object rawPitch = sourcePitches.get(index);
                    Object rawProbability = sourceProbabilities.get(index);
                    if (!(rawProbability instanceof Number probability)) continue;
                    double confidence = probability.doubleValue();
                    if (rawPitch instanceof Number pitch && pitch.doubleValue() >= 60 && pitch.doubleValue() <= 1300
                            && confidence > probabilities.get(target)) {
                        pitches.set(target, pitch.doubleValue());
                        probabilities.set(target, confidence);
                        hasVoice = true;
                    }
                }
                Number rms = features.get("rms_dbfs", Number.class);
                Number clip = features.get("clipped_fraction", Number.class);
                if (rms != null) rmsDbfs = Math.max(rmsDbfs, rms.doubleValue());
                if (clip != null) clipped = Math.max(clipped, clip.doubleValue());
            }
        }
        if (!hasVoice) return null;
        return new KaraokePitchFeatureRequest(durationSeconds, pitches, probabilities, rmsDbfs, clipped, 0, 0, true);
    }

    private static Document featureDocument(KaraokePitchFeatureRequest features) {
        return new Document("duration_seconds", features.durationSeconds())
                .append("f0_hz", features.f0Hz())
                .append("voiced_probability", features.voicedProbability())
                .append("rms_dbfs", features.rmsDbfs())
                .append("clipped_fraction", features.clippedFraction());
    }

    private Document ownedRoomSession(Long roomId, String sessionId, Authentication authentication) {
        Document session = requireSession(roomId, sessionId);
        roomService.getRoom(roomId, authentication);
        return session;
    }

    private Document requireSession(Long roomId, String sessionId) {
        Document session = mongo.findOne(sessionQuery(roomId, sessionId), Document.class, COLLECTION);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_SCORE_SESSION_NOT_FOUND");
        return session;
    }

    private static Query sessionQuery(Long roomId, String sessionId) {
        return Query.query(Criteria.where("roomId").is(roomId).and("sessionId").is(sessionId));
    }

    private static KaraokeRoomScoreSessionResponse response(Document session) {
        List<?> participants = session.getList("participants", Object.class, List.of());
        Object rawScore = session.get("scoreResult");
        Map<String, Object> score = rawScore instanceof Map<?, ?> map
                ? map.entrySet().stream().filter(entry -> entry.getKey() instanceof String)
                    .collect(LinkedHashMap::new, (result, entry) -> result.put((String) entry.getKey(), entry.getValue()), LinkedHashMap::putAll)
                : null;
        return new KaraokeRoomScoreSessionResponse(session.getString("sessionId"), session.getString("playbackRunId"),
                session.getString("songId"), session.getString("status"), Boolean.TRUE.equals(session.getBoolean("ended")),
                participants.size(), score);
    }
}
