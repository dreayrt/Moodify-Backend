package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.recommendation.RecommendationTypes;
import com.laphuth.moodify.repositories.RecommendationStore;
import com.laphuth.moodify.services.PersonalizedMixService;
import com.laphuth.moodify.services.RecommendationCatalog;
import com.laphuth.moodify.services.RecommendationOutcomeDispatcher;
import com.laphuth.moodify.services.RecommendationPreferenceService;
import com.laphuth.moodify.services.RecommendationRuntime;
import com.laphuth.moodify.services.RecommendationService;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.dto.playlist.PlaylistResponse;
import com.laphuth.moodify.entities.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class RecommendationApi {
    private final RecommendationService recommendations;
    private final RecommendationCatalog catalog;
    private final PersonalizedMixService mixes;
    private final RecommendationStore store;
    private final RecommendationPreferenceService preferences;
    private final RecommendationRuntime runtime;
    private final RecommendationOutcomeDispatcher outcomes;

    public RecommendationApi(RecommendationService recommendations, RecommendationCatalog catalog,
        PersonalizedMixService mixes, RecommendationStore store,RecommendationPreferenceService preferences,
        RecommendationRuntime runtime, RecommendationOutcomeDispatcher outcomes) {
        this.recommendations = recommendations; this.catalog = catalog; this.mixes = mixes; this.store = store;this.preferences=preferences;
        this.runtime=runtime;this.outcomes=outcomes;
    }
    private User user(Authentication auth) { return catalog.user(auth == null ? null : auth.getName()); }

    @GetMapping("/recommendations/home")
    public Home home(Authentication auth,@RequestParam(required=false) @Size(max=200) String genre) { return recommendations.home(user(auth),genre); }

    @GetMapping("/recommendations/playlists")
    public Home playlists(Authentication auth) { return recommendations.playlists(user(auth)); }

    @GetMapping("/recommendations/playlist-continuation")
    public Home continuation(Authentication auth, @RequestParam @Size(max=100) String playlistId) {
        return recommendations.continuation(user(auth), playlistId, null);
    }

    @GetMapping("/recommendations/next")
    public Home next(Authentication auth, @RequestParam @Size(max=100) String seedTrackId) {
        return recommendations.continuation(user(auth), null, seedTrackId);
    }

    @GetMapping("/me/personalized-playlists")
    public List<Mix> mixes(Authentication auth) { return mixes.list(user(auth)); }

    @GetMapping("/me/personalized-playlists/{id}")
    public Mix mix(Authentication auth, @PathVariable @Size(max=100) String id) {
        return mixes.read(user(auth), id, null);
    }

    @GetMapping("/me/personalized-playlists/{id}/versions/{versionId}")
    public Mix version(Authentication auth, @PathVariable @Size(max=100) String id,
                       @PathVariable @Size(max=100) String versionId) {
        return mixes.read(user(auth), id, versionId);
    }

    @PostMapping("/me/personalized-playlists/{id}/refresh")
    public Mix refresh(Authentication auth, @PathVariable @Size(max=100) String id) {
        return mixes.refresh(user(auth), id);
    }

    @PostMapping("/me/personalized-playlists/{id}/copies")
    public PlaylistResponse copy(Authentication auth, @PathVariable @Size(max=100) String id,
                                 @Valid @RequestBody CopyRequest request) {
        return mixes.copy(user(auth), id, request);
    }

    @PostMapping("/playback/queues")
    public RecommendationTypes.Queue queue(Authentication auth, @Valid @RequestBody QueueRequest request) {
        return recommendations.queue(user(auth), request);
    }

    @PostMapping("/recommendations/events")
    public Map<String, Integer> events(Authentication auth, @Valid @RequestBody EventBatch batch) {
        return Map.of("accepted", recommendations.events(user(auth), batch));
    }

    @PutMapping("/recommendations/feedback")
    public Map<String, Boolean> feedback(Authentication auth, @Valid @RequestBody Feedback feedback) {
        recommendations.feedback(user(auth), feedback);
        return Map.of("success", true);
    }

    public record PreferenceState(@jakarta.validation.constraints.NotNull Boolean enabled) {}

    @GetMapping("/me/followed-artists")
    public Set<String> followedArtists(Authentication auth) { return preferences.followedArtists(user(auth)); }

    @PutMapping("/me/followed-artists/{id}")
    public Map<String,Boolean> followArtist(Authentication auth,@PathVariable @Size(max=64) String id,
        @Valid @RequestBody PreferenceState state) {
        preferences.setFollowed(user(auth),id,state.enabled());
        return Map.of("followed",state.enabled());
    }

    @PutMapping("/me/favorite-albums/{id}")
    public Map<String,Boolean> favoriteAlbum(Authentication auth,@PathVariable @Size(max=80) String id,
        @Valid @RequestBody PreferenceState state) {
        preferences.setAlbumFavorite(user(auth),id,state.enabled());
        return Map.of("favorited",state.enabled());
    }

    @GetMapping("/me/recommendation-preferences")
    public PreferenceContext preferenceSummary(Authentication auth) { return recommendations.preferenceSummary(user(auth)); }

    @GetMapping("/admin/recommendations/campaigns")
    public List<Campaign> campaigns(Authentication auth) {
        user(auth); store.requireReady(); return store.campaigns(Instant.now(), true);
    }

    @GetMapping("/admin/recommendations/readiness")
    public Readiness readiness(Authentication auth) {
        user(auth); return catalog.readiness();
    }

    public record Controls(@jakarta.validation.constraints.NotNull Boolean enabled,
        @jakarta.validation.constraints.NotNull Boolean collaborative,
        @jakarta.validation.constraints.NotNull Boolean campaigns,
        @jakarta.validation.constraints.Min(1) long expectedRevision) {}

    @GetMapping("/admin/recommendations/operations")
    public Map<String,Object> operations(Authentication auth) {
        user(auth);return Map.of("runtime",runtime.snapshot(),"outcomeQueue",outcomes.status());
    }
    @PutMapping("/admin/recommendations/controls")
    public RecommendationRuntime.Flags controls(Authentication auth,@Valid @RequestBody Controls value) {
        user(auth);return runtime.update(value.enabled(),value.collaborative(),value.campaigns(),value.expectedRevision());
    }
    @GetMapping("/admin/recommendations/catalog-review")
    public Map<String,Object> review(Authentication auth,
        @RequestParam(defaultValue="0") @jakarta.validation.constraints.Min(0) int offset,
        @RequestParam(defaultValue="50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int limit) {
        user(auth);return catalog.review(offset,limit);
    }

    @PostMapping("/admin/recommendations/campaigns")
    public Map<String, String> createCampaign(Authentication auth, @Valid @RequestBody CampaignRequest value) {
        validateCampaign(value);
        User user = user(auth); store.requireReady();
        return Map.of("id", store.saveCampaign(user.getId(), null, value));
    }

    @PutMapping("/admin/recommendations/campaigns/{id}")
    public Map<String, String> updateCampaign(Authentication auth, @PathVariable @Size(max=100) String id,
                                             @Valid @RequestBody CampaignRequest value) {
        validateCampaign(value);
        User user = user(auth); store.requireReady();
        return Map.of("id", store.saveCampaign(user.getId(), id, value));
    }

    private void validateCampaign(CampaignRequest value) {
        if (!value.endsAt().isAfter(value.startsAt())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign end must follow its start");
        try { ZoneId.of(value.timezone()); }
        catch (java.time.DateTimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone"); }
        if (value.targets().stream().anyMatch(t -> t.entityType() != EntityType.TRACK && t.entityType() != EntityType.PLAYLIST)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaigns support catalog tracks and public playlists");
        }
    }
}
