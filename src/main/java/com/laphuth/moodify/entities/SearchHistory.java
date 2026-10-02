package com.laphuth.moodify.entities;

import com.laphuth.moodify.entities.enums.SearchType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "search_history")
public class SearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "keyword", nullable = false)
    private String keyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "search_type", nullable = false)
    private SearchType searchType;

    @Column(name = "detected_emotion", length = 50)
    private String detectedEmotion;

    @Column(name = "emotion_confidence", precision = 5, scale = 4)
    private BigDecimal emotionConfidence;

    @Column(name = "selected_target_type", length = 20)
    private String selectedTargetType;

    @Column(name = "selected_target_id", length = 64)
    private String selectedTargetId;

    @Column(name = "searched_at", nullable = false)
    private LocalDateTime searchedAt;

    public SearchHistory() {
    }

    public SearchHistory(User user, String keyword, SearchType searchType, String detectedEmotion, BigDecimal emotionConfidence) {
        this.user = user;
        this.keyword = keyword;
        this.searchType = searchType;
        this.detectedEmotion = detectedEmotion;
        this.emotionConfidence = emotionConfidence;
        this.searchedAt = LocalDateTime.now();
    }

    @PrePersist
    public void prePersist() {
        if (this.searchedAt == null) {
            this.searchedAt = LocalDateTime.now();
        }
        if (this.searchType == null) {
            this.searchType = SearchType.EMOTION;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public SearchType getSearchType() {
        return searchType;
    }

    public void setSearchType(SearchType searchType) {
        this.searchType = searchType;
    }

    public String getDetectedEmotion() {
        return detectedEmotion;
    }

    public void setDetectedEmotion(String detectedEmotion) {
        this.detectedEmotion = detectedEmotion;
    }

    public BigDecimal getEmotionConfidence() {
        return emotionConfidence;
    }

    public void setEmotionConfidence(BigDecimal emotionConfidence) {
        this.emotionConfidence = emotionConfidence;
    }

    public String getSelectedTargetType() {
        return selectedTargetType;
    }

    public void setSelectedTargetType(String selectedTargetType) {
        this.selectedTargetType = selectedTargetType;
    }

    public String getSelectedTargetId() {
        return selectedTargetId;
    }

    public void setSelectedTargetId(String selectedTargetId) {
        this.selectedTargetId = selectedTargetId;
    }

    public LocalDateTime getSearchedAt() {
        return searchedAt;
    }

    public void setSearchedAt(LocalDateTime searchedAt) {
        this.searchedAt = searchedAt;
    }
}
