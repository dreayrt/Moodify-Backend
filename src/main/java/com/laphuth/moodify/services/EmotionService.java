package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.*;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.SearchHistory;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.SearchType;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EmotionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionService.class);

    private final RestClient restClient;
    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final SearchHistoryRepository searchHistoryRepository;

    public EmotionService(
        @Value("${emotion.service.base-url}") String baseUrl,
        MongoTemplate mongoTemplate,
        UserRepository userRepository,
        SearchHistoryRepository searchHistoryRepository
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.searchHistoryRepository = searchHistoryRepository;
    }

    public EmotionPredictResponse predictEmotion(String text, String strategy) {
        String effectiveStrategy = (strategy != null && !strategy.isBlank()) ? strategy.trim() : "empathy";
        Map<String, String> requestBody = Map.of(
            "text", text.trim(),
            "strategy", effectiveStrategy
        );

        log.info("Goi dich vu AI Emotion voi text='{}', strategy='{}'", text, effectiveStrategy);
        return restClient.post()
            .uri("/predict")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(EmotionPredictResponse.class);
    }

    public MoodRecommendationResponse recommendTracksByMood(
        String text,
        String strategy,
        Integer limit,
        String principal
    ) {
        EmotionPredictResponse emotionResponse = predictEmotion(text, strategy);
        int safeLimit = (limit != null && limit > 0) ? Math.min(limit, 50) : 20;

        List<TrackResponse> recommendedTracks = findMatchingTracks(emotionResponse, safeLimit);

        // Luu lich su tim kiem neu user da dang nhap
        saveSearchHistoryIfAuthenticated(principal, text, emotionResponse);

        return new MoodRecommendationResponse(
            emotionResponse,
            recommendedTracks,
            recommendedTracks.size()
        );
    }

    private List<TrackResponse> findMatchingTracks(EmotionPredictResponse emotion, int limit) {
        MusicRecommendationInfo musicRec = emotion.getMusicRecommendation();
        double targetValence = (musicRec != null && musicRec.getTargetValence() != null) ? musicRec.getTargetValence() : 0.5;
        double minValence = (musicRec != null && musicRec.getMinValence() != null) ? musicRec.getMinValence() : 0.0;
        double maxValence = (musicRec != null && musicRec.getMaxValence() != null) ? musicRec.getMaxValence() : 1.0;

        double targetEnergy = (musicRec != null && musicRec.getTargetEnergy() != null) ? musicRec.getTargetEnergy() : 0.5;
        double minEnergy = (musicRec != null && musicRec.getMinEnergy() != null) ? musicRec.getMinEnergy() : 0.0;
        double maxEnergy = (musicRec != null && musicRec.getMaxEnergy() != null) ? musicRec.getMaxEnergy() : 1.0;

        List<String> seedGenres = (musicRec != null && musicRec.getSeedGenres() != null)
            ? musicRec.getSeedGenres()
            : Collections.emptyList();

        // 1. Lay danh sach ung vien tu MongoDB
        Query query = new Query();
        query.addCriteria(Criteria.where("moderationStatus").nin("rejected", "REJECTED"));
        query.addCriteria(Criteria.where("visibility").nin("private", "unlisted"));
        query.addCriteria(Criteria.where("status").nin("archived", "disabled", "draft"));
        query.with(Sort.by(Sort.Direction.DESC, "popularity"));
        query.limit(250);

        List<Track> candidateTracks = mongoTemplate.find(query, Track.class);
        if (candidateTracks.isEmpty()) {
            candidateTracks = mongoTemplate.find(new Query().limit(250), Track.class);
        }

        if (candidateTracks.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Tinh toan khoang cach Vector dac trung am nhac (Audio Features Distance)
        record ScoredTrack(Track track, double distance) {}

        List<ScoredTrack> scoredList = new ArrayList<>();
        for (Track track : candidateTracks) {
            Track.AudioFeatures af = track.getAudioFeatures();
            double v = (af != null && af.getValence() != null) ? af.getValence() : 0.5;
            double e = (af != null && af.getEnergy() != null) ? af.getEnergy() : 0.5;

            // Khoang cach Euclid toi target
            double dist = Math.hypot(v - targetValence, e - targetEnergy);

            // Neu nam trong dai min-max ly tuong: thuong diem (-0.2 khoang cach)
            boolean inValence = v >= minValence && v <= maxValence;
            boolean inEnergy = e >= minEnergy && e <= maxEnergy;
            if (inValence && inEnergy) {
                dist -= 0.2;
            }

            // Neu trung the loai goi y: thuong them (-0.15 khoang cach)
            if (track.getGenres() != null && !track.getGenres().isEmpty()) {
                boolean matchGenre = track.getGenres().stream().anyMatch(g ->
                    seedGenres.stream().anyMatch(sg -> sg.equalsIgnoreCase(g) || g.toLowerCase().contains(sg.toLowerCase()))
                );
                if (matchGenre) {
                    dist -= 0.15;
                }
            }

            scoredList.add(new ScoredTrack(track, dist));
        }

        // 3. Sap xep theo khoang cach tang dan (phu hop nhat len dau)
        scoredList.sort(Comparator.comparingDouble(ScoredTrack::distance));

        return scoredList.stream()
            .limit(limit)
            .map(st -> TrackResponse.from(st.track()))
            .toList();
    }

    private void saveSearchHistoryIfAuthenticated(String principal, String text, EmotionPredictResponse emotion) {
        if (principal == null || principal.isBlank() || "anonymousUser".equalsIgnoreCase(principal)) {
            return;
        }

        try {
            Optional<User> userOpt = userRepository.findByEmailOrUsername(principal, principal);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                SearchHistory history = new SearchHistory();
                history.setUser(user);
                history.setKeyword(text);
                history.setSearchType(SearchType.EMOTION);
                history.setDetectedEmotion(emotion.getLabel() != null ? emotion.getLabel().toUpperCase() : "OTHER");
                if (emotion.getConfidence() != null) {
                    history.setEmotionConfidence(BigDecimal.valueOf(emotion.getConfidence()));
                }
                history.setSearchedAt(LocalDateTime.now());
                searchHistoryRepository.save(history);
                log.info("Da luu lich su tim kiem cam xuc cho user '{}'", user.getUsername());
            }
        } catch (Exception e) {
            log.warn("Khong the luu lich su tim kiem cam xuc: {}", e.getMessage());
        }
    }

    public Page<SearchHistory> getUserSearchHistory(String principal, int page, int size) {
        User user = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new RuntimeException("User not found"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50));
        return searchHistoryRepository.findByUserOrderBySearchedAtDesc(user, pageable);
    }
}
