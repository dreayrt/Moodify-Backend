package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.contentlead.ContentLeadCatalogResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadTrackResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadTracksPageResponse;
import com.laphuth.moodify.dto.contentlead.TrackUpdateRequest;
import com.laphuth.moodify.dto.contentlead.TrackUploadRequest;
import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.ocr.OcrExtractResponse;
import com.laphuth.moodify.services.ContentLeadCatalogService;
import com.laphuth.moodify.services.OcrService;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import com.laphuth.moodify.dto.contentlead.ContentLeadAudienceResponse;
import com.laphuth.moodify.services.ContentLeadAnalyticsService;

@RestController
@RequestMapping({"/api/content-lead", "/api/artists", "/api/artist"})
public class ContentLeadCatalogApi {
    private final TrackService trackService;
    private final ContentLeadCatalogService contentLeadCatalogService;
    private final ContentLeadAnalyticsService contentLeadAnalyticsService;
    private final OcrService ocrService;

    public ContentLeadCatalogApi(
        TrackService trackService,
        ContentLeadCatalogService contentLeadCatalogService,
        ContentLeadAnalyticsService contentLeadAnalyticsService,
        OcrService ocrService
    ) {
        this.trackService = trackService;
        this.contentLeadCatalogService = contentLeadCatalogService;
        this.contentLeadAnalyticsService = contentLeadAnalyticsService;
        this.ocrService = ocrService;
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
    public ResponseEntity<ContentLeadCatalogResponse> getMyCatalog(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(
            contentLeadCatalogService.getCurrentCatalog(
                authentication.getName(),
                page,
                size,
                query
            )
        );
    }

    @GetMapping("/me/tracks")
    public ResponseEntity<ContentLeadTracksPageResponse> getMyTracks(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(
            contentLeadCatalogService.getCurrentTracks(
                authentication.getName(),
                page,
                size,
                query
            )
        );
    }

    @GetMapping({"/me/analytics/audience-channels", "/analytics/audience-channels"})
    public ResponseEntity<ContentLeadAudienceResponse> getAudienceAnalytics(
        Authentication authentication,
        @RequestParam(defaultValue = "30d") String period,
        @RequestParam(required = false) String trackId
    ) {
        return ResponseEntity.ok(
            contentLeadAnalyticsService.getAudienceAnalytics(
                authentication.getName(),
                period,
                trackId
            )
        );
    }

    @PostMapping(value = "/me/tracks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ContentLeadTrackResponse> uploadTrack(
        Authentication authentication,
        @ModelAttribute TrackUploadRequest request,
        @RequestParam("audioFile") MultipartFile audioFile,
        @RequestParam(value = "coverFile", required = false) MultipartFile coverFile,
        @RequestParam(value = "licenseDocFile", required = false) MultipartFile licenseDocFile
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            contentLeadCatalogService.uploadTrack(
                authentication.getName(),
                request,
                audioFile,
                coverFile,
                licenseDocFile
            )
        );
    }

    @PatchMapping("/me/tracks/{trackId}")
    public ResponseEntity<ContentLeadTrackResponse> updateTrack(
        Authentication authentication,
        @PathVariable String trackId,
        @RequestBody TrackUpdateRequest request
    ) {
        return ResponseEntity.ok(
            contentLeadCatalogService.updateTrack(
                authentication.getName(),
                trackId,
                request
            )
        );
    }

    @DeleteMapping("/me/tracks/{trackId}")
    public ResponseEntity<Map<String, Object>> deleteTrack(
        Authentication authentication,
        @PathVariable String trackId
    ) {
        contentLeadCatalogService.deleteTrack(authentication.getName(), trackId);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Track deleted successfully",
            "trackId", trackId
        ));
    }

    @PostMapping(value = "/ocr/extract-license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OcrExtractResponse> extractLicenseFromDocument(
        Authentication authentication,
        @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(ocrService.extractLicenseDocument(file));
    }
}

