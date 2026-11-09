package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.Track;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TrackRepository extends MongoRepository<Track, String> {
    java.util.List<Track> findByModerationStatus(String moderationStatus);
    java.util.List<Track> findByModerationStatusIn(java.util.List<String> moderationStatuses);
    java.util.List<Track> findByModerationStatusIgnoreCase(String moderationStatus);
    Page<Track> findByModerationStatus(String moderationStatus, Pageable pageable);
    Page<Track> findByModerationStatusIn(java.util.List<String> moderationStatuses, Pageable pageable);
    Page<Track> findByArtistSpotifyId(String artistSpotifyId, Pageable pageable);
    List<Track> findByArtistSpotifyId(String artistSpotifyId);

    @Query("{ 'status': { '$regex': '^published$', '$options': 'i' }, 'visibility': { '$regex': '^public$', '$options': 'i' }, 'local_path': { '$type': 'string', '$regex': '[A-Za-z0-9]' }, 'moderation_status': { '$not': { '$regex': '^rejected$', '$options': 'i' } } }")
    Page<Track> findCatalogPublic(Pageable pageable);

    @Query("{ '$or': [ { 'name': { '$regex': ?0, '$options': 'i' } }, { 'artist_name': { '$regex': ?0, '$options': 'i' } }, { 'album_name': { '$regex': ?0, '$options': 'i' } } ], 'status': { '$regex': '^published$', '$options': 'i' }, 'visibility': { '$regex': '^public$', '$options': 'i' }, 'local_path': { '$type': 'string', '$regex': '[A-Za-z0-9]' }, 'moderation_status': { '$not': { '$regex': '^rejected$', '$options': 'i' } } }")
    Page<Track> searchCatalogPublic(String literalRegex, Pageable pageable);

    @Query("{ 'artist_spotify_id': ?0, 'status': { '$regex': '^published$', '$options': 'i' }, 'visibility': { '$regex': '^public$', '$options': 'i' }, 'local_path': { '$type': 'string', '$regex': '[A-Za-z0-9]' }, 'moderation_status': { '$not': { '$regex': '^rejected$', '$options': 'i' } } }")
    Page<Track> findPublicByArtistSpotifyId(String artistSpotifyId, Pageable pageable);

    @Query("{ 'genres': ?0, 'status': { '$regex': '^published$', '$options': 'i' }, 'visibility': { '$regex': '^public$', '$options': 'i' }, 'local_path': { '$type': 'string', '$regex': '[A-Za-z0-9]' }, 'moderation_status': { '$not': { '$regex': '^rejected$', '$options': 'i' } } }")
    Page<Track> findByGenresContaining(String genre, Pageable pageable);

    @Query("{ 'genres': { '$in': ?0 }, 'status': { '$regex': '^published$', '$options': 'i' }, 'visibility': { '$regex': '^public$', '$options': 'i' }, 'local_path': { '$type': 'string', '$regex': '[A-Za-z0-9]' }, 'moderation_status': { '$not': { '$regex': '^rejected$', '$options': 'i' } } }")
    Page<Track> findByGenresIn(List<String> genres, Pageable pageable);
    
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

    List<Track> findByNameContainingIgnoreCase(String name);

    List<Track> findByArtistName(String artistName);

    List<Track> findByArtistNameContainingIgnoreCase(String artistName);
}
