package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.FavoriteAlbumEntity;
import com.laphuth.moodify.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteAlbumRepository extends JpaRepository<FavoriteAlbumEntity, Long> {
    boolean existsByUserAndAlbumId(User user, String albumId);

    Optional<FavoriteAlbumEntity> findByUserAndAlbumId(User user, String albumId);

    void deleteByUserAndAlbumId(User user, String albumId);

    @Query("SELECT f.albumId FROM FavoriteAlbumEntity f WHERE f.user = :user ORDER BY f.createdAt DESC")
    List<String> findAlbumIdsByUser(@Param("user") User user);
}
