package com.laphuth.moodify.dto.emotion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MusicRecommendationInfo {

    @JsonProperty("strategy")
    private String strategy;

    @JsonProperty("mood_analysis")
    private String moodAnalysis;

    @JsonProperty("target_valence")
    private Double targetValence;

    @JsonProperty("min_valence")
    private Double minValence;

    @JsonProperty("max_valence")
    private Double maxValence;

    @JsonProperty("target_energy")
    private Double targetEnergy;

    @JsonProperty("min_energy")
    private Double minEnergy;

    @JsonProperty("max_energy")
    private Double maxEnergy;

    @JsonProperty("target_danceability")
    private Double targetDanceability;

    @JsonProperty("target_acousticness")
    private Double targetAcousticness;

    @JsonProperty("target_mode")
    private String targetMode;

    @JsonProperty("target_tempo")
    private String targetTempo;

    @JsonProperty("seed_genres")
    private List<String> seedGenres;

    @JsonProperty("spotify_params")
    private Map<String, Object> spotifyParams;

    public MusicRecommendationInfo() {
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getMoodAnalysis() {
        return moodAnalysis;
    }

    public void setMoodAnalysis(String moodAnalysis) {
        this.moodAnalysis = moodAnalysis;
    }

    public Double getTargetValence() {
        return targetValence;
    }

    public void setTargetValence(Double targetValence) {
        this.targetValence = targetValence;
    }

    public Double getMinValence() {
        return minValence;
    }

    public void setMinValence(Double minValence) {
        this.minValence = minValence;
    }

    public Double getMaxValence() {
        return maxValence;
    }

    public void setMaxValence(Double maxValence) {
        this.maxValence = maxValence;
    }

    public Double getTargetEnergy() {
        return targetEnergy;
    }

    public void setTargetEnergy(Double targetEnergy) {
        this.targetEnergy = targetEnergy;
    }

    public Double getMinEnergy() {
        return minEnergy;
    }

    public void setMinEnergy(Double minEnergy) {
        this.minEnergy = minEnergy;
    }

    public Double getMaxEnergy() {
        return maxEnergy;
    }

    public void setMaxEnergy(Double maxEnergy) {
        this.maxEnergy = maxEnergy;
    }

    public Double getTargetDanceability() {
        return targetDanceability;
    }

    public void setTargetDanceability(Double targetDanceability) {
        this.targetDanceability = targetDanceability;
    }

    public Double getTargetAcousticness() {
        return targetAcousticness;
    }

    public void setTargetAcousticness(Double targetAcousticness) {
        this.targetAcousticness = targetAcousticness;
    }

    public String getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(String targetMode) {
        this.targetMode = targetMode;
    }

    public String getTargetTempo() {
        return targetTempo;
    }

    public void setTargetTempo(String targetTempo) {
        this.targetTempo = targetTempo;
    }

    public List<String> getSeedGenres() {
        return seedGenres;
    }

    public void setSeedGenres(List<String> seedGenres) {
        this.seedGenres = seedGenres;
    }

    public Map<String, Object> getSpotifyParams() {
        return spotifyParams;
    }

    public void setSpotifyParams(Map<String, Object> spotifyParams) {
        this.spotifyParams = spotifyParams;
    }
}
