package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.analytics.TrackVisitRequest;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import com.laphuth.moodify.services.PlatformTrafficService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/analytics")
public class TrafficAnalyticsApi {

    private final PlatformTrafficService platformTrafficService;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;

    public TrafficAnalyticsApi(
        PlatformTrafficService platformTrafficService,
        UserRepository userRepository,
        TrackRepository trackRepository
    ) {
        this.platformTrafficService = platformTrafficService;
        this.userRepository = userRepository;
        this.trackRepository = trackRepository;
    }

    @PostMapping("/track-visit")
    public ResponseEntity<Map<String, Object>> trackVisit(
        @RequestBody TrackVisitRequest request,
        Authentication authentication
    ) {
        Long userId = null;
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getName())) {
            Optional<User> u = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
            if (u.isPresent()) {
                userId = u.get().getId();
            }
        }

        boolean success = platformTrafficService.recordVisit(request, userId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Visit recorded" : "Failed to record visit"
        ));
    }

    @PostMapping("/seed-traffic")
    public ResponseEntity<Map<String, Object>> seedTraffic(
        Authentication authentication,
        @RequestParam(defaultValue = "150") int count
    ) {
        List<String> targetIds = new ArrayList<>();

        if (authentication != null && authentication.isAuthenticated()) {
            Optional<User> userOpt = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                String artistSpotifyId = user.getArtistSpotifyId();
                if (artistSpotifyId != null && !artistSpotifyId.isBlank()) {
                    List<Track> tracks = trackRepository.findByArtistSpotifyId(artistSpotifyId.trim());
                    for (Track t : tracks) {
                        if (t.getId() != null) targetIds.add(t.getId());
                        if (t.getSpotifyId() != null) targetIds.add(t.getSpotifyId());
                    }
                }
            }
        }

        if (targetIds.isEmpty()) {
            List<Track> sampleTracks = trackRepository.findByModerationStatus("approved");
            for (int i = 0; i < Math.min(5, sampleTracks.size()); i++) {
                Track t = sampleTracks.get(i);
                if (t.getId() != null) targetIds.add(t.getId());
            }
        }

        int inserted = platformTrafficService.seedTrafficData(targetIds, count);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "inserted", inserted,
            "targetCount", targetIds.size(),
            "message", "Successfully seeded " + inserted + " traffic events"
        ));
    }
}
