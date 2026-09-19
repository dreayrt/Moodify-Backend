package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.library.LibraryPageResponse;
import com.laphuth.moodify.services.UserLibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users/me/library")
public class UserLibraryApi {
    private final UserLibraryService libraryService;

    public UserLibraryApi(UserLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping("/tracks/{trackSpotifyId}")
    public ResponseEntity<Void> addTrack(
        Authentication authentication,
        @PathVariable String trackSpotifyId
    ) {
        libraryService.addTrack(authentication.getName(), trackSpotifyId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/tracks/{trackSpotifyId}")
    public ResponseEntity<Void> removeTrack(
        Authentication authentication,
        @PathVariable String trackSpotifyId
    ) {
        libraryService.removeTrack(authentication.getName(), trackSpotifyId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tracks")
    public ResponseEntity<LibraryPageResponse> getUserTracks(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
            libraryService.getUserTracks(authentication.getName(), page, size)
        );
    }

    @GetMapping("/track-ids")
    public ResponseEntity<List<String>> getLikedTrackIds(Authentication authentication) {
        return ResponseEntity.ok(
            libraryService.getLikedTrackIds(authentication.getName())
        );
    }

    @GetMapping("/tracks/{trackSpotifyId}/exists")
    public ResponseEntity<Map<String, Boolean>> checkTrackExists(
        Authentication authentication,
        @PathVariable String trackSpotifyId
    ) {
        boolean exists = libraryService.isTrackInLibrary(
            authentication.getName(),
            trackSpotifyId
        );
        return ResponseEntity.ok(Map.of("exists", exists));
    }
}
