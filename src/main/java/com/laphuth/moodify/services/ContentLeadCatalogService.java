package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.contentlead.ContentLeadAlbumResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadCatalogResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadProfileResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadTrackResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadTracksPageResponse;
import com.laphuth.moodify.dto.contentlead.TrackUpdateRequest;
import com.laphuth.moodify.dto.contentlead.TrackUploadRequest;
import com.laphuth.moodify.entities.Album;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.ContentReviewRequest;
import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.ContentReviewActionRepository;
import com.laphuth.moodify.repositories.ContentReviewRequestRepository;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
public class ContentLeadCatalogService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int ALBUM_PREVIEW_SIZE = 50;

    private final UserRepository userRepository;
    private final ArtistRepository artistRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final SongLicenseRepository songLicenseRepository;
    private final ContentReviewRequestRepository contentReviewRequestRepository;
    private final ContentReviewActionRepository contentReviewActionRepository;

    public ContentLeadCatalogService(
        UserRepository userRepository,
        ArtistRepository artistRepository,
        TrackRepository trackRepository,
        AlbumRepository albumRepository,
        SongLicenseRepository songLicenseRepository,
        ContentReviewRequestRepository contentReviewRequestRepository,
        ContentReviewActionRepository contentReviewActionRepository
    ) {
        this.userRepository = userRepository;
        this.artistRepository = artistRepository;
        this.trackRepository = trackRepository;
        this.albumRepository = albumRepository;
        this.songLicenseRepository = songLicenseRepository;
        this.contentReviewRequestRepository = contentReviewRequestRepository;
        this.contentReviewActionRepository = contentReviewActionRepository;
    }

    public ContentLeadCatalogResponse getCurrentCatalog(
        String principal,
        int page,
        int size,
        String query
    ) {
        String artistSpotifyId = resolveCurrentContentLeadUser(principal).getArtistSpotifyId().trim();

        Artist artist = artistRepository.findBySpotifyId(artistSpotifyId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Content catalog profile not found in MongoDB"
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

        return new ContentLeadCatalogResponse(
            ContentLeadProfileResponse.from(artist),
            tracks.map(ContentLeadTrackResponse::from).getContent(),
            albums.map(ContentLeadAlbumResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    public ContentLeadTracksPageResponse getCurrentTracks(
        String principal,
        int page,
        int size,
        String query
    ) {
        String artistSpotifyId = resolveCurrentContentLeadUser(principal).getArtistSpotifyId().trim();
        Pageable trackPageable = buildTrackPageable(page, size);
        Page<Track> tracks = resolveTracks(artistSpotifyId, query, trackPageable);

        return new ContentLeadTracksPageResponse(
            tracks.map(ContentLeadTrackResponse::from).getContent(),
            tracks.getNumber(),
            tracks.getSize(),
            tracks.getTotalElements(),
            tracks.getTotalPages()
        );
    }

    private User resolveCurrentContentLeadUser(String principal) {
        User currentUser = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));

        if (currentUser.getRole() != UserRole.CONTENT_LEAD) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only Content Lead accounts can access content catalog"
            );
        }

        String artistSpotifyId = currentUser.getArtistSpotifyId();
        if (artistSpotifyId == null || artistSpotifyId.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Content Lead account is not linked to a catalog id"
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

    public ContentLeadTrackResponse uploadTrack(
        String principal,
        TrackUploadRequest request,
        MultipartFile audioFile,
        MultipartFile coverFile,
        MultipartFile licenseDocFile
    ) {
        User currentUser = userRepository.findByEmailOrUsername(principal, principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (currentUser.getRole() != UserRole.CONTENT_LEAD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Content Lead accounts can upload tracks");
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
        track.setFeaturedArtists(feat.isEmpty() ? null : feat);
        track.setArtistSpotifyId(artistSpotifyId);
        track.setAlbumName(request.getAlbumName() != null && !request.getAlbumName().isBlank() ? request.getAlbumName().trim() : null);
        track.setGenres(List.of(request.getGenre() != null && !request.getGenre().isBlank() ? request.getGenre().trim() : "Pop"));
        track.setExplicit(request.isExplicit());
        track.setLyricsPlain(request.getLyricsPlain() != null && !request.getLyricsPlain().isBlank() ? request.getLyricsPlain().trim() : null);
        track.setDescription(request.getDescription() != null && !request.getDescription().isBlank() ? request.getDescription().trim() : null);
        track.setImageUrl(coverUrl);
        track.setLocalPath(audioUrl);
        track.setStatus(normalizeTrackStatus(request.getStatus()));
        track.setVisibility(normalizeTrackVisibility(request.getVisibility()));
        track.setCreatedAt(Instant.now());
        track.setUpdatedAt(Instant.now());
        track.setModerationStatus("pending");

        Track savedTrack = trackRepository.save(track);

        // 3. Save SongLicense
        SongLicense license = new SongLicense();
        license.setTrackId(savedTrack.getId());
        license.setLicenseType(request.getLicenseType() != null ? request.getLicenseType() : "DIGITAL_STREAMING");
        // Satisfy MySQL chk_song_license_owner:
        // (distributor_id IS NOT NULL AND copyright_owner IS NULL) OR (distributor_id IS NULL AND copyright_owner IS NOT NULL)
        if (request.getDistributorId() != null && request.getDistributorId() > 0) {
            license.setDistributorId(request.getDistributorId());
            Long contractId = request.getDistributionContractId();
            license.setDistributionContractId(contractId != null && contractId > 0 ? contractId : null);
            license.setCopyrightOwner(null);
        } else {
            license.setDistributorId(null);
            license.setDistributionContractId(null);
            String owner = request.getCopyrightOwner() != null && !request.getCopyrightOwner().isBlank()
                ? request.getCopyrightOwner().trim()
                : mainArtistName;
            license.setCopyrightOwner(owner);
        }
        license.setDocumentSonglicensesUrl(licenseDocUrl);
        license.setStatus(com.laphuth.moodify.entities.enums.LicenseStatus.ACTIVE);

        if (request.getIssueDate() != null && !request.getIssueDate().isBlank()) {
            try {
                license.setIssueDate(LocalDate.parse(request.getIssueDate().trim()));
            } catch (DateTimeParseException ignored) {}
        }
        if (request.getExpiryDate() != null && !request.getExpiryDate().isBlank() && !request.isPerpetual()) {
            try {
                license.setExpiryDate(LocalDate.parse(request.getExpiryDate().trim()));
            } catch (DateTimeParseException ignored) {}
        }
        songLicenseRepository.save(license);

        // 4. Save ContentReviewRequest
        ContentReviewRequest reviewRequest = new ContentReviewRequest();
        reviewRequest.setArtistUserId(currentUser.getId());
        reviewRequest.setContentType("TRACK");
        reviewRequest.setContentId(savedTrack.getId());
        reviewRequest.setRequestType("PUBLISH");
        reviewRequest.setStatus("PENDING");
        reviewRequest.setSubmittedAt(LocalDateTime.now());
        contentReviewRequestRepository.save(reviewRequest);

        return ContentLeadTrackResponse.from(savedTrack);
    }

    private String saveUploadedFile(MultipartFile file, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        try {
            Path targetDir = Paths.get(subDir).toAbsolutePath().normalize();
            Files.createDirectories(targetDir);

            String origName = file.getOriginalFilename();
            String ext = "";
            if (origName != null && origName.contains(".")) {
                ext = origName.substring(origName.lastIndexOf("."));
            }
            String uniqueName = UUID.randomUUID().toString() + ext;
            Path targetPath = targetDir.resolve(uniqueName);

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return "/" + subDir.replace("\\", "/") + "/" + uniqueName;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file: " + e.getMessage());
        }
    }

    @Transactional
    public ContentLeadTrackResponse updateTrack(
        String principal,
        String trackId,
        TrackUpdateRequest request
    ) {
        User currentUser = resolveCurrentContentLeadUser(principal);
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
        track.setModerationStatus("pending");
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

        return ContentLeadTrackResponse.from(savedTrack);
    }

    @Transactional
    public void deleteTrack(String principal, String trackId) {
        String artistSpotifyId = resolveCurrentContentLeadUser(principal).getArtistSpotifyId().trim();
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Track does not belong to the current catalog");
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

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String emptyToDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
