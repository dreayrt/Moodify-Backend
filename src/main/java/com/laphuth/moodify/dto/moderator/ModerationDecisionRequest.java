package com.laphuth.moodify.dto.moderator;

public class ModerationDecisionRequest {
    private String trackId;
    private String actionType; // approve, reject, needs_revision
    private String rejectionReason;
    private String internalNote;
    private Boolean explicitTag;
    private Double moderationScore;

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getInternalNote() { return internalNote; }
    public void setInternalNote(String internalNote) { this.internalNote = internalNote; }

    public Boolean getExplicitTag() { return explicitTag; }
    public void setExplicitTag(Boolean explicitTag) { this.explicitTag = explicitTag; }

    public Double getModerationScore() { return moderationScore; }
    public void setModerationScore(Double moderationScore) { this.moderationScore = moderationScore; }
}