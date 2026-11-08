package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.RecommendationStore;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.entities.User;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RecommendationContextService {
    private final RecommendationCatalog catalog;
    private final RecommendationStore store;
    private final RecommendationEngine engine;
    private final RecommendationPreferenceService preferences;
    private final RecommendationRuntime runtime;

    public RecommendationContextService(RecommendationCatalog catalog, RecommendationStore store, RecommendationEngine engine, RecommendationPreferenceService preferences,
        RecommendationRuntime runtime) {
        this.catalog = catalog; this.store = store; this.engine = engine; this.preferences=preferences;
        this.runtime=runtime;
    }

    public Context load(User user) {
        String runtimeKey=runtime.rankingKey();
        if(user.getStatus()!=com.laphuth.moodify.entities.enums.UserStatus.ACTIVE)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Account is not active");
        store.requireReady();
        Instant now = Instant.now();
        var snapshot = catalog.snapshot();
        List<Signal> signals = canonicalSignals(snapshot,store.signals(user.getId(),now));
        Map<String,Signal> saved=new HashMap<>();
        for(Signal signal:signals) if(signal.saved())saved.put(signal.trackId(),signal);
        for(var playlist:catalog.ownPlaylists(user)) {
            if(playlist.getGeneratedSourceId()!=null)continue;
            for(var track:snapshot.playlistTracks(playlist)) saved.putIfAbsent(track.getId(),
                new Signal(track.getId(),2.0,playlist.getUpdatedAt()==null?now:playlist.getUpdatedAt(),true,false));
        }
        signals.removeIf(Signal::saved);signals.addAll(saved.values());
        PreferenceContext preference=preferences.read(user,snapshot,signals,now);
        String fingerprint=RecommendationPreferenceService.fingerprint(List.of(preference.fingerprint()),
            saved.values().stream().map(s->s.trackId()+":"+s.weight()).toList());
        preference=new PreferenceContext(preference.followedArtists(),preference.albumTrackPriors(),preference.intentTracks(),
            preference.intentArtists(),fingerprint,preference.diagnostics());
        Profile base=engine.profile(snapshot,signals,store.collaborativeScores(user.getId(),now),store.modelVersion(user.getId(),now),now);
        Profile profile=new Profile(base.weights(),base.recentTrackIds(),base.collaborativeScores(),base.modelVersion(),preference,Set.of());
        var blocked = store.blocked(user.getId());
        var campaigns = store.campaigns(now, false);
        var trend=store.trendStats(now,snapshot.aliases());
        var ranked=engine.rank(snapshot,profile,trend.tracks(),trend.activeListeners(),campaigns,blocked);
        if(!runtimeKey.equals(runtime.rankingKey()))throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,"Recommendation controls changed; retry the request");
        return new Context(user,snapshot,profile,blocked,campaigns,ranked,now,trend,runtimeKey);
    }

    static List<Signal> canonicalSignals(RecommendationCatalog.Snapshot catalog,List<Signal> raw) {
        List<Signal> result=new ArrayList<>();Map<String,Signal> saved=new HashMap<>();
        for(Signal signal:raw) {
            String id=catalog.canonical(signal.trackId());
            Signal canonical=new Signal(id,signal.weight(),signal.at(),signal.saved(),signal.earlySkip());
            if(signal.saved()) saved.merge(id,canonical,(a,b)->a.at().isAfter(b.at())?a:b);
            else result.add(canonical);
        }
        result.addAll(saved.values());return result;
    }

    public Context scoped(Context original,String genre) {
        if(genre==null||genre.isBlank())return original;
        Set<String> requested=RecommendationEngine.genres(Arrays.asList(genre.split(",")));
        if(requested.contains("all"))return original;
        if(requested.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid genre context");
        Profile p=original.profile();Profile scoped=new Profile(p.weights(),p.recentTrackIds(),p.collaborativeScores(),p.modelVersion(),p.preferences(),requested);
        return new Context(original.user(),original.catalog(),scoped,original.blocked(),original.campaigns(),
            engine.rank(original.catalog(),scoped,original.trend().tracks(),original.trend().activeListeners(),original.campaigns(),original.blocked()),original.now(),original.trend(),original.runtimeKey());
    }

    public record Context(User user, RecommendationCatalog.Snapshot catalog, Profile profile,
                          Map<String, Set<String>> blocked, List<Campaign> campaigns,
                          List<RankedTrack> ranked, Instant now,RecommendationStore.TrendStats trend,String runtimeKey) {}
}
