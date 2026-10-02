package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.EmotionPredictResponse;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;

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
}
