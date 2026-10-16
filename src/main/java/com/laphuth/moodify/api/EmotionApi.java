package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.emotion.EmotionPredictRequest;
import com.laphuth.moodify.dto.emotion.EmotionPredictResponse;
import com.laphuth.moodify.dto.emotion.MoodRecommendationResponse;
import com.laphuth.moodify.dto.searchhistory.SearchHistoryResponse;
import com.laphuth.moodify.services.EmotionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emotions")
public class EmotionApi {

    private final EmotionService emotionService;

    public EmotionApi(EmotionService emotionService) {
        this.emotionService = emotionService;
    }

    /**
     * Du doan cam xuc tu van ban (Text -> Emotion).
     */
    @PostMapping("/predict")
    public ResponseEntity<EmotionPredictResponse> predictEmotion(
        @Valid @RequestBody EmotionPredictRequest request
    ) {
        EmotionPredictResponse response = emotionService.predictEmotion(
            request.getText(),
            request.getStrategy()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Goi y bai hat phu hop theo tam trang (Text -> Emotion -> Track Playlist).
     */
    @PostMapping("/recommend")
    public ResponseEntity<MoodRecommendationResponse> recommendTracks(
        @Valid @RequestBody EmotionPredictRequest request
    ) {
        MoodRecommendationResponse response = emotionService.recommendTracksByMood(
            request.getText(),
            request.getStrategy(),
            request.getLimit()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Lay lich su tim kiem theo tam trang cua nguoi dung hien tai.
     */
    @GetMapping("/history")
    public ResponseEntity<Page<SearchHistoryResponse>> getHistory(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        Page<SearchHistoryResponse> history = emotionService.getUserSearchHistory(
            authentication.getName(),
            page,
            size
        );
        return ResponseEntity.ok(history);
    }
}
