package com.laphuth.moodify.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "karaoke_rooms")
public class KaraokeRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User hostUser;

    @Column(name = "max_members", nullable = false)
    private Integer maxMembers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KaraokeRoomVisibility visibility;

    @Column(name = "invite_code_hash", length = 64)
    private String inviteCodeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "current_track_id", length = 64)
    private String currentTrackId;

    @Enumerated(EnumType.STRING)
    @Column(name = "playback_status", nullable = false, length = 12)
    private KaraokePlaybackStatus playbackStatus = KaraokePlaybackStatus.IDLE;

    @Column(name = "playback_position_ms", nullable = false)
    private Long playbackPositionMs = 0L;

    @Column(name = "playback_started_at")
    private LocalDateTime playbackStartedAt;

    @Column(name = "playback_version", nullable = false)
    private Long playbackVersion = 0L;

    @Column(name = "playback_run_id", length = 36)
    private String playbackRunId;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public User getHostUser() { return hostUser; }
    public void setHostUser(User hostUser) { this.hostUser = hostUser; }
    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }
    public KaraokeRoomVisibility getVisibility() { return visibility; }
    public void setVisibility(KaraokeRoomVisibility visibility) { this.visibility = visibility; }
    public String getInviteCodeHash() { return inviteCodeHash; }
    public void setInviteCodeHash(String inviteCodeHash) { this.inviteCodeHash = inviteCodeHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getCurrentTrackId() { return currentTrackId; }
    public void setCurrentTrackId(String currentTrackId) { this.currentTrackId = currentTrackId; }
    public KaraokePlaybackStatus getPlaybackStatus() { return playbackStatus; }
    public void setPlaybackStatus(KaraokePlaybackStatus playbackStatus) { this.playbackStatus = playbackStatus; }
    public Long getPlaybackPositionMs() { return playbackPositionMs == null ? 0L : playbackPositionMs; }
    public void setPlaybackPositionMs(Long playbackPositionMs) { this.playbackPositionMs = playbackPositionMs; }
    public LocalDateTime getPlaybackStartedAt() { return playbackStartedAt; }
    public void setPlaybackStartedAt(LocalDateTime playbackStartedAt) { this.playbackStartedAt = playbackStartedAt; }
    public Long getPlaybackVersion() { return playbackVersion == null ? 0L : playbackVersion; }
    public void setPlaybackVersion(Long playbackVersion) { this.playbackVersion = playbackVersion; }
    public String getPlaybackRunId() { return playbackRunId; }
    public void setPlaybackRunId(String playbackRunId) { this.playbackRunId = playbackRunId; }
}
