package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.searchhistory.SearchHistoryRequest;
import com.laphuth.moodify.dto.searchhistory.SearchHistoryResponse;
import com.laphuth.moodify.dto.searchhistory.SearchHistorySelectionRequest;
import com.laphuth.moodify.entities.enums.SearchType;
import com.laphuth.moodify.services.SearchHistoryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/me/search-history")
public class SearchHistoryApi {
    private final SearchHistoryService searchHistoryService;

    public SearchHistoryApi(SearchHistoryService searchHistoryService) {
        this.searchHistoryService = searchHistoryService;
    }

    @GetMapping
    public ResponseEntity<Page<SearchHistoryResponse>> getHistory(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) SearchType type,
        @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(searchHistoryService.getHistory(
            authentication.getName(), page, size, type, q
        ));
    }

    @PostMapping
    public ResponseEntity<SearchHistoryResponse> recordSearch(
        Authentication authentication,
        @Valid @RequestBody SearchHistoryRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(searchHistoryService.record(authentication.getName(), request));
    }

    @PatchMapping("/{historyId}/selection")
    public ResponseEntity<SearchHistoryResponse> selectResult(
        Authentication authentication,
        @PathVariable Long historyId,
        @Valid @RequestBody SearchHistorySelectionRequest request
    ) {
        return ResponseEntity.ok(searchHistoryService.selectResult(authentication.getName(), historyId, request));
    }

    @DeleteMapping("/{historyId}")
    public ResponseEntity<Map<String, Object>> deleteItem(
        Authentication authentication,
        @PathVariable Long historyId
    ) {
        searchHistoryService.deleteItem(authentication.getName(), historyId);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Đã xóa mục lịch sử tìm kiếm."
        ));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearHistory(Authentication authentication) {
        long deletedCount = searchHistoryService.clearHistory(authentication.getName());
        return ResponseEntity.ok(Map.of(
            "success", true,
            "deletedCount", deletedCount,
            "message", "Đã xóa toàn bộ lịch sử tìm kiếm."
        ));
    }
}
