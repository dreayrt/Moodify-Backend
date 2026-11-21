package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Album;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminFavoritesTest {

    private JdbcTemplate jdbcTemplate;
    private TrackRepository trackRepository;
    private MongoTemplate mongoTemplate;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AlbumRepository albumRepository;
    private ArtistRepository artistRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        trackRepository = mock(TrackRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        albumRepository = mock(AlbumRepository.class);
        artistRepository = mock(ArtistRepository.class);
        adminService = new AdminService(
                jdbcTemplate,
                trackRepository,
                mongoTemplate,
                userRepository,
                passwordEncoder,
                albumRepository,
                artistRepository
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void songFavoritesReadsCombinedUserLibraryAndLegacyAndResolvesMetadata() throws Exception {
        Track track = new Track();
        track.setId("mongo-trk-1");
        track.setSpotifyId("sp-trk-1");
        track.setName("Chúng Ta Của Hiện Tại");
        track.setArtistName("Sơn Tùng M-TP");
        track.setImageUrl("https://cdn.moodify.click/ctcht.jpg");
        when(trackRepository.findById("sp-trk-1")).thenReturn(Optional.empty());
        when(trackRepository.findBySpotifyId("sp-trk-1")).thenReturn(Optional.of(track));
        when(trackRepository.findById("sp-missing")).thenReturn(Optional.empty());
        when(trackRepository.findBySpotifyId("sp-missing")).thenReturn(Optional.empty());

        AtomicReference<String> capturedCountSql = new AtomicReference<>();
        when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), eq(Long.class)))
                .thenAnswer(inv -> {
                    capturedCountSql.set(inv.getArgument(0));
                    return 2L;
                });

        Timestamp ts1 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 14, 30));
        Timestamp ts2 = Timestamp.valueOf(LocalDateTime.of(2026, 11, 19, 9, 15));

        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> rm = inv.getArgument(2);
                    ResultSet rs1 = mockFavoriteRow(11L, 2L, "Nguyễn Văn A", "a@moodify.vn", "/uploads/avatars/a.png", "sp-trk-1", "SONG", "LIBRARY", ts1);
                    ResultSet rs2 = mockFavoriteRow(22L, 3L, "Trần Thị B", "b@moodify.vn", null, "sp-missing", "SONG", "LEGACY_SONG", ts2);
                    return List.of(rm.mapRow(rs1, 0), rm.mapRow(rs2, 1));
                });

        Map<String, Object> result = adminService.getFavorites("SONG", null, 0, 12);

        assertThat(capturedCountSql.get())
                .contains("user_library_tracks")
                .contains("favorite_songs")
                .contains("NOT EXISTS");
        assertThat(result.get("total")).isEqualTo(2L);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(2);

        assertThat(items.get(0).get("source")).isEqualTo("LIBRARY");
        assertThat(items.get(0).get("targetTitle")).isEqualTo("Chúng Ta Của Hiện Tại");
        assertThat(items.get(0).get("targetSubtitle")).isEqualTo("Sơn Tùng M-TP");
        assertThat(items.get(0).get("targetCoverUrl")).isEqualTo("https://cdn.moodify.click/ctcht.jpg");

        assertThat(items.get(1).get("source")).isEqualTo("LEGACY_SONG");
        assertThat(items.get(1).get("targetTitle")).isEqualTo("Bài hát đã lưu");
    }

    @Test
    @SuppressWarnings("unchecked")
    void albumFavoritesResolvesSqlAndMongoPrefixedIds() throws Exception {
        Album mongoAlbum = new Album();
        mongoAlbum.setId("67abc123");
        mongoAlbum.setName("Sky Tour");
        mongoAlbum.setArtistName("Sơn Tùng M-TP");
        mongoAlbum.setTotalTracks(8);
        mongoAlbum.setReleaseDate("2020-06-12");
        mongoAlbum.setImageUrl("https://cdn.moodify.click/sky.jpg");
        when(albumRepository.findById("67abc123")).thenReturn(Optional.of(mongoAlbum));

        when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(2L);

        Timestamp ts = Timestamp.valueOf(LocalDateTime.of(2026, 11, 21, 10, 0));
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> rm = inv.getArgument(2);
                    ResultSet rs1 = mockFavoriteRow(101L, 5L, "Người nghe 1", "u1@moodify.vn", null, "sql:12", "ALBUM", "FAVORITE_ALBUMS", ts);
                    ResultSet rs2 = mockFavoriteRow(102L, 6L, "Người nghe 2", "u2@moodify.vn", null, "mongo:67abc123", "ALBUM", "FAVORITE_ALBUMS", ts);
                    return List.of(rm.mapRow(rs1, 0), rm.mapRow(rs2, 1));
                });

        when(jdbcTemplate.query(contains("FROM albums a"), any(RowMapper.class), eq(12L)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(12L);
                    when(rs.getString("title")).thenReturn("Album Độc Lập");
                    when(rs.getString("artist_name")).thenReturn("Nghệ Sĩ Hải");
                    when(rs.getInt("track_count")).thenReturn(5);
                    when(rs.getDate("release_date")).thenReturn(Date.valueOf("2025-05-01"));
                    when(rs.getString("cover_url")).thenReturn("/uploads/covers/alb12.jpg");
                    return List.of(rm.mapRow(rs, 0));
                });

        Map<String, Object> result = adminService.getFavorites("ALBUM", null, 0, 12);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("targetTitle")).isEqualTo("Album Độc Lập");
        assertThat(items.get(0).get("targetSubtitle")).isEqualTo("Nghệ Sĩ Hải • 5 bài hát (2025-05-01)");
        assertThat(items.get(0).get("targetCoverUrl").toString()).endsWith("/uploads/covers/alb12.jpg");

        assertThat(items.get(1).get("targetTitle")).isEqualTo("Sky Tour");
        assertThat(items.get(1).get("targetSubtitle")).isEqualTo("Sơn Tùng M-TP • 8 bài hát (2020-06-12)");
        assertThat(items.get(1).get("targetCoverUrl")).isEqualTo("https://cdn.moodify.click/sky.jpg");
    }

    @Test
    @SuppressWarnings("unchecked")
    void albumFavoritesSearchIncludesPrefixedTargetIds() {
        Album mongoMatch = new Album();
        mongoMatch.setId("67mongo99");
        mongoMatch.setSpotifyId("sp-mid");
        mongoMatch.setName("Midnight Vibes");
        when(albumRepository.findByNameContainingIgnoreCase("Midnight")).thenReturn(List.of(mongoMatch));

        when(jdbcTemplate.query(contains("FROM albums a LEFT JOIN users"), any(RowMapper.class), any(), any()))
                .thenReturn(List.of("12"));

        List<Object> capturedArgs = new ArrayList<>();
        when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), eq(Long.class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArgument(1);
                    capturedArgs.addAll(List.of(args));
                    return 0L;
                });
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(List.of());

        adminService.getFavorites("ALBUM", "Midnight", 0, 12);

        assertThat(capturedArgs).contains("mongo:67mongo99", "mongo:sp-mid", "12", "sql:12");
    }

    @Test
    @SuppressWarnings("unchecked")
    void albumLeaderboardMergesRawAndPrefixedAliases() throws Exception {
        when(jdbcTemplate.query(contains("FROM favorite_albums"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> rm = inv.getArgument(1);
                    ResultSet r1 = mockLeaderboardRow("12", 2L);
                    ResultSet r2 = mockLeaderboardRow("sql:12", 3L);
                    return List.of(rm.mapRow(r1, 0), rm.mapRow(r2, 1));
                });

        when(jdbcTemplate.query(contains("FROM albums a"), any(RowMapper.class), eq(12L)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(12L);
                    when(rs.getString("title")).thenReturn("Album Hợp Nhất");
                    when(rs.getString("artist_name")).thenReturn("Tác Giả");
                    when(rs.getInt("track_count")).thenReturn(4);
                    when(rs.getDate("release_date")).thenReturn(null);
                    when(rs.getString("cover_url")).thenReturn("");
                    return List.of(rm.mapRow(rs, 0));
                });

        List<Map<String, Object>> leaderboard = adminService.getFavoriteLeaderboard("ALBUM");

        assertThat(leaderboard).hasSize(1);
        assertThat(leaderboard.get(0).get("targetTitle")).isEqualTo("Album Hợp Nhất");
        assertThat(leaderboard.get(0).get("favoriteCount")).isEqualTo(5L);
        assertThat(leaderboard.get(0).get("targetId")).isEqualTo("sql:12");
        assertThat(leaderboard.get(0).get("targetCoverUrl")).isEqualTo("/images/cover-placeholder.svg");
    }

    @Test
    void deleteSongFavoriteRespectsSourceAndCleansDuplicate() {
        when(jdbcTemplate.queryForList(contains("FROM user_library_tracks"), eq(99L)))
                .thenReturn(List.of(Map.of("user_id", 7L, "track_id", "trk-1")));
        when(jdbcTemplate.update("DELETE FROM user_library_tracks WHERE id = ?", 99L)).thenReturn(1);
        when(jdbcTemplate.update("DELETE FROM favorite_songs WHERE user_id = ? AND track_id = ?", 7L, "trk-1"))
                .thenReturn(1);

        adminService.deleteFavorite(99L, "SONG", "LIBRARY");

        verify(jdbcTemplate).update("DELETE FROM user_library_tracks WHERE id = ?", 99L);
        verify(jdbcTemplate).update("DELETE FROM favorite_songs WHERE user_id = ? AND track_id = ?", 7L, "trk-1");
        verify(jdbcTemplate, never()).update("DELETE FROM favorite_songs WHERE id = ?", 99L);

        when(jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", 55L)).thenReturn(1);
        adminService.deleteFavorite(55L, "SONG", "LEGACY_SONG");
        verify(jdbcTemplate).update("DELETE FROM favorite_songs WHERE id = ?", 55L);
        verify(jdbcTemplate, never()).update("DELETE FROM user_library_tracks WHERE id = ?", 55L);

        when(jdbcTemplate.update("DELETE FROM user_library_tracks WHERE id = ?", 404L)).thenReturn(0);
        when(jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", 404L)).thenReturn(0);
        assertThatThrownBy(() -> adminService.deleteFavorite(404L, "SONG", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    private ResultSet mockFavoriteRow(
            long id,
            long userId,
            String userName,
            String userEmail,
            String userAvatar,
            String targetId,
            String itemType,
            String favSource,
            Timestamp createdAt
    ) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getLong("user_id")).thenReturn(userId);
        when(rs.getString("user_name")).thenReturn(userName);
        when(rs.getString("user_email")).thenReturn(userEmail);
        when(rs.getString("user_avatar")).thenReturn(userAvatar);
        when(rs.getString("target_id")).thenReturn(targetId);
        when(rs.getString("item_type")).thenReturn(itemType);
        when(rs.getString("fav_source")).thenReturn(favSource);
        when(rs.getTimestamp("created_at")).thenReturn(createdAt);
        return rs;
    }

    private ResultSet mockLeaderboardRow(String targetId, long count) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("target_id")).thenReturn(targetId);
        when(rs.getLong("fav_count")).thenReturn(count);
        return rs;
    }
}
