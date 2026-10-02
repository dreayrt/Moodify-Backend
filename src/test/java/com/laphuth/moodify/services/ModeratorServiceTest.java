package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.moderator.ModerationHistoryResponse;
import com.laphuth.moodify.entities.ContentReviewAction;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void getPendingQueueShouldReturnTracksInPendingQueue() {
        Track pendingTrack = new Track();
        pendingTrack.setId("track-pending-1");
        pendingTrack.setName("Bài hát đang chờ duyệt");
        pendingTrack.setArtistName("Content Lead Artist");
        pendingTrack.setModerationStatus("pending");

        when(trackRepository.findByModerationStatusIn(List.of("pending", "PENDING")))
            .thenReturn(List.of(pendingTrack));
        when(songLicenseRepository.findByTrackIdIn(List.of("track-pending-1")))
            .thenReturn(List.of());

        var queue = moderatorService.getPendingQueue();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).id()).isEqualTo("track-pending-1");
        assertThat(queue.get(0).title()).isEqualTo("Bài hát đang chờ duyệt");
        assertThat(queue.get(0).status()).isEqualTo("pending");
    }
}
