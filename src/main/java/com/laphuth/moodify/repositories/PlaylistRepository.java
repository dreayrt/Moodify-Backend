package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.Playlist;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends MongoRepository<Playlist, String> {
    java.util.List<Playlist> findByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    List<Playlist> findByUsernameOrderByCreatedAtDesc(String username);

    List<Playlist> findByIsPublicTrueOrderByCreatedAtDesc();

    List<Playlist> findByIsPublicTrueAndNameContainingIgnoreCaseOrderByCreatedAtDesc(String name);

    Optional<Playlist> findByIdAndUsername(String id, String username);

    void deleteByIdAndUsername(String id, String username);
}
