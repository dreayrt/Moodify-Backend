package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.ArtistProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArtistProfileRepository extends JpaRepository<ArtistProfileEntity, String> {
    Optional<ArtistProfileEntity> findByUserId(Long userId);
}
