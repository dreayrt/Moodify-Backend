package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.searchhistory.SearchHistoryRequest;
import com.laphuth.moodify.dto.searchhistory.SearchHistoryResponse;
import com.laphuth.moodify.dto.searchhistory.SearchHistorySelectionRequest;
import com.laphuth.moodify.entities.SearchHistory;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.SearchType;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SearchHistoryService {
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final SearchHistoryRepository searchHistoryRepository;

    public SearchHistoryService(
        UserRepository userRepository,
        SearchHistoryRepository searchHistoryRepository
    ) {
        this.userRepository = userRepository;
        this.searchHistoryRepository = searchHistoryRepository;
    }

    @Transactional(readOnly = true)
    public Page<SearchHistoryResponse> getHistory(
        String principal,
        int page,
        int size,
        SearchType searchType,
        String keyword
    ) {
        User user = findUser(principal);
        Pageable pageable = PageRequest.of(
            Math.max(0, page),
            Math.clamp(size, 1, MAX_PAGE_SIZE),
            Sort.by(Sort.Order.desc("searchedAt"), Sort.Order.desc("id"))
        );
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (normalizedKeyword != null && normalizedKeyword.length() > 255) {
            normalizedKeyword = normalizedKeyword.substring(0, 255);
        }

        return searchHistoryRepository.findForUser(user, searchType, normalizedKeyword, pageable)
            .map(SearchHistoryResponse::from);
    }

    @Transactional
    public SearchHistoryResponse record(String principal, SearchHistoryRequest request) {
        User user = findUser(principal);
        String keyword = request.keyword().trim();
        if (keyword.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ khóa tìm kiếm không được để trống.");
        }

        SearchHistory history = new SearchHistory();
        history.setUser(user);
        history.setKeyword(keyword);
        history.setSearchType(request.searchType());
        if (request.searchType() == SearchType.EMOTION) {
            if (request.detectedEmotion() == null || request.detectedEmotion().isBlank()
                || request.emotionConfidence() == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tìm kiếm cảm xúc cần có nhãn và độ tin cậy từ mô hình."
                );
            }
            history.setDetectedEmotion(
                request.detectedEmotion().trim().toUpperCase()
            );
            history.setEmotionConfidence(request.emotionConfidence());
        }
        return SearchHistoryResponse.from(searchHistoryRepository.save(history));
    }

    @Transactional
    public SearchHistoryResponse selectResult(
        String principal,
        Long historyId,
        SearchHistorySelectionRequest request
    ) {
        if (request.targetType() != SearchType.TRACK
            && request.targetType() != SearchType.ARTIST
            && request.targetType() != SearchType.ALBUM
            && request.targetType() != SearchType.PLAYLIST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại kết quả được chọn không hợp lệ.");
        }

        User user = findUser(principal);
        SearchHistory history = searchHistoryRepository.findByIdAndUser(historyId, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch sử tìm kiếm."));
        history.setSelectedTargetType(request.targetType().name());
        history.setSelectedTargetId(request.targetId().trim());
        return SearchHistoryResponse.from(searchHistoryRepository.save(history));
    }

    @Transactional
    public void deleteItem(String principal, Long historyId) {
        User user = findUser(principal);
        SearchHistory history = searchHistoryRepository.findByIdAndUser(historyId, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch sử tìm kiếm."));
        searchHistoryRepository.delete(history);
    }

    @Transactional
    public long clearHistory(String principal) {
        return searchHistoryRepository.deleteByUser(findUser(principal));
    }

    private User findUser(String principal) {
        return userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng."));
    }
}
