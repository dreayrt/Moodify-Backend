package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.TrackService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/tracks")
public class TrackApi {
    private final TrackService trackService;
    private final TrackRepository trackRepository;

    public TrackApi(TrackService trackService, TrackRepository trackRepository) {
        this.trackService = trackService;
        this.trackRepository = trackRepository;
    }

    @GetMapping("/debug/genres")
    public ResponseEntity<?> getAllGenres() {
        Page<Track> tracks = trackRepository.findAll(PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "popularity")));
        Map<String, Integer> genreCounts = new TreeMap<>();
        for (Track t : tracks) {
            List<String> genres = t.getGenres();
            if (genres != null) {
                for (String g : genres) {
                    genreCounts.merge(g, 1, Integer::sum);
                }
            }
        }
        return ResponseEntity.ok(Map.of(
            "totalTracks", tracks.getTotalElements(),
            "genreCounts", genreCounts,
            "sampleByGenre", tracks.getContent().stream()
                .collect(Collectors.groupingBy(
                    t -> t.getGenres() == null || t.getGenres().isEmpty() ? "_no_genre" : t.getGenres().get(0),
                    Collectors.mapping(t -> Map.of("name", t.getName(), "artist", t.getArtistName()),
                        Collectors.toList())
                ))
        ));
    }

    @GetMapping
    public ResponseEntity<TrackPageResponse> getTracks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String genre
    ) {
        if (genre != null && !genre.isBlank()) {
            return ResponseEntity.ok(trackService.getTracksByGenre(genre.trim(), page, size));
        }
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

    @GetMapping(value = {"/{id}/stream", "/stream/{id}"})
    public ResponseEntity<ResourceRegion> streamTrack(
        @PathVariable String id,
        @RequestHeader HttpHeaders headers
    ) throws IOException {
        Track track = null;
        try {
            track = trackService.getTrackEntity(id);
        } catch (Exception ignored) {
        }

        File audioFile = resolveAudioFile(track);
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Audio file not available yet for track: " + id);
        }

        FileSystemResource resource = new FileSystemResource(audioFile);
        long contentLength = resource.contentLength();
        HttpRange range = headers.getRange().isEmpty() ? null : headers.getRange().get(0);

        if (range != null) {
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(1024 * 1024, end - start + 1); // 1MB chunk
            ResourceRegion region = new ResourceRegion(resource, start, rangeLength);
            return ResponseEntity.status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(region);
        } else {
            long rangeLength = Math.min(1024 * 1024, contentLength);
            ResourceRegion region = new ResourceRegion(resource, 0, rangeLength);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(region);
        }
    }

    private File resolveAudioFile(Track track) {
        if (track == null) return null;
        String localPath = track.getLocalPath();
        String spotifyId = track.getSpotifyId();

        // 1. Direct path check
        if (localPath != null && !localPath.isBlank()) {
            File f = new File(localPath);
            if (f.exists() && f.length() > 0) return f;

            File fJustName = new File("data/audio", f.getName());
            if (fJustName.exists() && fJustName.length() > 0) return fJustName;

            File fData = new File("data", localPath);
            if (fData.exists() && fData.length() > 0) return fData;

            File fBackend = new File("Moodify-Backend", localPath);
            if (fBackend.exists() && fBackend.length() > 0) return fBackend;
        }

        // 2. Search data/audio directory by spotifyId
        File[] searchDirs = new File[] { new File("data/audio"), new File("Moodify-Backend/data/audio") };
        for (File audioDir : searchDirs) {
            if (audioDir.exists() && audioDir.isDirectory() && spotifyId != null) {
                File[] matches = audioDir.listFiles((dir, name) -> name.contains(spotifyId));
                if (matches != null && matches.length > 0) {
                    return matches[0];
                }
                File[] subdirs = audioDir.listFiles(File::isDirectory);
                if (subdirs != null) {
                    for (File subdir : subdirs) {
                        File[] subMatches = subdir.listFiles((dir, name) -> name.contains(spotifyId));
                        if (subMatches != null && subMatches.length > 0) {
                            return subMatches[0];
                        }
                    }
                }
            }
        }
        return null;
    }
}
