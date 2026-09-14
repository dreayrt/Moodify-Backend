package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.Artist;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface artistRepoository extends MongoRepository<Artist, String> {
    Optional<Artist> findBySpotifyId(String spotifyId);
}
