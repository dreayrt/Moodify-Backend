package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.karaoke.rooms.KaraokeVoiceTicketResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Date;
import java.util.UUID;

@Service
public class KaraokeMediaTicketService {
    private final KaraokeRoomService roomService;
    private final String ticketSecret;
    private final String serviceSecret;
    private final String websocketUrl;
    private final ConcurrentMap<String, VoiceLease> leases = new ConcurrentHashMap<>();
    private static final long TICKET_TTL_SECONDS = 60;
    private static final long LEASE_TTL_SECONDS = 15;

    public KaraokeMediaTicketService(KaraokeRoomService roomService,
                                     @Value("${karaoke.media.ticket-secret:}") String ticketSecret,
                                     @Value("${karaoke.media.service-secret:}") String serviceSecret,
                                     @Value("${karaoke.media.websocket-url:}") String websocketUrl) {
        this.roomService = roomService;
        this.ticketSecret = ticketSecret;
        this.serviceSecret = serviceSecret;
        this.websocketUrl = websocketUrl;
    }

    public KaraokeVoiceTicketResponse createTicket(Long roomId, Authentication authentication) {
        Long userId = roomService.authenticatedUserId(authentication);
        if (!roomService.isActiveMemberInOpenRoom(roomId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_MEMBERSHIP_REQUIRED");
        }
        if (ticketSecret.getBytes(StandardCharsets.UTF_8).length < 32
                || serviceSecret.getBytes(StandardCharsets.UTF_8).length < 32 || websocketUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KARAOKE_MEDIA_NOT_CONFIGURED");
        }
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(TICKET_TTL_SECONDS);
        String sessionId = UUID.randomUUID().toString();
        String ticket = Jwts.builder()
                .subject(Long.toString(userId))
                .issuer("moodify-karaoke-backend")
                .claim("aud", "moodify-karaoke-media")
                .claim("roomId", roomId)
                .claim("type", "karaoke-voice")
                .id(sessionId)
                .claim("sid", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(Keys.hmacShaKeyFor(ticketSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        return new KaraokeVoiceTicketResponse(ticket, websocketUrl, expires);
    }

    public boolean consumeTicket(String signedTicket, String sessionId, Long userId, Long roomId) {
        if (!matchesTicket(signedTicket, sessionId, userId, roomId)) return false;
        if (!roomService.isActiveMemberInOpenRoom(roomId, userId)) return false;
        leases.put(sessionId, new VoiceLease(userId, roomId, Instant.now().plusSeconds(LEASE_TTL_SECONDS)));
        return true;
    }

    public boolean renewLease(String sessionId, Long userId, Long roomId) {
        if (sessionId == null || userId == null || roomId == null) return false;
        VoiceLease previous = leases.get(sessionId);
        if (previous == null || !previous.userId.equals(userId) || !previous.roomId.equals(roomId)
                || previous.expiresAt.isBefore(Instant.now())) return false;
        if (!roomService.isActiveMemberInOpenRoom(roomId, userId)) {
            leases.remove(sessionId, previous);
            return false;
        }
        return leases.replace(sessionId, previous,
                new VoiceLease(userId, roomId, Instant.now().plusSeconds(LEASE_TTL_SECONDS)));
    }

    public void releaseLease(String sessionId, Long userId, Long roomId) {
        if (sessionId == null || userId == null || roomId == null) return;
        leases.computeIfPresent(sessionId, (ignored, lease) ->
                lease.userId.equals(userId) && lease.roomId.equals(roomId) ? null : lease);
    }

    public boolean isValidServiceKey(String candidate) {
        if (candidate == null || serviceSecret.getBytes(StandardCharsets.UTF_8).length < 32) return false;
        return java.security.MessageDigest.isEqual(
                serviceSecret.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelayString = "${karaoke.media.registry-sweep-ms:30000}")
    public void expireRegistries() {
        Instant now = Instant.now();
        leases.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private boolean matchesTicket(String signedTicket, String sessionId, Long userId, Long roomId) {
        if (signedTicket == null || signedTicket.isBlank() || sessionId == null || userId == null || roomId == null
                || ticketSecret.getBytes(StandardCharsets.UTF_8).length < 32) return false;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(ticketSecret.getBytes(StandardCharsets.UTF_8)))
                    .requireIssuer("moodify-karaoke-backend")
                    .requireAudience("moodify-karaoke-media")
                    .build()
                    .parseSignedClaims(signedTicket)
                    .getPayload();
            return "karaoke-voice".equals(claims.get("type", String.class))
                    && sessionId.equals(claims.getId())
                    && sessionId.equals(claims.get("sid", String.class))
                    && Long.toString(userId).equals(claims.getSubject())
                    && Long.toString(roomId).equals(String.valueOf(claims.get("roomId")));
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    private record VoiceLease(Long userId, Long roomId, Instant expiresAt) {}
}
