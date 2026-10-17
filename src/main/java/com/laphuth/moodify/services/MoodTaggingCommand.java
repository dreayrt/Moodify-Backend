package com.laphuth.moodify.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.entities.Track;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Opt-in batch command for deriving reviewed mood_tags from the model's own base
 * audio profiles. The feature distance is delegated to MoodTrackScorer, shared
 * with live mood search.
 */
@Component
@ConditionalOnProperty(name = "mood-tags.command")
public class MoodTaggingCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MoodTaggingCommand.class);
    private static final int TOOL_SCHEMA_VERSION = 1;

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MoodTrackScorer scorer = new MoodTrackScorer();
    private final ConfigurableApplicationContext applicationContext;

    @Value("${mood-tags.command}")
    private String command;

    @Value("${mood-tags.profiles-file:}")
    private String profilesFile;

    @Value("${mood-tags.output:}")
    private String outputFile;

    @Value("${mood-tags.reviewed-file:}")
    private String reviewedFile;

    @Value("${mood-tags.manifest:}")
    private String manifestFile;

    @Value("${mood-tags.max-distance:}")
    private String maxDistanceValue;

    @Value("${mood-tags.min-margin:}")
    private String minMarginValue;

    @Value("${mood-tags.min-feature-coverage:1.0}")
    private double minFeatureCoverage;

    public MoodTaggingCommand(
        MongoTemplate mongoTemplate,
        ConfigurableApplicationContext applicationContext
    ) {
        this.mongoTemplate = mongoTemplate;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            switch (command.trim().toLowerCase()) {
                case "preview" -> preview();
                case "apply" -> applyReviewedTags();
                case "rollback" -> rollback();
                default -> throw new IllegalArgumentException(
                    "Unsupported mood-tags.command. Use preview, apply, or rollback."
                );
            }
        } catch (Exception exception) {
            log.error("Mood tag batch command failed: {}", exception.getMessage(), exception);
            exitCode = 1;
        } finally {
            int finalExitCode = exitCode;
            int code = SpringApplication.exit(applicationContext, () -> finalExitCode);
            System.exit(code);
        }
    }

    private void preview() throws IOException {
        requireFile(profilesFile, "mood-tags.profiles-file");
        requireFile(outputFile, "mood-tags.output");
        ProfileBundle bundle = objectMapper.readValue(Path.of(profilesFile).toFile(), ProfileBundle.class);
        validateBundle(bundle);

        Double maxDistance = optionalUnitValue(maxDistanceValue, "mood-tags.max-distance");
        Double minMargin = optionalUnitValue(minMarginValue, "mood-tags.min-margin");
        if ((maxDistance == null) != (minMargin == null)) {
            throw new IllegalArgumentException(
                "Set both mood-tags.max-distance and mood-tags.min-margin, or leave both unset for ranking-only preview."
            );
        }
        if (!Double.isFinite(minFeatureCoverage) || minFeatureCoverage < 0.0 || minFeatureCoverage > 1.0) {
            throw new IllegalArgumentException("mood-tags.min-feature-coverage must be between 0 and 1");
        }

        List<Track> tracks = readValidTracks();
        Map<String, StoredState> states = readStoredStates();
        validateUniqueTrackIds(tracks);
        if (tracks.size() != states.size()) {
            throw new IllegalStateException("Mapped track count differs from raw MongoDB document count");
        }

        List<PreviewTrack> previews = tracks.stream()
            .sorted(Comparator.comparing(Track::getSpotifyId))
            .map(track -> createPreview(track, states.get(track.getSpotifyId()), bundle.profiles(), maxDistance, minMargin))
            .toList();
        long ignoredDocuments = countIgnoredDocuments();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", TOOL_SCHEMA_VERSION);
        document.put("generatedAt", Instant.now().toString());
        document.put("profileSourcePath", bundle.sourcePath());
        document.put("profileSourceSha256", bundle.sourceSha256());
        document.put("ignoredDocumentsWithoutValidSpotifyId", ignoredDocuments);
        document.put("ignoredDocumentSamples", readIgnoredDocumentSamples());
        document.put("profileLabels", bundle.profiles().stream().map(EmotionProfile::tag).toList());
        document.put("maxDistance", maxDistance);
        document.put("minMargin", minMargin);
        document.put("minFeatureCoverage", minFeatureCoverage);
        document.put("reviewInstructions", "Review every track, then fill approvedMoodTags with an array of canonical profile tags. Use [] when no tag is justified.");
        document.put("tracks", previews);
        writeNewJson(Path.of(outputFile), document);

        long candidateCount = previews.stream().filter(item -> "CANDIDATE".equals(item.status())).count();
        log.info(
            "Mood tag preview created: {} valid tracks, {} ignored documents without valid spotify_id, {} candidates, output={}",
            tracks.size(), ignoredDocuments, candidateCount, Path.of(outputFile).toAbsolutePath()
        );
        if (maxDistance == null) {
            log.info("No assignment thresholds were provided; preview contains profile rankings only.");
        }
    }

    private PreviewTrack createPreview(
        Track track,
        StoredState existing,
        List<EmotionProfile> profiles,
        Double maxDistance,
        Double minMargin
    ) {
        if (existing == null) {
            throw new IllegalStateException("No raw MongoDB state found for spotify_id=" + track.getSpotifyId());
        }

        List<ProfileScore> scores = profiles.stream()
            .map(profile -> {
                MoodTrackScorer.Score score = scorer.scoreAudioFeatures(track, toRecommendationInfo(profile));
                return new ProfileScore(profile.label(), profile.tag(), score.distance(), score.featureCoverage());
            })
            .sorted(Comparator.comparingDouble(ProfileScore::distance))
            .toList();

        ProfileScore best = scores.getFirst();
        double margin = scores.size() > 1 ? scores.get(1).distance() - best.distance() : 0.0;
        String status;
        String proposedTag = null;
        if (best.featureCoverage() < minFeatureCoverage) {
            status = "INSUFFICIENT_FEATURE_COVERAGE";
        } else if (maxDistance == null || minMargin == null) {
            status = "RANKING_ONLY_THRESHOLDS_UNSET";
        } else if (best.distance() > maxDistance) {
            status = "OUTSIDE_MAX_DISTANCE";
        } else if (margin < minMargin) {
            status = "AMBIGUOUS_PROFILE";
        } else {
            status = "CANDIDATE_REQUIRES_REVIEW";
            proposedTag = best.tag();
        }

        Map<String, Object> audioValues = audioFeatureValues(track);

        return new PreviewTrack(
            track.getSpotifyId(),
            track.getName(),
            track.getArtistName(),
            existing.present(),
            existing.tags(),
            audioValues,
            scores,
            best.tag(),
            best.distance(),
            margin,
            status,
            proposedTag,
            null
        );
    }

    private void applyReviewedTags() throws IOException {
        requireFile(profilesFile, "mood-tags.profiles-file");
        requireFile(reviewedFile, "mood-tags.reviewed-file");
        requireFile(manifestFile, "mood-tags.manifest");

        ProfileBundle bundle = objectMapper.readValue(Path.of(profilesFile).toFile(), ProfileBundle.class);
        validateBundle(bundle);
        JsonNode review = objectMapper.readTree(Path.of(reviewedFile).toFile());
        if (review.path("schemaVersion").asInt(-1) != TOOL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Reviewed file has an unsupported schemaVersion");
        }
        if (!bundle.sourceSha256().equals(review.path("profileSourceSha256").asText())) {
            throw new IllegalStateException("Model profile source changed since preview; create and review a fresh preview");
        }

        Map<String, EmotionProfile> allowedTags = new HashMap<>();
        bundle.profiles().forEach(profile -> allowedTags.put(profile.tag(), profile));
        Map<String, StoredState> currentStates = readStoredStates();
        Map<String, Track> currentTracks = readTracksBySpotifyId();
        if (currentStates.size() != currentTracks.size()) {
            throw new IllegalStateException("Mapped track count differs from raw MongoDB document count");
        }
        JsonNode reviewedTracks = review.path("tracks");
        if (review.path("ignoredDocumentsWithoutValidSpotifyId").asLong(-1) != countIgnoredDocuments()
            || !objectMapper.valueToTree(readIgnoredDocumentSamples()).equals(review.path("ignoredDocumentSamples"))) {
            throw new IllegalStateException("MongoDB documents without valid spotify_id changed since preview");
        }
        if (!reviewedTracks.isArray() || reviewedTracks.size() != currentTracks.size()) {
            throw new IllegalArgumentException("Reviewed file must contain every current track exactly once");
        }

        Set<String> seen = new HashSet<>();
        List<ManifestEntry> entries = new ArrayList<>();
        for (JsonNode item : reviewedTracks) {
            String spotifyId = requiredText(item, "spotifyId");
            if (!seen.add(spotifyId)) {
                throw new IllegalArgumentException("Duplicate spotifyId in reviewed file: " + spotifyId);
            }
            StoredState current = currentStates.get(spotifyId);
            if (current == null || !currentTracks.containsKey(spotifyId)) {
                throw new IllegalStateException("Track no longer exists or has an invalid spotify_id: " + spotifyId);
            }
            boolean previewPresent = item.path("moodTagsFieldPresent").asBoolean(false);
            List<String> previewTags = nullableStringArray(item.get("existingMoodTags"), "existingMoodTags");
            if (!sameState(current, new StoredState(previewPresent, previewTags))) {
                throw new IllegalStateException("mood_tags changed since preview for spotify_id=" + spotifyId);
            }
            if (!objectMapper.valueToTree(audioFeatureValues(currentTracks.get(spotifyId)))
                .equals(item.path("audioFeatures"))) {
                throw new IllegalStateException("Audio features changed since preview for spotify_id=" + spotifyId);
            }
            JsonNode approved = item.get("approvedMoodTags");
            if (approved == null || !approved.isArray()) {
                throw new IllegalArgumentException("Fill approvedMoodTags with an array for spotify_id=" + spotifyId);
            }
            List<String> approvedTags = readStringArray(approved, "approvedMoodTags");
            if (approvedTags.size() > 1) {
                throw new IllegalArgumentException("Approve at most one emotion tag per track; use [] when uncertain");
            }
            if (new HashSet<>(approvedTags).size() != approvedTags.size()) {
                throw new IllegalArgumentException("Duplicate approved tag for spotify_id=" + spotifyId);
            }
            for (String tag : approvedTags) {
                if (!allowedTags.containsKey(tag)) {
                    throw new IllegalArgumentException("Unknown mood tag '" + tag + "' for spotify_id=" + spotifyId);
                }
            }
            entries.add(new ManifestEntry(spotifyId, current.present(), current.tags(), approvedTags));
        }
        if (seen.size() != currentTracks.size()) {
            throw new IllegalArgumentException("Reviewed file omitted one or more current tracks");
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", TOOL_SCHEMA_VERSION);
        manifest.put("status", "PREPARED");
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("profileSourcePath", bundle.sourcePath());
        manifest.put("profileSourceSha256", bundle.sourceSha256());
        manifest.put("entries", entries);
        writeNewJson(Path.of(manifestFile), manifest);

        MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
        for (ManifestEntry entry : entries) {
            StoredState current = readState(collection, entry.spotifyId());
            StoredState before = new StoredState(entry.beforePresent(), entry.beforeTags());
            StoredState after = new StoredState(true, entry.afterTags());
            if (sameState(current, after)) continue;
            if (!sameState(current, before)) {
                throw new IllegalStateException("Concurrent mood_tags change detected for spotify_id=" + entry.spotifyId());
            }
            UpdateResult result = collection.updateOne(
                stateFilter(entry.spotifyId(), before),
                Updates.set("mood_tags", entry.afterTags())
            );
            if (result.getMatchedCount() != 1) {
                throw new IllegalStateException("Expected one document update for spotify_id=" + entry.spotifyId());
            }
        }
        manifest.put("status", "APPLIED");
        manifest.put("appliedAt", Instant.now().toString());
        replaceJson(Path.of(manifestFile), manifest);
        log.info("Applied reviewed mood_tags to {} tracks. Rollback manifest: {}", entries.size(), Path.of(manifestFile).toAbsolutePath());
    }

    private void rollback() throws IOException {
        requireFile(manifestFile, "mood-tags.manifest");
        Path manifestPath = Path.of(manifestFile);
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
        if (manifest.path("schemaVersion").asInt(-1) != TOOL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Manifest has an unsupported schemaVersion");
        }
        String status = manifest.path("status").asText();
        if (!"PREPARED".equals(status) && !"APPLIED".equals(status)) {
            throw new IllegalArgumentException("Only PREPARED or APPLIED manifests can be rolled back");
        }
        JsonNode entriesNode = manifest.path("entries");
        if (!entriesNode.isArray()) throw new IllegalArgumentException("Manifest entries are missing");

        List<ManifestEntry> entries = readManifestEntries(entriesNode);
        MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
        Map<String, StoredState> states = readStoredStates();
        Set<String> seen = new HashSet<>();
        for (ManifestEntry entry : entries) {
            if (!seen.add(entry.spotifyId())) {
                throw new IllegalArgumentException("Duplicate spotify_id in rollback manifest: " + entry.spotifyId());
            }
            StoredState current = states.get(entry.spotifyId());
            StoredState before = new StoredState(entry.beforePresent(), entry.beforeTags());
            StoredState after = new StoredState(true, entry.afterTags());
            if (current == null || (!sameState(current, before) && !sameState(current, after))) {
                throw new IllegalStateException("Cannot safely rollback; mood_tags changed after apply for spotify_id=" + entry.spotifyId());
            }
        }

        for (ManifestEntry entry : entries) {
            StoredState current = states.get(entry.spotifyId());
            StoredState before = new StoredState(entry.beforePresent(), entry.beforeTags());
            StoredState after = new StoredState(true, entry.afterTags());
            if (sameState(current, before)) continue;
            Bson update = before.present()
                ? Updates.set("mood_tags", before.tags())
                : Updates.unset("mood_tags");
            UpdateResult result = collection.updateOne(stateFilter(entry.spotifyId(), after), update);
            if (result.getMatchedCount() != 1) {
                throw new IllegalStateException("Expected one document rollback for spotify_id=" + entry.spotifyId());
            }
        }

        Map<String, Object> updatedManifest = objectMapper.convertValue(
            manifest,
            new TypeReference<Map<String, Object>>() {}
        );
        updatedManifest.put("status", "ROLLED_BACK");
        updatedManifest.put("rolledBackAt", Instant.now().toString());
        replaceJson(manifestPath, updatedManifest);
        log.info("Restored mood_tags for {} tracks from {}", entries.size(), manifestPath.toAbsolutePath());
    }

    private List<ManifestEntry> readManifestEntries(JsonNode entriesNode) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (JsonNode item : entriesNode) {
            entries.add(new ManifestEntry(
                requiredText(item, "spotifyId"),
                item.path("beforePresent").asBoolean(false),
                nullableStringArray(item.get("beforeTags"), "beforeTags"),
                readStringArray(item.path("afterTags"), "afterTags")
            ));
        }
        return entries;
    }

    private Map<String, Track> readTracksBySpotifyId() {
        Map<String, Track> tracks = new TreeMap<>();
        for (Track track : readValidTracks()) {
            String id = track.getSpotifyId();
            if (id == null || id.isBlank() || tracks.putIfAbsent(id, track) != null) {
                throw new IllegalStateException("Every track must have a unique non-empty spotify_id");
            }
        }
        return tracks;
    }

    private List<Track> readValidTracks() {
        Query query = new Query(Criteria.where("spotifyId").type(2).ne(""));
        return mongoTemplate.find(query, Track.class, "tracks");
    }

    private long countIgnoredDocuments() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
        long total = collection.countDocuments();
        long valid = collection.countDocuments(validTrackFilter());
        return total - valid;
    }

    private List<IgnoredDocumentSample> readIgnoredDocumentSamples() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
        return collection.find(Filters.nor(validTrackFilter()))
            .projection(Projections.include("_id", "name", "spotify_id"))
            .limit(20)
            .into(new ArrayList<>())
            .stream()
            .map(document -> {
                Object rawSpotifyId = document.get("spotify_id");
                String valueType = rawSpotifyId == null
                    ? (document.containsKey("spotify_id") ? "null" : "missing")
                    : rawSpotifyId.getClass().getSimpleName();
                String valuePreview = rawSpotifyId instanceof String value
                    ? value.substring(0, Math.min(value.length(), 32))
                    : null;
                return new IgnoredDocumentSample(
                    String.valueOf(document.get("_id")),
                    document.getString("name"),
                    document.containsKey("spotify_id"),
                    valueType,
                    valuePreview
                );
            })
            .sorted(Comparator.comparing(IgnoredDocumentSample::documentId))
            .toList();
    }

    private static Bson validTrackFilter() {
        return Filters.and(
            Filters.type("spotify_id", BsonType.STRING),
            Filters.ne("spotify_id", "")
        );
    }

    private static Map<String, Object> audioFeatureValues(Track track) {
        Track.AudioFeatures audio = track.getAudioFeatures();
        Map<String, Object> audioValues = new LinkedHashMap<>();
        audioValues.put("valence", audio == null ? null : audio.getValence());
        audioValues.put("energy", audio == null ? null : audio.getEnergy());
        audioValues.put("danceability", audio == null ? null : audio.getDanceability());
        audioValues.put("acousticness", audio == null ? null : audio.getAcousticness());
        audioValues.put("bpm", audio == null ? null : audio.getBpm());
        audioValues.put("key_signature", audio == null ? null : audio.getKeySignature());
        return audioValues;
    }

    private Map<String, StoredState> readStoredStates() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("tracks");
        List<Document> documents = collection.find(validTrackFilter())
            .projection(Projections.include("spotify_id", "mood_tags"))
            .into(new ArrayList<>());
        Map<String, StoredState> states = new TreeMap<>();
        for (Document document : documents) {
            String spotifyId = document.getString("spotify_id");
            if (spotifyId == null || spotifyId.isBlank()) {
                throw new IllegalStateException("Every track must have a non-empty spotify_id");
            }
            Object rawTags = document.get("mood_tags");
            List<String> tags = null;
            if (rawTags != null) {
                if (!(rawTags instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) {
                    throw new IllegalStateException("mood_tags must be an array of strings for spotify_id=" + spotifyId);
                }
                tags = list.stream().map(String.class::cast).toList();
            }
            StoredState state = new StoredState(document.containsKey("mood_tags"), tags);
            if (states.putIfAbsent(spotifyId, state) != null) {
                throw new IllegalStateException("Duplicate spotify_id in MongoDB: " + spotifyId);
            }
        }
        return states;
    }

    private StoredState readState(MongoCollection<Document> collection, String spotifyId) {
        List<Document> documents = collection.find(Filters.eq("spotify_id", spotifyId))
            .projection(Projections.include("spotify_id", "mood_tags"))
            .limit(2)
            .into(new ArrayList<>());
        if (documents.size() != 1) throw new IllegalStateException("Expected one track for spotify_id=" + spotifyId);
        Document document = documents.getFirst();
        Object rawTags = document.get("mood_tags");
        List<String> tags = null;
        if (rawTags != null) {
            if (!(rawTags instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalStateException("mood_tags must be an array of strings for spotify_id=" + spotifyId);
            }
            tags = list.stream().map(String.class::cast).toList();
        }
        return new StoredState(document.containsKey("mood_tags"), tags);
    }

    private MusicRecommendationInfo toRecommendationInfo(EmotionProfile profile) {
        MusicRecommendationInfo info = new MusicRecommendationInfo();
        info.setTargetValence(profile.valence());
        info.setTargetEnergy(profile.energy());
        info.setTargetDanceability(profile.danceability());
        info.setTargetAcousticness(profile.acousticness());
        info.setTargetMode(Integer.toString(profile.mode()));
        info.setTargetTempo(profile.targetTempo() + " BPM");
        info.setSeedGenres(List.of());
        info.setSpotifyParams(Map.of("target_mode", Integer.toString(profile.mode())));
        return info;
    }

    private void validateBundle(ProfileBundle bundle) {
        if (bundle == null || bundle.schemaVersion() != 1 || bundle.profiles() == null || bundle.profiles().isEmpty()) {
            throw new IllegalArgumentException("Invalid model profile bundle");
        }
        Set<String> tags = new HashSet<>();
        for (EmotionProfile profile : bundle.profiles()) {
            if (profile.tag() == null || profile.tag().isBlank() || !tags.add(profile.tag())) {
                throw new IllegalArgumentException("Model profile tags must be unique and non-empty");
            }
            for (double value : List.of(profile.valence(), profile.energy(), profile.danceability(), profile.acousticness())) {
                if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("Model profile unit features must be between 0 and 1");
                }
            }
            if (profile.mode() != 0 && profile.mode() != 1) {
                throw new IllegalArgumentException("Model profile mode must be 0 or 1");
            }
            if (!Double.isFinite(profile.targetTempo()) || profile.targetTempo() <= 0.0) {
                throw new IllegalArgumentException("Model profile tempo must be positive");
            }
        }
    }

    private void validateUniqueTrackIds(List<Track> tracks) {
        Set<String> ids = new HashSet<>();
        for (Track track : tracks) {
            if (track.getSpotifyId() == null || track.getSpotifyId().isBlank() || !ids.add(track.getSpotifyId())) {
                throw new IllegalStateException("Every track must have a unique non-empty spotify_id");
            }
        }
    }

    private static boolean sameState(StoredState left, StoredState right) {
        return left.present() == right.present() && java.util.Objects.equals(left.tags(), right.tags());
    }

    private static Bson stateFilter(String spotifyId, StoredState expected) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq("spotify_id", spotifyId));
        if (!expected.present()) {
            conditions.add(Filters.exists("mood_tags", false));
        } else {
            conditions.add(Filters.exists("mood_tags", true));
            conditions.add(Filters.eq("mood_tags", expected.tags()));
        }
        return Filters.and(conditions);
    }

    private static Double optionalUnitValue(String value, String name) {
        if (value == null || value.isBlank()) return null;
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number between 0 and 1");
        }
        if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException(name + " must be a number between 0 and 1");
        }
        return parsed;
    }

    private static void requireFile(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + property);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing non-empty " + field);
        }
        return value.asText();
    }

    private static List<String> nullableStringArray(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        return readStringArray(node, field);
    }

    private static List<String> readStringArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-empty strings only");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private void writeNewJson(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (Files.exists(absolute)) throw new IllegalArgumentException("Refusing to overwrite existing file: " + absolute);
        Files.createDirectories(absolute.getParent());
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(absolute, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void replaceJson(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(temporary, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record StoredState(boolean present, List<String> tags) {}

    private record ProfileBundle(int schemaVersion, String sourcePath, String sourceSha256, List<EmotionProfile> profiles) {}

    private record EmotionProfile(
        String label,
        String tag,
        double valence,
        double energy,
        double danceability,
        double acousticness,
        int mode,
        double targetTempo,
        List<String> seedGenres,
        String description
    ) {}

    private record ProfileScore(String label, String tag, double distance, double featureCoverage) {}

    private record PreviewTrack(
        String spotifyId,
        String name,
        String artistName,
        boolean moodTagsFieldPresent,
        List<String> existingMoodTags,
        Map<String, Object> audioFeatures,
        List<ProfileScore> profileScores,
        String closestProfile,
        double closestDistance,
        double marginToNextProfile,
        String status,
        String proposedMoodTag,
        List<String> approvedMoodTags
    ) {}

    private record ManifestEntry(String spotifyId, boolean beforePresent, List<String> beforeTags, List<String> afterTags) {}

    private record IgnoredDocumentSample(
        String documentId,
        String name,
        boolean spotifyIdFieldPresent,
        String spotifyIdValueType,
        String spotifyIdPreview
    ) {}
}
