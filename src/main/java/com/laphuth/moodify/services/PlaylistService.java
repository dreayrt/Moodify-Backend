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
    private final com.laphuth.moodify.repositories.UserRepository users;

    public PlaylistService(PlaylistRepository playlistRepository, TrackRepository trackRepository,com.laphuth.moodify.repositories.UserRepository users) {
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;this.users=users;
    }

    public PlaylistResponse createPlaylist(String username, CreatePlaylistRequest request) {
        Playlist playlist = new Playlist(
            request.name(),
            request.description(),
            request.coverUrl(),
            username,
            request.isPublic()
        );
        playlist.setOwnerUserId(actor(username).getId());
        Playlist saved = playlistRepository.save(playlist);
        return PlaylistResponse.from(saved, Collections.emptyList());
    }

    public List<PlaylistResponse> getUserPlaylists(String username) {
        var user=actor(username);Map<String,Playlist> own=new LinkedHashMap<>();
        for(Playlist p:playlistRepository.findByUsernameOrderByCreatedAtDesc(username))if(com.laphuth.moodify.recommendation.RecommendationCatalog.owns(p,user))own.put(p.getId(),p);
        for(Playlist p:playlistRepository.findByOwnerUserIdOrderByCreatedAtDesc(user.getId()))own.put(p.getId(),p);
        List<Playlist> playlists=List.copyOf(own.values());
        return playlists.stream().map(p -> {
            List<TrackResponse> tracks = loadTracksInOrder(p.getTrackSpotifyIds());
            return PlaylistResponse.from(p, tracks);
        }).collect(Collectors.toList());
    }

    public List<PlaylistResponse> searchPublicPlaylists(String query) {
        List<Playlist> playlists;
        if (query != null && !query.isBlank()) {
            playlists = playlistRepository.findByIsPublicTrueAndNameContainingIgnoreCaseOrderByCreatedAtDesc(query.trim());
        } else {
            playlists = playlistRepository.findByIsPublicTrueOrderByCreatedAtDesc();
        }
        return playlists.stream().map(p -> {
            List<TrackResponse> tracks = loadTracksInOrder(p.getTrackSpotifyIds());
            return PlaylistResponse.from(p, tracks);
        }).collect(Collectors.toList());
    }

    public PlaylistResponse updatePlaylistVisibility(String id, String username, boolean isPublic) {
        Playlist playlist=owned(id,username);
        playlist.setIsPublic(isPublic);
        playlist.setRevision(playlist.getRevision() + 1);
        playlist.setUpdatedAt(Instant.now());
        Playlist saved = playlistRepository.save(playlist);
        List<TrackResponse> tracks = loadTracksInOrder(saved.getTrackSpotifyIds());
        return PlaylistResponse.from(saved, tracks);
    }

    public PlaylistResponse getPlaylist(String id) {
        return getPlaylist(id, null);
    }

    public PlaylistResponse getPlaylist(String id, String principal) {
        Playlist playlist = playlistRepository.findById(id)
            .orElseThrow(() -> new PlaylistNotFoundException("Playlist not found: " + id));

        if (!Boolean.TRUE.equals(playlist.getIsPublic()) && (principal==null||!com.laphuth.moodify.recommendation.RecommendationCatalog.owns(playlist,actor(principal)))) {
            throw new PlaylistNotFoundException("Playlist not found or access denied: " + id);
        }

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public PlaylistResponse addTrackToPlaylist(String id, String username, String trackSpotifyId) {
        Playlist playlist=owned(id,username);

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
            playlist.setRevision(playlist.getRevision() + 1);
            playlist.setTrackSpotifyIds(ids);
            playlist.setUpdatedAt(Instant.now());
            playlistRepository.save(playlist);
        }

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public PlaylistResponse removeTrackFromPlaylist(String id, String username, String trackSpotifyId) {
        Playlist playlist=owned(id,username);

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
            playlist.setRevision(playlist.getRevision() + 1);
            playlist.setTrackSpotifyIds(ids);
            playlist.setUpdatedAt(Instant.now());
            playlistRepository.save(playlist);
        }

        List<TrackResponse> tracks = loadTracksInOrder(playlist.getTrackSpotifyIds());
        return PlaylistResponse.from(playlist, tracks);
    }

    public void deletePlaylist(String id, String username) {
        Playlist playlist=owned(id,username);
        playlistRepository.delete(playlist);
    }

    private com.laphuth.moodify.entities.User actor(String principal) {
        return users.findByEmailOrUsername(principal,principal).orElseThrow(()->new PlaylistNotFoundException("User not found"));
    }
    private Playlist owned(String id,String principal) {
        Playlist playlist=playlistRepository.findById(id).orElseThrow(()->new PlaylistNotFoundException("Playlist not found"));
        if(!com.laphuth.moodify.recommendation.RecommendationCatalog.owns(playlist,actor(principal)))throw new PlaylistNotFoundException("Playlist not found or access denied");
        return playlist;
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
                ordered.add(TrackResponse.fromSummary(t));
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
