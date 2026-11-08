package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.album.*;
import com.laphuth.moodify.services.AlbumService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/albums")
public class AlbumApi {

    private final AlbumService albumService;

    public AlbumApi(AlbumService albumService) {
        this.albumService = albumService;
    }

    @GetMapping
    public ResponseEntity<Page<AlbumResponse>> getPublicAlbums(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication authentication
    ) {
        String username = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(albumService.getPublicAlbums(q, page, size, username));
    }

    @GetMapping("/me")
    public ResponseEntity<List<AlbumResponse>> getUserAlbums(Authentication authentication) {
        requireAuth(authentication);
        return ResponseEntity.ok(albumService.getUserAlbums(authentication.getName()));
    }

    @GetMapping("/favorites/me")
    public ResponseEntity<List<AlbumResponse>> getUserFavoriteAlbums(Authentication authentication) {
        requireAuth(authentication);
        return ResponseEntity.ok(albumService.getUserFavoriteAlbums(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlbumDetailResponse> getAlbumDetail(
        @PathVariable String id,
        Authentication authentication
    ) {
        String username = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(albumService.getAlbumDetail(id, username));
    }

    @PostMapping
    public ResponseEntity<AlbumResponse> createAlbum(
        Authentication authentication,
        @Valid @RequestBody CreateAlbumRequest request
    ) {
        requireAuth(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(albumService.createAlbum(authentication.getName(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AlbumResponse> updateAlbum(
        @PathVariable Long id,
        Authentication authentication,
        @Valid @RequestBody UpdateAlbumRequest request
    ) {
        requireAuth(authentication);
        return ResponseEntity.ok(albumService.updateAlbum(id, authentication.getName(), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlbum(
        @PathVariable Long id,
        Authentication authentication
    ) {
        requireAuth(authentication);
        albumService.deleteAlbum(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tracks")
    public ResponseEntity<AlbumDetailResponse> addTrack(
        @PathVariable Long id,
        Authentication authentication,
        @Valid @RequestBody AddTrackToAlbumRequest request
    ) {
        requireAuth(authentication);
        return ResponseEntity.ok(albumService.addTrackToAlbum(id, authentication.getName(), request.trackId()));
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    public ResponseEntity<AlbumDetailResponse> removeTrack(
        @PathVariable Long id,
        @PathVariable String trackId,
        Authentication authentication
    ) {
        requireAuth(authentication);
        return ResponseEntity.ok(albumService.removeTrackFromAlbum(id, authentication.getName(), trackId));
    }

    @PostMapping("/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(
        @PathVariable String id,
        Authentication authentication
    ) {
        requireAuth(authentication);
        boolean favorited = albumService.toggleFavoriteAlbum(authentication.getName(), id);
        return ResponseEntity.ok(Map.of("favorited", favorited, "albumId", id));
    }

    @GetMapping("/{id}/favorite/status")
    public ResponseEntity<Map<String, Object>> getFavoriteStatus(
        @PathVariable String id,
        Authentication authentication
    ) {
        boolean favorited = authentication != null && albumService.isAlbumFavorited(authentication.getName(), id);
        return ResponseEntity.ok(Map.of("favorited", favorited, "albumId", id));
    }

    private void requireAuth(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để thực hiện thao tác này");
        }
    }
}
