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
    void testPredictEmotionWithHttpDependency() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/predict", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"label\":\"Enjoyment\",\"confidence\":0.9,\"music_recommendation\":{\"target_valence\":0.8,\"target_energy\":0.7}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
        MongoTemplate mongoTemplate = Mockito.mock(MongoTemplate.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        SearchHistoryRepository searchHistoryRepository = Mockito.mock(SearchHistoryRepository.class);

        EmotionService emotionService = new EmotionService(
            "http://127.0.0.1:" + server.getAddress().getPort(),
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
        } finally { server.stop(0); }
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
