package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.entities.Track;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scores catalog tracks against the numeric and tag profile returned by the emotion model. */
public final class MoodTrackScorer {

    private static final double VALENCE_WEIGHT = 0.26;
    private static final double ENERGY_WEIGHT = 0.26;
    private static final double DANCEABILITY_WEIGHT = 0.16;
    private static final double ACOUSTICNESS_WEIGHT = 0.16;
    private static final double TEMPO_WEIGHT = 0.11;
    private static final double MODE_WEIGHT = 0.05;
    private static final double MISSING_FEATURE_PENALTY = 0.20;
    private static final double MATCHED_TAG_BONUS = 0.05;
    private static final double TEMPO_NORMALIZATION_BPM = 120.0;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    public Score score(Track track, MusicRecommendationInfo profile) {
        return score(track, profile, null);
    }

    public Score score(Track track, MusicRecommendationInfo profile, String emotionLabel) {
        String targetMoodTag = profile != null && "empathy".equalsIgnoreCase(profile.getStrategy())
            ? emotionLabel
            : null;
        return scoreInternal(track, profile, true, targetMoodTag);
    }

    /**
     * Scores only the audio features with the same formula used for search ranking.
     * This path is used when deriving catalog mood tags so existing tags cannot
     * influence their own classification.
     */
    public Score scoreAudioFeatures(Track track, MusicRecommendationInfo profile) {
        return scoreInternal(track, profile, false, null);
    }

    private Score scoreInternal(Track track, MusicRecommendationInfo profile, boolean includeTagBonus, String targetMoodTag) {
        if (track == null || profile == null) {
            return new Score(1.0, 0.0, List.of(), false, Map.of());
        }

        Track.AudioFeatures audio = track.getAudioFeatures();
        Map<String, FeatureDistance> featureDistances = new LinkedHashMap<>();
        // expected weight, available weight, and weighted squared error
        double[] totals = new double[3];

        addFeature(featureDistances, totals, "valence", VALENCE_WEIGHT,
            unitValue(profile.getTargetValence()), unitValue(audio == null ? null : audio.getValence()), 1.0,
            unitValue(profile.getMinValence()), unitValue(profile.getMaxValence()));
        addFeature(featureDistances, totals, "energy", ENERGY_WEIGHT,
            unitValue(profile.getTargetEnergy()), unitValue(audio == null ? null : audio.getEnergy()), 1.0,
            unitValue(profile.getMinEnergy()), unitValue(profile.getMaxEnergy()));
        addFeature(featureDistances, totals, "danceability", DANCEABILITY_WEIGHT,
            unitValue(profile.getTargetDanceability()), unitValue(audio == null ? null : audio.getDanceability()), 1.0,
            null, null);
        addFeature(featureDistances, totals, "acousticness", ACOUSTICNESS_WEIGHT,
            unitValue(profile.getTargetAcousticness()), unitValue(audio == null ? null : audio.getAcousticness()), 1.0,
            null, null);

        Double targetTempo = parseNumber(profile.getTargetTempo());
        if (targetTempo != null && targetTempo > 0.0) {
            addFeature(featureDistances, totals, "tempo", TEMPO_WEIGHT, targetTempo,
                audio == null ? null : positiveValue(audio.getBpm()), TEMPO_NORMALIZATION_BPM, null, null);
        }

        Integer targetMode = targetMode(profile.getSpotifyParams(), profile.getTargetMode());
        if (targetMode != null) {
            Integer actualMode = modeFromKey(audio == null ? null : audio.getKeySignature());
            addFeature(featureDistances, totals, "mode", MODE_WEIGHT, targetMode.doubleValue(),
                actualMode == null ? null : actualMode.doubleValue(), 1.0, null, null);
        }

        double expectedWeight = totals[0];
        double availableWeight = totals[1];
        double featureCoverage = expectedWeight == 0.0 ? 0.0 : availableWeight / expectedWeight;
        double distance = availableWeight == 0.0
            ? 1.0
            : Math.sqrt(totals[2] / availableWeight);
        if (expectedWeight > 0.0) {
            distance += MISSING_FEATURE_PENALTY * (1.0 - featureCoverage);
        }

        boolean valenceAndEnergyInRange = isWithinRange(
            audio == null ? null : audio.getValence(), profile.getMinValence(), profile.getMaxValence()
        ) && isWithinRange(
            audio == null ? null : audio.getEnergy(), profile.getMinEnergy(), profile.getMaxEnergy()
        );

        List<String> matchedTags = includeTagBonus
            ? findMatchedTags(track, profile.getSeedGenres(), targetMoodTag)
            : List.of();
        // Keep genre/mood tags as a small preference; do not let the range bounds
        // award a free score reduction to a track merely because it sits anywhere in the band.
        if (includeTagBonus && !matchedTags.isEmpty()) distance -= MATCHED_TAG_BONUS;

        return new Score(
            round(Math.max(0.0, Math.min(1.0, distance))),
            round(featureCoverage),
            matchedTags,
            valenceAndEnergyInRange,
            featureDistances
        );
    }

    private static void addFeature(
        Map<String, FeatureDistance> details,
        double[] totals,
        String name,
        double weight,
        Double target,
        Double actual,
        double normalization,
        Double minimum,
        Double maximum
    ) {
        if (target == null || !Double.isFinite(target) || normalization <= 0.0) return;

        totals[0] += weight;
        if (actual == null || !Double.isFinite(actual)) {
            details.put(name, new FeatureDistance(weight, target, null, null, null, null));
            return;
        }

        double normalizedDistance = Math.min(1.0, Math.abs(actual - target) / normalization);
        double weightedSquaredError = weight * normalizedDistance * normalizedDistance;
        totals[1] += weight;
        totals[2] += weightedSquaredError;

        Boolean insideRecommendedRange = minimum != null && maximum != null
            ? actual >= minimum && actual <= maximum
            : null;
        details.put(name, new FeatureDistance(
            weight,
            round(target),
            round(actual),
            round(normalizedDistance),
            round(weightedSquaredError),
            insideRecommendedRange
        ));
    }

    private static List<String> findMatchedTags(Track track, List<String> seeds, String targetMoodTag) {
        Set<String> catalogTags = new LinkedHashSet<>();
        addTags(catalogTags, track.getGenres());
        addTags(catalogTags, track.getGenresRaw());
        addTags(catalogTags, track.getMoodTags());

        List<String> matched = seeds == null ? new java.util.ArrayList<>() : seeds.stream()
            .filter(seed -> seed != null && catalogTags.contains(normalizeTag(seed)))
            .distinct()
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        String normalizedMood = targetMoodTag == null || targetMoodTag.isBlank()
            ? null
            : normalizeTag(targetMoodTag);
        if (normalizedMood != null && track.getMoodTags() != null
            && track.getMoodTags().stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(MoodTrackScorer::normalizeTag)
                .anyMatch(normalizedMood::equals)) {
            matched.add("mood:" + normalizedMood);
        }
        return List.copyOf(matched);
    }

    private static void addTags(Set<String> destination, Collection<String> tags) {
        if (tags == null) return;
        tags.stream()
            .filter(tag -> tag != null && !tag.isBlank())
            .map(MoodTrackScorer::normalizeTag)
            .forEach(destination::add);
    }

    private static String normalizeTag(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "vpop", "vietnamesepop" -> "pop";
            case "rhythmandblues" -> "rnb";
            default -> normalized;
        };
    }

    private static Integer targetMode(Map<String, Object> spotifyParams, String humanReadableMode) {
        if (spotifyParams != null) {
            Object mode = spotifyParams.get("target_mode");
            Integer parsed = parseMode(mode == null ? null : mode.toString());
            if (parsed != null) return parsed;
        }
        return parseMode(humanReadableMode);
    }

    private static Integer modeFromKey(String keySignature) {
        return parseMode(keySignature);
    }

    private static Integer parseMode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        if (normalized.equals("0") || normalized.contains("minor") || normalized.contains("gam thu")) return 0;
        if (normalized.equals("1") || normalized.contains("major") || normalized.contains("gam truong")) return 1;
        return null;
    }

    private static Double parseNumber(String value) {
        if (value == null) return null;
        Matcher matcher = NUMBER_PATTERN.matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group()) : null;
    }

    private static Double unitValue(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : null;
    }

    private static Double positiveValue(Double value) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : null;
    }

    private static boolean isWithinRange(Double value, Double minimum, Double maximum) {
        return value != null && minimum != null && maximum != null && value >= minimum && value <= maximum;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public record FeatureDistance(
        double weight,
        Double targetValue,
        Double actualValue,
        Double normalizedDistance,
        Double weightedSquaredError,
        Boolean insideRecommendedRange
    ) {}

    public record Score(
        double distance,
        double featureCoverage,
        List<String> matchedTags,
        boolean valenceAndEnergyInRange,
        Map<String, FeatureDistance> featureDistances
    ) {
        public Score {
            matchedTags = matchedTags == null ? List.of() : List.copyOf(matchedTags);
            featureDistances = featureDistances == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(featureDistances));
        }
    }
}
