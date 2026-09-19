package com.laphuth.moodify.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "playlists")
public class Playlist {
    @Id
    private String id;

    private String name;

    private String description;

    @Field("cover_url")
    private String coverUrl;

    private String username;

    @Field("is_public")
    private Boolean isPublic = true;

    @Field("track_spotify_ids")
    private List<String> trackSpotifyIds = new ArrayList<>();

    @Field("created_at")
    private Instant createdAt = Instant.now();

    @Field("updated_at")
    private Instant updatedAt = Instant.now();

    public Playlist() {}

    public Playlist(String name, String description, String coverUrl, String username, Boolean isPublic) {
        this.name = name;
        this.description = description;
        this.coverUrl = coverUrl;
        this.username = username;
        this.isPublic = isPublic != null ? isPublic : true;
        this.trackSpotifyIds = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public List<String> getTrackSpotifyIds() {
        if (trackSpotifyIds == null) {
            trackSpotifyIds = new ArrayList<>();
        }
        return trackSpotifyIds;
    }

    public void setTrackSpotifyIds(List<String> trackSpotifyIds) {
        this.trackSpotifyIds = trackSpotifyIds != null ? trackSpotifyIds : new ArrayList<>();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
