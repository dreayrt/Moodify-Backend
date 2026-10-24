package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.SearchHistory;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.SearchType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Long> {
    Page<SearchHistory> findByUserOrderBySearchedAtDesc(User user, Pageable pageable);
    List<SearchHistory> findTop10ByUserOrderBySearchedAtDesc(User user);

    @Query("""
        select history from SearchHistory history
        where history.user = :user
          and (:searchType is null or history.searchType = :searchType)
          and (:keyword is null or lower(history.keyword) like lower(concat('%', :keyword, '%')))
        """)
    Page<SearchHistory> findForUser(
        @Param("user") User user,
        @Param("searchType") SearchType searchType,
        @Param("keyword") String keyword,
        Pageable pageable
    );

    Optional<SearchHistory> findByIdAndUser(Long id, User user);

    long deleteByUser(User user);
}
