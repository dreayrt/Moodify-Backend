package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.Track;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TrackRepository extends MongoRepository<Track, String> {
    java.util.List<Track> findByModerationStatus(String moderationStatus);
    Page<Track> findByModerationStatus(String moderationStatus, Pageable pageable);
    Page<Track> findByArtistSpotifyId(String artistSpotifyId, Pageable pageable);

    Page<Track> findByGenresContaining(String genre, Pageable pageable);
    
    Optional<Track> findBySpotifyId(String spotifyId);
    
    List<Track> findBySpotifyIdIn(List<String> spotifyIds);

    Page<Track> findByArtistSpotifyIdAndNameContainingIgnoreCase(
        String artistSpotifyId,
        String name,
        Pageable pageable
    );

    Page<Track> findByNameContainingIgnoreCaseOrArtistNameContainingIgnoreCaseOrAlbumNameContainingIgnoreCase(
        String name,
        String artistName,
        String albumName,
        Pageable pageable
    );
}
