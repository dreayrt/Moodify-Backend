package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.services.ArtistCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/artists/me", "/api/artist/me"})
public class ArtistCatalogApi {
    private final ArtistCatalogService artistCatalogService;

    public ArtistCatalogApi(ArtistCatalogService artistCatalogService) {
        this.artistCatalogService = artistCatalogService;
    }

    @GetMapping({"", "/catalog"})
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

    @GetMapping("/tracks")
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
}
