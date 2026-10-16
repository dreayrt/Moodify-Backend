package com.laphuth.moodify.dto.searchhistory;

import com.laphuth.moodify.entities.SearchHistory;
import com.laphuth.moodify.entities.enums.SearchType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SearchHistoryResponse(
    Long id,
    String keyword,
    SearchType searchType,
    String detectedEmotion,
    BigDecimal emotionConfidence,
    String selectedTargetType,
    String selectedTargetId,
    LocalDateTime searchedAt
) {
    public static SearchHistoryResponse from(SearchHistory history) {
        return new SearchHistoryResponse(
            history.getId(),
            history.getKeyword(),
            history.getSearchType(),
            history.getDetectedEmotion(),
            history.getEmotionConfidence(),
            history.getSelectedTargetType(),
            history.getSelectedTargetId(),
            history.getSearchedAt()
        );
    }
}
