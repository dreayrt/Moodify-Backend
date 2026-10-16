package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.entities.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MoodTrackScorerTest {

    private final MoodTrackScorer scorer = new MoodTrackScorer();

    @Test
    void ranksFullFeatureMatchAheadOfDistantRemix() {
        MusicRecommendationInfo profile = sadnessProfile();

        MoodTrackScorer.Score expected = scorer.score(track("match", 0.27, 0.29, 0.35, 0.70, 79.2, "A# Minor"), profile);
        MoodTrackScorer.Score remix = scorer.score(track("remix", 0.49, 0.97, 0.85, 0.13, 136.0, "C Major"), profile);

        assertEquals(1.0, expected.featureCoverage());
        assertTrue(expected.distance() < remix.distance());
        assertTrue(expected.valenceAndEnergyInRange());
        assertFalse(remix.valenceAndEnergyInRange());
    }

    @Test
    void penalizesMissingAudioFeaturesInsteadOfReplacingThemWithNeutralValues() {
        MusicRecommendationInfo profile = sadnessProfile();
        Track partialTrack = new Track();
        Track.AudioFeatures features = new Track.AudioFeatures();
        features.setValence(0.27);
        features.setEnergy(0.29);
        partialTrack.setAudioFeatures(features);

        MoodTrackScorer.Score partial = scorer.score(partialTrack, profile);
        MoodTrackScorer.Score complete = scorer.score(track("match", 0.27, 0.29, 0.35, 0.70, 79.2, "A# Minor"), profile);

        assertTrue(partial.featureCoverage() < 1.0);
        assertTrue(partial.distance() > complete.distance());
    }

    @Test
    void normalizesCatalogGenreAliasesAndParsesModeAndTempo() {
        MusicRecommendationInfo profile = new MusicRecommendationInfo();
        profile.setSeedGenres(List.of("pop"));
        profile.setTargetTempo("79.2 BPM");
        profile.setSpotifyParams(Map.of("target_mode", "0"));
        Track catalogTrack = track("vpop", 0.27, 0.29, 0.35, 0.70, 79.2, "A# Minor");
        catalogTrack.setGenresRaw(List.of("V-Pop"));

        MoodTrackScorer.Score score = scorer.score(catalogTrack, profile);

        assertEquals(List.of("pop"), score.matchedTags());
        assertTrue(score.distance() < 1.0);
    }

    private static MusicRecommendationInfo sadnessProfile() {
        MusicRecommendationInfo profile = new MusicRecommendationInfo();
        profile.setTargetValence(0.271);
        profile.setMinValence(0.091);
        profile.setMaxValence(0.451);
        profile.setTargetEnergy(0.294);
        profile.setMinEnergy(0.114);
        profile.setMaxEnergy(0.474);
        profile.setTargetDanceability(0.346);
        profile.setTargetAcousticness(0.704);
        profile.setTargetTempo("79.2 BPM");
        profile.setTargetMode("Gam thứ (Minor)");
        profile.setSpotifyParams(Map.of("target_mode", "0"));
        profile.setSeedGenres(List.of("sad", "acoustic", "piano"));
        return profile;
    }

    private static Track track(String spotifyId, double valence, double energy, double danceability,
                               double acousticness, double bpm, String keySignature) {
        Track track = new Track();
        track.setSpotifyId(spotifyId);
        Track.AudioFeatures features = new Track.AudioFeatures();
        features.setValence(valence);
        features.setEnergy(energy);
        features.setDanceability(danceability);
        features.setAcousticness(acousticness);
        features.setBpm(bpm);
        features.setKeySignature(keySignature);
        track.setAudioFeatures(features);
        return track;
    }
}
