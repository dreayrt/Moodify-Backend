package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistProfileResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.services.ArtistCatalogService;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/artists")
public class ArtistCatalogApi {
    private final TrackService trackService;
    private final ArtistCatalogService artistCatalogService;

    public ArtistCatalogApi(TrackService trackService, ArtistCatalogService artistCatalogService) {
        this.trackService = trackService;
        this.artistCatalogService = artistCatalogService;
    }

    @GetMapping("/{artistId}/tracks")
    public ResponseEntity<TrackPageResponse> getArtistTracks(
        @PathVariable String artistId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(trackService.getTracksByArtist(artistId, page, size));
    }

    @GetMapping({"/me", "/me/catalog"})
    public ResponseEntity<ArtistCatalogResponse> getMyCatalog(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(
            artistCatalogService.getCurrentArtistCatalog(
                authentication.getName(),
                page,
                size,
                query
            )
        );
    }

    @GetMapping("/me/tracks")
    public ResponseEntity<ArtistTracksPageResponse> getMyTracks(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(
            artistCatalogService.getCurrentArtistTracks(
                authentication.getName(),
                page,
                size,
                query
            )
        );
    }

    @GetMapping
    public ResponseEntity<List<ArtistProfileResponse>> searchArtists(
        @RequestParam String query
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        
        List<Artist> artists = artistCatalogService.searchArtists(query.trim());
        List<ArtistProfileResponse> response = artists.stream()
            .map(ArtistProfileResponse::from)
            .toList();
        
        return ResponseEntity.ok(response);
    }
}
