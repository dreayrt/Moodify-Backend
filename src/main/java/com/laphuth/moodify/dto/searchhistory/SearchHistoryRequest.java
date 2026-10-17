package com.laphuth.moodify.dto.searchhistory;

import com.laphuth.moodify.entities.enums.SearchType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SearchHistoryRequest(
    @NotBlank @Size(max = 255) String keyword,
    @NotNull SearchType searchType,
    @Size(max = 50) String detectedEmotion,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal emotionConfidence
) {
}
