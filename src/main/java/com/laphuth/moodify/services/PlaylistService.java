package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.playlist.CreatePlaylistRequest;
import com.laphuth.moodify.dto.playlist.PlaylistResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Playlist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.PlaylistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlaylistService {
    private final PlaylistRepository playlistRepository;
    private final TrackRepository trackRepository;

    public PlaylistService(PlaylistRepository playlistRepository, TrackRepository trackRepository) {
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
    }

    public PlaylistResponse createPlaylist(String username, CreatePlaylistRequest request) {
        Playlist playlist = new Playlist(
            request.name(),
            request.description(),
            request.coverUrl(),
            username,
            request.isPublic()
        );
        Playlist saved = playlistRepository.save(playlist);
        return PlaylistResponse.from(saved, Collections.emptyList());
    }

    public List<PlaylistResponse> getUserPlaylists(String username) {
        List<Playlist> playlists = playlistRepository.findByUsernameOrderByCreatedAtDesc(username);
        return playlists.stream().map(p -> {
            List<TrackResponse> tracks = loadTracksInOrder(p.getTrackSpotifyIds());
            return PlaylistResponse.from(p, tracks);
        }).collect(Collectors.toList());
    }

    public PlaylistResponse getPlaylist(String id) {
        Playlist playlist = playlistRepository.findById(id)
            .orElseThrow(() -> new PlaylistNotFoundException("Playlist not found: " + id));

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public PlaylistResponse addTrackToPlaylist(String id, String username, String trackSpotifyId) {
        Playlist playlist = playlistRepository.findByIdAndUsername(id, username)
            .orElseThrow(() -> new PlaylistNotFoundException("Playlist not found or access denied: " + id));

        String resolvedId = trackSpotifyId;
        Optional<Track> trackOpt = trackRepository.findBySpotifyId(trackSpotifyId);
        if (trackOpt.isEmpty()) {
            trackOpt = trackRepository.findById(trackSpotifyId);
        }
        if (trackOpt.isPresent()) {
            resolvedId = trackOpt.get().getSpotifyId();
        }

        List<String> ids = playlist.getTrackSpotifyIds();
        if (!ids.contains(resolvedId)) {
            ids.add(resolvedId);
            playlist.setTrackSpotifyIds(ids);
            playlist.setUpdatedAt(Instant.now());
            playlistRepository.save(playlist);
        }

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public PlaylistResponse removeTrackFromPlaylist(String id, String username, String trackSpotifyId) {
        Playlist playlist = playlistRepository.findByIdAndUsername(id, username)
            .orElseThrow(() -> new PlaylistNotFoundException("Playlist not found or access denied: " + id));

        String resolvedId = trackSpotifyId;
        Optional<Track> trackOpt = trackRepository.findBySpotifyId(trackSpotifyId);
        if (trackOpt.isEmpty()) {
            trackOpt = trackRepository.findById(trackSpotifyId);
        }
        if (trackOpt.isPresent()) {
            resolvedId = trackOpt.get().getSpotifyId();
        }

        List<String> ids = playlist.getTrackSpotifyIds();
        boolean removed = ids.remove(resolvedId) || ids.remove(trackSpotifyId);
        if (removed) {
            playlist.setTrackSpotifyIds(ids);
            playlist.setUpdatedAt(Instant.now());
            playlistRepository.save(playlist);
        }

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public void deletePlaylist(String id, String username) {
        Playlist playlist = playlistRepository.findByIdAndUsername(id, username)
            .orElseThrow(() -> new PlaylistNotFoundException("Playlist not found or access denied: " + id));
        playlistRepository.delete(playlist);
    }

    private List<TrackResponse> loadTracksInOrder(List<String> spotifyIds) {
        if (spotifyIds == null || spotifyIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Track> found = trackRepository.findBySpotifyIdIn(spotifyIds);
        Map<String, Track> trackMap = new HashMap<>();
        for (Track t : found) {
            if (t.getSpotifyId() != null) trackMap.put(t.getSpotifyId(), t);
            if (t.getId() != null) trackMap.put(t.getId(), t);
        }

        // Check if any wasn't found by spotifyIdIn, try by Mongo id
        for (String id : spotifyIds) {
            if (!trackMap.containsKey(id)) {
                trackRepository.findById(id).ifPresent(t -> {
                    trackMap.put(id, t);
                    if (t.getSpotifyId() != null) trackMap.put(t.getSpotifyId(), t);
                });
            }
        }

        List<TrackResponse> ordered = new ArrayList<>();
        for (String id : spotifyIds) {
            Track t = trackMap.get(id);
            if (t != null) {
                ordered.add(TrackResponse.from(t));
            }
        }
        return ordered;
    }

    public static class PlaylistNotFoundException extends RuntimeException {
        public PlaylistNotFoundException(String message) {
            super(message);
        }
    }
}
