package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class KaraokeRoomRealtimeGateway {
    // Spring Boot 4 does not expose a Jackson 2 ObjectMapper bean in this app.
    // Keep a private mapper for the small WebSocket event payloads instead of
    // making the gateway depend on MVC's JSON auto-configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long heartbeatTimeoutMs;
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<MemberKey, java.util.Set<String>> memberSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<MemberKey, Long> disconnectedMembers = new ConcurrentHashMap<>();

    public KaraokeRoomRealtimeGateway(
            @Value("${karaoke.rooms.socket-heartbeat-timeout-ms:30000}") long heartbeatTimeoutMs) {
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    public void registerRoom(WebSocketSession session, Long roomId, Long userId) {
        Connection connection = new Connection(
                new ConcurrentWebSocketSessionDecorator(session, 5000, 128 * 1024),
                roomId, userId, false);
        connections.put(session.getId(), connection);
        disconnectedMembers.remove(new MemberKey(roomId, userId));
        memberSessions.computeIfAbsent(new MemberKey(roomId, userId), ignored -> ConcurrentHashMap.newKeySet())
                .add(session.getId());
    }

    public void registerRoomsList(WebSocketSession session, Long userId) {
        connections.put(session.getId(), new Connection(
                new ConcurrentWebSocketSessionDecorator(session, 5000, 128 * 1024),
                null, userId, true));
    }

    public Connection connection(String sessionId) {
        return connections.get(sessionId);
    }

    public void heartbeat(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection != null) connection.lastHeartbeat.set(System.currentTimeMillis());
    }

    public RemovedConnection remove(String sessionId) {
        Connection connection = connections.remove(sessionId);
        if (connection == null || connection.roomId == null) {
            return connection == null ? null : new RemovedConnection(connection, false);
        }
        MemberKey key = new MemberKey(connection.roomId, connection.userId);
        boolean[] last = {false};
        memberSessions.computeIfPresent(key, (ignored, remaining) -> {
            remaining.remove(sessionId);
            last[0] = remaining.isEmpty();
            return last[0] ? null : remaining;
        });
        if (last[0]) disconnectedMembers.put(key, System.currentTimeMillis());
        return new RemovedConnection(connection, last[0]);
    }

    public boolean hasRoomMemberSession(Long roomId, Long userId) {
        java.util.Set<String> sessions = memberSessions.get(new MemberKey(roomId, userId));
        return sessions != null && !sessions.isEmpty();
    }

    public List<DisconnectedMember> disconnectedMembersOlderThan(long gracePeriodMs) {
        long cutoff = System.currentTimeMillis() - gracePeriodMs;
        List<DisconnectedMember> expired = new ArrayList<>();
        disconnectedMembers.forEach((key, since) -> {
            if (since <= cutoff && !hasRoomMemberSession(key.roomId, key.userId)) {
                expired.add(new DisconnectedMember(key.roomId, key.userId));
            }
        });
        return expired;
    }

    public void forgetDisconnectedMember(Long roomId, Long userId) {
        disconnectedMembers.remove(new MemberKey(roomId, userId));
    }

    public void publishRoomChanged(Long roomId, String eventType) {
        Map<String, Object> event = Map.of("type", eventType, "roomId", roomId);
        sendTo(connection -> connection.roomsList || roomId.equals(connection.roomId), event);
    }

    public void publishRoomScoreUpdated(Long roomId, String sessionId, String status, Map<String, Object> score) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "score.updated");
        event.put("roomId", roomId);
        event.put("sessionId", sessionId);
        event.put("status", status);
        if (score != null) event.put("score", score);
        sendTo(connection -> roomId.equals(connection.roomId), event);
    }

    public void publishRoomsChanged() {
        sendTo(connection -> connection.roomsList, Map.of("type", "rooms.updated"));
    }

    public void publishRoomClosed(Long roomId) {
        sendTo(connection -> roomId.equals(connection.roomId),
                Map.of("type", "room.closed", "roomId", roomId));
    }

    public void disconnectRoomMember(Long roomId, Long userId) {
        memberSessions.getOrDefault(new MemberKey(roomId, userId), java.util.Set.of()).stream()
                .map(connections::get)
                .filter(connection -> connection != null)
                .forEach(connection -> close(connection.session, CloseStatus.NORMAL));
    }

    public void disconnectRoom(Long roomId) {
        connections.values().stream()
                .filter(connection -> roomId.equals(connection.roomId))
                .forEach(connection -> close(connection.session, CloseStatus.NORMAL));
    }

    @Scheduled(fixedDelayString = "${karaoke.rooms.socket-sweep-interval-ms:15000}")
    public void closeStaleConnections() {
        long cutoff = System.currentTimeMillis() - heartbeatTimeoutMs;
        connections.values().stream()
                .filter(connection -> connection.lastHeartbeat.get() < cutoff)
                .forEach(connection -> close(connection.session, CloseStatus.SESSION_NOT_RELIABLE));
    }

    private void sendTo(java.util.function.Predicate<Connection> predicate, Map<String, Object> event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            return;
        }
        List<Connection> recipients = new ArrayList<>(connections.values());
        for (Connection connection : recipients) {
            if (!predicate.test(connection) || !connection.session.isOpen()) continue;
            try {
                connection.session.sendMessage(new TextMessage(payload));
            } catch (IOException exception) {
                close(connection.session, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
            // The connection is already unavailable; its normal cleanup path will remove it.
        }
    }

    private record MemberKey(Long roomId, Long userId) {}

    public static final class Connection {
        private final WebSocketSession session;
        private final Long roomId;
        private final Long userId;
        private final boolean roomsList;
        private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());

        private Connection(WebSocketSession session, Long roomId, Long userId, boolean roomsList) {
            this.session = session;
            this.roomId = roomId;
            this.userId = userId;
            this.roomsList = roomsList;
        }

        public Long roomId() { return roomId; }
        public Long userId() { return userId; }
    }

    public record RemovedConnection(Connection connection, boolean lastRoomSession) {}
    public record DisconnectedMember(Long roomId, Long userId) {}
}
