package com.laphuth.moodify.recommendation;

import static com.laphuth.moodify.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationService {
    private final RecommendationCatalog catalog;
    private final RecommendationContextService contexts;
    private final RecommendationStore store;
    private final RecommendationEngine engine;
    private final PersonalizedMixService mixes;
    private final RecommendationTokens tokens;
    private final RecommendationPreferenceService preferences;
    private final PreferenceEntityResolver identities;
    private final RecommendationRuntime runtime;

    public RecommendationService(RecommendationCatalog catalog, RecommendationContextService contexts,
        RecommendationStore store, RecommendationEngine engine, PersonalizedMixService mixes,
        RecommendationTokens tokens,RecommendationPreferenceService preferences,PreferenceEntityResolver identities,
        RecommendationRuntime runtime) {
        this.catalog = catalog; this.contexts = contexts; this.store = store; this.engine = engine;
        this.mixes = mixes; this.tokens = tokens;this.preferences=preferences;this.identities=identities;
        this.runtime=runtime;
    }

    public PreferenceContext preferenceSummary(User user) { return contexts.load(user).profile().preferences(); }

    public Home home(User user) { return home(user,null); }
    public Home home(User user,String genre) {
        runtime.requireGeneration();
        var original=contexts.load(user);
        var context = contexts.scoped(original,genre);
        String requestId = UUID.randomUUID().toString();
        Instant expires = context.now().plusSeconds(1800);
        List<Section> sections = new ArrayList<>();
        List<Item> mixItems = new ArrayList<>();
        for (MixType type : MixType.values()) {
            if (type == MixType.TREND_FOR_YOU && original.ranked().stream().noneMatch(r -> "TREND".equals(r.source()))) continue;
            Mix mix = mixes.ensure(original, type, false);
            if (mix.trackCount() > 0 && !context.blocked().getOrDefault("PERSONALIZED_PLAYLIST", Set.of()).contains(mix.id())) {
                mixItems.add(item(user.getId(), requestId, expires, EntityType.PERSONALIZED_PLAYLIST,
                    mix.id(), mix.versionId(), mix.name(), mix.description(), mix.imageUrl(), mix.trackCount(),
                    "PERSONAL_MIX", !context.profile().personalized() ? "Bản phối khám phá để bắt đầu tìm gu của bạn" :
                        "Bản phối riêng dựa trên lịch sử nghe và thư viện của bạn", 1, null));
            }
        }
        sections.add(new Section("personal_mixes", "Bản phối của bạn", "Cập nhật theo lịch, giữ ổn định khi bạn đang nghe.", mixItems));
        sections.add(new Section("for_you", "Dành cho bạn", !context.profile().personalized() ?
            "Bắt đầu nghe và lưu bài để gợi ý ngày càng phù hợp." : "Từ những giai điệu bạn nghe và lưu gần đây.",
            trackItems(context, requestId, expires, engine.diversify(context.ranked(), 8, 2))));
        sections.add(new Section("playlists_for_you", "Playlist hợp gu", "Khám phá những tuyển tập công khai phù hợp.",
            playlistItems(context, requestId, expires, 8)));
        List<RankedTrack> trending = context.ranked().stream().filter(r -> "TREND".equals(r.source())).toList();
        if (!trending.isEmpty()) sections.add(new Section("trending", "Đang được nghe nhiều hơn", "Tăng trưởng người nghe trong 24 giờ, có kiểm tra quy mô mẫu.",
            trackItems(context, requestId, expires, engine.diversify(trending, 8, 2))));
        List<RankedTrack> campaign = context.ranked().stream().filter(r -> "CAMPAIGN".equals(r.source())).toList();
        if (!campaign.isEmpty()) sections.add(new Section("campaigns", "Tuyển chọn đang diễn ra", "Nội dung theo những đợt tuyển chọn còn hiệu lực.",
            trackItems(context, requestId, expires, engine.diversify(campaign, 8, 2))));
        return persist(context, requestId, expires, sections);
    }

    public Home playlists(User user) {
        runtime.requireGeneration();
        var context = contexts.load(user);
        String requestId = UUID.randomUUID().toString(); Instant expires = context.now().plusSeconds(1800);
        return persist(context, requestId, expires, List.of(new Section("playlists_for_you", "Playlist hợp gu", "", playlistItems(context, requestId, expires, 30))));
    }

    public Home continuation(User user, String playlistId, String seedTrackId) {
        runtime.requireGeneration();
        var context = contexts.load(user);
        List<Track> seeds;
        if (playlistId != null) seeds = context.catalog().playlistTracks(catalog.accessiblePlaylist(playlistId, user));
        else {
            Track seed = context.catalog().resolve(seedTrackId);
            if (seed == null) throw RecommendationCatalog.notFound();
            seeds = List.of(seed);
        }
        Set<String> existing = seeds.stream().map(Track::getId).collect(Collectors.toSet());
        List<RankedTrack> ranked = context.ranked().stream().filter(r -> !existing.contains(r.track().getId()))
            .map(r -> new RankedTrack(r.track(), 0.35 * r.score() + 0.65 * seeds.stream()
                .mapToDouble(seed -> engine.similarity(r.track(), seed)).max().orElse(0), "CONTINUATION", "CONTINUATION",
                "Gần với nội dung bạn đang nghe"))
            .sorted(Comparator.comparingDouble(RankedTrack::score).reversed().thenComparing(r -> r.track().getId())).toList();
        String requestId = UUID.randomUUID().toString(); Instant expires = context.now().plusSeconds(1800);
        return persist(context, requestId, expires, List.of(new Section("continuation", "Gợi ý nghe tiếp", "Chỉ thêm vào playlist khi bạn chủ động chọn.",
            trackItems(context, requestId, expires, engine.diversify(ranked, 20, 3)))));
    }

    private Home persist(RecommendationContextService.Context context, String requestId, Instant expires, List<Section> sections) {
        Home home = new Home(requestId, context.now(), expires, context.profile().modelVersion(), "hybrid-slate-v1.2",
            !context.profile().personalized(), sections);
        store.saveDecision(context.user().getId(), home);
        if(sections.stream().allMatch(s->s.items().isEmpty()))runtime.count("recommendation.empty");
        return home;
    }

    private List<Item> trackItems(RecommendationContextService.Context context, String requestId, Instant expires, List<RankedTrack> tracks) {
        return tracks.stream().map(r -> item(context.user().getId(), requestId, expires, EntityType.TRACK,
            r.track().getId(), null, r.track().getName(), r.track().getArtistName(),
            TrackResponse.fromSummary(r.track()).imageUrl(), 1, r.reasonCode(), r.reason(), r.score(), TrackResponse.fromSummary(r.track()))).toList();
    }

    private List<Item> playlistItems(RecommendationContextService.Context context, String requestId, Instant expires, int limit) {
        Map<String, Double> trackScores = context.ranked().stream().collect(Collectors.toMap(r -> r.track().getId(), RankedTrack::score));
        record Candidate(Playlist playlist, List<Track> tracks, double score) {}
        List<Candidate> ranked = new ArrayList<>();
        for (Playlist playlist : catalog.publicPlaylists()) {
            if (context.blocked().getOrDefault("PLAYLIST", Set.of()).contains(playlist.getId())) continue;
            List<Track> available = context.catalog().playlistTracks(playlist).stream()
                .filter(t -> trackScores.containsKey(t.getId())).toList();
            if (available.isEmpty()) continue;
            double score = available.stream().mapToDouble(t -> trackScores.getOrDefault(t.getId(), 0.0)).average().orElse(0);
            score += 0.1 * engine.campaignFit(EntityType.PLAYLIST, playlist.getId(),
                available.stream().flatMap(t -> t.getGenres() == null ? java.util.stream.Stream.empty() : t.getGenres().stream()).distinct().toList(), context.campaigns());
            double availability = available.size() / (double)Math.max(1, playlist.getTrackSpotifyIds().size());
            ranked.add(new Candidate(playlist, available, score * availability));
        }
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(c -> c.playlist().getId()));
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            Set<String> ids = candidate.tracks().stream().map(Track::getId).collect(Collectors.toSet());
            boolean duplicate = selected.stream().anyMatch(prior -> {
                Set<String> overlap = prior.tracks().stream().map(Track::getId).collect(Collectors.toSet());
                Set<String> union = new HashSet<>(overlap); union.addAll(ids); overlap.retainAll(ids);
                return overlap.size() / (double)union.size() > 0.85;
            });
            if (!duplicate) selected.add(candidate);
            if (selected.size() == limit) break;
        }
        return selected.stream().map(c -> item(context.user().getId(), requestId, expires, EntityType.PLAYLIST,
            c.playlist().getId(), c.playlist().getUpdatedAt() == null ? null : c.playlist().getUpdatedAt().toString(),
            c.playlist().getName(), c.playlist().getDescription(),
            c.playlist().getCoverUrl() == null ? TrackResponse.fromSummary(c.tracks().getFirst()).imageUrl() : c.playlist().getCoverUrl(),
            c.tracks().size(), "PLAYLIST_CONTENT", "Nội dung gần với những bài bạn quan tâm", c.score(), null)).toList();
    }

    private Item item(long userId, String requestId, Instant expires, EntityType type, String id, String version,
                      String title, String subtitle, String image, int count, String code, String reason, double score, TrackResponse track) {
        String itemId = UUID.randomUUID().toString();
        String token = tokens.issue(new TokenClaims(userId, "ITEM", itemId, requestId, type, id, version, null, null, expires.getEpochSecond()));
        return new Item(itemId, type, id, version, title, subtitle, image, count, code, reason, score, token, track);
    }

    public RecommendationTypes.Queue queue(User user, QueueRequest request) {
        store.requireReady();
        TokenClaims item = null;
        if (request.attributionToken() != null && !request.attributionToken().isBlank()) {
            item = tokens.verify(request.attributionToken(), user.getId(), "ITEM");
            if (item.entityType() != request.entityType() || !item.entityId().equals(request.entityId()) ||
                !Objects.equals(item.versionId(), request.versionId()) || !store.itemBelongsToUser(item, user.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recommendation does not match requested content");
            }
        }
        var snapshot = catalog.snapshot();
        List<Track> tracks; String version = request.versionId();
        switch(request.entityType()) {
            case TRACK -> {
                Track track = snapshot.resolve(request.entityId());
                if (track == null) throw RecommendationCatalog.notFound();
                tracks = List.of(track);
            }
            case PLAYLIST -> {
                Playlist playlist = catalog.accessiblePlaylist(request.entityId(), user);
                tracks = snapshot.playlistTracks(playlist);
                version = playlist.getUpdatedAt() == null ? null : playlist.getUpdatedAt().toString();
            }
            case PERSONALIZED_PLAYLIST -> {
                Mix mix = mixes.read(user, request.entityId(), version);
                version = mix.versionId();
                tracks = mixes.queueTracks(user, request.entityId(), version, snapshot);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported queue content");
        }
        Map<String, Set<String>> blocked = store.blocked(user.getId());
        tracks = tracks.stream().filter(t -> !blocked.getOrDefault("TRACK", Set.of()).contains(t.getId()) &&
            (t.getArtistSpotifyId() == null || !blocked.getOrDefault("ARTIST", Set.of()).contains(t.getArtistSpotifyId()))).toList();
        String queueId = UUID.randomUUID().toString(); Instant expires = Instant.now().plus(Duration.ofHours(12));
        List<QueueTrack> queue = new ArrayList<>();
        for (int position = 0; position < tracks.size(); position++) {
            Track track = tracks.get(position);
            String token = tokens.issue(new TokenClaims(user.getId(), "QUEUE", item == null ? null : item.itemId(),
                item == null ? null : item.requestId(), request.entityType(), request.entityId(), version,
                track.getId(), position, expires.getEpochSecond()));
            queue.add(new QueueTrack(TrackResponse.fromSummary(track), token, position));
        }
        return new RecommendationTypes.Queue(queueId, request.entityType(), request.entityId(), version, queue, expires);
    }

    public int events(User user, EventBatch batch) {
        store.requireReady(); int inserted = 0;
        // Validate the entire batch before accepting any event.
        List<TokenClaims> claims = batch.events().stream().map(event -> {
            TokenClaims claim = tokens.verify(event.attributionToken(), user.getId(), "ITEM");
            if (!store.itemBelongsToUser(claim, user.getId())) throw RecommendationCatalog.notFound();
            Instant now = Instant.now();
            if (event.occurredAt().isAfter(now.plusSeconds(120)) || event.occurredAt().isBefore(now.minus(Duration.ofDays(1)))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event timestamp is outside the accepted window");
            }
            if (event.eventType() == EventType.SAVE_COPY || event.eventType() == EventType.PLAY_START) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playback and save outcomes are recorded by the server");
            }
            return claim;
        }).toList();
        for (int i = 0; i < batch.events().size(); i++) {
            Event event = batch.events().get(i);
            if (store.event(user.getId(), event.eventId().toString(), event.eventType().name(), claims.get(i), event.occurredAt(), null, null, Map.of())) inserted++;
        }
        return inserted;
    }

    public void feedback(User user, Feedback feedback) {
        store.requireReady();
        if(feedback.feedbackType()==FeedbackType.FOLLOW) {
            if(feedback.entityType()!=EntityType.ARTIST)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"FOLLOW requires an artist");
            preferences.setFollowed(user,feedback.entityId(),feedback.enabled());
            return; // Preference fingerprint rebuilds current mixes; mutation remains idempotent.
        }
        if(feedback.entityType()==EntityType.ARTIST)feedback=new Feedback(EntityType.ARTIST,identities.artist(feedback.entityId()).id(),feedback.feedbackType(),feedback.enabled());
        if (feedback.entityType() == EntityType.PERSONALIZED_PLAYLIST) mixes.read(user, feedback.entityId(), null);
        else if (feedback.entityType() == EntityType.PLAYLIST) catalog.accessiblePlaylist(feedback.entityId(), user);
        else if (feedback.entityType() == EntityType.TRACK) {
            var track=catalog.snapshot().resolve(feedback.entityId());if(track==null)throw RecommendationCatalog.notFound();
            feedback=new Feedback(EntityType.TRACK,track.getId(),feedback.feedbackType(),feedback.enabled());
        }
        store.feedback(user.getId(), feedback);
        mixes.invalidate(user.getId());
    }

    public void forgetHistory(long userId) {
        store.forgetProfile(userId);
        mixes.invalidate(userId);
    }

    public PlaybackContext playbackContext(User user, String queueToken, String trackId, String requestedSource,
                                           String requestedSourceId, String deviceType) {
        String source = Set.of("HOME","SEARCH","PLAYLIST","ALBUM","ARTIST","LIBRARY","FAVORITES","EMOTION","OTHER")
            .contains(Objects.toString(requestedSource, "")) ? requestedSource : "OTHER";
        String sourceId = requestedSourceId;
        TokenClaims verifiedClaims = null;
        if (queueToken != null && !queueToken.isBlank()) {
            TokenClaims claims = tokens.verify(queueToken, user.getId(), "QUEUE");
            verifiedClaims = claims;
            var track = catalog.playableTrack(trackId);
            if (!Objects.equals(claims.trackId(), track.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Queue context does not match the track");
            }
            Map<String,Set<String>> blocked=store.blocked(user.getId());
            if(blocked.getOrDefault("TRACK",Set.of()).contains(track.getId()) ||
                (track.getArtistSpotifyId()!=null&&blocked.getOrDefault("ARTIST",Set.of()).contains(track.getArtistSpotifyId())) ||
                blocked.getOrDefault(claims.entityType().name(),Set.of()).contains(claims.entityId()))
                throw RecommendationCatalog.notFound();
            if (claims.entityType() == EntityType.PLAYLIST) catalog.accessiblePlaylist(claims.entityId(), user);
            if (claims.entityType() == EntityType.PERSONALIZED_PLAYLIST) mixes.owned(user.getId(), claims.entityId());
            source = claims.entityType() == EntityType.TRACK ? "HOME" : "PLAYLIST";
            sourceId = claims.entityType() == EntityType.TRACK ? null : claims.entityId();
        }
        String device = Set.of("WEB","ANDROID","IOS","OTHER").contains(Objects.toString(deviceType,"")) ? deviceType : "WEB";
        return new PlaybackContext(source, sourceId != null && sourceId.length() <= 64 ? sourceId : null, device, queueToken, verifiedClaims);
    }
}
