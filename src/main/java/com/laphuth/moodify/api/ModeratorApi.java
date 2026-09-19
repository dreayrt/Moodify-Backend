package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.moderator.ModerationDecisionRequest;
import com.laphuth.moodify.dto.moderator.ModerationHistoryResponse;
import com.laphuth.moodify.dto.moderator.ModerationQueueTrackResponse;
import com.laphuth.moodify.services.ModeratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/moderator")
public class ModeratorApi {

    private final ModeratorService moderatorService;

    public ModeratorApi(ModeratorService moderatorService) {
        this.moderatorService = moderatorService;
    }

    @GetMapping("/queue")
    public ResponseEntity<List<ModerationQueueTrackResponse>> getPendingQueue() {
        return ResponseEntity.ok(moderatorService.getPendingQueue());
    }

    @GetMapping("/history")
    public ResponseEntity<List<ModerationHistoryResponse>> getHistory() {
        return ResponseEntity.ok(moderatorService.getHistory());
    }

    @PostMapping("/decision")
    public ResponseEntity<?> processDecision(
        Authentication authentication,
        @RequestBody ModerationDecisionRequest request
    ) {
        moderatorService.processDecision(request, authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Processed moderation decision successfully"));
    }
}
