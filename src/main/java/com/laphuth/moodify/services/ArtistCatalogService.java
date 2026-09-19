package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.artist.ArtistAlbumResponse;
import com.laphuth.moodify.dto.artist.ArtistCatalogResponse;
import com.laphuth.moodify.dto.artist.ArtistProfileResponse;
import com.laphuth.moodify.dto.artist.ArtistTrackResponse;
import com.laphuth.moodify.dto.artist.ArtistTracksPageResponse;
import com.laphuth.moodify.dto.artist.TrackUpdateRequest;
import com.laphuth.moodify.entities.Album;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.artistRepoository;
import com.laphuth.moodify.repositories.userRepository;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.entities.ContentReviewRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArtistCatalogService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int ALBUM_PREVIEW_SIZE = 50;

    private final userRepository userRepository;
    private final artistRepoository artistRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final SongLicenseRepository songLicenseRepository;
    private final ContentReviewRequestRepository contentReviewRequestRepository;
    private final ContentReviewActionRepository contentReviewActionRepository;

    public ArtistCatalogService(
        userRepository userRepository,
        artistRepoository artistRepository,
        TrackRepository trackRepository,
        AlbumRepository albumRepository,
        SongLicenseRepository songLicenseRepository,
        ContentReviewRequestRepository contentReviewRequestRepository,
        ContentReviewActionRepository contentReviewActionRepository
    ) {
        this.songLicenseRepository = songLicenseRepository;
        this.contentReviewRequestRepository = contentReviewRequestRepository;
        this.contentReviewActionRepository = contentReviewActionRepository;
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
        String artistSpotifyId = resolveCurrentArtistUser(principal).getArtistSpotifyId().trim();

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
        String artistSpotifyId = resolveCurrentArtistUser(principal).getArtistSpotifyId().trim();
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

    private User resolveCurrentArtistUser(String principal) {
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

        return currentUser;
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

    public ArtistTrackResponse uploadTrack(
        String principal,
        com.laphuth.moodify.dto.artist.TrackUploadRequest request,
        org.springframework.web.multipart.MultipartFile audioFile,
        org.springframework.web.multipart.MultipartFile coverFile,
        org.springframework.web.multipart.MultipartFile licenseDocFile
    ) {
        User currentUser = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (currentUser.getRole() != userRole.ARTIST) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only artist accounts can upload tracks");
        }

        String artistSpotifyId = currentUser.getArtistSpotifyId();
        Artist artist = artistRepository.findBySpotifyId(artistSpotifyId != null ? artistSpotifyId : "")
            .orElse(null);
        String mainArtistName = artist != null ? artist.getName() : currentUser.getFullname();

        // 1. Save audio file
        String audioUrl = saveUploadedFile(audioFile, "uploads/audio");
        String coverUrl = coverFile != null && !coverFile.isEmpty() ? saveUploadedFile(coverFile, "uploads/covers") : null;
        String licenseDocUrl = licenseDocFile != null && !licenseDocFile.isEmpty() ? saveUploadedFile(licenseDocFile, "uploads/licenses") : null;

        // 2. Save Track to MongoDB
        String feat = request.getFeaturedArtists() != null ? request.getFeaturedArtists().trim() : "";
        String fullArtist = feat.isEmpty() ? mainArtistName : mainArtistName + " feat. " + feat;

        Track track = new Track();
        track.setName(request.getTitle() != null && !request.getTitle().isBlank() ? request.getTitle().trim() : "Bản phối mới");
        track.setArtistName(fullArtist);
        track.setArtistSpotifyId(artistSpotifyId);
        track.setAlbumName(request.getAlbumName());
        track.setFeaturedArtists(emptyToNull(request.getFeaturedArtists()));
        track.setDescription(emptyToNull(request.getDescription()));
        track.setStatus(normalizeTrackStatus(request.getStatus()));
        track.setVisibility(normalizeTrackVisibility(request.getVisibility()));
        track.setGenres(java.util.List.of(request.getGenre() != null && !request.getGenre().isBlank() ? request.getGenre().trim() : "Pop"));
        track.setImageUrl(coverUrl);
        track.setLocalPath(audioUrl);
        track.setExplicit(request.isExplicit());
        track.setLyricsPlain(request.getLyricsPlain());
        track.setDurationFormatted("0:00");
        track.setDurationMs(0);
        track.setDownloadStatus("completed");
        track.setModerationStatus("pending"); // PENDING for review
        track.setCreatedAt(Instant.now());
        track.setUpdatedAt(Instant.now());

        Track savedTrack = trackRepository.save(track);

        // 3. Save SongLicense to MySQL
        try {
            com.laphuth.moodify.entities.SongLicense license = new com.laphuth.moodify.entities.SongLicense();
            license.setTrackId(savedTrack.getId());
            license.setLicenseType(request.getLicenseType() != null ? request.getLicenseType() : "DIGITAL_STREAMING");

            // CHECK constraint: ((distributor_id IS NOT NULL AND copyright_owner IS NULL) OR (distributor_id IS NULL AND copyright_owner IS NOT NULL))
            if (request.getDistributorId() != null && request.getDistributorId() > 0) {
                license.setDistributorId(request.getDistributorId());
                license.setDistributionContractId(request.getDistributionContractId());
                license.setCopyrightOwner(null);
            } else {
                license.setDistributorId(null);
                license.setDistributionContractId(null);
                String owner = (request.getCopyrightOwner() != null && !request.getCopyrightOwner().isBlank()) 
                    ? request.getCopyrightOwner().trim() 
                    : mainArtistName;
                license.setCopyrightOwner(owner);
            }

            if (request.getIssueDate() != null && !request.getIssueDate().isBlank()) {
                try { license.setIssueDate(java.time.LocalDate.parse(request.getIssueDate())); } catch (Exception ignored) { license.setIssueDate(java.time.LocalDate.now()); }
            } else {
                license.setIssueDate(java.time.LocalDate.now());
            }

            if (!request.isPerpetual() && request.getExpiryDate() != null && !request.getExpiryDate().isBlank()) {
                try { license.setExpiryDate(java.time.LocalDate.parse(request.getExpiryDate())); } catch (Exception ignored) {}
            } else {
                license.setExpiryDate(null);
            }

            license.setStatus(com.laphuth.moodify.entities.enums.LicenseStatus.PENDING);
            license.setDocumentSonglicensesUrl(licenseDocUrl);

            songLicenseRepository.save(license);
        } catch (Exception e) {
            // Rollback MongoDB track if MySQL fails
            trackRepository.deleteById(savedTrack.getId());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save song license: " + e.getMessage());
        }

        // 4. Create ContentReviewRequest in MySQL (content_review_requests table)
        try {
            ContentReviewRequest reviewRequest = new ContentReviewRequest();
            reviewRequest.setArtistUserId(currentUser.getId());
            reviewRequest.setContentType("TRACK");
            reviewRequest.setContentId(savedTrack.getId());
            reviewRequest.setRequestType("PUBLISH");
            reviewRequest.setStatus("PENDING");
            reviewRequest.setSubmittedAt(LocalDateTime.now());
            contentReviewRequestRepository.save(reviewRequest);
        } catch (Exception e) {
            // Log but don't rollback - the track and license are already saved
            System.err.println("Warning: Failed to create content review request: " + e.getMessage());
        }

        return ArtistTrackResponse.from(savedTrack);
    }

    public ArtistTrackResponse updateTrack(
        String principal,
        String trackId,
        TrackUpdateRequest request
    ) {
        User currentUser = resolveCurrentArtistUser(principal);
        String artistSpotifyId = currentUser.getArtistSpotifyId().trim();
        Track track = resolveOwnedTrack(trackId, artistSpotifyId);

        String title = request.getTitle() != null ? request.getTitle().trim() : "";
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Track title is required");
        }

        Artist artist = artistRepository.findBySpotifyId(artistSpotifyId).orElse(null);
        String mainArtistName = artist != null ? artist.getName() : currentUser.getFullname();
        String featuredArtists = emptyToNull(request.getFeaturedArtists());
        String artistName = featuredArtists == null ? mainArtistName : mainArtistName + " feat. " + featuredArtists;

        track.setName(title);
        track.setArtistName(artistName);
        track.setFeaturedArtists(featuredArtists);
        track.setAlbumName(emptyToNull(request.getAlbumName()));
        track.setDescription(emptyToNull(request.getDescription()));
        track.setGenres(List.of(emptyToDefault(request.getGenre(), "Pop")));
        if (request.getExplicit() != null) {
            track.setExplicit(request.getExplicit());
        }
        if (request.getLyricsPlain() != null) {
            track.setLyricsPlain(emptyToNull(request.getLyricsPlain()));
        }
        track.setStatus(normalizeTrackStatus(request.getStatus()));
        track.setVisibility(normalizeTrackVisibility(request.getVisibility()));
        track.setUpdatedAt(Instant.now());

        Track savedTrack = trackRepository.save(track);

        contentReviewRequestRepository
            .findByContentIdAndContentTypeAndStatus(trackId, "TRACK", "PENDING")
            .ifPresentOrElse(reviewRequest -> {
                reviewRequest.setRequestType("UPDATE");
                reviewRequest.setSubmittedAt(LocalDateTime.now());
                contentReviewRequestRepository.save(reviewRequest);
            }, () -> {
                ContentReviewRequest reviewRequest = new ContentReviewRequest();
                reviewRequest.setArtistUserId(currentUser.getId());
                reviewRequest.setContentType("TRACK");
                reviewRequest.setContentId(trackId);
                reviewRequest.setRequestType("UPDATE");
                reviewRequest.setStatus("PENDING");
                reviewRequest.setSubmittedAt(LocalDateTime.now());
                contentReviewRequestRepository.save(reviewRequest);
            });

        return ArtistTrackResponse.from(savedTrack);
    }

    @Transactional
    public void deleteTrack(String principal, String trackId) {
        String artistSpotifyId = resolveCurrentArtistUser(principal).getArtistSpotifyId().trim();
        Track track = resolveOwnedTrack(trackId, artistSpotifyId);

        List<ContentReviewRequest> reviewRequests =
            contentReviewRequestRepository.findByContentIdAndContentType(track.getId(), "TRACK");
        List<Long> reviewRequestIds = reviewRequests.stream()
            .map(ContentReviewRequest::getId)
            .filter(id -> id != null)
            .toList();

        if (!reviewRequestIds.isEmpty()) {
            contentReviewActionRepository.deleteByReviewRequestIdIn(reviewRequestIds);
        }
        contentReviewRequestRepository.deleteAll(reviewRequests);
        songLicenseRepository.deleteByTrackId(track.getId());
        trackRepository.delete(track);
    }

    private Track resolveOwnedTrack(String trackId, String artistSpotifyId) {
        Track track = trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found"));

        if (track.getArtistSpotifyId() == null || !track.getArtistSpotifyId().trim().equals(artistSpotifyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Track does not belong to the current artist");
        }

        return track;
    }

    private String normalizeTrackStatus(String status) {
        String normalized = emptyToDefault(status, "draft").toLowerCase();
        if (!List.of("draft", "published", "scheduled").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid track status");
        }
        return normalized;
    }

    private String normalizeTrackVisibility(String visibility) {
        String normalized = emptyToDefault(visibility, "private").toLowerCase();
        if (!List.of("public", "private", "unlisted").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid track visibility");
        }
        return normalized;
    }

    private String emptyToDefault(String value, String fallback) {
        String normalized = emptyToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String saveUploadedFile(org.springframework.web.multipart.MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) return null;
        try {
            java.nio.file.Path uploadDir = java.nio.file.Paths.get(folder);
            if (!java.nio.file.Files.exists(uploadDir)) {
                java.nio.file.Files.createDirectories(uploadDir);
            }
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String fileName = java.util.UUID.randomUUID() + extension;
            java.nio.file.Path filePath = uploadDir.resolve(fileName);
            java.nio.file.Files.copy(file.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "/" + folder + "/" + fileName;
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot save file: " + e.getMessage());
        }
    }


    public List<Artist> searchArtists(String query) {
        return artistRepository.findByNameContainingIgnoreCase(query);
    }
}




