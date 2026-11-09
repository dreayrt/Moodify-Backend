package com.laphuth.moodify.services;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.laphuth.moodify.repositories.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class EmotionHttpContractTest {
    @Test void slowModelHasABoundedTimeoutAndNeverInventsAResult() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/predict",exchange->{
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(10_000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var service=new EmotionService("http://127.0.0.1:"+server.getAddress().getPort(),mock(MongoTemplate.class),mock(UserRepository.class),mock(SearchHistoryRepository.class));
            long start=System.nanoTime();
            var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.predictEmotion("timeout fixture","empathy"));
            assertEquals(503,error.getStatusCode().value());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<9500);
        } finally { server.stop(0); }
    }
    @Test void realHttpSnakeCaseResponseCanBeDecoded() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/predict",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] response="{\"text\":\"fixture\",\"label\":\"Enjoyment\",\"confidence\":0.9,\"music_recommendation\":{\"strategy\":\"empathy\",\"target_valence\":0.5,\"target_energy\":0.5}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var service=new EmotionService("http://127.0.0.1:"+server.getAddress().getPort(),mock(MongoTemplate.class),mock(UserRepository.class),mock(SearchHistoryRepository.class));
            var response=service.predictEmotion("fixture","empathy");
            assertEquals("Enjoyment",response.getLabel());
            assertEquals(.5,response.getMusicRecommendation().getTargetEnergy());
        } finally { server.stop(0); }
    }
}
