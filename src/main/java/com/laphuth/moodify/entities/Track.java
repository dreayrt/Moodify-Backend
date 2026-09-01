package com.laphuth.moodify.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "tracks")
public class Track {
    @Id
    private String id;

    @Field("spotify_id")
    private String spotifyId;

    private String name;

    @Field("artist_name")
    private String artistName;

    @Field("artist_spotify_id")
    private String artistSpotifyId;

    @Field("album_spotify_id")
    private String albumSpotifyId;

    @Field("album_name")
    private String albumName;

    @Field("image_url")
    private String imageUrl;

    @Field("release_date")
    private String releaseDate;

    @Field("track_number")
    private Integer trackNumber;

    @Field("duration_ms")
    private Integer durationMs;

    private boolean explicit;
    private List<String> genres;

    @Field("lyrics_plain")
    private String lyricsPlain;

    @Field("local_path")
    private String localPath;

    @Field("download_status")
    private String downloadStatus;

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
    public String getAlbumSpotifyId() { return albumSpotifyId; }
    public void setAlbumSpotifyId(String albumSpotifyId) { this.albumSpotifyId = albumSpotifyId; }
    public String getAlbumName() { return albumName; }
    public void setAlbumName(String albumName) { this.albumName = albumName; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public Integer getTrackNumber() { return trackNumber; }
    public void setTrackNumber(Integer trackNumber) { this.trackNumber = trackNumber; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
    public String getLyricsPlain() { return lyricsPlain; }
    public void setLyricsPlain(String lyricsPlain) { this.lyricsPlain = lyricsPlain; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getDownloadStatus() { return downloadStatus; }
    public void setDownloadStatus(String downloadStatus) { this.downloadStatus = downloadStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
