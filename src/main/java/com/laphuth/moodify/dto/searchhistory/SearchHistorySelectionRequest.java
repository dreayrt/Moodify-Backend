package com.laphuth.moodify.dto.searchhistory;

import com.laphuth.moodify.entities.enums.SearchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SearchHistorySelectionRequest(
    @NotNull SearchType targetType,
    @NotBlank @Size(max = 64) String targetId
) {
}
