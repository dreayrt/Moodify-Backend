package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistProfileResponse;
import com.laphuth.moodify.dto.artist.ArtistTrackResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.dto.artist.TrackUpdateRequest;
import com.laphuth.moodify.dto.artist.TrackUploadRequest;
import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.services.ArtistCatalogService;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping({"/api/artists", "/api/artist"})
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

    @PostMapping(value = "/me/tracks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArtistTrackResponse> uploadTrack(
        Authentication authentication,
        @ModelAttribute TrackUploadRequest request,
        @RequestParam("audioFile") MultipartFile audioFile,
        @RequestParam(value = "coverFile", required = false) MultipartFile coverFile,
        @RequestParam(value = "licenseDocFile", required = false) MultipartFile licenseDocFile
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            artistCatalogService.uploadTrack(
                authentication.getName(),
                request,
                audioFile,
                coverFile,
                licenseDocFile
            )
        );
    }

    @PatchMapping("/me/tracks/{trackId}")
    public ResponseEntity<ArtistTrackResponse> updateTrack(
        Authentication authentication,
        @PathVariable String trackId,
        @RequestBody TrackUpdateRequest request
    ) {
        return ResponseEntity.ok(
            artistCatalogService.updateTrack(
                authentication.getName(),
                trackId,
                request
            )
        );
    }

    @DeleteMapping("/me/tracks/{trackId}")
    public ResponseEntity<Void> deleteTrack(
        Authentication authentication,
        @PathVariable String trackId
    ) {
        artistCatalogService.deleteTrack(authentication.getName(), trackId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<ArtistProfileResponse>> searchArtists(
        @RequestParam(required = false) String query
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
