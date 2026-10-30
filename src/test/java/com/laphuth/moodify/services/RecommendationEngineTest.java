package com.laphuth.moodify.services;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import static org.assertj.core.api.Assertions.*;
import com.laphuth.moodify.entities.Track;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationEngineTest {
    final RecommendationEngine engine = new RecommendationEngine();

    static Track track(String id, String artist, String genre, double energy) {
        Track track = new Track(); track.setId(id); track.setSpotifyId("external-" + id);
        track.setArtistSpotifyId(artist); track.setArtistName(artist); track.setName(id);
        track.setGenres(List.of(genre));
        Track.AudioFeatures audio = new Track.AudioFeatures(); audio.setEnergy(energy); audio.setValence(0.5);
        audio.setDanceability(0.5); audio.setAcousticness(1 - energy); audio.setBpm(80.0);
        track.setAudioFeatures(audio); return track;
    }

    @Test void canonicalizesLibraryAndHistoryWithoutDuplicatingSameTrack() {
        Track track = track("one", "artist", "pop", 0.2);
        var catalog = new RecommendationCatalog.Snapshot(Map.of("one", track), Map.of("external-one", "one"));
        Instant now = Instant.now();
        Profile profile = engine.profile(catalog, List.of(new Signal("external-one", 4, now, true, false),
            new Signal("one", 2, now, false, false)), Map.of(), "baseline", now);
        assertThat(profile.weights()).containsOnly(entry("one", 6.0));
        assertThat(profile.recentTrackIds()).containsExactly("one");
    }

    @Test void smallOrShrinkingSamplesAreNotTrends() {
        assertThat(RecommendationEngine.trend(2, 0, 10, 10)).isZero();
        assertThat(RecommendationEngine.trend(10, 20, 100, 100)).isZero();
        assertThat(RecommendationEngine.trend(10, 0, 100, 0)).isZero();
        assertThat(RecommendationEngine.trend(20, 3, 100, 100)).isPositive();
    }

    @Test void populationGrowthDoesNotAutomaticallyBecomeATrackTrend() {
        double genuine = RecommendationEngine.trend(20, 3, 100, 100);
        double populationDriven = RecommendationEngine.trend(20, 3, 1000, 100);
        assertThat(genuine).isGreaterThan(populationDriven);
    }

    @Test void missingAudioIsNotAnExactMatchAndGenreAliasesMatch() {
        Track a = track("a", "first", "v-pop", 0.2), b = track("b", "second", "pop", 0.2);
        assertThat(engine.similarity(a, b)).isCloseTo(0.75, within(0.00001));
        b.setAudioFeatures(null);
        assertThat(engine.similarity(a, b)).isCloseTo(0.35, within(0.00001));
    }

    @Test void blockedArtistAndTrackNeverEnterTheCandidateList() {
        Track a = track("a", "blocked-artist", "pop", 0.2), b = track("b", "other", "pop", 0.2);
        var catalog = new RecommendationCatalog.Snapshot(Map.of("a", a, "b", b), Map.of());
        var ranked = engine.rank(catalog, new Profile(Map.of(), List.of(), Map.of(), "baseline"), Map.of(),
            new double[]{1,1}, List.of(), Map.of("ARTIST", Set.of("blocked-artist"), "TRACK", Set.of("b")));
        assertThat(ranked).isEmpty();
    }

    @Test void generatedMixDoesNotRepeatTracksToFillItsTarget() {
        List<RankedTrack> ranked = List.of(new RankedTrack(track("a", "one", "pop", 0.2), .9, "CONTENT", "CONTENT", ""),
            new RankedTrack(track("b", "two", "pop", 0.3), .8, "CONTENT", "CONTENT", ""));
        var selected = engine.mix(ranked, new Profile(Map.of(), List.of(), Map.of(), "baseline"), MixType.DAILY_MIX, 30, Set.of());
        assertThat(selected).hasSize(2).extracting(r -> r.track().getId()).doesNotHaveDuplicates();
    }

    @Test void discoveryPrioritizesUnobservedTracksAndRespectsArtistLimit() {
        List<RankedTrack> ranked = new ArrayList<>();
        for (int i = 0; i < 20; i++) ranked.add(new RankedTrack(track("t" + i, "artist" + (i/4), "pop", .3), 1 - i/100.0, "CONTENT", "CONTENT", ""));
        var selected = engine.mix(ranked, new Profile(Map.of("t0", 4.0, "t1", 4.0), List.of(), Map.of(), "baseline"), MixType.WEEKLY_DISCOVERY, 10, Set.of());
        assertThat(selected.stream().filter(r -> Set.of("t0","t1").contains(r.track().getId()))).isEmpty();
        assertThat(selected.stream().collect(java.util.stream.Collectors.groupingBy(r -> r.track().getArtistSpotifyId(), java.util.stream.Collectors.counting())).values())
            .allMatch(count -> count <= 2);
    }

    @Test void inactiveCampaignDoesNotBoostItsTracks() {
        Instant now = Instant.now();
        Campaign inactive = new Campaign("c", "campaign", 1, now.minusSeconds(60), now.plusSeconds(60), 100, List.of(), List.of(new Target(EntityType.TRACK,"a")), false, "Asia/Ho_Chi_Minh");
        assertThat(engine.campaignFit(EntityType.TRACK, "a", List.of("pop"), List.of(inactive))).isZero();
    }

    @Test void refreshSchedulesFollowVietnamLocalDayAndWeek() {
        Instant now = Instant.parse("2026-10-26T18:00:00Z"); // Tuesday 01:00 in Vietnam
        assertThat(PersonalizedMixService.nextRefresh(MixType.DAILY_MIX, now)).isEqualTo(Instant.parse("2026-10-27T17:00:00Z"));
        assertThat(PersonalizedMixService.nextRefresh(MixType.WEEKLY_DISCOVERY, now)).isEqualTo(Instant.parse("2026-11-01T17:00:00Z"));
    }
}
