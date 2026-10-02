package com.laphuth.moodify.dto.emotion;

import com.laphuth.moodify.dto.track.TrackResponse;

import java.util.List;

public class MoodRecommendationResponse {

    private EmotionPredictResponse emotion;
    private List<TrackResponse> tracks;
    private int totalMatched;

    public MoodRecommendationResponse() {
    }

    public MoodRecommendationResponse(EmotionPredictResponse emotion, List<TrackResponse> tracks, int totalMatched) {
        this.emotion = emotion;
        this.tracks = tracks;
        this.totalMatched = totalMatched;
    }

    public EmotionPredictResponse getEmotion() {
        return emotion;
    }

    public void setEmotion(EmotionPredictResponse emotion) {
        this.emotion = emotion;
    }

    public List<TrackResponse> getTracks() {
        return tracks;
    }

    public void setTracks(List<TrackResponse> tracks) {
        this.tracks = tracks;
    }

    public int getTotalMatched() {
        return totalMatched;
    }

    public void setTotalMatched(int totalMatched) {
        this.totalMatched = totalMatched;
    }
}
