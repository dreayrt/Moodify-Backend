package com.laphuth.moodify.entities;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_library_tracks")
public class UserLibraryTracks {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    @Column(name = "track_spotify_id")
    private String trackSpotifyId;
    @Column(name = "added_at")
    private LocalDateTime addedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTrackSpotifyId() {
        return trackSpotifyId;
    }

    public void setTrackSpotifyId(String trackSpotifyId) {
        this.trackSpotifyId = trackSpotifyId;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(LocalDateTime addedAt) {
        this.addedAt = addedAt;
    }
}
