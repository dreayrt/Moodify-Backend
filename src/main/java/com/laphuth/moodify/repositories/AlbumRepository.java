package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.Album;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AlbumRepository extends MongoRepository<Album, String> {
    Page<Album> findByArtistSpotifyId(String artistSpotifyId, Pageable pageable);
}
