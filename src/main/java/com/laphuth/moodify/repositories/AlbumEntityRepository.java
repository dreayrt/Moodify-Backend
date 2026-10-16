package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.AlbumEntity;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.AlbumStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AlbumEntityRepository extends JpaRepository<AlbumEntity, Long> {
    List<AlbumEntity> findByUserOrderByCreatedAtDesc(User user);

    Page<AlbumEntity> findByIsPublicTrueAndStatusOrderByCreatedAtDesc(AlbumStatus status, Pageable pageable);

    @Query("SELECT a FROM AlbumEntity a WHERE a.isPublic = true AND a.status = :status AND " +
           "(LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(a.user.fullname) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY a.createdAt DESC")
    Page<AlbumEntity> searchPublicAlbums(@Param("q") String query, @Param("status") AlbumStatus status, Pageable pageable);
}
