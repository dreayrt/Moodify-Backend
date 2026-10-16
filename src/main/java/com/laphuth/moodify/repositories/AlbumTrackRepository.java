package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.AlbumEntity;
import com.laphuth.moodify.entities.AlbumTrackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlbumTrackRepository extends JpaRepository<AlbumTrackEntity, Long> {
    List<AlbumTrackEntity> findByAlbumOrderByTrackOrderAscAddedAtAsc(AlbumEntity album);

    Optional<AlbumTrackEntity> findByAlbumAndTrackId(AlbumEntity album, String trackId);

    boolean existsByAlbumAndTrackId(AlbumEntity album, String trackId);

    void deleteByAlbumAndTrackId(AlbumEntity album, String trackId);

    long countByAlbum(AlbumEntity album);
}
