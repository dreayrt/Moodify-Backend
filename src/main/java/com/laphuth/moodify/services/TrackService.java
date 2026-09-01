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
    private static final int MAX_PAGE_SIZE = 100;

    private final TrackRepository trackRepository;

    public TrackService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    public TrackPageResponse getTracks(int page, int size, String query) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        //Pageable: yeu cau muon lay trang nao(yeu cau phan trang)
        Pageable pageable = PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Direction.ASC, "name")
        );
        //Page ket qua sau khi database lay trang do
        Page<Track> tracks = query == null || query.isBlank()
            ? trackRepository.findAll(pageable)
            : findByQuery(query.trim(), pageable);

        return new TrackPageResponse(
            tracks.map(TrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    public TrackResponse getTrack(String id) {
        Track track = trackRepository.findById(id)
            .orElseThrow(() -> new TrackNotFoundException(id));
        return TrackResponse.from(track);
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
