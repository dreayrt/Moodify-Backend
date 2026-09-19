package com.laphuth.moodify.dto.library;

import java.util.List;

public class LibraryTrackResponse {
    private String id;
    private String spotifyId;
    private String name;
    private String artistName;
    private String artistSpotifyId;
    private String albumName;
    private int durationMs;
    private int popularity;
    private String previewUrl;
    private String imageUrl;
    private List<String> genres;
    private String addedAt;
    private String lyricsPlain;
    private String lyricsSynced;
    private String localPath;

    public LibraryTrackResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSpotifyId() {
        return spotifyId;
    }

    public void setSpotifyId(String spotifyId) {
        this.spotifyId = spotifyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArtistName() {
        return artistName;
    }

    public void setArtistName(String artistName) {
        this.artistName = artistName;
    }

    public String getArtistSpotifyId() {
        return artistSpotifyId;
    }

    public void setArtistSpotifyId(String artistSpotifyId) {
        this.artistSpotifyId = artistSpotifyId;
    }

    public String getAlbumName() {
        return albumName;
    }

    public void setAlbumName(String albumName) {
        this.albumName = albumName;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public int getPopularity() {
        return popularity;
    }

    public void setPopularity(int popularity) {
        this.popularity = popularity;
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    public void setPreviewUrl(String previewUrl) {
        this.previewUrl = previewUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<String> getGenres() {
        return genres;
    }

    public void setGenres(List<String> genres) {
        this.genres = genres;
    }

    public String getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(String addedAt) {
        this.addedAt = addedAt;
    }

    public String getLyricsPlain() {
        return lyricsPlain;
    }

    public void setLyricsPlain(String lyricsPlain) {
        this.lyricsPlain = lyricsPlain;
    }

    public String getLyricsSynced() {
        return lyricsSynced;
    }

    public void setLyricsSynced(String lyricsSynced) {
        this.lyricsSynced = lyricsSynced;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }
}
