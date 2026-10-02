package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.library.LibraryPageResponse;
import com.laphuth.moodify.dto.library.LibraryTrackResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.UserLibraryTracks;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserLibraryTracksRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserLibraryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final UserLibraryTracksRepository libraryRepository;

    public UserLibraryService(
        UserRepository userRepository,
        TrackRepository trackRepository,
        UserLibraryTracksRepository libraryRepository
    ) {
        this.userRepository = userRepository;
        this.trackRepository = trackRepository;
        this.libraryRepository = libraryRepository;
    }

    @Transactional
    public void addTrack(String principal, String trackSpotifyId) {
        User user = findUserByPrincipal(principal);
        
        // Verify track exists in MongoDB by spotifyId or id
        Track track = trackRepository.findBySpotifyId(trackSpotifyId)
            .or(() -> trackRepository.findById(trackSpotifyId))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Track not found: " + trackSpotifyId
            ));
        
        String resolvedId = track.getSpotifyId() != null ? track.getSpotifyId() : track.getId();

        // Check if already in library
        if (libraryRepository.existsByUserAndTrackSpotifyId(user, resolvedId)) {
            return; // Already exists, idempotent operation
        }
        
        // Add to library
        UserLibraryTracks libraryTrack = new UserLibraryTracks();
        libraryTrack.setUser(user);
        libraryTrack.setTrackSpotifyId(resolvedId);
        libraryTrack.setAddedAt(LocalDateTime.now());
        libraryRepository.save(libraryTrack);
    }

    @Transactional
    public void removeTrack(String principal, String trackSpotifyId) {
        User user = findUserByPrincipal(principal);
        libraryRepository.deleteByUserAndTrackSpotifyId(user, trackSpotifyId);
        // Also attempt delete by Mongo id if it differs
        trackRepository.findBySpotifyId(trackSpotifyId)
            .ifPresent(t -> {
                if (t.getId() != null && !t.getId().equals(trackSpotifyId)) {
                    libraryRepository.deleteByUserAndTrackSpotifyId(user, t.getId());
                }
            });
    }

    public LibraryPageResponse getUserTracks(String principal, int page, int size) {
        User user = findUserByPrincipal(principal);
        
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        
        Page<UserLibraryTracks> libraryPage = libraryRepository
            .findByUserOrderByAddedAtDesc(user, pageable);
        
        // Extract track spotify IDs
        List<String> trackSpotifyIds = libraryPage.getContent().stream()
            .map(UserLibraryTracks::getTrackSpotifyId)
            .collect(Collectors.toList());
        
        // Batch query MongoDB for all tracks
        List<Track> tracks = trackRepository.findBySpotifyIdIn(trackSpotifyIds);
        
        // Create a map for quick lookup
        Map<String, Track> trackMap = tracks.stream()
            .collect(Collectors.toMap(Track::getSpotifyId, t -> t, (k1, k2) -> k1));
        
        // Build response with addedAt timestamps
        List<LibraryTrackResponse> libraryTracks = new ArrayList<>();
        for (UserLibraryTracks libraryTrack : libraryPage.getContent()) {
            Track track = trackMap.get(libraryTrack.getTrackSpotifyId());
            if (track == null) {
                track = trackRepository.findById(libraryTrack.getTrackSpotifyId()).orElse(null);
            }
            if (track != null) {
                LibraryTrackResponse response = new LibraryTrackResponse();
                // Copy fields directly from Track entity
                response.setId(track.getId());
                response.setSpotifyId(track.getSpotifyId());
                response.setName(track.getName());
                response.setArtistName(track.getArtistName());
                response.setArtistSpotifyId(track.getArtistSpotifyId());
                response.setAlbumName(track.getAlbumName());
                response.setDurationMs(track.getDurationMs());
                response.setPopularity(track.getPopularity());
                response.setPreviewUrl(null);
                response.setImageUrl(track.getImageUrl());
                response.setGenres(track.getGenres());
                response.setLyricsPlain(track.getLyricsPlain());
                response.setLyricsSynced(track.getLyricsSynced());
                response.setLocalPath(track.getLocalPath());
                // Add library-specific field
                response.setAddedAt(libraryTrack.getAddedAt() != null ? libraryTrack.getAddedAt().toString() : LocalDateTime.now().toString());
                libraryTracks.add(response);
            }
        }
        
        return new LibraryPageResponse(
            libraryTracks,
            libraryPage.getNumber(),
            libraryPage.getTotalPages(),
            libraryPage.getTotalElements(),
            libraryPage.getSize()
        );
    }

    public List<String> getLikedTrackIds(String principal) {
        User user = findUserByPrincipal(principal);
        return libraryRepository.findTrackSpotifyIdsByUser(user);
    }

    public boolean isTrackInLibrary(String principal, String trackSpotifyId) {
        User user = findUserByPrincipal(principal);
        boolean exists = libraryRepository.existsByUserAndTrackSpotifyId(user, trackSpotifyId);
        if (!exists) {
            // Also check by resolved ID
            exists = trackRepository.findBySpotifyId(trackSpotifyId)
                .map(t -> libraryRepository.existsByUserAndTrackSpotifyId(user, t.getId()))
                .orElse(false);
        }
        return exists;
    }

    private User findUserByPrincipal(String principal) {
        return userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));
    }
}
