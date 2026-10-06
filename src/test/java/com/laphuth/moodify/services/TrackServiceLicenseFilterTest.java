package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackServiceLicenseFilterTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private SongLicenseRepository songLicenseRepository;

    private TrackService trackService;

    @BeforeEach
    void setUp() {
        trackService = new TrackService(trackRepository, songLicenseRepository);
    }

    @Test
    void shouldFilterOutTrackWhenLicenseIsExpiredInDatabase() {
        Track expiredTrack = new Track();
        expiredTrack.setId("expired-123");
        expiredTrack.setName("test expired");
        expiredTrack.setImageUrl("/uploads/covers/test.jpg");
        expiredTrack.setVisibility("public");
        expiredTrack.setStatus("published");

        Track activeTrack = new Track();
        activeTrack.setId("active-456");
        activeTrack.setName("active song");
        activeTrack.setImageUrl("/uploads/covers/active.jpg");
        activeTrack.setVisibility("public");
        activeTrack.setStatus("published");

        when(songLicenseRepository.findIneligibleTrackIds())
            .thenReturn(List.of("expired-123"));

        when(trackRepository.findByNameContainingIgnoreCaseOrArtistNameContainingIgnoreCaseOrAlbumNameContainingIgnoreCase(
            eq("test"), eq("test"), eq("test"), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(expiredTrack)));

        TrackPageResponse result = trackService.getTracks(0, 10, "test");

        assertNotNull(result);
        assertEquals(0, result.content().size(), "Expired track should be filtered out from search results");
    }

    @Test
    void shouldThrowExceptionWhenGettingExpiredTrackDirectly() {
        Track expiredTrack = new Track();
        expiredTrack.setId("expired-123");
        expiredTrack.setName("test expired");
        expiredTrack.setVisibility("public");
        expiredTrack.setStatus("published");

        when(trackRepository.findById("expired-123")).thenReturn(Optional.of(expiredTrack));
        when(songLicenseRepository.findIneligibleTrackIds())
            .thenReturn(List.of("expired-123"));

        assertThrows(TrackService.TrackNotFoundException.class, () -> {
            trackService.getTrack("expired-123");
        });
    }

    @Test
    void shouldResolveCoverImageUrlToFullUrl() {
        new AudioUrlResolver("http://test-cdn.com", "http://test-server:9000");

        Track track = new Track();
        track.setId("valid-789");
        track.setName("My valid song");
        track.setImageUrl("/uploads/covers/my-cover.png");
        track.setVisibility("public");
        track.setStatus("published");

        when(trackRepository.findById("valid-789")).thenReturn(Optional.of(track));
        when(songLicenseRepository.findIneligibleTrackIds())
            .thenReturn(List.of());

        TrackResponse response = trackService.getTrack("valid-789");

        assertNotNull(response);
        assertEquals("http://test-server:9000/uploads/covers/my-cover.png", response.imageUrl(),
            "Image URL should be resolved using configured base URL");
    }
}
