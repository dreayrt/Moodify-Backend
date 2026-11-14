package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.contentlead.ContentLeadCatalogResponse;
import com.laphuth.moodify.dto.contentlead.TrackUpdateRequest;
import com.laphuth.moodify.dto.contentlead.TrackUploadRequest;
import com.laphuth.moodify.dto.ocr.OcrExtractResponse;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

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
    private AudioMetadataService audioMetadataService;

    @Mock
    private OcrService ocrService;

    @InjectMocks
    private ContentLeadCatalogService contentLeadCatalogService;

    @Test
    void uploadPersistsMeasuredDurationForSearchAndStudioWithoutEssentia() throws Exception {
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(Optional.of(contentLeadUser("artist-1")));
        when(artistRepository.findBySpotifyId("artist-1")).thenReturn(Optional.of(artist("artist-1")));
        when(ocrService.extractLicenseDocument(any())).thenReturn(new OcrExtractResponse(true, "OK"));
        when(audioMetadataService.durationMs(any())).thenReturn(302347);
        Path[] storedAudio = new Path[1];
        Path[] storedLicense = new Path[1];
        when(trackRepository.save(any())).thenAnswer(invocation -> {
            Track saved = invocation.getArgument(0);
            saved.setId("uploaded-track");
            storedAudio[0] = Path.of(saved.getLocalPath().substring(1));
            return saved;
        });
        when(songLicenseRepository.save(any())).thenAnswer(invocation -> {
            com.laphuth.moodify.entities.SongLicense license = invocation.getArgument(0);
            storedLicense[0] = Path.of(license.getDocumentSonglicensesUrl().substring(1));
            return license;
        });
        TrackUploadRequest request = new TrackUploadRequest();
        request.setTitle("Uploaded song");
        request.setCopyrightOwner("Test owner");
        request.setDistributionContractCode("TEST-LNAB-20261110-0003");
        request.setIssueDate(LocalDate.of(2020, 1, 1).toString());
        request.setPerpetual(true);
        try {
            var response = contentLeadCatalogService.uploadTrack("contentlead01", request,
                new MockMultipartFile("audioFile", "song.mp3", "audio/mpeg", new byte[]{1}), null,
                new MockMultipartFile("licenseDocFile", "license.pdf", "application/pdf", new byte[]{1}));
            assertThat(response.duration()).isEqualTo("5:02");
            var captor = org.mockito.ArgumentCaptor.forClass(Track.class);
            verify(trackRepository).save(captor.capture());
            Track saved = captor.getValue();
            assertThat(saved.getDurationMs()).isEqualTo(302347);
            assertThat(com.laphuth.moodify.dto.track.TrackResponse.from(saved).durationMs()).isEqualTo(302347);
            assertThat(saved.getAudioFeatures()).isNull();
            var licenseCaptor = org.mockito.ArgumentCaptor.forClass(com.laphuth.moodify.entities.SongLicense.class);
            verify(songLicenseRepository).save(licenseCaptor.capture());
            assertThat(licenseCaptor.getValue().getDistributionContractCode()).isEqualTo("TEST-LNAB-20261110-0003");
            assertThat(licenseCaptor.getValue().getDistributorId()).isNull();
            assertThat(licenseCaptor.getValue().getDistributionContractId()).isNull();
        } finally {
            if (storedAudio[0] != null) Files.deleteIfExists(storedAudio[0]);
            if (storedLicense[0] != null) Files.deleteIfExists(storedLicense[0]);
        }
    }

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

    @Test
    void deleteTrackShouldKeepReviewHistoryAndCancelOnlyOpenRequests() {
        User user = contentLeadUser("44ZNcW1ZSGB9oqX1ALnriH");
        Track track = track("44ZNcW1ZSGB9oqX1ALnriH");
        track.setId("mongo-track-1");
        track.setGenres(List.of("Pop"));
        track.setImageUrl("/covers/come-my-way.png");

        ContentReviewRequest rejectedRequest = new ContentReviewRequest();
        rejectedRequest.setId(21L);
        rejectedRequest.setContentId(track.getId());
        rejectedRequest.setStatus("REJECTED");

        ContentReviewRequest pendingRequest = new ContentReviewRequest();
        pendingRequest.setId(22L);
        pendingRequest.setContentId(track.getId());
        pendingRequest.setStatus("PENDING");

        List<ContentReviewRequest> reviewRequests = List.of(rejectedRequest, pendingRequest);
        when(userRepository.findByEmailOrUsername("contentlead01", "contentlead01"))
            .thenReturn(Optional.of(user));
        when(trackRepository.findById("mongo-track-1")).thenReturn(Optional.of(track));
        when(contentReviewRequestRepository.findByContentIdAndContentType("mongo-track-1", "TRACK"))
            .thenReturn(reviewRequests);

        contentLeadCatalogService.deleteTrack("contentlead01", "mongo-track-1");

        assertThat(rejectedRequest.getStatus()).isEqualTo("REJECTED");
        assertThat(pendingRequest.getStatus()).isEqualTo("CANCELLED");
        assertThat(pendingRequest.getResolvedAt()).isNotNull();
        assertThat(rejectedRequest.getContentTitle()).isEqualTo("Túy Âm");
        assertThat(rejectedRequest.getArtistName()).isEqualTo("Xesi");
        assertThat(rejectedRequest.getContentGenre()).isEqualTo("Pop");
        assertThat(rejectedRequest.getContentCoverUrl()).isEqualTo("/covers/come-my-way.png");
        verify(contentReviewRequestRepository).saveAll(reviewRequests);
        verify(songLicenseRepository).deleteByTrackId("mongo-track-1");
        verify(trackRepository).delete(track);
        verify(contentReviewRequestRepository, never()).deleteAll(reviewRequests);
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
