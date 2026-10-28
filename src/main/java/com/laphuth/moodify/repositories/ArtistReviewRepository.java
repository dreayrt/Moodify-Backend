package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.ArtistReviewEntity;
import com.laphuth.moodify.entities.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArtistReviewRepository extends JpaRepository<ArtistReviewEntity, Long> {

    Optional<ArtistReviewEntity> findByUserAndArtistId(User user, String artistId);

    Page<ArtistReviewEntity> findByArtistIdAndStatusOrderByCreatedAtDesc(String artistId, String status, Pageable pageable);

    long countByArtistIdAndStatus(String artistId, String status);

    @Query("SELECT AVG(r.rating) FROM ArtistReviewEntity r WHERE r.artistId = :artistId AND r.status = 'VISIBLE'")
    Double calculateAverageRating(@Param("artistId") String artistId);
}
