package com.laphuth.moodify.dto.contentlead;

public class TrackUpdateRequest {
    private String title;
    private String genre;
    private String featuredArtists;
    private String albumName;
    private String status;
    private String visibility;
    private Boolean explicit;
    private String lyricsPlain;
    private String description;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public String getFeaturedArtists() { return featuredArtists; }
    public void setFeaturedArtists(String featuredArtists) { this.featuredArtists = featuredArtists; }

    public String getAlbumName() { return albumName; }
    public void setAlbumName(String albumName) { this.albumName = albumName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public Boolean getExplicit() { return explicit; }
    public void setExplicit(Boolean explicit) { this.explicit = explicit; }

    public String getLyricsPlain() { return lyricsPlain; }
    public void setLyricsPlain(String lyricsPlain) { this.lyricsPlain = lyricsPlain; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
