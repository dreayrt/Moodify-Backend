package com.laphuth.moodify.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_review_actions")
public class ContentReviewAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_request_id", nullable = false)
    private Long reviewRequestId;

    @Column(name = "moderator_user_id")
    private Long moderatorUserId;

    @Column(name = "action", nullable = false, length = 30)
    private String action; // START_REVIEW, APPROVE, REJECT, RETURN_FOR_EDIT

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getReviewRequestId() { return reviewRequestId; }
    public void setReviewRequestId(Long reviewRequestId) { this.reviewRequestId = reviewRequestId; }

    public Long getModeratorUserId() { return moderatorUserId; }
    public void setModeratorUserId(Long moderatorUserId) { this.moderatorUserId = moderatorUserId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
