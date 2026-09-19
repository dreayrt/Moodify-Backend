package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.TrackUpdateRequest;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.entities.enums.userStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.artistRepoository;
import com.laphuth.moodify.repositories.userRepository;
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
class ArtistCatalogServiceTest {
    @Mock
    private userRepository userRepository;

    @Mock
    private artistRepoository artistRepository;

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
    private ArtistCatalogService artistCatalogService;

    @Test
    void getCurrentArtistCatalogShouldReturnTracksForLinkedArtist() {
        User user = artistUser("44ZNcW1ZSGB9oqX1ALnriH");
        Artist artist = artist("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");

        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(user));
        when(artistRepository.findBySpotifyId("44ZNcW1ZSGB9oqX1ALnriH"))
            .thenReturn(Optional.of(artist));
        when(trackRepository.findByArtistSpotifyId(eq("44ZNcW1ZSGB9oqX1ALnriH"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(track)));
        when(albumRepository.findByArtistSpotifyId(eq("44ZNcW1ZSGB9oqX1ALnriH"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        ArtistCatalogResponse response = artistCatalogService.getCurrentArtistCatalog(
            "artist01",
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
    void getCurrentArtistCatalogShouldRejectNonArtistUser() {
        User user = artistUser(null);
        user.setRole(userRole.USER);
        when(userRepository.findByEmailOrUsername("listener01", "listener01"))
            .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> artistCatalogService.getCurrentArtistCatalog(
            "listener01",
            0,
            50,
            null
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(
                ((ResponseStatusException) exception).getStatusCode()
            ).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getCurrentArtistCatalogShouldRequireLinkedSpotifyArtistId() {
        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(artistUser(null)));

        assertThatThrownBy(() -> artistCatalogService.getCurrentArtistCatalog(
            "artist01",
            0,
            50,
            null
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(
                ((ResponseStatusException) exception).getStatusCode()
            ).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void getCurrentArtistTracksShouldReturnPagedTracks() {
        User user = artistUser("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");

        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findByArtistSpotifyIdAndNameContainingIgnoreCase(
            eq("44ZNcW1ZSGB9oqX1ALnriH"),
            eq("Túy"),
            any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(track)));

        var response = artistCatalogService.getCurrentArtistTracks(
            "artist01",
            0,
            20,
            "Túy"
        );

        assertThat(response.tracks()).hasSize(1);
        assertThat(response.tracks().getFirst().title()).isEqualTo("Túy Âm");
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void updateTrackShouldPersistChangesForOwnedTrack() {
        User user = artistUser("44ZNcW1ZSGB9oqX1ALnriH");
        Artist artist = artist("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");
        track.setId("track-1");

        TrackUpdateRequest request = new TrackUpdateRequest();
        request.setTitle("Tên mới");
        request.setGenre("EDM");
        request.setAlbumName("Album mới");
        request.setDescription("Mô tả mới");
        request.setFeaturedArtists("Guest");
        request.setStatus("published");
        request.setVisibility("public");
        request.setExplicit(true);

        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findById("track-1")).thenReturn(Optional.of(track));
        when(artistRepository.findBySpotifyId("44ZNcW1ZSGB9oqX1ALnriH"))
            .thenReturn(Optional.of(artist));
        when(trackRepository.save(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(contentReviewRequestRepository.findByContentIdAndContentTypeAndStatus(
            "track-1",
            "TRACK",
            "PENDING"
        )).thenReturn(Optional.empty());

        var response = artistCatalogService.updateTrack("artist01", "track-1", request);

        assertThat(response.title()).isEqualTo("Tên mới");
        assertThat(response.genre()).isEqualTo("EDM");
        assertThat(response.albumName()).isEqualTo("Album mới");
        assertThat(response.description()).isEqualTo("Mô tả mới");
        assertThat(response.status()).isEqualTo("published");
        assertThat(response.visibility()).isEqualTo("public");
        assertThat(response.explicit()).isTrue();
        verify(trackRepository).save(track);
        verify(contentReviewRequestRepository).save(any(ContentReviewRequest.class));
    }

    @Test
    void deleteTrackShouldRemoveOnlyOwnedTrackAndRelatedRows() {
        User user = artistUser("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");
        track.setId("track-1");

        ContentReviewRequest reviewRequest = new ContentReviewRequest();
        reviewRequest.setId(12L);

        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findById("track-1")).thenReturn(Optional.of(track));
        when(contentReviewRequestRepository.findByContentIdAndContentType("track-1", "TRACK"))
            .thenReturn(List.of(reviewRequest));

        artistCatalogService.deleteTrack("artist01", "track-1");

        verify(contentReviewActionRepository).deleteByReviewRequestIdIn(List.of(12L));
        verify(contentReviewRequestRepository).deleteAll(List.of(reviewRequest));
        verify(songLicenseRepository).deleteByTrackId("track-1");
        verify(trackRepository).delete(track);
    }

    @Test
    void deleteTrackShouldRejectTrackFromAnotherArtist() {
        User user = artistUser("artist-a");
        Track track = track("artist-b");
        track.setId("track-1");

        when(userRepository.findByEmailOrUsername("artist01", "artist01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findById("track-1")).thenReturn(Optional.of(track));

        assertThatThrownBy(() -> artistCatalogService.deleteTrack("artist01", "track-1"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(
                ((ResponseStatusException) exception).getStatusCode()
            ).isEqualTo(HttpStatus.FORBIDDEN));

        verify(trackRepository, never()).delete(any());
    }

    private User artistUser(String artistSpotifyId) {
        User user = new User();
        user.setId(2L);
        user.setFullname("Moodify Artist");
        user.setUsername("artist01");
        user.setEmail("artist@moodify.local");
        user.setRole(userRole.ARTIST);
        user.setStatus(userStatus.ACTIVE);
        user.setArtistSpotifyId(artistSpotifyId);
        return user;
    }

    private Artist artist(String spotifyId) {
        Artist artist = new Artist();
        artist.setSpotifyId(spotifyId);
        artist.setName("Xesi");
        artist.setGenres(List.of("pop"));
        return artist;
    }

    private Track track(String artistSpotifyId) {
        Track track = new Track();
        track.setSpotifyId("3b2kCFZhX9GYnQ58qL1cAM");
        track.setName("Túy Âm");
        track.setArtistName("Xesi, Hoaprox");
        track.setArtistSpotifyId(artistSpotifyId);
        track.setGenres(List.of("pop"));
        track.setDurationFormatted("4:33");
        track.setModerationStatus("approved");
        return track;
    }
}
