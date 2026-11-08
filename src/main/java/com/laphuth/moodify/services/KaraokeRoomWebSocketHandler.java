package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.security.JwtService;
import com.laphuth.moodify.security.TokenType;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

@Component
public class KaraokeRoomWebSocketHandler extends TextWebSocketHandler {
    // Use a local mapper: this handler only parses/serializes WebSocket frames,
    // and should not require a Jackson 2 bean from Spring Boot 4.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserDeviceService userDeviceService;
    private final KaraokeRoomService roomService;
    private final KaraokeRoomRealtimeGateway realtimeGateway;

    public KaraokeRoomWebSocketHandler(JwtService jwtService,
                                       UserRepository userRepository,
                                       UserDeviceService userDeviceService,
                                       KaraokeRoomService roomService,
                                       KaraokeRoomRealtimeGateway realtimeGateway) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userDeviceService = userDeviceService;
        this.roomService = roomService;
        this.realtimeGateway = realtimeGateway;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception exception) {
            reject(session, "INVALID_MESSAGE");
            return;
        }

        KaraokeRoomRealtimeGateway.Connection connection = realtimeGateway.connection(session.getId());
        if (connection == null) {
            subscribe(session, payload);
            return;
        }
        if ("heartbeat".equals(payload.path("type").asText())) {
            realtimeGateway.heartbeat(session.getId());
            send(session, Map.of("type", "heartbeat.ack"));
            return;
        }
        reject(session, "UNSUPPORTED_MESSAGE");
    }

    private void subscribe(WebSocketSession session, JsonNode payload) throws IOException {
        String type = payload.path("type").asText();
        String token = payload.path("accessToken").asText();
        User user = authenticate(token);
        if (user == null) {
            reject(session, "AUTHENTICATION_REQUIRED");
            return;
        }
        String deviceUuid = payload.path("deviceUuid").asText();
        if (!deviceUuid.isBlank() && !userDeviceService.isDeviceActive(user.getId(), deviceUuid)) {
            reject(session, "DEVICE_NOT_ACTIVE");
            return;
        }

        if ("subscribeRooms".equals(type)) {
            realtimeGateway.registerRoomsList(session, user.getId());
            send(session, Map.of("type", "subscribed", "scope", "rooms"));
            return;
        }
        if (!"subscribeRoom".equals(type) || !payload.path("roomId").canConvertToLong()) {
            reject(session, "INVALID_SUBSCRIPTION");
            return;
        }

        Long roomId = payload.path("roomId").asLong();
        if (!roomService.isRoomOpen(roomId) || !roomService.isActiveMember(roomId, user.getId())) {
            reject(session, "ROOM_MEMBERSHIP_REQUIRED");
            return;
        }
        realtimeGateway.registerRoom(session, roomId, user.getId());
        send(session, Map.of("type", "subscribed", "scope", "room", "roomId", roomId));
    }

    private User authenticate(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String username = jwtService.extractUsername(token);
            if (!jwtService.isTokenValid(token, username, TokenType.ACCESS)) return null;
            return userRepository.findByUsername(username)
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .orElse(null);
        } catch (JwtException | IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        KaraokeRoomRealtimeGateway.RemovedConnection removed = realtimeGateway.remove(session.getId());
        if (removed == null || removed.connection().roomId() == null || !removed.lastRoomSession()) return;
        Long roomId = removed.connection().roomId();
        Long userId = removed.connection().userId();
        // Keep membership for a short reconnect grace period. The cleanup task
        // expires it only if no replacement socket reconnects in that window.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private void reject(WebSocketSession session, String code) throws IOException {
        if (session.isOpen()) {
            send(session, Map.of("type", "error", "code", code));
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    private void send(WebSocketSession session, Map<String, Object> value) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(value)));
    }
}
