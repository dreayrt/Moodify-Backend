package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.EmotionPredictResponse;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class EmotionServiceTest {

    @Test
    void testPredictEmotionWithRunningContainer() {
        MongoTemplate mongoTemplate = Mockito.mock(MongoTemplate.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        SearchHistoryRepository searchHistoryRepository = Mockito.mock(SearchHistoryRepository.class);

        EmotionService emotionService = new EmotionService(
            "http://localhost:8000",
            mongoTemplate,
            userRepository,
            searchHistoryRepository
        );

        EmotionPredictResponse response = emotionService.predictEmotion(
            "Hôm nay tôi rất vui và yêu đời!",
            "empathy"
        );

        assertNotNull(response);
        assertEquals("Enjoyment", response.getLabel());
        assertNotNull(response.getMusicRecommendation());
        assertNotNull(response.getMusicRecommendation().getTargetValence());
        System.out.println("Test passed! Emotion: " + response.getLabel() + " (" + response.getEmoji() + ")");
        System.out.println("Target Valence: " + response.getMusicRecommendation().getTargetValence());
        System.out.println("Seed Genres: " + response.getMusicRecommendation().getSeedGenres());
    }

    @Test
    void reportsModelUnavailableInsteadOfInventingNeutralPrediction() {
        EmotionService emotionService = new EmotionService(
            "http://127.0.0.1:1",
            Mockito.mock(MongoTemplate.class),
            Mockito.mock(UserRepository.class),
            Mockito.mock(SearchHistoryRepository.class)
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> emotionService.predictEmotion("I feel sad", "empathy"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}
