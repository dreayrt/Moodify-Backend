package com.laphuth.moodify.entities;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "album_tracks", uniqueConstraints = {
    @UniqueConstraint(name = "uq_album_track", columnNames = {"album_id", "track_id"})
})
public class AlbumTrackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private AlbumEntity album;

    @Column(name = "track_id", nullable = false, length = 80)
    private String trackId;

    @Column(name = "track_order", nullable = false)
    private Integer trackOrder = 1;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) addedAt = LocalDateTime.now();
        if (trackOrder == null) trackOrder = 1;
    }

    public AlbumTrackEntity() {}

    public AlbumTrackEntity(AlbumEntity album, String trackId, Integer trackOrder) {
        this.album = album;
        this.trackId = trackId;
        this.trackOrder = trackOrder != null ? trackOrder : 1;
        this.addedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AlbumEntity getAlbum() { return album; }
    public void setAlbum(AlbumEntity album) { this.album = album; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public Integer getTrackOrder() { return trackOrder; }
    public void setTrackOrder(Integer trackOrder) { this.trackOrder = trackOrder; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}
