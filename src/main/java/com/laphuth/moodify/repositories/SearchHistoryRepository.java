package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.SearchHistory;
import com.laphuth.moodify.entities.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Long> {
    Page<SearchHistory> findByUserOrderBySearchedAtDesc(User user, Pageable pageable);
    List<SearchHistory> findTop10ByUserOrderBySearchedAtDesc(User user);
}
