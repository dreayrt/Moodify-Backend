package com.laphuth.moodify.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "albums")
public class Album {
    @Id
    private String id;

    @Field("spotify_id")
    private String spotifyId;

    private String name;

    @Field("artist_name")
    private String artistName;

    @Field("artist_spotify_id")
    private String artistSpotifyId;

    @Field("image_url")
    private String imageUrl;

    @Field("release_date")
    private String releaseDate;

    @Field("total_tracks")
    private Integer totalTracks;

    @Field("downloaded_tracks_count")
    private Integer downloadedTracksCount;

    @Field("is_fully_downloaded")
    private Boolean fullyDownloaded;

    @Field("track_ids")
    private List<String> trackIds;

    @Field("created_at")
    private Instant createdAt;

    @Field("updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSpotifyId() { return spotifyId; }
    public void setSpotifyId(String spotifyId) { this.spotifyId = spotifyId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArtistName() { return artistName; }
    public void setArtistName(String artistName) { this.artistName = artistName; }
    public String getArtistSpotifyId() { return artistSpotifyId; }
    public void setArtistSpotifyId(String artistSpotifyId) { this.artistSpotifyId = artistSpotifyId; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public Integer getTotalTracks() { return totalTracks; }
    public void setTotalTracks(Integer totalTracks) { this.totalTracks = totalTracks; }
    public Integer getDownloadedTracksCount() { return downloadedTracksCount; }
    public void setDownloadedTracksCount(Integer downloadedTracksCount) { this.downloadedTracksCount = downloadedTracksCount; }
    public Boolean getFullyDownloaded() { return fullyDownloaded; }
    public void setFullyDownloaded(Boolean fullyDownloaded) { this.fullyDownloaded = fullyDownloaded; }
    public List<String> getTrackIds() { return trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
