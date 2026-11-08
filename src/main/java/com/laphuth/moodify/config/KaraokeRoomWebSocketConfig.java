package com.laphuth.moodify.config;

import com.laphuth.moodify.services.KaraokeRoomWebSocketHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class KaraokeRoomWebSocketConfig implements WebSocketConfigurer {
    private final KaraokeRoomWebSocketHandler handler;
    private final String allowedOrigins;

    public KaraokeRoomWebSocketConfig(KaraokeRoomWebSocketHandler handler,
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            origins = new String[]{"http://localhost:3000", "http://127.0.0.1:3000"};
        }
        // Production routes the API host's /api/* path to Spring Boot; the bare path falls through to Next.js.
        registry.addHandler(handler, "/ws/karaoke", "/api/ws/karaoke")
                .setAllowedOriginPatterns(origins);
    }
}
