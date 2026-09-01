package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/tracks")
public class TrackApi {
    private final TrackService trackService;

    public TrackApi(TrackService trackService) {
        this.trackService = trackService;
    }

    @GetMapping
    public ResponseEntity<TrackPageResponse> getTracks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(trackService.getTracks(page, size, query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrackResponse> getTrack(@PathVariable String id) {
        try {
            return ResponseEntity.ok(trackService.getTrack(id));
        } catch (TrackService.TrackNotFoundException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Track not found");
        }
    }
}
