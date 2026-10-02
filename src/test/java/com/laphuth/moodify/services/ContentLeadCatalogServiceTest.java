package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.contentlead.ContentLeadCatalogResponse;
import com.laphuth.moodify.dto.contentlead.TrackUpdateRequest;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentLeadCatalogServiceTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private SongLicenseRepository songLicenseRepository;

    @Mock
    private ContentReviewRequestRepository contentReviewRequestRepository;

    @Mock
    private ContentReviewActionRepository contentReviewActionRepository;

    @InjectMocks
    private ContentLeadCatalogService contentLeadCatalogService;

    @Test
    void getCurrentCatalogShouldReturnTracksForContentLead() {
        User user = contentLeadUser("44ZNcW1ZSGB9oqX1ALnriH");
        Artist artist = artist("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");

        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(Optional.of(user));
        when(artistRepository.findBySpotifyId("44ZNcW1ZSGB9oqX1ALnriH"))
            .thenReturn(Optional.of(artist));
        when(trackRepository.findByArtistSpotifyId(eq("44ZNcW1ZSGB9oqX1ALnriH"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(track)));
        when(albumRepository.findByArtistSpotifyId(eq("44ZNcW1ZSGB9oqX1ALnriH"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        ContentLeadCatalogResponse response = contentLeadCatalogService.getCurrentCatalog(
            "contentlead01",
            0,
            50,
            null
        );

        assertThat(response.artist().spotifyId()).isEqualTo("44ZNcW1ZSGB9oqX1ALnriH");
        assertThat(response.tracks()).hasSize(1);
        assertThat(response.tracks().getFirst().title()).isEqualTo("Túy Âm");
        verify(trackRepository).findByArtistSpotifyId(eq("44ZNcW1ZSGB9oqX1ALnriH"), any(Pageable.class));
    }

    @Test
    void getCurrentCatalogShouldRejectNonContentLeadUser() {
        User user = contentLeadUser(null);
        user.setRole(UserRole.USER);
        when(userRepository.findByEmailOrUsername("listener01", "listener01"))
            .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> contentLeadCatalogService.getCurrentCatalog(
            "listener01",
            0,
            50,
            null
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verify(artistRepository, never()).findBySpotifyId(any());
    }

    @Test
    void getCurrentCatalogShouldRequireLinkedCatalogId() {
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(Optional.of(contentLeadUser(null)));

        assertThatThrownBy(() -> contentLeadCatalogService.getCurrentCatalog(
            "contentlead01",
            0,
            50,
            null
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        verify(artistRepository, never()).findBySpotifyId(any());
    }

    @Test
    void deleteTrackShouldDeleteTrackAndLicenses() {
        User user = contentLeadUser("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");
        track.setId("mongo-track-1");

        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findById("mongo-track-1"))
            .thenReturn(Optional.of(track));
        when(contentReviewRequestRepository.findByContentIdAndContentType("mongo-track-1", "TRACK"))
            .thenReturn(List.of());

        contentLeadCatalogService.deleteTrack("contentlead01", "mongo-track-1");

        verify(songLicenseRepository).deleteByTrackId("mongo-track-1");
        verify(trackRepository).delete(track);
    }

    private User contentLeadUser(String spotifyId) {
        User user = new User();
        user.setId(10L);
        user.setFullname("Content Lead User");
        user.setUsername("contentlead01");
        user.setEmail("contentlead01@example.com");
        user.setRole(UserRole.CONTENT_LEAD);
        user.setStatus(UserStatus.ACTIVE);
        user.setArtistSpotifyId(spotifyId);
        return user;
    }

    private Artist artist(String spotifyId) {
        Artist artist = new Artist();
        artist.setSpotifyId(spotifyId);
        artist.setName("Xesi");
        return artist;
    }

    private Track track(String spotifyId) {
        Track track = new Track();
        track.setId("mongo-track-1");
        track.setName("Túy Âm");
        track.setArtistSpotifyId(spotifyId);
        track.setArtistName("Xesi");
        track.setStatus("published");
        track.setVisibility("public");
        return track;
    }
}
