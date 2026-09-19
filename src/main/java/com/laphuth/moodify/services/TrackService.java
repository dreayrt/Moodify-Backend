package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class TrackService {
    private static final int MAX_PAGE_SIZE = 500;

    private final TrackRepository trackRepository;

    public TrackService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    public static String removeAccents(String text) {
        if (text == null) return "";
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String result = pattern.matcher(normalized).replaceAll("");
        return result.replace('đ', 'd').replace('Đ', 'D').trim().toLowerCase();
    }

    public TrackPageResponse getTracks(int page, int size, String query) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Direction.ASC, "name")
        );

        Page<Track> tracks;
        if (query == null || query.isBlank()) {
            tracks = trackRepository.findAll(pageable);
        } else {
            String trimmed = query.trim();
            tracks = findByQuery(trimmed, pageable);
            // If standard repository search returned 0 results, try unaccented Vietnamese matching
            if (tracks.isEmpty()) {
                String normQuery = removeAccents(trimmed);
                java.util.List<Track> all = trackRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
                java.util.List<Track> matched = all.stream()
                    .filter(t -> removeAccents(t.getName()).contains(normQuery)
                        || removeAccents(t.getArtistName()).contains(normQuery)
                        || (t.getAlbumName() != null && removeAccents(t.getAlbumName()).contains(normQuery)))
                    .toList();

                int start = Math.min(safePage * safeSize, matched.size());
                int end = Math.min(start + safeSize, matched.size());
                java.util.List<Track> paged = matched.subList(start, end);
                tracks = new org.springframework.data.domain.PageImpl<>(paged, pageable, matched.size());
            }
        }

        return new TrackPageResponse(
            tracks.map(TrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    public Track getTrackEntity(String idOrSpotifyId) {
        return trackRepository.findById(idOrSpotifyId)
            .or(() -> trackRepository.findBySpotifyId(idOrSpotifyId))
            .orElseThrow(() -> new TrackNotFoundException(idOrSpotifyId));
    }

    public TrackResponse getTrack(String id) {
        Track track = getTrackEntity(id);
        return TrackResponse.from(track);
    }

    public TrackPageResponse getTracksByGenre(String genre, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        
        Pageable pageable = PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Direction.DESC, "popularity")
        );
        
        Page<Track> tracks = trackRepository.findByGenresContaining(genre, pageable);
        
        return new TrackPageResponse(
            tracks.map(TrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    public TrackPageResponse getTracksByArtist(String artistSpotifyId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        
        Pageable pageable = PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Direction.DESC, "popularity")
        );
        
        Page<Track> tracks = trackRepository.findByArtistSpotifyId(artistSpotifyId, pageable);
        
        return new TrackPageResponse(
            tracks.map(TrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    private Page<Track> findByQuery(String query, Pageable pageable) {
        return trackRepository
            .findByNameContainingIgnoreCaseOrArtistNameContainingIgnoreCaseOrAlbumNameContainingIgnoreCase(
                query,
                query,
                query,
                pageable
            );
    }

    public static class TrackNotFoundException extends RuntimeException {
        public TrackNotFoundException(String id) {
            super("Track not found: " + id);
        }
    }
}
