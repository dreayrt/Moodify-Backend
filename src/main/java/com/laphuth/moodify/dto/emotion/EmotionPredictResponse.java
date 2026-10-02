package com.laphuth.moodify.dto.emotion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EmotionPredictResponse {

    @JsonProperty("text")
    private String text;

    @JsonProperty("label")
    private String label;

    @JsonProperty("emoji")
    private String emoji;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("probabilities")
    private Map<String, Double> probabilities;

    @JsonProperty("music_recommendation")
    private MusicRecommendationInfo musicRecommendation;

    public EmotionPredictResponse() {
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Map<String, Double> getProbabilities() {
        return probabilities;
    }

    public void setProbabilities(Map<String, Double> probabilities) {
        this.probabilities = probabilities;
    }

    public MusicRecommendationInfo getMusicRecommendation() {
        return musicRecommendation;
    }

    public void setMusicRecommendation(MusicRecommendationInfo musicRecommendation) {
        this.musicRecommendation = musicRecommendation;
    }
}
