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

    @Field("featured_artists")
    private String featuredArtists;

    @Field("description")
    private String description;

    private String status;
    private String visibility;

    @Field("image_url")
    private String imageUrl;

    @Field("release_date")
    private String releaseDate;

    @Field("track_number")
    private Integer trackNumber;

    @Field("duration_ms")
    private Integer durationMs;

    @Field("duration_formatted")
    private String durationFormatted;

    private boolean explicit;
    private List<String> genres;

    @Field("genres_raw")
    private List<String> genresRaw;

    @Field("popularity")
    private Integer popularity;

    @Field("lyrics_plain")
    private String lyricsPlain;

    @Field("local_path")
    private String localPath;

    @Field("download_status")
    private String downloadStatus;

    @Field("spotify_url")
    private String spotifyUrl;

    @Field("moderation_status")
    private String moderationStatus;

    @Field("moderation_score")
    private Double moderationScore;

    @Field("audio_features")
    private AudioFeatures audioFeatures;

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
    public String getFeaturedArtists() { return featuredArtists; }
    public void setFeaturedArtists(String featuredArtists) { this.featuredArtists = featuredArtists; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public Integer getTrackNumber() { return trackNumber; }
    public void setTrackNumber(Integer trackNumber) { this.trackNumber = trackNumber; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public String getDurationFormatted() { return durationFormatted; }
    public void setDurationFormatted(String durationFormatted) { this.durationFormatted = durationFormatted; }
    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
    public List<String> getGenresRaw() { return genresRaw; }
    public void setGenresRaw(List<String> genresRaw) { this.genresRaw = genresRaw; }
    public Integer getPopularity() { return popularity; }
    public void setPopularity(Integer popularity) { this.popularity = popularity; }
    public String getLyricsPlain() { return lyricsPlain; }
    public void setLyricsPlain(String lyricsPlain) { this.lyricsPlain = lyricsPlain; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getDownloadStatus() { return downloadStatus; }
    public void setDownloadStatus(String downloadStatus) { this.downloadStatus = downloadStatus; }
    public String getSpotifyUrl() { return spotifyUrl; }
    public void setSpotifyUrl(String spotifyUrl) { this.spotifyUrl = spotifyUrl; }
    public String getModerationStatus() { return moderationStatus; }
    public void setModerationStatus(String moderationStatus) { this.moderationStatus = moderationStatus; }
    public Double getModerationScore() { return moderationScore; }
    public void setModerationScore(Double moderationScore) { this.moderationScore = moderationScore; }
    public AudioFeatures getAudioFeatures() { return audioFeatures; }
    public void setAudioFeatures(AudioFeatures audioFeatures) { this.audioFeatures = audioFeatures; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class AudioFeatures {
        @Field("energy")
        private Double energy;

        @Field("danceability")
        private Double danceability;

        @Field("valence")
        private Double valence;

        @Field("acousticness")
        private Double acousticness;

        public Double getEnergy() { return energy; }
        public void setEnergy(Double energy) { this.energy = energy; }
        public Double getDanceability() { return danceability; }
        public void setDanceability(Double danceability) { this.danceability = danceability; }
        public Double getValence() { return valence; }
        public void setValence(Double valence) { this.valence = valence; }
        public Double getAcousticness() { return acousticness; }
        public void setAcousticness(Double acousticness) { this.acousticness = acousticness; }
    }
}
