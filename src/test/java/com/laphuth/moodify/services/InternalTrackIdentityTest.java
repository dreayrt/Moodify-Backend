package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Playlist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.PlaylistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InternalTrackIdentityTest {
    @Test void exposesInternalIdWhenSpotifyIdIsMissing() {
        Track track = new Track();
        track.setId("507f1f77bcf86cd799439011");
        track.setName("Local track");
        assertEquals(track.getId(), TrackResponse.fromSummary(track).spotifyId());
        track.setSpotifyId("realSpotifyId");
        assertEquals("realSpotifyId", TrackResponse.fromSummary(track).spotifyId());
    }

    @Test void playlistRoundTripsTrackWithoutSpotifyId() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        TrackRepository tracks = mock(TrackRepository.class);
        UserRepository users = mock(UserRepository.class);
        User user = new User(); user.setId(4L); user.setUsername("owner");
        Playlist playlist = new Playlist(); playlist.setId("playlist"); playlist.setOwnerUserId(4L);
        Track track = new Track(); track.setId("507f1f77bcf86cd799439011"); track.setName("Local track");
        when(users.findByEmailOrUsername("owner", "owner")).thenReturn(Optional.of(user));
        when(playlists.findById("playlist")).thenReturn(Optional.of(playlist));
        when(playlists.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tracks.findBySpotifyId(track.getId())).thenReturn(Optional.empty());
        when(tracks.findById(track.getId())).thenReturn(Optional.of(track));
        when(tracks.findBySpotifyIdIn(anyList())).thenReturn(List.of());
        PlaylistService service = new PlaylistService(playlists, tracks, users);
        service.addTrackToPlaylist("playlist", "owner", track.getId());
        assertEquals(List.of(track.getId()), playlist.getTrackSpotifyIds());
        service.removeTrackFromPlaylist("playlist", "owner", track.getId());
        assertTrue(playlist.getTrackSpotifyIds().isEmpty());
    }
}
