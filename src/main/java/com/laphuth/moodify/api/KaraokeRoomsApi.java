package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.karaoke.rooms.CreateKaraokeRoomRequest;
import com.laphuth.moodify.dto.karaoke.rooms.JoinKaraokeRoomRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomCreatedResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomDetailResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomQueueItemResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomSummaryResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokePlaybackCommandRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeVoiceTicketResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomContributionRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomScoreSessionResponse;
import com.laphuth.moodify.dto.karaoke.rooms.QueueKaraokeSongRequest;
import com.laphuth.moodify.services.KaraokeRoomRealtimeGateway;
import com.laphuth.moodify.services.KaraokeRoomService;
import com.laphuth.moodify.services.KaraokeMediaTicketService;
import com.laphuth.moodify.services.KaraokeRoomScoringService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/karaoke/rooms")
public class KaraokeRoomsApi {
    private final KaraokeRoomService roomService;
    private final KaraokeRoomRealtimeGateway realtimeGateway;
    private final KaraokeMediaTicketService mediaTicketService;
    private final KaraokeRoomScoringService scoringService;

    public KaraokeRoomsApi(KaraokeRoomService roomService, KaraokeRoomRealtimeGateway realtimeGateway,
                           KaraokeMediaTicketService mediaTicketService,
                           KaraokeRoomScoringService scoringService) {
        this.roomService = roomService;
        this.realtimeGateway = realtimeGateway;
        this.mediaTicketService = mediaTicketService;
        this.scoringService = scoringService;
    }

    @GetMapping
    public List<KaraokeRoomSummaryResponse> listRooms() {
        return roomService.listOpenRooms();
    }

    @PostMapping
    public ResponseEntity<KaraokeRoomCreatedResponse> createRoom(
            @Valid @RequestBody CreateKaraokeRoomRequest request, Authentication authentication) {
        KaraokeRoomCreatedResponse response = roomService.createRoom(request, authentication);
        realtimeGateway.publishRoomsChanged();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{roomId}")
    public KaraokeRoomDetailResponse getRoom(@PathVariable Long roomId, Authentication authentication) {
        return roomService.getRoom(roomId, authentication);
    }

    @PostMapping("/{roomId}/join")
    public KaraokeRoomDetailResponse joinRoom(@PathVariable Long roomId,
                                               @Valid @RequestBody(required = false) JoinKaraokeRoomRequest request,
                                               Authentication authentication) {
        KaraokeRoomDetailResponse response = roomService.joinRoom(roomId, request, authentication);
        realtimeGateway.publishRoomChanged(roomId, "member.joined");
        return response;
    }

    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable Long roomId, Authentication authentication) {
        Long userId = roomService.authenticatedUserId(authentication);
        boolean changed = roomService.leaveRoom(roomId, authentication);
        realtimeGateway.disconnectRoomMember(roomId, userId);
        if (changed) realtimeGateway.publishRoomChanged(roomId, "member.left");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roomId}/queue")
    public KaraokeRoomQueueItemResponse addQueueItem(@PathVariable Long roomId,
                                                      @Valid @RequestBody QueueKaraokeSongRequest request,
                                                      Authentication authentication) {
        KaraokeRoomQueueItemResponse response = roomService.addQueueItem(roomId, request, authentication);
        realtimeGateway.publishRoomChanged(roomId, "queue.updated");
        return response;
    }

    @DeleteMapping("/{roomId}/queue/{itemId}")
    public ResponseEntity<Void> removeQueueItem(@PathVariable Long roomId, @PathVariable Long itemId,
                                                 Authentication authentication) {
        boolean changed = roomService.removeQueueItem(roomId, itemId, authentication);
        if (changed) realtimeGateway.publishRoomChanged(roomId, "queue.updated");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roomId}/close")
    public ResponseEntity<Void> closeRoom(@PathVariable Long roomId, Authentication authentication) {
        boolean changed = roomService.closeRoom(roomId, authentication);
        realtimeGateway.disconnectRoom(roomId);
        if (changed) realtimeGateway.publishRoomsChanged();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roomId}/playback/start")
    public KaraokeRoomDetailResponse startPlayback(@PathVariable Long roomId,
            @Valid @RequestBody KaraokePlaybackCommandRequest request, Authentication authentication) {
        KaraokeRoomDetailResponse response = roomService.startPlayback(roomId, request.expectedVersion(), authentication);
        realtimeGateway.publishRoomChanged(roomId, "playback.updated");
        return response;
    }

    @PostMapping("/{roomId}/playback/pause")
    public KaraokeRoomDetailResponse pausePlayback(@PathVariable Long roomId,
            @Valid @RequestBody KaraokePlaybackCommandRequest request, Authentication authentication) {
        KaraokeRoomDetailResponse response = roomService.pausePlayback(roomId, request.expectedVersion(), authentication);
        realtimeGateway.publishRoomChanged(roomId, "playback.updated");
        return response;
    }

    @PostMapping("/{roomId}/playback/resume")
    public KaraokeRoomDetailResponse resumePlayback(@PathVariable Long roomId,
            @Valid @RequestBody KaraokePlaybackCommandRequest request, Authentication authentication) {
        KaraokeRoomDetailResponse response = roomService.resumePlayback(roomId, request.expectedVersion(), authentication);
        realtimeGateway.publishRoomChanged(roomId, "playback.updated");
        return response;
    }

    @PostMapping("/{roomId}/playback/skip")
    public KaraokeRoomDetailResponse skipPlayback(@PathVariable Long roomId,
            @Valid @RequestBody KaraokePlaybackCommandRequest request, Authentication authentication) {
        KaraokeRoomDetailResponse response = roomService.skipPlayback(roomId, request.expectedVersion(), authentication);
        realtimeGateway.publishRoomChanged(roomId, "playback.updated");
        return response;
    }

    @PostMapping("/{roomId}/voice-ticket")
    public KaraokeVoiceTicketResponse createVoiceTicket(@PathVariable Long roomId, Authentication authentication) {
        return mediaTicketService.createTicket(roomId, authentication);
    }

    @PostMapping("/{roomId}/score-sessions/current")
    public KaraokeRoomScoreSessionResponse startRoomScoreSession(@PathVariable Long roomId,
                                                                  Authentication authentication) {
        return scoringService.startCurrent(roomId, authentication);
    }

    @PostMapping("/{roomId}/score-sessions/{sessionId}/participants/me")
    public KaraokeRoomScoreSessionResponse registerScoreParticipant(@PathVariable Long roomId,
            @PathVariable String sessionId, Authentication authentication) {
        return scoringService.registerParticipant(roomId, sessionId, authentication);
    }

    @PutMapping("/{roomId}/score-sessions/{sessionId}/contribution")
    public KaraokeRoomScoreSessionResponse submitRoomContribution(@PathVariable Long roomId,
            @PathVariable String sessionId, @RequestBody KaraokeRoomContributionRequest request,
            Authentication authentication) {
        return scoringService.submitContribution(roomId, sessionId, request, authentication);
    }

    @PostMapping("/{roomId}/score-sessions/{sessionId}/finish")
    public KaraokeRoomScoreSessionResponse finishRoomScoreSession(@PathVariable Long roomId,
            @PathVariable String sessionId, Authentication authentication) {
        return scoringService.finish(roomId, sessionId, authentication);
    }

    @GetMapping("/{roomId}/score-sessions/{sessionId}")
    public KaraokeRoomScoreSessionResponse getRoomScoreSession(@PathVariable Long roomId,
            @PathVariable String sessionId, Authentication authentication) {
        return scoringService.get(roomId, sessionId, authentication);
    }
}
