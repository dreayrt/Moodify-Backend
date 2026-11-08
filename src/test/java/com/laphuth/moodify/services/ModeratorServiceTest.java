package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.moderator.ModerationHistoryResponse;
import com.laphuth.moodify.dto.moderator.ModerationDecisionRequest;
import com.laphuth.moodify.entities.ContentReviewAction;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.SongLicenseDetailsProjection;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeratorServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private SongLicenseRepository songLicenseRepository;

    @Mock
    private ContentReviewRequestRepository contentReviewRequestRepository;

    @Mock
    private ContentReviewActionRepository contentReviewActionRepository;

    @Mock
    private UserRepository userRepo;

    @InjectMocks
    private ModeratorService moderatorService;

    @Test
    void getHistoryShouldReturnPersistedReviewActions() {
        ContentReviewAction action = new ContentReviewAction();
        action.setId(31L);
        action.setReviewRequestId(7L);
        action.setModeratorUserId(2L);
        action.setAction("APPROVE");
        action.setReason("Đạt tiêu chuẩn phát hành");
        action.setCreatedAt(LocalDateTime.of(2026, 9, 19, 21, 45, 0));

        ContentReviewRequest request = new ContentReviewRequest();
        request.setId(7L);
        request.setContentId("track-1");
        request.setSubmittedAt(LocalDateTime.of(2026, 9, 19, 21, 40, 0));

        Track track = new Track();
        track.setId("track-1");
        track.setName("Bài hát đã duyệt");
        track.setArtistName("Moodify Artist");
        track.setGenres(List.of("Pop"));
        track.setImageUrl("/covers/track-1.png");
        track.setExplicit(true);

        User moderator = new User();
        moderator.setId(2L);
        moderator.setFullname("Kiểm Duyệt Viên");

        when(contentReviewActionRepository.findAll(any(Sort.class))).thenReturn(List.of(action));
        when(contentReviewRequestRepository.findAllById(List.of(7L))).thenReturn(List.of(request));
        when(trackRepository.findAllById(List.of("track-1"))).thenReturn(List.of(track));
        when(userRepo.findAllById(List.of(2L))).thenReturn(List.of(moderator));

        List<ModerationHistoryResponse> history = moderatorService.getHistory();

        assertThat(history).hasSize(1);
        ModerationHistoryResponse item = history.getFirst();
        assertThat(item.id()).isEqualTo("31");
        assertThat(item.trackId()).isEqualTo("track-1");
        assertThat(item.trackTitle()).isEqualTo("Bài hát đã duyệt");
        assertThat(item.artistName()).isEqualTo("Moodify Artist");
        assertThat(item.genre()).isEqualTo("Pop");
        assertThat(item.decision()).isEqualTo("approved");
        assertThat(item.internalNote()).isEqualTo("Đạt tiêu chuẩn phát hành");
        assertThat(item.reviewerName()).isEqualTo("Kiểm Duyệt Viên");
        assertThat(item.reviewDurationSec()).isEqualTo(300);
        assertThat(item.assignedExplicitTag()).isTrue();
    }

    @Test
    void getHistoryShouldUseRequestSnapshotWhenTrackWasDeleted() {
        ContentReviewAction action = new ContentReviewAction();
        action.setId(52L);
        action.setReviewRequestId(12L);
        action.setModeratorUserId(3L);
        action.setAction("REJECT");
        action.setReason("Vi phạm bản quyền; metadata sai lệch");
        action.setCreatedAt(LocalDateTime.of(2026, 10, 28, 20, 40));

        ContentReviewRequest request = new ContentReviewRequest();
        request.setId(12L);
        request.setContentId("deleted-track-1");
        request.setContentTitle("Come My Way");
        request.setArtistName("Teeme");
        request.setContentGenre("Pop");
        request.setContentCoverUrl("/covers/come-my-way.png");
        request.setSubmittedAt(LocalDateTime.of(2026, 10, 28, 20, 35));

        User moderator = new User();
        moderator.setId(3L);
        moderator.setFullname("Kiểm Duyệt Viên");

        when(contentReviewActionRepository.findAll(any(Sort.class))).thenReturn(List.of(action));
        when(contentReviewRequestRepository.findAllById(List.of(12L))).thenReturn(List.of(request));
        when(trackRepository.findAllById(List.of("deleted-track-1"))).thenReturn(List.of());
        when(userRepo.findAllById(List.of(3L))).thenReturn(List.of(moderator));

        List<ModerationHistoryResponse> history = moderatorService.getHistory();

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().trackTitle()).isEqualTo("Come My Way");
        assertThat(history.getFirst().artistName()).isEqualTo("Teeme");
        assertThat(history.getFirst().genre()).isEqualTo("Pop");
        assertThat(history.getFirst().coverUrl()).isEqualTo("/covers/come-my-way.png");
        assertThat(history.getFirst().decision()).isEqualTo("rejected");
        assertThat(history.getFirst().rejectionReason()).isEqualTo("Vi phạm bản quyền; metadata sai lệch");
    }

    @Test
    void getPendingQueueShouldReturnTracksInPendingQueue() {
        Track pendingTrack = new Track();
        pendingTrack.setId("track-pending-1");
        pendingTrack.setName("Bài hát đang chờ duyệt");
        pendingTrack.setArtistName("Content Lead Artist");
        pendingTrack.setModerationStatus("pending");

        when(trackRepository.findByModerationStatusIn(List.of("pending", "PENDING")))
            .thenReturn(List.of(pendingTrack));
        when(songLicenseRepository.findDetailsByTrackIdIn(List.of("track-pending-1")))
            .thenReturn(List.of());

        var queue = moderatorService.getPendingQueue();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).id()).isEqualTo("track-pending-1");
        assertThat(queue.get(0).title()).isEqualTo("Bài hát đang chờ duyệt");
        assertThat(queue.get(0).status()).isEqualTo("pending");
        assertThat(queue.get(0).licenseRecordExists()).isFalse();
        assertThat(queue.get(0).licenseType()).isNull();
        assertThat(queue.get(0).licenseStatus()).isNull();
    }

    @Test
    void getPendingQueueShouldReturnLicenseFieldsFromDatabaseProjection() {
        Track pendingTrack = new Track();
        pendingTrack.setId("track-with-license");
        pendingTrack.setName("Bài hát có giấy phép");
        pendingTrack.setModerationStatus("pending");

        SongLicenseDetailsProjection license = mock(SongLicenseDetailsProjection.class);
        when(license.getTrackId()).thenReturn("track-with-license");
        when(license.getLicenseType()).thenReturn("DIGITAL_STREAMING");
        when(license.getCopyrightOwner()).thenReturn("Moodify Artist");
        when(license.getDistributorId()).thenReturn(8L);
        when(license.getDistributorName()).thenReturn("Distributor One");
        when(license.getDistributionContractId()).thenReturn(19L);
        when(license.getDistributionContractCode()).thenReturn("CTR-2026-019");
        when(license.getIssueDate()).thenReturn("2026-01-01");
        when(license.getExpiryDate()).thenReturn("2026-12-31");
        when(license.getLicenseStatus()).thenReturn("ACTIVE");
        when(license.getLicenseDocumentUrl()).thenReturn("https://example.test/license.pdf");

        when(trackRepository.findByModerationStatusIn(List.of("pending", "PENDING")))
            .thenReturn(List.of(pendingTrack));
        when(songLicenseRepository.findDetailsByTrackIdIn(List.of("track-with-license")))
            .thenReturn(List.of(license));

        var queue = moderatorService.getPendingQueue();

        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().licenseRecordExists()).isTrue();
        assertThat(queue.getFirst().licenseType()).isEqualTo("DIGITAL_STREAMING");
        assertThat(queue.getFirst().copyrightOwner()).isEqualTo("Moodify Artist");
        assertThat(queue.getFirst().distributorName()).isEqualTo("Distributor One");
        assertThat(queue.getFirst().distributionContractCode()).isEqualTo("CTR-2026-019");
        assertThat(queue.getFirst().issueDate()).isEqualTo("2026-01-01");
        assertThat(queue.getFirst().licenseStatus()).isEqualTo("ACTIVE");
        assertThat(queue.getFirst().licenseDocumentUrl()).isEqualTo("https://example.test/license.pdf");
    }

    @Test
    void rejectShouldSaveReasonAndAuditActionBeforeUpdatingTrack() {
        Track track = new Track();
        track.setId("track-reject-1");
        track.setModerationStatus("pending");

        ContentReviewRequest reviewRequest = new ContentReviewRequest();
        reviewRequest.setId(42L);
        reviewRequest.setContentId(track.getId());
        reviewRequest.setContentType("TRACK");
        reviewRequest.setStatus("PENDING");

        User moderator = new User();
        moderator.setId(3L);
        moderator.setUsername("moderator");

        ModerationDecisionRequest request = new ModerationDecisionRequest();
        request.setTrackId(track.getId());
        request.setActionType("reject");
        request.setRejectionReason("Bản quyền chưa được xác minh; metadata sai lệch");

        when(trackRepository.findById(track.getId())).thenReturn(Optional.of(track));
        when(contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "PENDING"))
            .thenReturn(Optional.of(reviewRequest));
        when(userRepo.findByEmailOrUsername("moderator", "moderator"))
            .thenReturn(Optional.of(moderator));
        when(songLicenseRepository.findByTrackId(track.getId())).thenReturn(Optional.empty());

        moderatorService.processDecision(request, "moderator");

        assertThat(reviewRequest.getStatus()).isEqualTo("REJECTED");
        assertThat(reviewRequest.getResolvedAt()).isNotNull();
        verify(contentReviewRequestRepository).save(reviewRequest);
        org.mockito.ArgumentCaptor<ContentReviewAction> actionCaptor =
            org.mockito.ArgumentCaptor.forClass(ContentReviewAction.class);
        verify(contentReviewActionRepository).saveAndFlush(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getReviewRequestId()).isEqualTo(42L);
        assertThat(actionCaptor.getValue().getModeratorUserId()).isEqualTo(3L);
        assertThat(actionCaptor.getValue().getAction()).isEqualTo("REJECT");
        assertThat(actionCaptor.getValue().getReason())
            .isEqualTo("Bản quyền chưa được xác minh; metadata sai lệch");
        assertThat(track.getModerationStatus()).isEqualTo("rejected");
        assertThat(track.getStatus()).isEqualTo("draft");
        verify(trackRepository).save(track);
    }

    @Test
    void rejectShouldCreateMissingRequestForPendingTrackAndSaveAudit() {
        Track track = new Track();
        track.setId("track-without-request");
        track.setArtistSpotifyId("artist-spotify-1");
        track.setModerationStatus("pending");

        User artistOwner = new User();
        artistOwner.setId(22L);
        artistOwner.setArtistSpotifyId("artist-spotify-1");

        User moderator = new User();
        moderator.setId(3L);

        ModerationDecisionRequest request = new ModerationDecisionRequest();
        request.setTrackId(track.getId());
        request.setActionType("reject");
        request.setRejectionReason("Vi phạm bản quyền");

        when(trackRepository.findById(track.getId())).thenReturn(Optional.of(track));
        when(contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "PENDING"))
            .thenReturn(Optional.empty());
        when(contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "IN_REVIEW"))
            .thenReturn(Optional.empty());
        ContentReviewRequest previousApprovedRequest = new ContentReviewRequest();
        previousApprovedRequest.setId(6L);
        previousApprovedRequest.setContentId(track.getId());
        previousApprovedRequest.setContentType("TRACK");
        previousApprovedRequest.setRequestType("PUBLISH");
        previousApprovedRequest.setStatus("APPROVED");
        when(contentReviewRequestRepository.findByContentIdAndContentType(track.getId(), "TRACK"))
            .thenReturn(List.of(previousApprovedRequest));
        when(contentReviewRequestRepository.save(any(ContentReviewRequest.class))).thenAnswer(invocation -> {
            ContentReviewRequest saved = invocation.getArgument(0);
            saved.setId(44L);
            return saved;
        });
        when(userRepo.findByArtistSpotifyId("artist-spotify-1"))
            .thenReturn(Optional.of(artistOwner));
        when(userRepo.findByEmailOrUsername("moderator", "moderator"))
            .thenReturn(Optional.of(moderator));
        when(songLicenseRepository.findByTrackId(track.getId())).thenReturn(Optional.empty());

        moderatorService.processDecision(request, "moderator");

        org.mockito.ArgumentCaptor<ContentReviewRequest> requestCaptor =
            org.mockito.ArgumentCaptor.forClass(ContentReviewRequest.class);
        verify(contentReviewRequestRepository, times(2)).save(requestCaptor.capture());
        ContentReviewRequest savedRequest = requestCaptor.getAllValues().getLast();
        assertThat(savedRequest.getId()).isEqualTo(44L);
        assertThat(savedRequest.getArtistUserId()).isEqualTo(22L);
        assertThat(savedRequest.getRequestType()).isEqualTo("UPDATE");
        assertThat(savedRequest.getStatus()).isEqualTo("REJECTED");

        org.mockito.ArgumentCaptor<ContentReviewAction> actionCaptor =
            org.mockito.ArgumentCaptor.forClass(ContentReviewAction.class);
        verify(contentReviewActionRepository).saveAndFlush(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getReviewRequestId()).isEqualTo(44L);
        assertThat(actionCaptor.getValue().getAction()).isEqualTo("REJECT");
        assertThat(actionCaptor.getValue().getReason()).isEqualTo("Vi phạm bản quyền");
        assertThat(track.getModerationStatus()).isEqualTo("rejected");
        verify(trackRepository).save(track);
    }

    @Test
    void rejectShouldPropagateMysqlAuditFailureAndLeaveTrackPending() {
        Track track = new Track();
        track.setId("track-audit-failure");
        track.setModerationStatus("pending");

        ContentReviewRequest reviewRequest = new ContentReviewRequest();
        reviewRequest.setId(43L);
        reviewRequest.setContentId(track.getId());
        reviewRequest.setContentType("TRACK");
        reviewRequest.setStatus("PENDING");

        User moderator = new User();
        moderator.setId(3L);

        ModerationDecisionRequest request = new ModerationDecisionRequest();
        request.setTrackId(track.getId());
        request.setActionType("reject");
        request.setRejectionReason("Vi phạm bản quyền");

        when(trackRepository.findById(track.getId())).thenReturn(Optional.of(track));
        when(contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "PENDING"))
            .thenReturn(Optional.of(reviewRequest));
        when(userRepo.findByEmailOrUsername("moderator", "moderator"))
            .thenReturn(Optional.of(moderator));
        doThrow(new DataIntegrityViolationException("audit insert failed"))
            .when(contentReviewActionRepository).saveAndFlush(any(ContentReviewAction.class));

        assertThatThrownBy(() -> moderatorService.processDecision(request, "moderator"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Không thể lưu lịch sử kiểm duyệt vào database");

        assertThat(track.getModerationStatus()).isEqualTo("pending");
        verify(trackRepository, never()).save(any(Track.class));
    }
}
