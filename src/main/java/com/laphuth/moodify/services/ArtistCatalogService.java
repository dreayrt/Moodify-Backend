package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.artist.ArtistAlbumResponse;
import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistProfileResponse;
import com.laphuth.moodify.dto.artist.ArtistTrackResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.entities.Album;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.artistRepoository;
import com.laphuth.moodify.repositories.userRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArtistCatalogService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int ALBUM_PREVIEW_SIZE = 50;

    private final userRepository userRepository;
    private final artistRepoository artistRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;

    public ArtistCatalogService(
        userRepository userRepository,
        artistRepoository artistRepository,
        TrackRepository trackRepository,
        AlbumRepository albumRepository
    ) {
        this.userRepository = userRepository;
        this.artistRepository = artistRepository;
        this.trackRepository = trackRepository;
        this.albumRepository = albumRepository;
    }

    public ArtistCatalogResponse getCurrentArtistCatalog(
        String principal,
        int page,
        int size,
        String query
    ) {
        String artistSpotifyId = resolveLinkedArtistSpotifyId(principal);

        Artist artist = artistRepository.findBySpotifyId(artistSpotifyId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Artist profile not found in MongoDB"
            ));

        Pageable trackPageable = buildTrackPageable(page, size);
        Page<Track> tracks = resolveTracks(artistSpotifyId, query, trackPageable);

        Pageable albumPageable = PageRequest.of(
            0,
            ALBUM_PREVIEW_SIZE,
            Sort.by(Sort.Direction.DESC, "updatedAt")
        );
        Page<Album> albums = albumRepository.findByArtistSpotifyId(
            artistSpotifyId,
            albumPageable
        );

        return new ArtistCatalogResponse(
            ArtistProfileResponse.from(artist),
            tracks.map(ArtistTrackResponse::from).getContent(),
            albums.map(ArtistAlbumResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    public ArtistTracksPageResponse getCurrentArtistTracks(
        String principal,
        int page,
        int size,
        String query
    ) {
        String artistSpotifyId = resolveLinkedArtistSpotifyId(principal);
        Pageable trackPageable = buildTrackPageable(page, size);
        Page<Track> tracks = resolveTracks(artistSpotifyId, query, trackPageable);

        return new ArtistTracksPageResponse(
            tracks.map(ArtistTrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    private String resolveLinkedArtistSpotifyId(String principal) {
        User currentUser = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));

        if (currentUser.getRole() != userRole.ARTIST) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only artist accounts can access artist catalog"
            );
        }

        String artistSpotifyId = currentUser.getArtistSpotifyId();
        if (artistSpotifyId == null || artistSpotifyId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Artist account is not linked to a Spotify artist id"
            );
        }

        return artistSpotifyId.trim();
    }

    private Pageable buildTrackPageable(int page, int size) {
        return PageRequest.of(
            Math.max(page, 0),
            Math.clamp(size, 1, MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "updatedAt")
        );
    }

    private Page<Track> resolveTracks(
        String artistSpotifyId,
        String query,
        Pageable pageable
    ) {
        if (query == null || query.isBlank()) {
            return trackRepository.findByArtistSpotifyId(artistSpotifyId, pageable);
        }
        return trackRepository.findByArtistSpotifyIdAndNameContainingIgnoreCase(
            artistSpotifyId,
            query.trim(),
            pageable
        );
    }
}
