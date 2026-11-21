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
import com.laphuth.moodify.repositories.SongLicenseDetailsProjection;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ModeratorService {

    private final TrackRepository trackRepository;
    private CatalogIntegrityService catalogIntegrity;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCatalogIntegrity(CatalogIntegrityService value) { catalogIntegrity=value; }

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
        List<SongLicenseDetailsProjection> licenses = songLicenseRepository.findDetailsByTrackIdIn(trackIds);
        Map<String, SongLicenseDetailsProjection> licenseMap = licenses.stream()
            .collect(Collectors.toMap(
                SongLicenseDetailsProjection::getTrackId,
                l -> l,
                (existing, replacement) -> existing
            ));

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

    @Transactional
    public void processDecision(ModerationDecisionRequest request, String moderatorPrincipal) {
        if (request == null || request.getTrackId() == null || request.getTrackId().isBlank()
            || request.getActionType() == null || request.getActionType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing trackId or actionType");
        }

        Track track = trackRepository.findById(request.getTrackId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found in MongoDB"));

        String action = request.getActionType().toLowerCase().trim();
        String reviewAction;
        String reviewRequestStatus;

        switch (action) {
            case "approve" -> {
                reviewAction = "APPROVE";
                reviewRequestStatus = "APPROVED";
            }
            case "reject" -> {
                if (request.getRejectionReason() == null || request.getRejectionReason().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn ít nhất một lý do từ chối.");
                }
                reviewAction = "REJECT";
                reviewRequestStatus = "REJECTED";
            }
            case "needs_revision" -> {
                reviewAction = "RETURN_FOR_EDIT";
                reviewRequestStatus = "PENDING"; // Still pending, waiting for artist revision
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actionType: " + action);
        }

        ContentReviewRequest reviewRequest = contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "PENDING")
            .or(() -> contentReviewRequestRepository
                .findByContentIdAndContentTypeAndStatus(track.getId(), "TRACK", "IN_REVIEW"))
            .orElseGet(() -> createReviewRequestForPendingTrack(track));
        snapshotTrackMetadata(reviewRequest, track);

        User moderator = userRepo.findByEmailOrUsername(moderatorPrincipal, moderatorPrincipal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Không thể xác định tài khoản kiểm duyệt để lưu lịch sử."
            ));

        String reason = request.getRejectionReason();
        if (reason == null || reason.isBlank()) {
            reason = request.getInternalNote();
        }
        if (reason != null && reason.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lý do kiểm duyệt vượt quá 1000 ký tự.");
        }

        reviewRequest.setStatus(reviewRequestStatus);
        if ("APPROVED".equals(reviewRequestStatus) || "REJECTED".equals(reviewRequestStatus)) {
            reviewRequest.setResolvedAt(LocalDateTime.now());
        }
        contentReviewRequestRepository.save(reviewRequest);

        ContentReviewAction reviewActionEntity = new ContentReviewAction();
        reviewActionEntity.setReviewRequestId(reviewRequest.getId());
        reviewActionEntity.setModeratorUserId(moderator.getId());
        reviewActionEntity.setAction(reviewAction);
        reviewActionEntity.setReason(reason);
        reviewActionEntity.setCreatedAt(LocalDateTime.now());
        try {
            contentReviewActionRepository.saveAndFlush(reviewActionEntity);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Không thể lưu lịch sử kiểm duyệt vào database. Vui lòng thử lại.",
                e
            );
        }

        SongLicense license=songLicenseRepository.findFirstByTrackIdOrderByIdDesc(track.getId()).orElse(null);
        if("approve".equals(action)) {
            if(license==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Thiếu hồ sơ giấy phép.");
            if(track.getDurationMs()==null||track.getDurationMs()<=0||track.getAudioHash()==null||!track.getAudioHash().matches("[0-9a-f]{64}")
                || track.getArtistIds()==null||track.getArtistIds().isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Bài hát thiếu thời lượng, hash hoặc định danh nghệ sĩ.");
            boolean simulated=CatalogLicensePolicy.simulated(track,license);
            if(!simulated) {
                if(!request.isRightsVerified() || "SIMULATION_DETECTED".equals(license.getDocumentInspectionStatus()) || ContentLeadCatalogService.looksSimulated(license.getCopyrightOwner(),license.getDistributionContractCode()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Phải xác nhận hồ sơ quyền thật; tài liệu mô phỏng không được duyệt như quyền thật.");
                if(request.getInternalNote()==null||request.getInternalNote().isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần ghi căn cứ xác minh hồ sơ quyền.");
                license.setAcquisitionCostVnd(license.getDeclaredAcquisitionCostVnd());
                license.setRightsEvidenceStatus("VERIFIED");
                license.setRightsVerifiedBy(moderator.getId());
                license.setRightsVerifiedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
                license.setRightsReviewNote(request.getInternalNote().trim());
            }
            license.setStatus(LicenseStatus.ACTIVE);
            if(!CatalogLicensePolicy.effective(license,java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")))
                || !CatalogLicensePolicy.evidenceReady(track,license,true))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Hồ sơ thiếu tài liệu, hash hoặc ngoài thời hạn hiệu lực.");
        } else if(license!=null && "reject".equals(action))license.setStatus(LicenseStatus.REVOKED);
        if(license!=null)songLicenseRepository.saveAndFlush(license);

        if ("approve".equals(action)) {
            track.setModerationStatus("approved");
            track.setStatus("published");
            double score = (request.getModerationScore() != null && request.getModerationScore() > 0)
                ? request.getModerationScore()
                : 98.0;
            track.setModerationScore(score);
            track.setDownloadStatus("completed");
        } else if ("reject".equals(action)) {
            track.setModerationStatus("rejected");
            track.setStatus("draft");
        } else {
            track.setModerationStatus("needs_revision");
            track.setStatus("draft");
        }
        if (request.getExplicitTag() != null) {
            track.setExplicit(request.getExplicitTag());
        }
        track.setUpdatedAt(Instant.now());
        if(catalogIntegrity==null)throw new IllegalStateException("Catalog integrity service unavailable");
        org.bson.Document patch=new org.bson.Document("moderation_status",track.getModerationStatus())
            .append("status",track.getStatus()).append("moderation_score",track.getModerationScore())
            .append("download_status",track.getDownloadStatus()).append("explicit",track.isExplicit())
            .append("updated_at",java.util.Date.from(track.getUpdatedAt()));
        catalogIntegrity.stagePatch(track.getId(),patch);
    }

    private ContentReviewRequest createReviewRequestForPendingTrack(Track track) {
        if (!"pending".equalsIgnoreCase(track.getModerationStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Bài hát không còn ở trạng thái chờ kiểm duyệt. Hãy tải lại hàng chờ."
            );
        }

        String artistSpotifyId = track.getArtistSpotifyId();
        if (artistSpotifyId == null || artistSpotifyId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Bài hát chưa liên kết với tài khoản nghệ sĩ nên không thể tạo yêu cầu kiểm duyệt."
            );
        }

        User artistOwner = userRepo.findByArtistSpotifyId(artistSpotifyId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Không tìm thấy tài khoản nghệ sĩ sở hữu bài hát để tạo yêu cầu kiểm duyệt."
            ));
        List<ContentReviewRequest> previousRequests = contentReviewRequestRepository
            .findByContentIdAndContentType(track.getId(), "TRACK");

        ContentReviewRequest reviewRequest = new ContentReviewRequest();
        reviewRequest.setArtistUserId(artistOwner.getId());
        reviewRequest.setContentType("TRACK");
        reviewRequest.setContentId(track.getId());
        reviewRequest.setRequestType(previousRequests.isEmpty() ? "PUBLISH" : "UPDATE");
        reviewRequest.setStatus("PENDING");
        reviewRequest.setSubmittedAt(LocalDateTime.now());
        return contentReviewRequestRepository.save(reviewRequest);
    }

    private void snapshotTrackMetadata(ContentReviewRequest reviewRequest, Track track) {
        reviewRequest.setContentTitle(track.getName());
        reviewRequest.setArtistName(track.getArtistName());
        reviewRequest.setContentGenre(firstGenre(track));
        reviewRequest.setContentCoverUrl(track.getImageUrl());
    }

    private String firstGenre(Track track) {
        if (track.getGenres() != null && !track.getGenres().isEmpty()) {
            return track.getGenres().get(0);
        }
        if (track.getGenresRaw() != null && !track.getGenresRaw().isEmpty()) {
            return track.getGenresRaw().get(0);
        }
        return null;
    }
}
