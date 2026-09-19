package com.laphuth.moodify.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_review_requests")
public class ContentReviewRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artist_user_id", nullable = false)
    private Long artistUserId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType; // TRACK, ALBUM

    @Column(name = "content_id", nullable = false, length = 64)
    private String contentId; // MongoDB Track ID

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType; // PUBLISH, UPDATE

    @Column(name = "status", nullable = false, length = 20)
    private String status; // PENDING, IN_REVIEW, APPROVED, REJECTED, CANCELLED

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (this.submittedAt == null) {
            this.submittedAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "PENDING";
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getArtistUserId() { return artistUserId; }
    public void setArtistUserId(Long artistUserId) { this.artistUserId = artistUserId; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }

    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
