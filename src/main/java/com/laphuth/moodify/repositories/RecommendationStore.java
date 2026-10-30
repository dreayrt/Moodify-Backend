package com.laphuth.moodify.repositories;

import com.laphuth.moodify.services.RecommendationRuntime;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Update;

@Repository
public class RecommendationStore {
    private final JdbcTemplate jdbc;
    private final MongoTemplate mongo;
    private final RecommendationPersistence persistence;
    private final int legacyOffsetMinutes;
    private final RecommendationRuntime runtime;
    private final long eventTimeoutMs;

    public RecommendationStore(JdbcTemplate jdbc, MongoTemplate mongo, RecommendationPersistence persistence,
        @Value("${recommendations.legacy-offset-minutes:-420}") int legacyOffsetMinutes,
        RecommendationRuntime runtime, @Value("${recommendations.event-timeout-ms:2000}") long eventTimeoutMs) {
        this.jdbc = jdbc; this.mongo = mongo; this.persistence = persistence;
        this.legacyOffsetMinutes = legacyOffsetMinutes;
        if(eventTimeoutMs < 1 || eventTimeoutMs > 30000) throw new IllegalArgumentException("Invalid recommendation event timeout");
        this.runtime=runtime; this.eventTimeoutMs=eventTimeoutMs;
    }

    public void requireReady() { persistence.requireReady(); }
    public void forgetProfile(long userId) {
        mongo.remove(Query.query(Criteria.where("_id").is("profile:" + userId).and("kind").is("PROFILE")),
            RecommendationPersistence.STATE);
    }
    public static LocalDateTime utc(Instant at) {
        // DATETIME is a wall-clock field: binding java.sql.Timestamp can apply the JVM/connection offset again.
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
    public static Instant instant(Object value) {
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        return Instant.parse(value.toString());
    }

    public List<Signal> signals(long userId, Instant now) {
        List<Signal> result = new ArrayList<>();
        jdbc.query("""
            SELECT track_id,
              CASE WHEN EXISTS(SELECT 1 FROM revenue_playback_sessions rp WHERE rp.listening_history_id=h.id)
                   THEN started_at ELSE TIMESTAMPADD(MINUTE, ?, started_at) END signal_at,
              listened_duration_ms,
              EXISTS(SELECT 1 FROM playback_events p WHERE p.listening_history_id=h.id
                     AND p.event_type='SKIP_NEXT') AS skipped
            FROM listening_history h WHERE user_id=? AND started_at>=?
            ORDER BY signal_at DESC LIMIT 3000
            """, rs -> {
                long duration = rs.getLong("listened_duration_ms");
                boolean earlySkip = rs.getBoolean("skipped") && duration < 15_000;
                Instant at = instant(rs.getObject("signal_at"));
                if (at.isAfter(now)) return;
                double age = Math.max(0, Duration.between(at, now).toHours()) / 24.0;
                double strength = earlySkip ? -0.35 : Math.min(2.0, duration / 30_000.0);
                if (duration >= 5_000 || earlySkip) result.add(new Signal(rs.getString("track_id"),
                    strength * Math.pow(0.5, age / 45.0), at, false, earlySkip));
            }, legacyOffsetMinutes, userId, utc(now.minus(Duration.ofDays(181))));
        jdbc.query("SELECT track_id, added_at FROM user_library_tracks WHERE user_id=?", rs -> {
            Instant addedAt = instant(rs.getObject("added_at")).plusSeconds(legacyOffsetMinutes * 60L);
            if (!addedAt.isAfter(now)) result.add(new Signal(rs.getString("track_id"), 4.0, addedAt, true, false));
        }, userId);
        return result;
    }

    public Map<String, Set<String>> blocked(long userId) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Document feedback : mongo.find(Query.query(Criteria.where("kind").is("FEEDBACK")
            .and("user_id").is(userId).and("feedback_type").in("HIDE", "NOT_INTERESTED")),
            Document.class, RecommendationPersistence.STATE)) {
            result.computeIfAbsent(feedback.getString("entity_type"), k -> new HashSet<>()).add(feedback.getString("entity_id"));
        }
        return result;
    }

    public Map<String, Double> collaborativeScores(long userId, Instant now) {
        if(!runtime.flags().collaborative()) return Map.of();
        Document profile = mongo.findById("profile:" + userId, Document.class, RecommendationPersistence.STATE);
        if (profile == null || profile.get("as_of") == null ||
            instant(profile.get("as_of")).isBefore(now.minus(Duration.ofDays(3)))) return Map.of();
        Object raw = profile.get("collaborative_scores");
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Double> result = new HashMap<>();
        values.forEach((key, value) -> {
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) result.put(key.toString(), number.doubleValue());
        });
        return result;
    }

    public String modelVersion(long userId, Instant now) {
        if(!runtime.flags().collaborative()) return "content-session-v1";
        Document profile = mongo.findById("profile:" + userId, Document.class, RecommendationPersistence.STATE);
        if (profile == null || profile.get("as_of") == null ||
            instant(profile.get("as_of")).isBefore(now.minus(Duration.ofDays(3)))) return "content-session-v1";
        return profile.getString("model_version") == null ? "content-session-v1" : profile.getString("model_version");
    }

    public record TrendStats(Map<String,double[]> tracks,double[] activeListeners) {}

    /** Distinct listeners are merged AFTER canonicalizing aliases, not by adding raw-ID counts. */
    public TrendStats trendStats(Instant now,Map<String,String> aliases) {
        List<Set<Long>> active=List.of(new HashSet<>(),new HashSet<>());
        Map<String,List<Set<Long>>> listeners=new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT track_id,user_id,CASE WHEN signal_at>=? THEN 0 ELSE 1 END period,
                            CASE WHEN listened_duration_ms>=30000 THEN 1 ELSE 0 END engaged
            FROM (SELECT h.track_id,h.user_id,h.listened_duration_ms,
                    CASE WHEN r.listening_history_id IS NOT NULL THEN h.started_at
                         ELSE TIMESTAMPADD(MINUTE,?,h.started_at) END signal_at
                  FROM listening_history h JOIN users u ON u.id=h.user_id AND u.role='USER' AND u.status='ACTIVE'
                  LEFT JOIN revenue_playback_sessions r ON r.listening_history_id=h.id
                  WHERE h.started_at>=?) normalized
            WHERE signal_at>=? AND signal_at<=?
            """,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                int period=rs.getInt("period");long user=rs.getLong("user_id");active.get(period).add(user);
                String canonical=aliases.get(rs.getString("track_id"));
                if(canonical!=null&&rs.getBoolean("engaged"))listeners.computeIfAbsent(canonical,k->List.of(new HashSet<>(),new HashSet<>())).get(period).add(user);
            },utc(now.minus(Duration.ofDays(1))),legacyOffsetMinutes,
            utc(now.minus(Duration.ofDays(2)).minusSeconds(Math.max(0,legacyOffsetMinutes)*60L)),utc(now.minus(Duration.ofDays(2))),utc(now));
        Map<String,double[]> counts=new HashMap<>();
        listeners.forEach((track,sets)->counts.put(track,new double[]{sets.get(0).size(),sets.get(1).size()}));
        return new TrendStats(counts,new double[]{active.get(0).size(),active.get(1).size()});
    }

    public List<Campaign> campaigns(Instant now, boolean all) {
        if(!all&&!runtime.flags().campaigns()) return List.of();
        Criteria criteria = Criteria.where("kind").is("CAMPAIGN");
        if (!all) criteria = criteria.and("status").is("ACTIVE").and("starts_at_utc").lte(Date.from(now))
            .and("ends_at_utc").gt(Date.from(now));
        return mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, all ? "updated_at" : "priority"))
            .limit(100), Document.class, RecommendationPersistence.STATE).stream().map(d ->
                new Campaign(d.getString("campaign_id"), d.getString("name"), ((Number)d.get("revision")).longValue(),
                    instant(d.get("starts_at_utc")), instant(d.get("ends_at_utc")), ((Number)d.get("priority")).intValue(),
                    d.getList("genres", String.class, List.of()), d.getList("targets", Document.class, List.of()).stream()
                        .map(t -> new Target(EntityType.valueOf(t.getString("entity_type")), t.getString("entity_id"))).toList(),
                    "ACTIVE".equals(d.getString("status")), d.getString("timezone"))).toList();
    }

    public void saveDecision(long userId, Home home) {
        List<Document> items = new ArrayList<>();
        for (Section section : home.sections()) {
            for (int position = 0; position < section.items().size(); position++) {
                Item item = section.items().get(position);
                items.add(new Document("item_id", item.itemId()).append("section_id", section.id()).append("position", position)
                    .append("entity_type", item.entityType().name()).append("entity_id", item.entityId())
                    .append("entity_version", item.versionId()).append("score", item.score()).append("reason_code", item.reasonCode()));
            }
        }
        // One atomic document embeds its bounded list of items instead of request/item tables.
        mongo.insert(new Document("_id", "decision:" + home.requestId()).append("kind", "DECISION")
            .append("request_id", home.requestId()).append("user_id", userId).append("model_version", home.modelVersion())
            .append("policy_version", home.policyVersion()).append("items", items)
            .append("occurred_at", Date.from(home.generatedAt())).append("expires_at", Date.from(home.generatedAt().plus(Duration.ofDays(7)))),
            RecommendationPersistence.EVENTS);
    }

    public boolean itemBelongsToUser(TokenClaims claims, long userId) {
        return mongo.exists(Query.query(Criteria.where("_id").is("decision:" + claims.requestId())
            .and("kind").is("DECISION").and("user_id").is(userId)
            .and("items").elemMatch(Criteria.where("item_id").is(claims.itemId())
                .and("entity_type").is(claims.entityType().name()).and("entity_id").is(claims.entityId()))),
            RecommendationPersistence.EVENTS);
    }

    public boolean event(long userId, String eventId, String type, TokenClaims claims, Instant occurredAt,
                         String playbackSessionId, Long historyId, Map<String, Object> payload) {
        try {
            mongo.getCollection(RecommendationPersistence.EVENTS).withTimeout(eventTimeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS)
                .insertOne(new Document("_id", "event:" + eventId).append("kind", "EVENT").append("event_id", eventId)
                .append("user_id", userId).append("event_type", type).append("recommendation_item_id", claims.itemId())
                .append("entity_type", claims.trackId() == null ? claims.entityType().name() : "TRACK")
                .append("entity_id", claims.trackId() == null ? claims.entityId() : claims.trackId())
                .append("entity_version", claims.versionId()).append("playback_session_id", playbackSessionId)
                .append("listening_history_id", historyId)
                .append("parent_playlist_type", claims.entityType() == EntityType.TRACK ? null : claims.entityType().name())
                .append("parent_playlist_id", claims.entityType() == EntityType.TRACK ? null : claims.entityId())
                .append("parent_playlist_version", claims.versionId()).append("queue_position", claims.position())
                .append("occurred_at", Date.from(occurredAt)).append("received_at", new Date()).append("payload", new Document(payload))
                .append("provenance", "FIRST_PARTY").append("expires_at", Date.from(Instant.now().plus(Duration.ofDays(30)))));
            return true;
        } catch (com.mongodb.MongoWriteException failure) {
            if(failure.getError().getCode()==11000) return false;
            throw failure;
        }
    }

    public void feedback(long userId, Feedback value) {
        String id = "feedback:" + UUID.nameUUIDFromBytes((userId + ":" + value.entityType() + ":" + value.entityId() + ":" + value.feedbackType())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Query query = Query.query(Criteria.where("_id").is(id).and("kind").is("FEEDBACK").and("user_id").is(userId));
        if (value.enabled()) {
            mongo.upsert(query, new Update().set("entity_type", value.entityType().name()).set("entity_id", value.entityId())
                .set("feedback_type", value.feedbackType().name()).setOnInsert("created_at", new Date()).set("updated_at", new Date()),
                RecommendationPersistence.STATE);
        } else mongo.remove(query, RecommendationPersistence.STATE);
    }

    public String saveCampaign(long userId, String id, CampaignRequest value) {
        boolean create = id == null;
        if (create) id = UUID.randomUUID().toString();
        List<Document> targets = value.targets().stream().map(t -> new Document("entity_type", t.entityType().name())
            .append("entity_id", t.entityId())).toList();
        Document document = new Document("_id", "campaign:" + id).append("kind", "CAMPAIGN").append("campaign_id", id)
            .append("name", value.name()).append("status", value.active() ? "ACTIVE" : "DRAFT")
            .append("revision", create ? 1L : value.expectedRevision() + 1).append("timezone", value.timezone())
            .append("starts_at_utc", Date.from(value.startsAt())).append("ends_at_utc", Date.from(value.endsAt()))
            .append("priority", value.priority()).append("genres", value.genres() == null ? List.of() : value.genres())
            .append("targets", targets).append("updated_by", userId).append("updated_at", new Date());
        if (create) mongo.insert(document, RecommendationPersistence.STATE);
        else {
            var replaced = mongo.getCollection(RecommendationPersistence.STATE).replaceOne(
                new Document("_id", "campaign:" + id).append("kind", "CAMPAIGN").append("revision", value.expectedRevision()), document);
            if (replaced.getMatchedCount() != 1) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Campaign was changed or not found");
        }
        return id;
    }
}
