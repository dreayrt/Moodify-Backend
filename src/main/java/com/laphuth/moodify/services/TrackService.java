package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class TrackService {
    private static final int MAX_PAGE_SIZE = 500;

    private final TrackRepository trackRepository;
    private final SongLicenseRepository songLicenseRepository;

    public TrackService(TrackRepository trackRepository, SongLicenseRepository songLicenseRepository) {
        this.trackRepository = trackRepository;
        this.songLicenseRepository = songLicenseRepository;
    }

    public Set<String> getIneligibleTrackIds() {
        try {
            return new HashSet<>(songLicenseRepository.findIneligibleTrackIds());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    public boolean isTrackPubliclyAvailable(Track track, Set<String> ineligibleIds) {
        if (track == null) {
            return false;
        }
        if (ineligibleIds != null && track.getId() != null && ineligibleIds.contains(track.getId())) {
            return false;
        }
        String visibility = track.getVisibility();
        if (visibility != null && ("private".equalsIgnoreCase(visibility) || "unlisted".equalsIgnoreCase(visibility))) {
            return false;
        }
        String status = track.getStatus();
        if (status != null && ("archived".equalsIgnoreCase(status) || "disabled".equalsIgnoreCase(status) || "draft".equalsIgnoreCase(status))) {
            return false;
        }
        String modStatus = track.getModerationStatus();
        if (modStatus != null && "rejected".equalsIgnoreCase(modStatus)) {
            return false;
        }
        return true;
    }

    public boolean isTrackPubliclyAvailable(Track track) {
        return isTrackPubliclyAvailable(track, getIneligibleTrackIds());
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

        Set<String> ineligibleIds = getIneligibleTrackIds();

        Page<Track> tracks;
        if (query == null || query.isBlank()) {
            tracks = trackRepository.findAll(pageable);
            List<Track> valid = tracks.getContent().stream()
                .filter(t -> isTrackPubliclyAvailable(t, ineligibleIds))
                .toList();
            tracks = new PageImpl<>(valid, pageable, tracks.getTotalElements() - (tracks.getNumberOfElements() - valid.size()));
        } else {
            String trimmed = query.trim();
            tracks = findByQuery(trimmed, pageable);
            List<Track> valid = tracks.getContent().stream()
                .filter(t -> isTrackPubliclyAvailable(t, ineligibleIds))
                .toList();

            // If standard repository search returned 0 results or all were ineligible, try unaccented Vietnamese matching
            if (valid.isEmpty()) {
                String normQuery = removeAccents(trimmed);
                List<Track> all = trackRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
                List<Track> matched = all.stream()
                    .filter(t -> isTrackPubliclyAvailable(t, ineligibleIds))
                    .filter(t -> removeAccents(t.getName()).contains(normQuery)
                        || removeAccents(t.getArtistName()).contains(normQuery)
                        || (t.getAlbumName() != null && removeAccents(t.getAlbumName()).contains(normQuery)))
                    .toList();

                int start = Math.min(safePage * safeSize, matched.size());
                int end = Math.min(start + safeSize, matched.size());
                List<Track> paged = matched.subList(start, end);
                tracks = new PageImpl<>(paged, pageable, matched.size());
            } else {
                tracks = new PageImpl<>(valid, pageable, tracks.getTotalElements() - (tracks.getNumberOfElements() - valid.size()));
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
        if (!isTrackPubliclyAvailable(track)) {
            throw new TrackNotFoundException(id);
        }
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
        Set<String> ineligibleIds = getIneligibleTrackIds();
        List<Track> valid = tracks.getContent().stream()
            .filter(t -> isTrackPubliclyAvailable(t, ineligibleIds))
            .toList();
        
        return new TrackPageResponse(
            valid.stream().map(TrackResponse::from).toList(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements() - (tracks.getNumberOfElements() - valid.size()),
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
        Set<String> ineligibleIds = getIneligibleTrackIds();
        List<Track> valid = tracks.getContent().stream()
            .filter(t -> isTrackPubliclyAvailable(t, ineligibleIds))
            .toList();
        
        return new TrackPageResponse(
            valid.stream().map(TrackResponse::from).toList(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements() - (tracks.getNumberOfElements() - valid.size()),
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
