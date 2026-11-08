package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.karaoke.rooms.KaraokeVoiceSessionRequest;
import com.laphuth.moodify.services.KaraokeMediaTicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/karaoke/media-sessions")
public class KaraokeMediaSessionsApi {
    private static final String SERVICE_KEY_HEADER = "X-Karaoke-Media-Key";
    private final KaraokeMediaTicketService ticketService;

    public KaraokeMediaSessionsApi(KaraokeMediaTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/consume")
    public Map<String, Boolean> consume(@RequestHeader(SERVICE_KEY_HEADER) String serviceKey,
                                        @Valid @RequestBody KaraokeVoiceSessionRequest request) {
        requireService(serviceKey);
        return Map.of("valid", ticketService.consumeTicket(
                request.ticket(), request.sessionId(), request.userId(), request.roomId()));
    }

    @PostMapping("/lease")
    public Map<String, Boolean> renew(@RequestHeader(SERVICE_KEY_HEADER) String serviceKey,
                                      @Valid @RequestBody KaraokeVoiceSessionRequest request) {
        requireService(serviceKey);
        return Map.of("valid", ticketService.renewLease(request.sessionId(), request.userId(), request.roomId()));
    }

    @PostMapping("/release")
    public ResponseEntity<Void> release(@RequestHeader(SERVICE_KEY_HEADER) String serviceKey,
                                         @Valid @RequestBody KaraokeVoiceSessionRequest request) {
        requireService(serviceKey);
        ticketService.releaseLease(request.sessionId(), request.userId(), request.roomId());
        return ResponseEntity.noContent().build();
    }

    private void requireService(String candidate) {
        if (!ticketService.isValidServiceKey(candidate)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "KARAOKE_MEDIA_SERVICE_UNAUTHORIZED");
        }
    }
}
