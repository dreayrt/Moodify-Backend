package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.album.*;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.AlbumEntity;
import com.laphuth.moodify.entities.AlbumTrackEntity;
import com.laphuth.moodify.entities.FavoriteAlbumEntity;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.AlbumStatus;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.repositories.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AlbumService {

    private final AlbumEntityRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final FavoriteAlbumRepository favoriteAlbumRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final PreferenceEntityResolver identities;
    private final RecommendationPreferenceService preferences;
    private final RecommendationCatalog recommendationCatalog;

    public AlbumService(
        AlbumEntityRepository albumRepository,
        AlbumTrackRepository albumTrackRepository,
        FavoriteAlbumRepository favoriteAlbumRepository,
        UserRepository userRepository,
        TrackRepository trackRepository,
        PreferenceEntityResolver identities,
        RecommendationPreferenceService preferences,
        RecommendationCatalog recommendationCatalog
    ) {
        this.albumRepository = albumRepository;
        this.albumTrackRepository = albumTrackRepository;
        this.favoriteAlbumRepository = favoriteAlbumRepository;
        this.userRepository = userRepository;
        this.trackRepository = trackRepository;this.identities=identities;this.preferences=preferences;this.recommendationCatalog=recommendationCatalog;
    }

    @Transactional
    public AlbumResponse createAlbum(String username, CreateAlbumRequest request) {
        User user = findUser(username);
        checkCanManageAlbum(user);

        AlbumEntity entity = new AlbumEntity();
        entity.setUser(user);
        entity.setTitle(request.title().trim());
        entity.setDescription(request.description());
        entity.setCoverUrl(request.coverUrl());
        entity.setIsPublic(request.isPublic() != null ? request.isPublic() : true);
        entity.setStatus(AlbumStatus.ACTIVE);
        entity.setReleaseDate(LocalDate.now());

        AlbumEntity saved = albumRepository.save(entity);
        return AlbumResponse.from(saved, 0, false);
    }

    @Transactional
    public AlbumResponse updateAlbum(Long id, String username, UpdateAlbumRequest request) {
        AlbumEntity album = findAlbumAndCheckPermission(id, username);

        if (request.title() != null && !request.title().isBlank()) {
            album.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            album.setDescription(request.description());
        }
        if (request.coverUrl() != null) {
            album.setCoverUrl(request.coverUrl());
        }
        if (request.isPublic() != null) {
            album.setIsPublic(request.isPublic());
        }

        AlbumEntity updated = albumRepository.save(album);
        int trackCount = (int) albumTrackRepository.countByAlbum(updated);
        boolean isFav = isAlbumFavorited(username, String.valueOf(id));
        return AlbumResponse.from(updated, trackCount, isFav);
    }

    @Transactional
    public void deleteAlbum(Long id, String username) {
        AlbumEntity album = findAlbumAndCheckPermission(id, username);
        albumRepository.delete(album);
    }

    @Transactional(readOnly = true)
    public Page<AlbumResponse> getPublicAlbums(String query, int page, int size, String currentUsername) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        Page<AlbumEntity> albumPage;

        if (query != null && !query.isBlank()) {
            albumPage = albumRepository.searchPublicAlbums(query.trim(), AlbumStatus.ACTIVE, pageable);
        } else {
            albumPage = albumRepository.findByIsPublicTrueAndStatusOrderByCreatedAtDesc(AlbumStatus.ACTIVE, pageable);
        }

        Set<String> userFavAlbumIds = getUserFavAlbumIds(currentUsername);

        return albumPage.map(album -> {
            int trackCount = (int) albumTrackRepository.countByAlbum(album);
            boolean isFav = userFavAlbumIds.contains(String.valueOf(album.getId()));
            return AlbumResponse.from(album, trackCount, isFav);
        });
    }

    @Transactional(readOnly = true)
    public List<AlbumResponse> getUserAlbums(String username) {
        User user = findUser(username);
        List<AlbumEntity> albums = albumRepository.findByUserOrderByCreatedAtDesc(user);
        Set<String> userFavAlbumIds = getUserFavAlbumIds(username);

        return albums.stream().map(album -> {
            int trackCount = (int) albumTrackRepository.countByAlbum(album);
            boolean isFav = userFavAlbumIds.contains(String.valueOf(album.getId()));
            return AlbumResponse.from(album, trackCount, isFav);
        }).collect(Collectors.toList());
    }

    public AlbumDetailResponse getAlbumDetail(Long id,String currentUsername) { return getAlbumDetail(String.valueOf(id),currentUsername); }

    @Transactional(readOnly = true)
    public AlbumDetailResponse getAlbumDetail(String id,String currentUsername) {
        User actor=currentUsername==null?new User():findUser(currentUsername);
        if(actor.getId()==null)actor.setId(-1L);
        var ref=identities.album(actor,id);
        var snapshot=recommendationCatalog.snapshot();
        List<TrackResponse> tracks=ref.trackIds().stream().map(snapshot::resolve).filter(Objects::nonNull).map(TrackResponse::fromSummary).toList();
        boolean favorite=currentUsername!=null&&preferences.hasAlbumFavorite(actor,ref);
        return new AlbumDetailResponse(ref.displayId(),ref.title(),ref.description(),ref.imageUrl(),ref.releaseDate(),ref.isPublic(),"ACTIVE",
            ref.ownerId(),ref.authorName(),null,tracks.size(),favorite,tracks,ref.createdAt(),ref.updatedAt());
    }

    @Transactional
    public AlbumDetailResponse addTrackToAlbum(Long albumId, String username, String trackId) {
        AlbumEntity album = findAlbumAndCheckPermission(albumId, username);

        // Resolve track from MongoDB
        Track track = trackRepository.findBySpotifyId(trackId)
            .or(() -> trackRepository.findById(trackId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài hát: " + trackId));

        String resolvedTrackId = track.getSpotifyId() != null ? track.getSpotifyId() : track.getId();

        if (!albumTrackRepository.existsByAlbumAndTrackId(album, resolvedTrackId)) {
            int nextOrder = (int) albumTrackRepository.countByAlbum(album) + 1;
            AlbumTrackEntity entry = new AlbumTrackEntity(album, resolvedTrackId, nextOrder);
            albumTrackRepository.save(entry);
        }

        return getAlbumDetail(albumId, username);
    }

    @Transactional
    public AlbumDetailResponse removeTrackFromAlbum(Long albumId, String username, String trackId) {
        AlbumEntity album = findAlbumAndCheckPermission(albumId, username);

        albumTrackRepository.deleteByAlbumAndTrackId(album, trackId);
        // Also check if passed MongoDB ID instead of Spotify ID
        trackRepository.findBySpotifyId(trackId).ifPresent(t -> {
            if (t.getId() != null) {
                albumTrackRepository.deleteByAlbumAndTrackId(album, t.getId());
            }
        });

        return getAlbumDetail(albumId, username);
    }

    @Transactional
    public boolean toggleFavoriteAlbum(String username,String albumId) {
        return preferences.toggleAlbum(findUser(username),albumId);
    }

    @Transactional(readOnly=true)
    public boolean isAlbumFavorited(String username,String albumId) {
        if(username==null||username.isBlank())return false;
        try { User actor=findUser(username);return preferences.hasAlbumFavorite(actor,identities.album(actor,albumId)); }
        catch(ResponseStatusException unavailable){return false;}
    }

    @Transactional(readOnly=true)
    public List<AlbumResponse> getUserFavoriteAlbums(String username) {
        User actor=findUser(username);Map<String,AlbumResponse> result=new LinkedHashMap<>();
        for(String raw:preferences.favoriteAlbumIds(actor)) {
            try {
                var ref=identities.album(actor,raw);
                result.putIfAbsent(ref.key(),new AlbumResponse(ref.displayId(),ref.title(),ref.description(),ref.imageUrl(),ref.releaseDate(),
                    ref.isPublic(),"ACTIVE",ref.ownerId(),ref.authorName(),null,ref.trackIds().size(),true,ref.createdAt(),ref.updatedAt()));
            } catch(ResponseStatusException invalid) {
                if(!Set.of(400,404,409).contains(invalid.getStatusCode().value()))throw invalid;
            }
        }
        return List.copyOf(result.values());
    }

    private User findUser(String username) {
        return userRepository.findByEmailOrUsername(username, username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại: " + username));
    }

    private void checkCanManageAlbum(User user) {
        if (user.getRole() != UserRole.CONTENT_LEAD && user.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Nghệ sĩ (CONTENT_LEAD) hoặc Quản trị viên mới có quyền tạo và phát hành Album");
        }
    }

    private AlbumEntity findAlbumAndCheckPermission(Long id, String username) {
        AlbumEntity album = albumRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy album: " + id));

        User user = findUser(username);
        checkCanManageAlbum(user);

        boolean isOwner = album.getUser() != null && album.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == UserRole.ADMIN;

        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền chỉnh sửa album này");
        }
        return album;
    }

    private Set<String> getUserFavAlbumIds(String username) {
        if (username == null || username.isBlank()) return Collections.emptySet();
        try {
            User user = findUser(username);
            Set<String> aliases=new HashSet<>();
            for(String raw:preferences.favoriteAlbumIds(user)) {
                try { aliases.addAll(identities.album(user,raw).aliases()); }
                catch(ResponseStatusException ignored) { }
            }
            return aliases;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private List<TrackResponse> loadTracksInOrder(List<String> trackIds) {
        if (trackIds == null || trackIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Track> foundBySpotify = trackRepository.findBySpotifyIdIn(trackIds);
        Map<String, Track> trackMap = new HashMap<>();
        for (Track t : foundBySpotify) {
            if (t.getSpotifyId() != null) trackMap.put(t.getSpotifyId(), t);
            if (t.getId() != null) trackMap.put(t.getId(), t);
        }

        List<TrackResponse> result = new ArrayList<>();
        for (String id : trackIds) {
            Track t = trackMap.get(id);
            if (t == null) {
                t = trackRepository.findById(id).orElse(null);
            }
            if (t == null) {
                t = trackRepository.findBySpotifyId(id).orElse(null);
            }
            if (t != null) {
                result.add(TrackResponse.fromSummary(t));
            }
        }
        return result;
    }
}
