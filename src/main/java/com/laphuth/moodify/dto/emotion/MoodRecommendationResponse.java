package com.laphuth.moodify.dto.emotion;

import com.laphuth.moodify.dto.track.TrackResponse;

import java.util.List;

public class MoodRecommendationResponse {

    private EmotionPredictResponse emotion;
    private List<TrackResponse> tracks;
    private int totalMatched;
    private int candidateCount;
    private String matchStatus;
    private String rankingVersion;
    private double maxMatchDistance;
    private MoodCatalogDiagnostics catalogDiagnostics;

    public MoodRecommendationResponse() {
    }

    public MoodRecommendationResponse(EmotionPredictResponse emotion, List<TrackResponse> tracks, int totalMatched) {
        this(emotion, tracks, totalMatched, totalMatched, "MATCHED", "audio-profile-v3-explainable", 0.35, null);
    }

    public MoodRecommendationResponse(
        EmotionPredictResponse emotion,
        List<TrackResponse> tracks,
        int totalMatched,
        int candidateCount,
        String matchStatus,
        String rankingVersion,
        double maxMatchDistance
    ) {
        this(emotion, tracks, totalMatched, candidateCount, matchStatus, rankingVersion, maxMatchDistance, null);
    }

    public MoodRecommendationResponse(
        EmotionPredictResponse emotion,
        List<TrackResponse> tracks,
        int totalMatched,
        int candidateCount,
        String matchStatus,
        String rankingVersion,
        double maxMatchDistance,
        MoodCatalogDiagnostics catalogDiagnostics
    ) {
        this.emotion = emotion;
        this.tracks = tracks;
        this.totalMatched = totalMatched;
        this.candidateCount = candidateCount;
        this.matchStatus = matchStatus;
        this.rankingVersion = rankingVersion;
        this.maxMatchDistance = maxMatchDistance;
        this.catalogDiagnostics = catalogDiagnostics;
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

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public String getMatchStatus() {
        return matchStatus;
    }

    public void setMatchStatus(String matchStatus) {
        this.matchStatus = matchStatus;
    }

    public String getRankingVersion() {
        return rankingVersion;
    }

    public void setRankingVersion(String rankingVersion) {
        this.rankingVersion = rankingVersion;
    }

    public double getMaxMatchDistance() {
        return maxMatchDistance;
    }

    public void setMaxMatchDistance(double maxMatchDistance) {
        this.maxMatchDistance = maxMatchDistance;
    }

    public MoodCatalogDiagnostics getCatalogDiagnostics() {
        return catalogDiagnostics;
    }

    public void setCatalogDiagnostics(MoodCatalogDiagnostics catalogDiagnostics) {
        this.catalogDiagnostics = catalogDiagnostics;
    }
}
