package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.ContentReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentReviewRequestRepository extends JpaRepository<ContentReviewRequest, Long> {
    List<ContentReviewRequest> findByContentIdAndContentType(String contentId, String contentType);
    List<ContentReviewRequest> findByArtistUserIdAndStatus(Long artistUserId, String status);
    List<ContentReviewRequest> findByStatus(String status);
    Optional<ContentReviewRequest> findByContentIdAndContentTypeAndStatus(String contentId, String contentType, String status);
}
