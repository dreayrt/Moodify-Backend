package com.laphuth.moodify.entities;

import jakarta.persistence.Column;
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
@Table(name = "karaoke_room_queue")
public class KaraokeRoomQueueItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private KaraokeRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_user_id", nullable = false)
    private User requester;

    @Column(name = "track_id", nullable = false, length = 64)
    private String trackId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public KaraokeRoom getRoom() { return room; }
    public void setRoom(KaraokeRoom room) { this.room = room; }
    public User getRequester() { return requester; }
    public void setRequester(User requester) { this.requester = requester; }
    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
