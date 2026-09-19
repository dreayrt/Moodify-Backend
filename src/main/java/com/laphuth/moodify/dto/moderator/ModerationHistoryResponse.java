package com.laphuth.moodify.dto.moderator;

import com.laphuth.moodify.entities.ContentReviewAction;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;

import java.time.Duration;

public record ModerationHistoryResponse(
    String id,
    String trackId,
    String trackTitle,
    String artistName,
    String genre,
    String coverUrl,
    String reviewedAt,
    String reviewerId,
    String reviewerName,
    String decision,
    String rejectionReason,
    String internalNote,
    int reviewDurationSec,
    Boolean assignedExplicitTag
) {
    public static ModerationHistoryResponse from(
        ContentReviewAction action,
        ContentReviewRequest request,
        Track track,
        User moderator
    ) {
        String decision = switch (normalize(action.getAction())) {
            case "APPROVE" -> "approved";
            case "REJECT" -> "rejected";
            case "RETURN_FOR_EDIT" -> "needs_revision";
            default -> "needs_revision";
        };

        int durationSec = 0;
        if (request != null && request.getSubmittedAt() != null && action.getCreatedAt() != null) {
            durationSec = Math.max(0, (int) Duration.between(request.getSubmittedAt(), action.getCreatedAt()).toSeconds());
        }

        return new ModerationHistoryResponse(
            action.getId() != null ? action.getId().toString() : "",
            request != null ? request.getContentId() : "",
            track != null && track.getName() != null ? track.getName() : "Bài hát đã kiểm duyệt",
            track != null && track.getArtistName() != null ? track.getArtistName() : "Nghệ sĩ",
            firstGenre(track),
            track != null ? track.getImageUrl() : null,
            action.getCreatedAt() != null ? action.getCreatedAt().toString() : null,
            action.getModeratorUserId() != null ? action.getModeratorUserId().toString() : "",
            moderator != null && moderator.getFullname() != null ? moderator.getFullname() : "Kiểm duyệt viên",
            decision,
            "rejected".equals(decision) || "needs_revision".equals(decision) ? action.getReason() : null,
            "approved".equals(decision) ? action.getReason() : null,
            durationSec,
            track != null ? track.isExplicit() : null
        );
    }

    private static String firstGenre(Track track) {
        if (track != null && track.getGenres() != null && !track.getGenres().isEmpty()) {
            return track.getGenres().get(0);
        }
        if (track != null && track.getGenresRaw() != null && !track.getGenresRaw().isEmpty()) {
            return track.getGenresRaw().get(0);
        }
        return "Pop";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
