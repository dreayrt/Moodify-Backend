package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.ContentReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContentReviewActionRepository extends JpaRepository<ContentReviewAction, Long> {
    List<ContentReviewAction> findByReviewRequestId(Long reviewRequestId);
    void deleteByReviewRequestIdIn(List<Long> reviewRequestIds);
}
