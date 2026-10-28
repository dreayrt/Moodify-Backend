package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.FavoriteArtistEntity;
import com.laphuth.moodify.entities.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FavoriteArtistRepository extends JpaRepository<FavoriteArtistEntity, Long> {

    boolean existsByUserAndArtistId(User user, String artistId);

    Optional<FavoriteArtistEntity> findByUserAndArtistId(User user, String artistId);

    void deleteByUserAndArtistId(User user, String artistId);

    long countByArtistId(String artistId);

    Page<FavoriteArtistEntity> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    java.util.List<FavoriteArtistEntity> findByUserOrderByCreatedAtDesc(User user);
}
