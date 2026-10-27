package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.artist.*;
import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.services.ArtistService;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/artists", "/api/artist"})
public class ArtistApi {

    private final ArtistService artistService;
    private final TrackService trackService;

    public ArtistApi(ArtistService artistService, TrackService trackService) {
        this.artistService = artistService;
        this.trackService = trackService;
    }

    @GetMapping
    public ResponseEntity<List<Artist>> searchArtists(@RequestParam(required = false) String query) {
        return ResponseEntity.ok(artistService.searchArtists(query));
    }

    @GetMapping("/followed")
    public ResponseEntity<List<FollowedArtistResponse>> getFollowedArtists(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(artistService.getFollowedArtists(authentication.getName()));
    }

    @GetMapping("/followed-ids")
    public ResponseEntity<List<String>> getFollowedArtistIds(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(artistService.getFollowedArtistIds(authentication.getName()));
    }

    @GetMapping("/{artistId}")
    public ResponseEntity<ArtistPublicProfileResponse> getPublicProfile(
        @PathVariable String artistId,
        Authentication authentication
    ) {
        String principal = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(artistService.getPublicProfile(artistId, principal));
    }

    @GetMapping("/{artistId}/tracks")
    public ResponseEntity<TrackPageResponse> getArtistTracks(
        @PathVariable String artistId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(trackService.getTracksByArtist(artistId, page, size));
    }

    @PostMapping("/{artistId}/follow")
    public ResponseEntity<FollowArtistResponse> toggleFollow(
        @PathVariable String artistId,
        Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Vui lòng đăng nhập để theo dõi nghệ sĩ"
            );
        }
        return ResponseEntity.ok(artistService.toggleFollow(authentication.getName(), artistId));
    }

    @GetMapping("/{artistId}/reviews")
    public ResponseEntity<ArtistReviewsPageResponse> getReviews(
        @PathVariable String artistId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(artistService.getReviews(artistId, page, size));
    }

    @PostMapping("/{artistId}/reviews")
    public ResponseEntity<ArtistReviewResponse> addOrUpdateReview(
        @PathVariable String artistId,
        @RequestBody CreateArtistReviewRequest request,
        Authentication authentication
    ) {
        return ResponseEntity.ok(artistService.addOrUpdateReview(authentication.getName(), artistId, request));
    }

    @PostMapping("/{artistId}/reviews/{reviewId}/reply")
    public ResponseEntity<ArtistReviewResponse> replyReview(
        @PathVariable String artistId,
        @PathVariable Long reviewId,
        @RequestBody ArtistReplyReviewRequest request,
        Authentication authentication
    ) {
        return ResponseEntity.ok(artistService.replyToReview(authentication.getName(), artistId, reviewId, request.reply()));
    }
}
