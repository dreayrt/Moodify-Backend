package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.moderator.ModerationDecisionRequest;
import com.laphuth.moodify.dto.moderator.ModerationHistoryResponse;
import com.laphuth.moodify.dto.moderator.ModerationQueueTrackResponse;
import com.laphuth.moodify.entities.ContentReviewAction;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ModeratorService {

    private final TrackRepository trackRepository;
    private final SongLicenseRepository songLicenseRepository;
    private final ContentReviewRequestRepository contentReviewRequestRepository;
    private final ContentReviewActionRepository contentReviewActionRepository;
    private final UserRepository userRepo;

    public ModeratorService(
        TrackRepository trackRepository,
        SongLicenseRepository songLicenseRepository,
        ContentReviewRequestRepository contentReviewRequestRepository,
        ContentReviewActionRepository contentReviewActionRepository,
        UserRepository userRepo
    ) {
        this.trackRepository = trackRepository;
        this.songLicenseRepository = songLicenseRepository;
        this.contentReviewRequestRepository = contentReviewRequestRepository;
        this.contentReviewActionRepository = contentReviewActionRepository;
        this.userRepo = userRepo;
    }

    public List<ModerationQueueTrackResponse> getPendingQueue() {
        List<Track> pendingTracks = trackRepository.findByModerationStatusIn(List.of("pending", "PENDING"));
        if (pendingTracks.isEmpty()) {
            return List.of();
        }

        List<String> trackIds = pendingTracks.stream().map(Track::getId).toList();
        List<SongLicense> licenses = songLicenseRepository.findByTrackIdIn(trackIds);
        Map<String, SongLicense> licenseMap = licenses.stream()
            .collect(Collectors.toMap(SongLicense::getTrackId, l -> l, (existing, replacement) -> existing));

        return pendingTracks.stream()
            .map(t -> ModerationQueueTrackResponse.from(t, licenseMap.get(t.getId())))
            .toList();
    }

    public List<ModerationHistoryResponse> getHistory() {
        List<ContentReviewAction> actions = contentReviewActionRepository.findAll(
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        if (actions.isEmpty()) {
            return List.of();
        }

        List<Long> requestIds = actions.stream()
            .map(ContentReviewAction::getReviewRequestId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        Map<Long, ContentReviewRequest> requestMap = contentReviewRequestRepository.findAllById(requestIds)
            .stream()
            .collect(Collectors.toMap(ContentReviewRequest::getId, r -> r, (e, r) -> e));

        List<String> trackIds = requestMap.values().stream()
            .map(ContentReviewRequest::getContentId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        Map<String, Track> trackMap = trackRepository.findAllById(trackIds)
            .stream()
            .collect(Collectors.toMap(Track::getId, t -> t, (e, r) -> e));

        List<Long> moderatorIds = actions.stream()
            .map(ContentReviewAction::getModeratorUserId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        Map<Long, User> moderatorMap = userRepo.findAllById(moderatorIds)
            .stream()
            .collect(Collectors.toMap(User::getId, u -> u, (e, r) -> e));

        return actions.stream()
            .map(action -> {
                ContentReviewRequest request = requestMap.get(action.getReviewRequestId());
                Track track = request != null ? trackMap.get(request.getContentId()) : null;
                User moderator = action.getModeratorUserId() != null
                    ? moderatorMap.get(action.getModeratorUserId())
                    : null;
                return ModerationHistoryResponse.from(action, request, track, moderator);
            })
            .toList();
    }

    public void processDecision(ModerationDecisionRequest request, String moderatorPrincipal) {
        if (request.getTrackId() == null || request.getActionType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing trackId or actionType");
        }

        Track track = trackRepository.findById(request.getTrackId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found in MongoDB"));

        // Resolve moderator user ID
        Long moderatorUserId = null;
        try {
            User moderator = userRepo.findByEmailOrUsername(moderatorPrincipal, moderatorPrincipal).orElse(null);
            if (moderator != null) {
                moderatorUserId = moderator.getId();
            }
        } catch (Exception ignored) {}

        String action = request.getActionType().toLowerCase().trim();
        String reviewAction;
        String reviewRequestStatus;

        switch (action) {
            case "approve" -> {
                track.setModerationStatus("approved");
                track.setStatus("published");
                double score = (request.getModerationScore() != null && request.getModerationScore() > 0)
                    ? request.getModerationScore()
                    : 98.0;
                track.setModerationScore(score);
                track.setDownloadStatus("completed");
                reviewAction = "APPROVE";
                reviewRequestStatus = "APPROVED";
            }
            case "reject" -> {
                track.setModerationStatus("rejected");
                track.setStatus("draft");
                reviewAction = "REJECT";
                reviewRequestStatus = "REJECTED";
            }
            case "needs_revision" -> {
                track.setModerationStatus("needs_revision");
                track.setStatus("draft");
                reviewAction = "RETURN_FOR_EDIT";
                reviewRequestStatus = "PENDING"; // Still pending, waiting for artist revision
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actionType: " + action);
        }

        if (request.getExplicitTag() != null) {
            track.setExplicit(request.getExplicitTag());
        }
        track.setUpdatedAt(Instant.now());
        trackRepository.save(track);

        // Update MySQL song_licenses accordingly
        songLicenseRepository.findByTrackId(track.getId()).ifPresent(license -> {
            if ("approve".equals(action)) {
                license.setStatus(LicenseStatus.ACTIVE);
            } else if ("reject".equals(action)) {
                license.setStatus(LicenseStatus.REVOKED);
            }
            songLicenseRepository.save(license);
        });

        // Update content_review_requests & create content_review_actions in MySQL
        try {
            // Find the PENDING review request for this track
            ContentReviewRequest reviewRequest = contentReviewRequestRepository
                .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "PENDING")
                .orElse(null);

            if (reviewRequest == null) {
                // Also try IN_REVIEW status
                reviewRequest = contentReviewRequestRepository
                    .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "IN_REVIEW")
                    .orElse(null);
            }

            if (reviewRequest != null) {
                // Update review request status
                reviewRequest.setStatus(reviewRequestStatus);
                if ("APPROVED".equals(reviewRequestStatus) || "REJECTED".equals(reviewRequestStatus)) {
                    reviewRequest.setResolvedAt(LocalDateTime.now());
                }
                contentReviewRequestRepository.save(reviewRequest);

                // Create review action audit trail
                ContentReviewAction reviewActionEntity = new ContentReviewAction();
                reviewActionEntity.setReviewRequestId(reviewRequest.getId());
                reviewActionEntity.setModeratorUserId(moderatorUserId);
                reviewActionEntity.setAction(reviewAction);

                // Build reason from request fields
                String reason = request.getRejectionReason();
                if (reason == null || reason.isBlank()) {
                    reason = request.getInternalNote();
                }
                reviewActionEntity.setReason(reason);
                reviewActionEntity.setCreatedAt(LocalDateTime.now());

                contentReviewActionRepository.save(reviewActionEntity);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to update content review tables: " + e.getMessage());
        }
    }
}
