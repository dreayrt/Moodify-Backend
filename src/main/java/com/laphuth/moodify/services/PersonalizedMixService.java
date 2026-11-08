package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.RecommendationPersistence;
import com.laphuth.moodify.repositories.RecommendationStore;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.dto.playlist.PlaylistResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.repositories.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonalizedMixService {
    static final String MIXES = RecommendationPersistence.STATE;
    static final String VERSIONS = RecommendationPersistence.STATE;
    private final MongoTemplate mongo;
    private final RecommendationContextService contexts;
    private final RecommendationCatalog catalog;
    private final RecommendationEngine engine;
    private final RecommendationStore store;
    private final UserRepository users;
    private final RecommendationRuntime runtime;

    public PersonalizedMixService(MongoTemplate mongo, RecommendationContextService contexts,
        RecommendationCatalog catalog, RecommendationEngine engine, RecommendationStore store, UserRepository users,
        RecommendationRuntime runtime) {
        this.mongo = mongo; this.contexts = contexts; this.catalog = catalog;
        this.engine = engine; this.store = store; this.users = users;
        this.runtime=runtime;
    }

    public List<Mix> list(User user) {
        runtime.requireGeneration();
        var context = contexts.load(user);
        return Arrays.stream(MixType.values()).filter(type -> type != MixType.TREND_FOR_YOU ||
            context.ranked().stream().anyMatch(r -> "TREND".equals(r.source()))).map(type -> ensure(context, type, false)).toList();
    }

    public Mix ensure(RecommendationContextService.Context context, MixType type, boolean force) {
        runtime.requireGeneration();
        if(!context.runtimeKey().equals(runtime.rankingKey()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Recommendation controls changed; retry");
        store.requireReady();
        long userId = context.user().getId();
        String id = stableId(userId, type);
        Document current = mongo.findById(id, Document.class, MIXES);
        Instant now = context.now();
        if (current != null) {
            Document version = versionDocument(userId, id, current.getString("active_version_id"));
            if (force && now.isBefore(RecommendationStore.instant(version.get("generated_at")).plusSeconds(300))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 5 minutes before refreshing this mix");
            }
            Set<String> activeCampaigns = context.campaigns().stream().map(c -> c.id() + ":" + c.revision())
                .collect(java.util.stream.Collectors.toSet());
            Set<String> versionCampaigns = new HashSet<>(version.getList("campaign_revisions", String.class, List.of()));
            if (!force && RecommendationStore.instant(current.get("refresh_due_at")).isAfter(now) &&
                activeCampaigns.equals(versionCampaigns)&&Objects.equals(version.getString("preference_fingerprint"),context.profile().preferences().fingerprint())&&
                Objects.equals(version.getString("runtime_key"),context.runtimeKey())) {
                return response(current, version, context.catalog());
            }
        }
        String oldVersion = current == null ? null : current.getString("active_version_id");
        Set<String> oldTracks = new HashSet<>();
        if (oldVersion != null) oldTracks.addAll(trackIds(versionDocument(userId, id, oldVersion)));
        List<RankedTrack> selected = engine.mix(context.ranked(), context.profile(), type, 30, oldTracks);
        String campaignKey = context.campaigns().stream().map(c -> c.id() + ":" + c.revision()).sorted().collect(java.util.stream.Collectors.joining(","));
        String generationKey = (force ? "manual:" + now.getEpochSecond() / 300 : periodKey(type, now)) + ":" +
            UUID.nameUUIDFromBytes((context.runtimeKey()+":"+campaignKey + ":" + context.profile().preferences().fingerprint() + ":" + (current == null ? "" : current.getString("rebuild_nonce"))).getBytes(StandardCharsets.UTF_8));
        String versionId = UUID.nameUUIDFromBytes((id + ":" + generationKey).getBytes(StandardCharsets.UTF_8)).toString();
        Instant nextRefresh = nextRefresh(type, now);
        for (Campaign activeCampaign : context.campaigns()) {
            if (activeCampaign.endsAt().isBefore(nextRefresh)) nextRefresh = activeCampaign.endsAt();
        }
        Document version = new Document("_id", versionId).append("kind", "MIX_VERSION").append("playlist_id", id).append("user_id", userId)
            .append("generation_key", generationKey).append("status", "PUBLISHED")
            .append("model_version", context.profile().modelVersion()).append("policy_version", "mix-v1.2").append("runtime_key",context.runtimeKey())
            .append("feature_snapshot_id", now.toString()).append("preference_fingerprint",context.profile().preferences().fingerprint())
            .append("campaign_revisions", context.campaigns().stream().map(c -> c.id() + ":" + c.revision()).toList())
            .append("tracks", selected.stream().map(r -> new Document("canonical_track_id", r.track().getId())
                .append("primary_source", r.source()).append("reason_code", r.reasonCode()).append("rank_score", r.score())).toList())
            .append("generated_at", Date.from(now)).append("recommendation_valid_until", Date.from(nextRefresh))
            .append("target_track_count", 30).append("actual_track_count", selected.size());
        try { mongo.insert(version, VERSIONS); }
        catch (DuplicateKeyException ignored) { version = versionDocument(userId, id, versionId); }

        Document stable = new Document("_id", id).append("kind", "MIX").append("user_id", userId).append("mix_type", type.name())
            .append("name", name(type)).append("description", description(type))
            .append("active_version_id", versionId).append("previous_version_id", oldVersion)
            .append("refresh_due_at", Date.from(nextRefresh)).append("updated_at", Date.from(now));
        if (current == null) {
            stable.append("created_at", Date.from(now));
            try { mongo.insert(stable, MIXES); }
            catch (DuplicateKeyException ignored) { stable = owned(userId, id); version = versionDocument(userId, id, stable.getString("active_version_id")); }
        } else {
            var changed = mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("kind").is("MIX").and("user_id").is(userId)
                .and("active_version_id").is(oldVersion).and("rebuild_nonce").is(current.get("rebuild_nonce"))), new Update().set("active_version_id", versionId)
                .set("previous_version_id", oldVersion).set("refresh_due_at", Date.from(nextRefresh))
                .set("updated_at", Date.from(now)), MIXES);
            if (changed.getMatchedCount() == 0) {
                stable = owned(userId, id); version = versionDocument(userId, id, stable.getString("active_version_id"));
            }
        }
        // Keep active snapshots; superseded or concurrently unpublished versions expire after seven days.
        String activeVersion = stable.getString("active_version_id");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(activeVersion).and("kind").is("MIX_VERSION")),
            new Update().unset("expires_at"), VERSIONS);
        if (oldVersion != null && !oldVersion.equals(activeVersion)) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(oldVersion).and("kind").is("MIX_VERSION")),
                new Update().set("expires_at", Date.from(now.plus(Duration.ofDays(7)))), VERSIONS);
        }
        if (!versionId.equals(activeVersion)) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(versionId).and("kind").is("MIX_VERSION")),
                new Update().set("expires_at", Date.from(now.plus(Duration.ofDays(7)))), VERSIONS);
        }
        return response(stable, version, context.catalog());
    }

    public Mix read(User user, String id, String versionId) {
        store.requireReady();
        Document stable = owned(user.getId(), id);
        if (versionId == null && runtime.flags().enabled()) {
            return ensure(contexts.load(user), MixType.valueOf(stable.getString("mix_type")), false);
        }
        return response(stable, versionDocument(user.getId(), id,
            versionId == null ? stable.getString("active_version_id") : versionId), catalog.snapshot());
    }

    public Mix refresh(User user, String id) {
        runtime.requireGeneration();
        Document stable = owned(user.getId(), id);
        return ensure(contexts.load(user), MixType.valueOf(stable.getString("mix_type")), true);
    }

    public List<Track> queueTracks(User user, String id, String versionId, RecommendationCatalog.Snapshot snapshot) {
        Document stable = owned(user.getId(), id);
        String actualVersion = versionId == null ? stable.getString("active_version_id") : versionId;
        Document version = versionDocument(user.getId(), id, actualVersion);
        // A superseded version remains readable for audit, but a new playback request resolves expired recommendations.
        if (!actualVersion.equals(stable.getString("active_version_id")) &&
            RecommendationStore.instant(version.get("recommendation_valid_until")).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This mix changed. Open the current version before playing.");
        }
        return trackIds(version).stream().map(snapshot::resolve).filter(Objects::nonNull).toList();
    }

    public PlaylistResponse copy(User user, String id, CopyRequest request) {
        Mix mix = read(user, id, request.versionId());
        String copyId = UUID.nameUUIDFromBytes((user.getId() + ":mix-copy:" + request.requestId()).getBytes(StandardCharsets.UTF_8)).toString();
        Document existing = mongo.findById(copyId, Document.class, "playlists");
        if (existing != null && (!Objects.equals(existing.get("generated_source_id"), id) ||
            !Objects.equals(existing.get("generated_source_version"), request.versionId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Copy request was already used for a different version");
        }
        if (existing == null) {
            Document playlist = new Document("_id", copyId).append("name", mix.name())
                .append("description", "Bản sao đã lưu từ bản phối cá nhân của bạn")
                .append("cover_url", mix.imageUrl()).append("username", user.getUsername()).append("owner_user_id", user.getId())
                .append("is_public", false).append("revision", 1L).append("source_kind", "USER")
                .append("track_spotify_ids", mix.tracks().stream().map(t -> t.spotifyId() == null ? t.id() : t.spotifyId()).toList())
                .append("generated_source_id", id).append("generated_source_version", mix.versionId())
                .append("created_at", Date.from(Instant.now())).append("updated_at", Date.from(Instant.now()));
            try { mongo.insert(playlist, "playlists"); }
            catch (DuplicateKeyException ignored) { /* deterministic ID makes retries idempotent */ }
        }
        Playlist saved = mongo.findById(copyId, Playlist.class);
        if (saved == null || !RecommendationCatalog.owns(saved, user)) throw RecommendationCatalog.notFound();
        String eventId = UUID.nameUUIDFromBytes((user.getId() + ":save-copy:" + request.requestId()).getBytes(StandardCharsets.UTF_8)).toString();
        store.event(user.getId(), eventId, "SAVE_COPY", new TokenClaims(user.getId(), "SERVER", null, null,
            EntityType.PERSONALIZED_PLAYLIST, id, mix.versionId(), null, null, 0), Instant.now(), null, null,
            Map.of("saved_playlist_id", copyId));
        return PlaylistResponse.from(saved, catalog.snapshot().playlistTracks(saved).stream().map(TrackResponse::fromSummary).toList());
    }

    Document owned(long userId, String id) {
        Document stable = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("kind").is("MIX").and("user_id").is(userId)), Document.class, MIXES);
        if (stable == null) throw RecommendationCatalog.notFound();
        return stable;
    }

    Document versionDocument(long userId, String id, String versionId) {
        Document version = mongo.findOne(Query.query(Criteria.where("_id").is(versionId).and("kind").is("MIX_VERSION")
            .and("playlist_id").is(id).and("user_id").is(userId)), Document.class, VERSIONS);
        if (version == null) throw RecommendationCatalog.notFound();
        return version;
    }

    private List<String> trackIds(Document version) {
        return version.getList("tracks", Document.class, List.of()).stream().map(d -> d.getString("canonical_track_id")).toList();
    }

    private Mix response(Document stable, Document version, RecommendationCatalog.Snapshot snapshot) {
        Map<String, Set<String>> blocked = store.blocked(((Number) stable.get("user_id")).longValue());
        List<TrackResponse> tracks = trackIds(version).stream().map(snapshot::resolve).filter(Objects::nonNull)
            .filter(track -> !blocked.getOrDefault("TRACK", Set.of()).contains(track.getId()) &&
                (track.getArtistSpotifyId() == null || !blocked.getOrDefault("ARTIST", Set.of()).contains(track.getArtistSpotifyId())))
            .map(TrackResponse::fromSummary).toList();
        return new Mix(stable.getString("_id"), MixType.valueOf(stable.getString("mix_type")),
            stable.getString("name"), stable.getString("description"), tracks.isEmpty() ? null : tracks.getFirst().imageUrl(),
            version.getString("_id"), RecommendationStore.instant(version.get("generated_at")),
            RecommendationStore.instant(stable.get("refresh_due_at")), tracks.size(), tracks, version.getString("model_version"));
    }

    public void invalidate(long userId) {
        mongo.updateMulti(Query.query(Criteria.where("kind").is("MIX").and("user_id").is(userId)), new Update()
            .set("refresh_due_at", new Date(0)).set("rebuild_nonce", UUID.randomUUID().toString()), MIXES);
    }

    public static String stableId(long userId, MixType type) {
        return UUID.nameUUIDFromBytes(("moodify:mix:" + userId + ":" + type).getBytes(StandardCharsets.UTF_8)).toString();
    }
    static String periodKey(MixType type, Instant now) {
        ZonedDateTime local = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        return type == MixType.WEEKLY_DISCOVERY ? local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString() :
            type == MixType.TREND_FOR_YOU ? "trend:" + now.getEpochSecond() / 21_600 : local.toLocalDate().toString();
    }
    static Instant nextRefresh(MixType type, Instant now) {
        ZonedDateTime local = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        return type == MixType.WEEKLY_DISCOVERY ? local.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            .atStartOfDay(local.getZone()).toInstant() : type == MixType.TREND_FOR_YOU ?
            Instant.ofEpochSecond((now.getEpochSecond() / 21_600 + 1) * 21_600) : local.toLocalDate().plusDays(1).atStartOfDay(local.getZone()).toInstant();
    }
    private static String name(MixType type) {
        return switch(type) { case DAILY_MIX -> "Bản phối hôm nay"; case WEEKLY_DISCOVERY -> "Khám phá tuần này"; case TREND_FOR_YOU -> "Xu hướng hợp gu"; };
    }
    private static String description(MixType type) {
        return switch(type) { case DAILY_MIX -> "Những giai điệu gần gu, xen kẽ khám phá mới.";
            case WEEKLY_DISCOVERY -> "Ưu tiên các bài bạn chưa từng nghe hoặc lưu.";
            case TREND_FOR_YOU -> "Ưu tiên xu hướng có đủ dữ liệu, kết hợp những bài phù hợp với bạn."; };
    }

    @Scheduled(fixedDelayString="${recommendations.mix-refresh-ms:900000}", initialDelayString="${recommendations.mix-refresh-ms:900000}")
    public void refreshDueMixes() {
        if(!runtime.flags().enabled())return;
        try {
            store.requireReady();
            List<Document> due = mongo.find(Query.query(Criteria.where("kind").is("MIX").and("refresh_due_at").lte(new Date())).limit(100), Document.class, MIXES);
            for (Document mix : due) {
                Object userId = mix.get("user_id");
                if (userId instanceof Number number) users.findById(number.longValue()).filter(u->u.getStatus()==com.laphuth.moodify.entities.enums.UserStatus.ACTIVE).ifPresent(user -> {
                    try { ensure(contexts.load(user), MixType.valueOf(mix.getString("mix_type")), false); }
                    catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Mix refresh failed for {}: {}", mix.getString("_id"), failure.getClass().getSimpleName()); }
                });
            }
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            // Recommendation storage is temporarily unavailable.
        }
    }
}
