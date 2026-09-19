package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.playlist.AddTrackToPlaylistRequest;
import com.laphuth.moodify.dto.playlist.CreatePlaylistRequest;
import com.laphuth.moodify.dto.playlist.PlaylistResponse;
import com.laphuth.moodify.services.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistApi {
    private final PlaylistService playlistService;

    public PlaylistApi(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @PostMapping
    public ResponseEntity<PlaylistResponse> createPlaylist(
        Authentication authentication,
        @Valid @RequestBody CreatePlaylistRequest request
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(playlistService.createPlaylist(authentication.getName(), request));
    }

    @GetMapping("/me")
    public ResponseEntity<List<PlaylistResponse>> getUserPlaylists(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ResponseEntity.ok(playlistService.getUserPlaylists(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistResponse> getPlaylist(@PathVariable String id) {
        try {
            return ResponseEntity.ok(playlistService.getPlaylist(id));
        } catch (PlaylistService.PlaylistNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{id}/tracks")
    public ResponseEntity<PlaylistResponse> addTrack(
        Authentication authentication,
        @PathVariable String id,
        @Valid @RequestBody AddTrackToPlaylistRequest request
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return ResponseEntity.ok(playlistService.addTrackToPlaylist(id, authentication.getName(), request.trackSpotifyId()));
        } catch (PlaylistService.PlaylistNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/tracks/{trackSpotifyId}")
    public ResponseEntity<PlaylistResponse> removeTrack(
        Authentication authentication,
        @PathVariable String id,
        @PathVariable String trackSpotifyId
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return ResponseEntity.ok(playlistService.removeTrackFromPlaylist(id, authentication.getName(), trackSpotifyId));
        } catch (PlaylistService.PlaylistNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePlaylist(
        Authentication authentication,
        @PathVariable String id
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            playlistService.deletePlaylist(id, authentication.getName());
            return ResponseEntity.noContent().build();
        } catch (PlaylistService.PlaylistNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
