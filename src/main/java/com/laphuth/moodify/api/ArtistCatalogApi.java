package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.dto.artist.ArtistTrackResponse;
import com.laphuth.moodify.dto.artist.TrackUpdateRequest;
import com.laphuth.moodify.services.ArtistCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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

    @org.springframework.web.bind.annotation.PostMapping(value = "/tracks", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArtistTrackResponse> uploadTrack(
        Authentication authentication,
        @org.springframework.web.bind.annotation.ModelAttribute com.laphuth.moodify.dto.artist.TrackUploadRequest request,
        @org.springframework.web.bind.annotation.RequestParam("audioFile") org.springframework.web.multipart.MultipartFile audioFile,
        @org.springframework.web.bind.annotation.RequestParam(value = "coverFile", required = false) org.springframework.web.multipart.MultipartFile coverFile,
        @org.springframework.web.bind.annotation.RequestParam(value = "licenseDocFile", required = false) org.springframework.web.multipart.MultipartFile licenseDocFile
    ) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(
            artistCatalogService.uploadTrack(
                authentication.getName(),
                request,
                audioFile,
                coverFile,
                licenseDocFile
            )
        );
    }

    @PatchMapping("/tracks/{trackId}")
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

    @DeleteMapping("/tracks/{trackId}")
    public ResponseEntity<Void> deleteTrack(
        Authentication authentication,
        @PathVariable String trackId
    ) {
        artistCatalogService.deleteTrack(authentication.getName(), trackId);
        return ResponseEntity.noContent().build();
    }

}
