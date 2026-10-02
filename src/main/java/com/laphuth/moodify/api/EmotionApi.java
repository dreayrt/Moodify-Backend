package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.emotion.EmotionPredictRequest;
import com.laphuth.moodify.dto.emotion.EmotionPredictResponse;
import com.laphuth.moodify.dto.emotion.MoodRecommendationResponse;
import com.laphuth.moodify.entities.SearchHistory;
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
     * Neu nguoi dung da dang nhap, he thong se tu dong ghi nhan vao search_history.
     */
    @PostMapping("/recommend")
    public ResponseEntity<MoodRecommendationResponse> recommendTracks(
        @Valid @RequestBody EmotionPredictRequest request,
        Authentication authentication
    ) {
        String principal = (authentication != null) ? authentication.getName() : null;
        MoodRecommendationResponse response = emotionService.recommendTracksByMood(
            request.getText(),
            request.getStrategy(),
            request.getLimit(),
            principal
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Lay lich su tim kiem theo tam trang cua nguoi dung hien tai.
     */
    @GetMapping("/history")
    public ResponseEntity<Page<SearchHistory>> getHistory(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        Page<SearchHistory> history = emotionService.getUserSearchHistory(
            authentication.getName(),
            page,
            size
        );
        return ResponseEntity.ok(history);
    }
}
