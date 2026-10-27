package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.album.AlbumResponse;
import com.laphuth.moodify.dto.artist.*;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.AlbumStatus;
import com.laphuth.moodify.repositories.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ArtistService {

    private final ArtistRepository artistRepository;
    private final ArtistProfileRepository artistProfileRepository;
    private final ArtistReviewRepository artistReviewRepository;
    private final FavoriteArtistRepository favoriteArtistRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository mongoAlbumRepository;
    private final AlbumEntityRepository mysqlAlbumRepository;
    private final AlbumTrackRepository albumTrackRepository;

    public ArtistService(
        ArtistRepository artistRepository,
        ArtistProfileRepository artistProfileRepository,
        ArtistReviewRepository artistReviewRepository,
        FavoriteArtistRepository favoriteArtistRepository,
        UserRepository userRepository,
        TrackRepository trackRepository,
        AlbumRepository mongoAlbumRepository,
        AlbumEntityRepository mysqlAlbumRepository,
        AlbumTrackRepository albumTrackRepository
    ) {
        this.artistRepository = artistRepository;
        this.artistProfileRepository = artistProfileRepository;
        this.artistReviewRepository = artistReviewRepository;
        this.favoriteArtistRepository = favoriteArtistRepository;
        this.userRepository = userRepository;
        this.trackRepository = trackRepository;
        this.mongoAlbumRepository = mongoAlbumRepository;
        this.mysqlAlbumRepository = mysqlAlbumRepository;
        this.albumTrackRepository = albumTrackRepository;
    }

    public List<Artist> searchArtists(String query) {
        if (query == null || query.isBlank()) {
            return artistRepository.findAll(PageRequest.of(0, 50)).getContent();
        }
        String cleanQuery = query.trim();
        try {
            cleanQuery = java.net.URLDecoder.decode(cleanQuery, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return artistRepository.findByNameContainingIgnoreCase(cleanQuery);
    }

    @Transactional(readOnly = true)
    public ArtistPublicProfileResponse getPublicProfile(String artistId, String principal) {
        String decodedId = artistId;
        try {
            decodedId = java.net.URLDecoder.decode(artistId, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        final String searchKey = decodedId;

        // 1. Resolve MongoDB artist or User
        Artist mongoArtist = artistRepository.findBySpotifyId(artistId)
                .or(() -> artistRepository.findById(artistId))
                .or(() -> artistRepository.findBySpotifyId(searchKey))
                .or(() -> {
                    List<Artist> byName = artistRepository.findByNameContainingIgnoreCase(searchKey);
                    return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
                })
                .orElse(null);

        User linkedUser = userRepository.findByArtistSpotifyId(artistId)
                .or(() -> userRepository.findByArtistSpotifyId(searchKey))
                .orElse(null);

        // Fallback: check profile in MySQL
        ArtistProfileEntity profile = artistProfileRepository.findById(artistId)
                .or(() -> artistProfileRepository.findById(searchKey))
                .or(() -> linkedUser != null ? artistProfileRepository.findByUserId(linkedUser.getId()) : Optional.empty())
                .orElse(null);

        String name = mongoArtist != null ? mongoArtist.getName() : null;
        if (name == null && linkedUser != null) {
            name = linkedUser.getFullname();
        }
        if (name == null && !searchKey.isBlank()) {
            name = searchKey;
        }
        if (name == null) {
            name = "Nghệ sĩ Moodify";
        }

        String imageUrl = mongoArtist != null ? AudioUrlResolver.resolveImageUrl(mongoArtist.getImageUrl()) : null;
        if (imageUrl == null && linkedUser != null) {
            imageUrl = AudioUrlResolver.resolveImageUrl(linkedUser.getAvatarUrl());
        }
        if (imageUrl == null) {
            imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500";
        }

        String bannerUrl = profile != null && profile.getBannerUrl() != null && !profile.getBannerUrl().isBlank()
                ? profile.getBannerUrl()
                : "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=1200";

        String bio = profile != null ? profile.getBio() : null;
        if (bio == null || bio.isBlank()) {
            bio = name + " là nghệ sĩ tài năng được yêu thích trên nền tảng Moodify Music.";
        }

        List<String> genres = mongoArtist != null && mongoArtist.getGenres() != null ? mongoArtist.getGenres() : List.of("V-Pop", "Acoustic");
        boolean verified = profile == null || Boolean.TRUE.equals(profile.getVerified());
        String fb = profile != null ? profile.getFacebookUrl() : null;
        String ins = profile != null ? profile.getInstagramUrl() : null;
        String yt = profile != null ? profile.getYoutubeUrl() : null;

        // 2. Followers & Following status
        String canonicalId = (mongoArtist != null && mongoArtist.getSpotifyId() != null && !mongoArtist.getSpotifyId().isBlank())
                ? mongoArtist.getSpotifyId()
                : artistId;
        long actualFollowers = favoriteArtistRepository.countByArtistId(canonicalId);
        if (!canonicalId.equals(artistId)) {
            actualFollowers = Math.max(actualFollowers, favoriteArtistRepository.countByArtistId(artistId));
        }
        long followersCount = mongoArtist != null && mongoArtist.getFollowers() != null
                ? Math.max(mongoArtist.getFollowers(), actualFollowers)
                : actualFollowers;

        boolean isFollowed = false;
        if (principal != null && !principal.isBlank()) {
            User currentUser = userRepository.findByUsername(principal)
                    .or(() -> userRepository.findByEmail(principal))
                    .orElse(null);
            if (currentUser != null) {
                isFollowed = favoriteArtistRepository.existsByUserAndArtistId(currentUser, canonicalId)
                        || (!canonicalId.equals(artistId) && favoriteArtistRepository.existsByUserAndArtistId(currentUser, artistId));
            }
        }

        // 3. Reviews & Ratings
        Double avg = artistReviewRepository.calculateAverageRating(artistId);
        double averageRating = avg != null ? Math.round(avg * 10.0) / 10.0 : 5.0;
        long reviewsCount = artistReviewRepository.countByArtistIdAndStatus(artistId, "VISIBLE");

        // 4. Tracks: Top Tracks & Appears On
        List<Track> artistTracks = new ArrayList<>();
        if (mongoArtist != null && mongoArtist.getSpotifyId() != null) {
            artistTracks.addAll(trackRepository.findByArtistSpotifyId(mongoArtist.getSpotifyId()));
        }
        if (artistTracks.isEmpty()) {
            artistTracks.addAll(trackRepository.findByArtistSpotifyId(artistId));
        }
        if (artistTracks.isEmpty() && name != null && !name.isBlank()) {
            artistTracks.addAll(trackRepository.findByArtistNameContainingIgnoreCase(name));
        }
        if (artistTracks.isEmpty() && !searchKey.equals(artistId)) {
            artistTracks.addAll(trackRepository.findByArtistNameContainingIgnoreCase(searchKey));
        }

        // If artist avatar was still default, use track image if available
        if (mongoArtist == null && linkedUser == null && !artistTracks.isEmpty() && artistTracks.get(0).getImageUrl() != null) {
            imageUrl = AudioUrlResolver.resolveImageUrl(artistTracks.get(0).getImageUrl());
        }

        List<TrackResponse> topTracks = artistTracks.stream()
                .distinct()
                .sorted(Comparator.comparingInt(t -> t.getPopularity() != null ? -t.getPopularity() : 0))
                .limit(10)
                .map(TrackResponse::from)
                .toList();

        // Appears On: tracks where featuredArtists contains artist name or id
        final String searchName = name.toLowerCase();
        List<TrackResponse> appearsOn = trackRepository.findAll(PageRequest.of(0, 100)).getContent().stream()
                .filter(t -> t.getFeaturedArtists() != null && t.getFeaturedArtists().toLowerCase().contains(searchName))
                .limit(6)
                .map(TrackResponse::from)
                .toList();

        // 5. Albums (from MySQL AlbumEntity and Mongo Album)
        List<AlbumResponse> albums = new ArrayList<>();
        if (linkedUser != null) {
            List<AlbumEntity> userAlbums = mysqlAlbumRepository.findByUserOrderByCreatedAtDesc(linkedUser);
            for (AlbumEntity alb : userAlbums) {
                int trackCount = (int) albumTrackRepository.countByAlbum(alb);
                albums.add(AlbumResponse.from(alb, trackCount, false));
            }
        }

        return new ArtistPublicProfileResponse(
            canonicalId,
            name,
            imageUrl,
            bannerUrl,
            bio,
            genres,
            verified,
            fb,
            ins,
            yt,
            followersCount,
            isFollowed,
            averageRating,
            reviewsCount,
            topTracks,
            albums,
            appearsOn
        );
    }

    private String resolveCanonicalArtistId(String artistId) {
        if (artistId == null || artistId.isBlank()) return artistId;
        String decodedId = artistId;
        try {
            decodedId = java.net.URLDecoder.decode(artistId, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        final String searchKey = decodedId;

        return artistRepository.findBySpotifyId(artistId)
                .or(() -> artistRepository.findById(artistId))
                .or(() -> artistRepository.findBySpotifyId(searchKey))
                .or(() -> {
                    List<Artist> byName = artistRepository.findByNameContainingIgnoreCase(searchKey);
                    return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
                })
                .map(a -> a.getSpotifyId() != null && !a.getSpotifyId().isBlank() ? a.getSpotifyId() : a.getId())
                .orElse(artistId);
    }

    @Transactional
    public FollowArtistResponse toggleFollow(String principal, String artistId) {
        final String targetId = resolveCanonicalArtistId(artistId);
        User user = findUserByPrincipal(principal);
        Optional<FavoriteArtistEntity> existing = favoriteArtistRepository.findByUserAndArtistId(user, targetId);
        if (existing.isEmpty() && !targetId.equals(artistId)) {
            existing = favoriteArtistRepository.findByUserAndArtistId(user, artistId);
        }

        boolean nowFollowed;
        if (existing.isPresent()) {
            favoriteArtistRepository.delete(existing.get());
            if (!targetId.equals(artistId)) {
                favoriteArtistRepository.deleteByUserAndArtistId(user, artistId);
            }
            nowFollowed = false;
        } else {
            FavoriteArtistEntity fav = new FavoriteArtistEntity(user, targetId);
            favoriteArtistRepository.save(fav);
            nowFollowed = true;
        }

        long count = favoriteArtistRepository.countByArtistId(targetId);
        Artist mongoArtist = artistRepository.findBySpotifyId(targetId).orElse(null);
        if (mongoArtist != null && mongoArtist.getFollowers() != null) {
            count = Math.max(mongoArtist.getFollowers(), count);
        }

        String msg = nowFollowed ? "Đã thêm nghệ sĩ vào Thư viện" : "Đã hủy theo dõi nghệ sĩ";
        return new FollowArtistResponse(targetId, nowFollowed, count, msg);
    }

    @Transactional(readOnly = true)
    public List<FollowedArtistResponse> getFollowedArtists(String principal) {
        User user = findUserByPrincipal(principal);
        List<FavoriteArtistEntity> favs = favoriteArtistRepository.findByUserOrderByCreatedAtDesc(user);
        if (favs.isEmpty()) return List.of();

        List<FollowedArtistResponse> result = new ArrayList<>();
        for (FavoriteArtistEntity fav : favs) {
            String aId = fav.getArtistId();
            Artist mongoArtist = artistRepository.findBySpotifyId(aId)
                    .or(() -> artistRepository.findById(aId))
                    .or(() -> {
                        List<Artist> byName = artistRepository.findByNameContainingIgnoreCase(aId);
                        return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
                    })
                    .orElse(null);

            User linkedUser = userRepository.findByArtistSpotifyId(aId).orElse(null);
            ArtistProfileEntity prof = artistProfileRepository.findById(aId).orElse(null);

            String name = mongoArtist != null ? mongoArtist.getName() : null;
            if (name == null && linkedUser != null) name = linkedUser.getFullname();
            if (name == null) name = aId;

            String imageUrl = mongoArtist != null ? AudioUrlResolver.resolveImageUrl(mongoArtist.getImageUrl()) : null;
            if (imageUrl == null && linkedUser != null) {
                imageUrl = AudioUrlResolver.resolveImageUrl(linkedUser.getAvatarUrl());
            }
            if (imageUrl == null) {
                imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500";
            }

            List<String> genres = mongoArtist != null && mongoArtist.getGenres() != null ? mongoArtist.getGenres() : List.of("V-Pop");
            boolean verified = prof == null || Boolean.TRUE.equals(prof.getVerified());
            long count = favoriteArtistRepository.countByArtistId(aId);
            if (mongoArtist != null && mongoArtist.getFollowers() != null) {
                count = Math.max(mongoArtist.getFollowers(), count);
            }

            result.add(new FollowedArtistResponse(
                aId,
                name,
                imageUrl,
                genres,
                verified,
                count,
                fav.getCreatedAt()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<String> getFollowedArtistIds(String principal) {
        User user = findUserByPrincipal(principal);
        return favoriteArtistRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(FavoriteArtistEntity::getArtistId)
                .toList();
    }

    @Transactional(readOnly = true)
    public ArtistReviewsPageResponse getReviews(String artistId, int page, int size) {
        final String targetId = resolveCanonicalArtistId(artistId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 50);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<ArtistReviewEntity> reviewPage = artistReviewRepository
                .findByArtistIdAndStatusOrderByCreatedAtDesc(targetId, "VISIBLE", pageable);

        Double avg = artistReviewRepository.calculateAverageRating(targetId);
        double averageRating = avg != null ? Math.round(avg * 10.0) / 10.0 : 5.0;

        List<ArtistReviewResponse> content = reviewPage.getContent().stream()
                .map(ArtistReviewResponse::from)
                .toList();

        return new ArtistReviewsPageResponse(
            content,
            reviewPage.getNumber(),
            reviewPage.getSize(),
            reviewPage.getTotalElements(),
            reviewPage.getTotalPages(),
            averageRating
        );
    }

    @Transactional
    public ArtistReviewResponse addOrUpdateReview(String principal, String artistId, CreateArtistReviewRequest req) {
        if (req.rating() == null || req.rating() < 1 || req.rating() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số sao đánh giá phải từ 1 đến 5");
        }

        final String targetId = resolveCanonicalArtistId(artistId);
        User user = findUserByPrincipal(principal);
        ArtistReviewEntity review = artistReviewRepository.findByUserAndArtistId(user, targetId)
                .orElseGet(() -> {
                    ArtistReviewEntity r = new ArtistReviewEntity();
                    r.setUser(user);
                    r.setArtistId(targetId);
                    return r;
                });

        review.setRating(req.rating());
        review.setComment(req.comment() != null ? req.comment().trim() : null);
        review.setStatus("VISIBLE");
        artistReviewRepository.save(review);

        return ArtistReviewResponse.from(review);
    }

    @Transactional
    public ArtistReviewResponse replyToReview(String principal, String artistId, Long reviewId, String reply) {
        User user = findUserByPrincipal(principal);
        // Verify user owns artist or is admin/content-lead
        boolean isOwner = (user.getArtistSpotifyId() != null && user.getArtistSpotifyId().equals(artistId))
                || "ADMIN".equals(user.getRole().name())
                || "CONTENT_LEAD".equals(user.getRole().name());

        if (!isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền phản hồi nhận xét của nghệ sĩ này");
        }

        ArtistReviewEntity review = artistReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));

        review.setArtistReply(reply != null ? reply.trim() : null);
        review.setArtistRepliedAt(LocalDateTime.now());
        artistReviewRepository.save(review);

        return ArtistReviewResponse.from(review);
    }

    private User findUserByPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập");
        }
        return userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại"));
    }
}
