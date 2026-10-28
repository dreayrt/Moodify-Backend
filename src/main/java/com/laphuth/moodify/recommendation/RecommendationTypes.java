package com.laphuth.moodify.recommendation;

import com.laphuth.moodify.dto.track.TrackResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecommendationTypes {
    private RecommendationTypes() {}

    public enum EntityType { TRACK, PLAYLIST, PERSONALIZED_PLAYLIST, ARTIST }
    public enum EventType { IMPRESSION, OPEN, PLAY_START, SAVE_COPY }
    public enum FeedbackType { HIDE, NOT_INTERESTED, FOLLOW }
    public enum MixType { DAILY_MIX, WEEKLY_DISCOVERY, TREND_FOR_YOU }

    public record Item(String itemId, EntityType entityType, String entityId, String versionId,
                       String title, String subtitle, String imageUrl, int trackCount,
                       String reasonCode, String reason, double score, String attributionToken,
                       TrackResponse track) {}
    public record Section(String id, String title, String description, List<Item> items) {}
    public record Home(String requestId, Instant generatedAt, Instant expiresAt,
                       String modelVersion, String policyVersion, boolean coldStart,
                       List<Section> sections) {}
    public record Event(@NotNull java.util.UUID eventId, @NotNull EventType eventType,
                        @NotBlank @Size(max=4096) String attributionToken,
                        @NotNull Instant occurredAt) {}
    public record EventBatch(@NotEmpty @Size(max=50) List<@Valid Event> events) {}
    public record Feedback(@NotNull EntityType entityType, @NotBlank @Size(max=100) String entityId,
                           @NotNull FeedbackType feedbackType, boolean enabled) {}
    public record QueueRequest(@NotNull EntityType entityType, @NotBlank @Size(max=100) String entityId,
                               @Size(max=100) String versionId, @Size(max=4096) String attributionToken) {}
    public record QueueTrack(TrackResponse track, String queueToken, int position) {}
    public record Queue(String queueId, EntityType entityType, String entityId, String versionId,
                        List<QueueTrack> tracks, Instant expiresAt) {}
    public record Mix(String id, MixType mixType, String name, String description, String imageUrl,
                      String versionId, Instant generatedAt, Instant refreshDueAt, int trackCount,
                      List<TrackResponse> tracks, String modelVersion) {}
    public record CopyRequest(@NotNull java.util.UUID requestId, @NotBlank @Size(max=100) String versionId) {}
    public record Target(@NotNull EntityType entityType, @NotBlank @Size(max=100) String entityId) {}
    public record CampaignRequest(@NotBlank @Size(max=150) String name, boolean active,
                                  @NotBlank @Size(max=60) String timezone,
                                  @NotNull Instant startsAt, @NotNull Instant endsAt,
                                  @Min(0) @Max(100) int priority,
                                  @Size(max=20) List<@Size(max=80) String> genres,
                                  @NotEmpty @Size(max=200) List<@Valid Target> targets,
                                  @Min(1) long expectedRevision) {}
    public record Campaign(String id, String name, long revision, Instant startsAt, Instant endsAt,
                           int priority, List<String> genres, List<Target> targets, boolean active, String timezone) {}
    public record PlaybackContext(String source, String sourceId, String deviceType,
                                  String queueToken, TokenClaims claims) {}
    public record TokenClaims(long userId, String purpose, String itemId, String requestId,
                              EntityType entityType, String entityId, String versionId,
                              String trackId, Integer position, long expiresAt) {}
    public record Signal(String trackId, double weight, Instant at, boolean saved, boolean earlySkip) {}
    public record PreferenceContext(Set<String> followedArtists, Map<String,Double> albumTrackPriors,
                                    Map<String,Double> intentTracks, Map<String,Double> intentArtists,
                                    String fingerprint, Map<String,Integer> diagnostics) {
        public static PreferenceContext empty() { return new PreferenceContext(Set.of(),Map.of(),Map.of(),Map.of(),"",Map.of()); }
    }
    public record Profile(Map<String, Double> weights, List<String> recentTrackIds,
                          Map<String, Double> collaborativeScores, String modelVersion,
                          PreferenceContext preferences, Set<String> requestedGenres) {
        public Profile(Map<String,Double> weights,List<String> recentTrackIds,Map<String,Double> collaborativeScores,String modelVersion) {
            this(weights,recentTrackIds,collaborativeScores,modelVersion,PreferenceContext.empty(),Set.of());
        }
        public boolean personalized() {
            return weights.values().stream().anyMatch(v->v>0)||!preferences.followedArtists().isEmpty()||
                !preferences.albumTrackPriors().isEmpty()||!preferences.intentTracks().isEmpty()||!preferences.intentArtists().isEmpty();
        }
    }
    public record RankedTrack(com.laphuth.moodify.entities.Track track, double score,
                              String source, String reasonCode, String reason) {}
    public record Readiness(int catalogTracks, int eligibleTracks, int publicPlaylists,
                            boolean requireActiveLicense, Map<String, Integer> issues) {}
}
