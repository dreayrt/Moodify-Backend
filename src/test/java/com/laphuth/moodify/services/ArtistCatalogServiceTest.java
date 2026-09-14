package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.entities.enums.userStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
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
