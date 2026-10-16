package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.EmotionPredictResponse;
import com.laphuth.moodify.dto.emotion.MoodCatalogDiagnostics;
import com.laphuth.moodify.dto.emotion.MoodRecommendationResponse;
import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.dto.searchhistory.SearchHistoryResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Stream;

@Service
public class EmotionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionService.class);
    private static final String RANKING_VERSION = "audio-profile-v4-mood-tags";
    private static final int LIMITED_RESULT_COUNT = 3;

    private final RestClient restClient;
    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final MoodTrackScorer moodTrackScorer;
    private final double maxMatchDistance;

    @Autowired
    public EmotionService(
        @Value("${emotion.service.base-url:http://localhost:8000}") String baseUrl,
        @Value("${mood.recommendations.max-distance:0.35}") double maxMatchDistance,
        MongoTemplate mongoTemplate,
        UserRepository userRepository,
        SearchHistoryRepository searchHistoryRepository
    ) {
        String effectiveUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "http://localhost:8000";
        this.restClient = RestClient.builder().baseUrl(effectiveUrl).build();
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.searchHistoryRepository = searchHistoryRepository;
        this.moodTrackScorer = new MoodTrackScorer();
        this.maxMatchDistance = Double.isFinite(maxMatchDistance)
            ? Math.clamp(maxMatchDistance, 0.0, 1.0)
            : 0.35;
    }

    /** Compatibility constructor for existing callers and tests that use the default cutoff. */
    public EmotionService(
        String baseUrl,
        MongoTemplate mongoTemplate,
        UserRepository userRepository,
        SearchHistoryRepository searchHistoryRepository
    ) {
        this(baseUrl, 0.35, mongoTemplate, userRepository, searchHistoryRepository);
    }

    public EmotionPredictResponse predictEmotion(String text, String strategy) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text cannot be blank");
        }

        String cleanText = text.trim();
        String effectiveStrategy = (strategy != null && !strategy.isBlank()) ? strategy.trim() : "empathy";
        Map<String, String> requestBody = Map.of(
            "text", cleanText,
            "strategy", effectiveStrategy
        );

        log.info("Requesting emotion prediction (textLength={}, strategy={})", cleanText.length(), effectiveStrategy);
        try {
            EmotionPredictResponse response = restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(EmotionPredictResponse.class);

            if (response == null || response.getLabel() == null || response.getLabel().isBlank()) {
                log.warn("Emotion service returned an empty or malformed prediction");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Emotion service returned an invalid response");
            }
            return response;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Emotion service rejected or failed the prediction (status={})", e.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Emotion service could not process the request");
        } catch (Exception e) {
            log.warn("Emotion service is unavailable ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Emotion service is unavailable");
        }
    }

    public MoodRecommendationResponse recommendTracksByMood(String text, String strategy, Integer limit) {
        EmotionPredictResponse emotionResponse = predictEmotion(text, strategy);
        MusicRecommendationInfo profile = emotionResponse.getMusicRecommendation();
        if (profile == null) {
            log.warn("Emotion service prediction omitted its music recommendation profile");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Emotion service returned an incomplete recommendation profile");
        }
        if (!isUnitValue(profile.getTargetValence()) || !isUnitValue(profile.getTargetEnergy())) {
            log.warn("Emotion service recommendation profile omitted valid valence or energy targets");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Emotion service returned an invalid recommendation profile");
        }

        int safeLimit = (limit != null && limit > 0) ? Math.min(limit, 50) : 20;
        RecommendationSelection selection = findMatchingTracks(profile, emotionResponse.getLabel(), safeLimit);

        return new MoodRecommendationResponse(
            emotionResponse,
            selection.tracks(),
            selection.totalMatched(),
            selection.candidateCount(),
            selection.matchStatus(),
            RANKING_VERSION,
            maxMatchDistance,
            selection.catalogDiagnostics()
        );
    }

    private RecommendationSelection findMatchingTracks(MusicRecommendationInfo profile, String emotionLabel, int limit) {
        Query query = new Query();
        query.addCriteria(Criteria.where("moderationStatus").nin("rejected", "REJECTED"));
        query.addCriteria(Criteria.where("visibility").nin("private", "unlisted"));
        query.addCriteria(Criteria.where("status").nin("archived", "disabled", "draft"));
        query.addCriteria(Criteria.where("spotifyId").type(2).ne(""));

        record ScoredTrack(Track track, MoodTrackScorer.Score score) {}
        Comparator<ScoredTrack> rankOrder = Comparator
            .comparingDouble((ScoredTrack item) -> item.score().distance())
            .thenComparing((ScoredTrack item) -> item.track().getPopularity(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.track().getSpotifyId(), Comparator.nullsLast(String::compareTo));

        PriorityQueue<ScoredTrack> bestMatches = new PriorityQueue<>(rankOrder.reversed());
        PriorityQueue<ScoredTrack> closestCandidates = new PriorityQueue<>(rankOrder.reversed());
        int candidateCount = 0;
        int matchedCount = 0;
        int tracksWithAudioFeatures = 0;
        int tracksWithCompleteAudioFeatures = 0;
        int tracksWithGenreTags = 0;
        int tracksWithMoodTags = 0;
        double totalFeatureCoverage = 0.0;
        try (Stream<Track> candidates = mongoTemplate.stream(query, Track.class)) {
            var iterator = candidates.iterator();
            while (iterator.hasNext()) {
                Track candidate = iterator.next();
                candidateCount++;
                ScoredTrack item = new ScoredTrack(candidate, moodTrackScorer.score(candidate, profile, emotionLabel));
                double featureCoverage = item.score().featureCoverage();
                totalFeatureCoverage += featureCoverage;
                if (featureCoverage > 0.0) tracksWithAudioFeatures++;
                if (featureCoverage >= 1.0) tracksWithCompleteAudioFeatures++;
                if (hasTags(candidate.getGenres()) || hasTags(candidate.getGenresRaw())) tracksWithGenreTags++;
                if (hasTags(candidate.getMoodTags())) tracksWithMoodTags++;
                addTopCandidate(closestCandidates, item, LIMITED_RESULT_COUNT, rankOrder);
                if (item.score().distance() <= maxMatchDistance) {
                    matchedCount++;
                    addTopCandidate(bestMatches, item, limit, rankOrder);
                }
            }
        }

        MoodCatalogDiagnostics catalogDiagnostics = new MoodCatalogDiagnostics(
            tracksWithAudioFeatures,
            tracksWithCompleteAudioFeatures,
            tracksWithGenreTags,
            tracksWithMoodTags,
            candidateCount == 0 ? 0.0 : round(totalFeatureCoverage / candidateCount)
        );

        if (candidateCount == 0) {
            return new RecommendationSelection(List.of(), 0, 0, "NO_CANDIDATES", catalogDiagnostics);
        }

        List<TrackResponse> recommendations;
        String status;
        if (matchedCount > 0) {
            recommendations = bestMatches.stream()
                .sorted(rankOrder)
                .map(item -> TrackResponse.fromMood(item.track(), item.score()))
                .toList();
            status = "MATCHED";
        } else {
            recommendations = closestCandidates.stream()
                .sorted(rankOrder)
                .limit(Math.min(limit, LIMITED_RESULT_COUNT))
                .map(item -> TrackResponse.fromMood(item.track(), item.score()))
                .toList();
            status = "LIMITED";
        }

        return new RecommendationSelection(recommendations, matchedCount, candidateCount, status, catalogDiagnostics);
    }

    private static <T> void addTopCandidate(
        PriorityQueue<T> candidates,
        T candidate,
        int limit,
        Comparator<T> rankOrder
    ) {
        if (limit < 1) return;
        candidates.add(candidate);
        if (candidates.size() > limit) candidates.poll();
    }

    private static boolean isUnitValue(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean hasTags(List<String> tags) {
        return tags != null && tags.stream().anyMatch(tag -> tag != null && !tag.isBlank());
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public Page<SearchHistoryResponse> getUserSearchHistory(String principal, int page, int size) {
        User user = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new RuntimeException("User not found"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50));
        return searchHistoryRepository.findByUserOrderBySearchedAtDesc(user, pageable)
            .map(SearchHistoryResponse::from);
    }

    private record RecommendationSelection(
        List<TrackResponse> tracks,
        int totalMatched,
        int candidateCount,
        String matchStatus,
        MoodCatalogDiagnostics catalogDiagnostics
    ) {}
}
