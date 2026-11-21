package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Album;
import com.laphuth.moodify.entities.Artist;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.AlbumRepository;
import com.laphuth.moodify.repositories.ArtistRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.repositories.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {
    private CatalogIntegrityService catalogIntegrity;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCatalogIntegrity(CatalogIntegrityService value) { catalogIntegrity=value; }

    private UserDeviceService userDeviceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserDeviceService(UserDeviceService value) { this.userDeviceService = value; }


    private final JdbcTemplate jdbcTemplate;
    private final TrackRepository trackRepository;
    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String COMBINED_SONG_FAVORITES_SUBQUERY =
        "(SELECT ult.id, ult.user_id, ult.track_id, ult.added_at AS created_at, 'LIBRARY' AS fav_source " +
        " FROM user_library_tracks ult " +
        " UNION ALL " +
        " SELECT fs.id, fs.user_id, fs.track_id, fs.created_at AS created_at, 'LEGACY_SONG' AS fav_source " +
        " FROM favorite_songs fs " +
        " WHERE NOT EXISTS (" +
        "   SELECT 1 FROM user_library_tracks ult2 " +
        "   WHERE ult2.user_id = fs.user_id AND ult2.track_id = fs.track_id" +
        " ))";

    private Track findAdminTrack(String id) {
        if (id == null || id.isBlank()) return null;
        String key = id.trim();
        if (key.startsWith("spotify:track:")) key = key.substring("spotify:track:".length());
        Track track = trackRepository.findById(key).orElse(null);
        return track != null ? track : trackRepository.findBySpotifyId(key).orElse(null);
    }

    private String resolveAudioUrl(String localPath) {
        return localPath == null || localPath.isBlank() ? "" : AudioUrlResolver.resolve(localPath);
    }

    private int resolveTrackDurationMs(Track track) {
        if (track == null) return 0;
        if (track.getDurationMs() != null && track.getDurationMs() > 0) return track.getDurationMs();
        String formatted = track.getDurationFormatted();
        if (formatted == null || !formatted.matches("\\d{1,4}:[0-5]\\d")) return 0;
        String[] parts = formatted.split(":");
        return (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) * 1000;
    }

    private String formatTrackDuration(Track track) {
        int durationMs = resolveTrackDurationMs(track);
        return durationMs > 0 ? formatDurationSeconds(durationMs / 1000) : "—";
    }

    private String resolveAdminImageUrl(String imageUrl) {
        String resolved = AudioUrlResolver.resolveImageUrl(imageUrl);
        return resolved != null ? resolved : "/images/cover-placeholder.svg";
    }

    /**
     * Ghi nhật ký hành động quản trị vào MongoDB collection "audit_logs"
     * với đúng danh tính người thực hiện (trước đây hardcode operator_user_id = 4
     * và insert vào bảng MySQL không tồn tại). Không đổi schema MySQL.
     */
    private void recordAudit(String principal, String entity, Long targetId, String action, String details) {
        try {
            User operator = userRepository.findByEmailOrUsername(principal, principal).orElse(null);
            if (operator == null) return;

            org.bson.Document log = new org.bson.Document();
            log.put("operatorUserId", operator.getId());
            log.put("operatorName", operator.getFullname());
            log.put("operatorRole", operator.getRole() != null ? operator.getRole().name() : "ADMIN");
            log.put("targetEntity", entity);
            log.put("targetId", targetId);
            log.put("action", action);
            log.put("details", details);
            log.put("createdAt", new java.util.Date());
            mongoTemplate.insert(log, "audit_logs");
        } catch (Exception e) {
            System.err.println("Failed to write audit log: " + e.getMessage());
        }
    }

    /** Đọc 100 nhật ký hành chính gần nhất để hiển thị trong tab Cài Đặt & Nhật Ký. */
    public List<Map<String, Object>> getAuditLogs() {
        Query query = Query.query(new Criteria())
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(100);
        List<org.bson.Document> docs = mongoTemplate.find(query, org.bson.Document.class, "audit_logs");

        List<Map<String, Object>> result = new ArrayList<>();
        for (org.bson.Document doc : docs) {
            Map<String, Object> log = new HashMap<>();
            log.put("id", doc.getObjectId("_id").toHexString());
            log.put("operatorName", doc.getString("operatorName") != null ? doc.getString("operatorName") : "Hệ thống");
            log.put("operatorRole", doc.getString("operatorRole") != null ? doc.getString("operatorRole") : "SYSTEM");
            log.put("targetEntity", doc.getString("targetEntity"));
            log.put("targetId", doc.get("targetId") != null ? doc.get("targetId").toString() : null);
            log.put("action", doc.getString("action"));
            log.put("details", doc.getString("details"));
            log.put("createdAt", doc.getDate("createdAt") != null ? doc.getDate("createdAt").toString() : null);
            result.add(log);
        }
        return result;
    }

    public AdminService(
            JdbcTemplate jdbcTemplate,
            TrackRepository trackRepository,
            MongoTemplate mongoTemplate,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AlbumRepository albumRepository,
            ArtistRepository artistRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.trackRepository = trackRepository;
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.albumRepository = albumRepository;
        this.artistRepository = artistRepository;
    }

    // ==========================================
    // 1. OVERVIEW & KPI METRICS (100% REAL DATA)
    // ==========================================
    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new HashMap<>();

        // 1. MySQL user & revenue metrics
        Long totalUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        Long activeUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'", Long.class);
        Long bannedUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'BANNED'", Long.class);
        Long artistUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role IN ('CONTENT_LEAD', 'ARTIST')", Long.class);
        Long moderatorUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'MODERATOR'", Long.class);

        Double totalRevenue = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_transactions WHERE status = 'SUCCESS'", Double.class);
        Long activeSubscriptions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM subscriptions WHERE status = 'ACTIVE'", Long.class);

        // 2. Streams & Favorites & Listening Duration counts from real DB
        Long totalStreams = 0L;
        try {
            totalStreams = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM listening_history", Long.class);
        } catch (Exception ignored) {}

        Long totalFavorites = 0L;
        try {
            totalFavorites = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f",
                Long.class
            );
        } catch (Exception ignored) {}

        Long totalListenedMs = 0L;
        try {
            totalListenedMs = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(listened_duration_ms), 0) FROM listening_history", Long.class);
        } catch (Exception ignored) {}
        double listenedHours = totalListenedMs != null ? Math.round((totalListenedMs / 3600000.0) * 100.0) / 100.0 : 0.0;
        String listenedFormatted = formatTotalDuration(totalListenedMs != null ? totalListenedMs : 0L);

        Long completedSessions = 0L;
        try {
            completedSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT pe.listening_history_id) FROM playback_events pe WHERE pe.event_type = 'COMPLETE'", Long.class);
        } catch (Exception ignored) {}
        double avgCompletionRate = (totalStreams != null && totalStreams > 0)
            ? Math.round(((double) (completedSessions != null ? completedSessions : 0) / totalStreams) * 1000.0) / 10.0
            : 0.0;

        Long activeDevices = 0L;
        try {
            activeDevices = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_devices WHERE status = 'ACTIVE'", Long.class);
        } catch (Exception ignored) {}

        List<Map<String, Object>> streamsByPlatform = new ArrayList<>();
        try {
            String platSql = "SELECT COALESCE(device_type, 'WEB') as device_type, COUNT(*) as cnt FROM listening_history GROUP BY device_type ORDER BY cnt DESC";
            streamsByPlatform = jdbcTemplate.query(platSql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("platform", rs.getString("device_type"));
                m.put("count", rs.getLong("cnt"));
                return m;
            });
        } catch (Exception ignored) {}

        List<Map<String, Object>> streamsBySource = new ArrayList<>();
        try {
            String srcSql = "SELECT COALESCE(source, 'HOME') as source, COUNT(*) as cnt FROM listening_history GROUP BY source ORDER BY cnt DESC";
            streamsBySource = jdbcTemplate.query(srcSql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("source", rs.getString("source"));
                m.put("count", rs.getLong("cnt"));
                return m;
            });
        } catch (Exception ignored) {}

        // 3. MongoDB metrics
        long totalTracks = trackRepository.count();
        long publishedTracks = mongoTemplate.count(
                Query.query(Criteria.where("moderation_status").is("approved")), Track.class);
        long pendingReviews = mongoTemplate.count(
                Query.query(Criteria.where("moderation_status").in("pending", "flagged")), Track.class);

        overview.put("totalUsers", totalUsers != null ? totalUsers : 0);
        overview.put("activeUsers", activeUsers != null ? activeUsers : 0);
        overview.put("bannedUsers", bannedUsers != null ? bannedUsers : 0);
        overview.put("artistUsers", artistUsers != null ? artistUsers : 0);
        overview.put("moderatorUsers", moderatorUsers != null ? moderatorUsers : 0);
        overview.put("totalRevenue", totalRevenue != null ? totalRevenue : 0.0);
        overview.put("activeSubscriptions", activeSubscriptions != null ? activeSubscriptions : 0);
        overview.put("totalTracks", totalTracks);
        overview.put("publishedTracks", publishedTracks);
        overview.put("pendingReviews", pendingReviews);
        overview.put("totalStreams", totalStreams != null ? totalStreams : 0);
        overview.put("totalFavorites", totalFavorites != null ? totalFavorites : 0);
        overview.put("totalListenedDurationMs", totalListenedMs != null ? totalListenedMs : 0);
        overview.put("totalListenedHours", listenedHours);
        overview.put("totalListenedFormatted", listenedFormatted);
        overview.put("completedSessions", completedSessions != null ? completedSessions : 0);
        overview.put("avgCompletionRate", avgCompletionRate);
        overview.put("activeDevices", activeDevices != null ? activeDevices : 0);
        overview.put("streamsByPlatform", streamsByPlatform);
        overview.put("streamsBySource", streamsBySource);

        // 4. Top listened tracks from real listening_history joined with MongoDB track metadata
        List<Map<String, Object>> topListened = getTopListenedTracks(5);
        overview.put("topListenedTracks", topListened);

        // 4b. Per-track completion rate, skip rate & retention metrics
        List<Map<String, Object>> performanceMetrics = getTrackPerformanceMetrics(10);
        overview.put("trackPerformanceMetrics", performanceMetrics);

        // 5. Top favorited tracks from real favorite_songs joined with MongoDB track metadata
        List<Map<String, Object>> topFavorited = getTopFavoritedTracks(5);
        overview.put("topFavoritedTracks", topFavorited);

        // 6. Listening trend by date from real listening_history (most recent up to 90 days, chronological)
        List<Map<String, Object>> trend = getListeningTrend();
        overview.put("listeningTrend", trend);

        // 6b. User registration trend by date (most recent 14 days, chronological)
        List<Map<String, Object>> userTrend = getUserRegistrationTrend();
        overview.put("userTrend", userTrend);

        // 7. Recent platform activities (real transactions, listening sessions, favorites)
        List<Map<String, Object>> recentActivities = getRecentActivities();
        overview.put("recentActivities", recentActivities);

        return overview;
    }

    public List<Map<String, Object>> getTopListenedTracks(int limit) {
        String sql = "SELECT track_id, COUNT(*) as stream_count FROM listening_history GROUP BY track_id ORDER BY stream_count DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, new Object[]{limit}, (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            String trackId = rs.getString("track_id");
            item.put("trackId", trackId);
            item.put("streamCount", rs.getLong("stream_count"));
            return item;
        });

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String trackId = (String) r.get("trackId");
            Track t = findAdminTrack(trackId);
            if (t != null) {
                r.put("title", t.getName());
                r.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
                r.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
                r.put("duration", formatTrackDuration(t));
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Không tìm thấy thông tin bài hát");
                r.put("artist", "Chưa có thông tin nghệ sĩ");
                r.put("coverUrl", "/images/cover-placeholder.svg");
                r.put("duration", "");
                r.put("genre", "Chưa phân loại");
                r.put("audioUrl", resolveAudioUrl(null));
            }
            result.add(r);
        }
        return result;
    }

    public List<Map<String, Object>> getTopFavoritedTracks(int limit) {
        String sql = "SELECT f.track_id, COUNT(*) as favorite_count FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f GROUP BY f.track_id ORDER BY favorite_count DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, new Object[]{limit}, (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            String trackId = rs.getString("track_id");
            item.put("trackId", trackId);
            item.put("favoriteCount", rs.getLong("favorite_count"));
            return item;
        });

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String trackId = (String) r.get("trackId");
            Track t = findAdminTrack(trackId);
            if (t != null) {
                r.put("title", t.getName());
                r.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
                r.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
                r.put("duration", formatTrackDuration(t));
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Không tìm thấy thông tin bài hát");
                r.put("artist", "Chưa có thông tin nghệ sĩ");
                r.put("coverUrl", "/images/cover-placeholder.svg");
                r.put("duration", "");
                r.put("genre", "Chưa phân loại");
                r.put("audioUrl", resolveAudioUrl(null));
            }
            result.add(r);
        }
        return result;
    }

    public List<Map<String, Object>> getListeningTrend() {
        String sql = """
            SELECT 
                DATE_FORMAT(lh.started_at, '%d/%m') as day_label, 
                DATE(lh.started_at) as day_date, 
                COUNT(DISTINCT lh.id) as count_val,
                COUNT(DISTINCT CASE WHEN pe.event_type = 'COMPLETE' THEN lh.id END) as completed_val
            FROM listening_history lh
            LEFT JOIN playback_events pe ON pe.listening_history_id = lh.id AND pe.event_type = 'COMPLETE'
            GROUP BY DATE(lh.started_at), DATE_FORMAT(lh.started_at, '%d/%m')
            ORDER BY day_date DESC LIMIT 90
        """;
        try {
            List<Map<String, Object>> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", rs.getString("day_date"));
                m.put("label", rs.getString("day_label"));
                m.put("streams", rs.getInt("count_val"));
                m.put("completed", rs.getInt("completed_val"));
                return m;
            });
            Collections.reverse(list);
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getUserRegistrationTrend() {
        String sql = "SELECT DATE_FORMAT(created_at, '%d/%m') as day_label, DATE(created_at) as day_date, COUNT(*) as count_val " +
                     "FROM users " +
                     "GROUP BY DATE(created_at), DATE_FORMAT(created_at, '%d/%m') " +
                     "ORDER BY day_date DESC LIMIT 14";
        try {
            List<Map<String, Object>> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", rs.getString("day_date"));
                m.put("label", rs.getString("day_label"));
                m.put("count", rs.getInt("count_val"));
                return m;
            });
            Collections.reverse(list);
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getRecentActivities() {
        List<Map<String, Object>> list = new ArrayList<>();

        // 1. Recent payments
        try {
            String paySql = "SELECT t.id, u.full_name, p.name as pkg_name, t.amount, t.paid_at, t.created_at " +
                            "FROM payment_transactions t " +
                            "JOIN subscriptions s ON t.subscription_id = s.id " +
                            "JOIN users u ON s.user_id = u.id " +
                            "JOIN service_packages p ON s.service_package_id = p.id " +
                            "ORDER BY t.created_at DESC LIMIT 5";
            jdbcTemplate.query(paySql, (rs, rowNum) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("id", "pay-" + rs.getLong("id"));
                a.put("type", "PAYMENT");
                a.put("title", rs.getString("full_name") + " đã đăng ký gói " + rs.getString("pkg_name"));
                a.put("detail", "Thanh toán thành công: " + String.format("%,.0f đ", rs.getDouble("amount")));
                Timestamp ts = rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at") : rs.getTimestamp("created_at");
                a.put("timestamp", ts != null
                        ? ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                        : "");
                list.add(a);
                return null;
            });
        } catch (Exception ignored) {}

        // 2. Recent listening sessions from listening_history
        try {
            String listenSql = "SELECT lh.id, u.full_name, lh.track_id, lh.device_type, lh.started_at " +
                               "FROM listening_history lh " +
                               "JOIN users u ON lh.user_id = u.id " +
                               "ORDER BY lh.started_at DESC LIMIT 5";
            jdbcTemplate.query(listenSql, (rs, rowNum) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("id", "listen-" + rs.getLong("id"));
                a.put("type", "STREAM");
                String trackId = rs.getString("track_id");
                Track t = findAdminTrack(trackId);
                String trackName = t != null ? t.getName() : "Bài hát";
                a.put("title", rs.getString("full_name") + " đã nghe " + trackName);
                a.put("detail", "Nền tảng: " + (rs.getString("device_type") != null ? rs.getString("device_type") : "Chưa xác định"));
                Timestamp ts = rs.getTimestamp("started_at");
                a.put("timestamp", ts != null
                        ? ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                        : "");
                list.add(a);
                return null;
            });
        } catch (Exception ignored) {}

        // 3. Recent favorites
        try {
            String favSql = "SELECT f.id, u.full_name, f.track_id, f.created_at " +
                            "FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f " +
                            "JOIN users u ON f.user_id = u.id " +
                            "ORDER BY f.created_at DESC LIMIT 5";
            jdbcTemplate.query(favSql, (rs, rowNum) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("id", "fav-" + rs.getLong("id"));
                a.put("type", "FAVORITE");
                String trackId = rs.getString("track_id");
                Track t = findAdminTrack(trackId);
                String trackName = t != null ? t.getName() : "Bài hát";
                a.put("title", rs.getString("full_name") + " đã yêu thích " + trackName);
                a.put("detail", "Thêm vào danh sách yêu thích cá nhân");
                a.put("timestamp", rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                        : "");
                list.add(a);
                return null;
            });
        } catch (Exception ignored) {}

        return list;
    }

    // ==========================================
    // 2. USER MANAGEMENT (MySQL)
    // ==========================================
    public List<Map<String, Object>> getUsers(String role, String status, String search) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.full_name, u.username, u.email, u.phone, u.role, u.status, " +
                "u.avatar_url, u.artist_spotify_id, u.last_login_at, u.created_at, " +
                "COUNT(d.id) as devices_count " +
                "FROM users u " +
                "LEFT JOIN user_devices d ON u.id = d.user_id AND d.status = 'ACTIVE' " +
                "WHERE 1=1 "
        );
        List<Object> params = new ArrayList<>();

        if (role != null && !role.isBlank() && !role.equalsIgnoreCase("ALL")) {
            String r = role.toUpperCase().trim();
            if ("CONTENT_LEAD".equals(r) || "ARTIST".equals(r)) {
                sql.append("AND u.role IN ('CONTENT_LEAD', 'ARTIST') ");
            } else {
                sql.append("AND u.role = ? ");
                params.add(r);
            }
        }

        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            sql.append("AND u.status = ? ");
            params.add(status.toUpperCase().trim());
        }

        if (search != null && !search.isBlank()) {
            sql.append("AND (LOWER(u.full_name) LIKE ? OR LOWER(u.username) LIKE ? OR LOWER(u.email) LIKE ?) ");
            String q = "%" + search.toLowerCase().trim() + "%";
            params.add(q);
            params.add(q);
            params.add(q);
        }

        sql.append("GROUP BY u.id ORDER BY u.id ASC LIMIT 1000");

        return jdbcTemplate.query(sql.toString(), params.toArray(), this::mapUserRow);
    }

    private Map<String, Object> mapUserRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> u = new HashMap<>();
        u.put("id", rs.getLong("id"));
        u.put("fullName", rs.getString("full_name"));
        u.put("username", rs.getString("username"));
        u.put("email", rs.getString("email"));
        u.put("phone", rs.getString("phone") != null ? rs.getString("phone") : "");
        u.put("role", rs.getString("role"));
        u.put("status", rs.getString("status"));
        u.put("avatarUrl", rs.getString("avatar_url"));
        u.put("artistSpotifyId", rs.getString("artist_spotify_id"));
        u.put("devicesCount", rs.getInt("devices_count"));
        u.put("lastLoginAt", rs.getTimestamp("last_login_at") != null
                ? rs.getTimestamp("last_login_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                : null);
        u.put("createdAt", rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "");
        return u;
    }

    private boolean isSamePrincipal(User target, String principal) {
        if (target == null || principal == null || principal.isBlank()) return false;
        String clean = principal.trim();
        return (target.getUsername() != null && target.getUsername().equalsIgnoreCase(clean))
                || (target.getEmail() != null && target.getEmail().equalsIgnoreCase(clean));
    }

    private Long countActiveAdmins() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'",
                Long.class
        );
    }

    private int revokeUserSessions(Long userId) {
        if (userId == null) return 0;
        if (userDeviceService != null) {
            return userDeviceService.revokeAllDevicesForUser(userId);
        }
        return jdbcTemplate.update(
                "UPDATE user_devices SET status = 'REVOKED' WHERE user_id = ? AND status = 'ACTIVE'",
                userId
        );
    }

    private void ensureContentLeadArtistProfile(User user, String artistSpotifyId) {
        if (artistSpotifyId != null && !artistSpotifyId.isBlank()) {
            String trimmed = artistSpotifyId.trim();
            user.setArtistSpotifyId(catalogIntegrity != null
                    ? catalogIntegrity.registerArtistIdentity(trimmed)
                    : trimmed);
            return;
        }
        if (user.getArtistSpotifyId() != null && !user.getArtistSpotifyId().isBlank()) {
            return;
        }
        String newArtistId = new org.bson.types.ObjectId().toHexString();
        Artist artist = new Artist();
        artist.setId(newArtistId);
        artist.setSpotifyId(null);
        String displayName = (user.getFullname() != null && !user.getFullname().isBlank())
                ? user.getFullname().trim()
                : user.getUsername();
        artist.setName(displayName);
        artist.setImageUrl(user.getAvatarUrl());
        artist.setFollowers(0);
        artist.setPopularity(0);
        artist.setGenres(List.of("pop"));
        artist.setGenresRaw(List.of("pop"));
        artist.setCreatedAt(Instant.now());
        artist.setUpdatedAt(Instant.now());
        artistRepository.save(artist);
        if (catalogIntegrity != null) {
            catalogIntegrity.registerArtist(artist);
        }
        user.setArtistSpotifyId(newArtistId);
    }

    @Transactional
    public void updateUserStatus(Long userId, String newStatus, String reason, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        UserStatus status;
        try {
            status = UserStatus.valueOf(newStatus.toUpperCase().trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ");
        }

        if (status != UserStatus.ACTIVE) {
            if (isSamePrincipal(user, principal)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Không thể tự khóa hoặc vô hiệu hóa tài khoản quản trị đang đăng nhập.");
            }
            if (user.getRole() == UserRole.ADMIN && user.getStatus() == UserStatus.ACTIVE) {
                Long activeAdmins = countActiveAdmins();
                if (activeAdmins != null && activeAdmins <= 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Không thể khóa quản trị viên hoạt động cuối cùng của hệ thống.");
                }
            }
        }

        user.setStatus(status);
        userRepository.save(user);

        if (status != UserStatus.ACTIVE) {
            revokeUserSessions(userId);
        }

        recordAudit(principal, "USER", userId, status.name(),
                reason != null ? reason : "Status changed by admin");
    }

    @Transactional
    public void updateUserRole(Long userId, String newRole, String staffCode, String artistSpotifyId, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String normalizedRole = newRole != null ? newRole.toUpperCase().trim() : "";
        if ("ARTIST".equals(normalizedRole)) {
            normalizedRole = "CONTENT_LEAD";
        }
        UserRole role;
        try {
            role = UserRole.valueOf(normalizedRole);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ");
        }

        UserRole previousRole = user.getRole();
        if (isSamePrincipal(user, principal) && role != previousRole) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể tự thay đổi vai trò của tài khoản đang đăng nhập.");
        }
        if (previousRole == UserRole.ADMIN && user.getStatus() == UserStatus.ACTIVE && role != UserRole.ADMIN) {
            Long activeAdmins = countActiveAdmins();
            if (activeAdmins != null && activeAdmins <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Không thể hạ quyền quản trị viên hoạt động cuối cùng của hệ thống.");
            }
        }

        user.setRole(role);
        if (role == UserRole.CONTENT_LEAD) {
            ensureContentLeadArtistProfile(user, artistSpotifyId);
        }
        userRepository.save(user);

        if (role != previousRole) {
            revokeUserSessions(userId);
        }

        recordAudit(principal, "USER", userId, "CHANGE_ROLE",
                "Vai trò chuyển sang " + role.name());
    }

    /**
     * Reset mật khẩu về một mật khẩu tạm ngẫu nhiên (không còn mặc định 123456).
     * Trả về mật khẩu tạm để admin thông báo cho người dùng.
     */
    @Transactional
    public String resetUserPassword(Long userId, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (isSamePrincipal(user, principal)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể tự đặt lại mật khẩu tạm từ màn hình quản trị. Hãy dùng chức năng đổi mật khẩu cá nhân.");
        }

        String tempPassword = generateTempPassword();
        user.setPassword(passwordEncoder.encode(tempPassword));
        userRepository.save(user);

        revokeUserSessions(userId);

        recordAudit(principal, "USER", userId, "RESET_PASSWORD",
                "Mật khẩu đã được đặt lại bằng mật khẩu tạm ngẫu nhiên");
        return tempPassword;
    }

    private String generateTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Transactional
    public void updateUserProfile(Long userId, Map<String, String> data) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (data.containsKey("fullName") && data.get("fullName") != null) {
            String fullName = data.get("fullName").trim();
            if (fullName.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Họ tên không được để trống");
            }
            user.setFullname(fullName);
        }
        if (data.containsKey("email") && data.get("email") != null) {
            String email = data.get("email").trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email không được để trống");
            }
            userRepository.findByEmail(email)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng bởi tài khoản khác");
                    });
            user.setEmail(email);
        }
        if (data.containsKey("phone")) {
            String rawPhone = data.get("phone");
            String normalizedPhone = rawPhone != null ? rawPhone.trim() : "";
            if (normalizedPhone.isEmpty()) {
                user.setPhone(null);
            } else {
                userRepository.findByPhone(normalizedPhone)
                        .filter(other -> !other.getId().equals(userId))
                        .ifPresent(other -> {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng bởi tài khoản khác");
                        });
                user.setPhone(normalizedPhone);
            }
        }
        userRepository.save(user);
    }

    @Transactional
    public void createUser(Map<String, Object> data) {
        String rawUsername = data.get("username") != null ? data.get("username").toString().trim().toLowerCase(Locale.ROOT) : "";
        String rawFullName = data.get("fullName") != null ? data.get("fullName").toString().trim() : "";
        String rawEmail = data.get("email") != null ? data.get("email").toString().trim().toLowerCase(Locale.ROOT) : "";
        String rawPhone = data.get("phone") != null ? data.get("phone").toString().trim() : "";
        String roleStr = data.get("role") != null ? data.get("role").toString() : "USER";
        String statusStr = data.get("status") != null ? data.get("status").toString() : "ACTIVE";
        String password = data.get("password") != null ? data.get("password").toString() : null;

        if (rawUsername.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên đăng nhập không được để trống");
        }
        if (rawEmail.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email không được để trống");
        }
        if (rawFullName.isEmpty()) {
            rawFullName = rawUsername;
        }
        if (userRepository.existsByUsername(rawUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tên đăng nhập đã tồn tại");
        }
        if (userRepository.existsByEmail(rawEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã tồn tại");
        }
        String normalizedPhone = rawPhone.isEmpty() ? null : rawPhone;
        if (normalizedPhone != null && userRepository.existsByPhone(normalizedPhone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng");
        }

        String r = roleStr.toUpperCase().trim();
        if ("ARTIST".equals(r)) {
            r = "CONTENT_LEAD";
        }
        UserRole role;
        UserStatus status;
        try {
            role = UserRole.valueOf(r);
            status = UserStatus.valueOf(statusStr.toUpperCase().trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vai trò hoặc trạng thái không hợp lệ");
        }

        User user = new User();
        user.setUsername(rawUsername);
        user.setFullname(rawFullName);
        user.setEmail(rawEmail);
        user.setPhone(normalizedPhone);
        user.setRole(role);
        user.setStatus(status);
        // Mật khẩu do admin cung cấp; nếu bỏ trống thì sinh mật khẩu tạm ngẫu nhiên
        user.setPassword(passwordEncoder.encode(
                (password != null && !password.isBlank()) ? password.trim() : generateTempPassword()));
        if (role == UserRole.CONTENT_LEAD) {
            ensureContentLeadArtistProfile(user, null);
        }
        userRepository.save(user);
    }

    public List<Map<String, Object>> getUserDevices(Long userId) {
        String sql = "SELECT id, user_id, device_uuid, platform, device_name, status, created_at FROM user_devices WHERE user_id = ? ORDER BY status ASC, created_at DESC";
        return jdbcTemplate.query(sql, new Object[]{userId}, (rs, rowNum) -> {
            Map<String, Object> d = new HashMap<>();
            d.put("id", rs.getLong("id"));
            d.put("userId", rs.getLong("user_id"));
            d.put("deviceUuid", rs.getString("device_uuid"));
            d.put("platform", rs.getString("platform"));
            d.put("deviceName", rs.getString("device_name"));
            d.put("status", rs.getString("status"));
            d.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            return d;
        });
    }

    public void revokeDevice(Long deviceId) {
        int updated = jdbcTemplate.update("UPDATE user_devices SET status = 'REVOKED' WHERE id = ?", deviceId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thiết bị không tồn tại");
        }
    }

    @Transactional
    public int revokeAllUserDevices(Long userId, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        int revoked = revokeUserSessions(user.getId());
        recordAudit(principal, "USER", userId, "REVOKE_ALL_DEVICES",
                "Đã thu hồi " + revoked + " phiên đăng nhập đang hoạt động");
        return revoked;
    }

    // ==========================================
    // 3. CATALOG & TRACK MANAGEMENT (MongoDB)
    // ==========================================
    public List<Map<String, Object>> getCatalogTracks(String query, String status) {
        Query q = new Query();
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            if (status.equalsIgnoreCase("published")) {
                q.addCriteria(Criteria.where("moderation_status").is("approved"));
            } else if (status.equalsIgnoreCase("flagged")) {
                q.addCriteria(Criteria.where("moderation_status").in("flagged", "pending"));
            } else if (status.equalsIgnoreCase("taken_down")) {
                q.addCriteria(Criteria.where("moderation_status").is("taken_down"));
            }
        }

        if (query != null && !query.isBlank()) {
            String regex = "(?i).*" + query.trim() + ".*";
            q.addCriteria(new Criteria().orOperator(
                    Criteria.where("name").regex(regex),
                    Criteria.where("artist_name").regex(regex),
                    Criteria.where("album_name").regex(regex)
            ));
        }

        q.with(Sort.by(Sort.Direction.DESC, "popularity", "created_at"));


        List<Track> list = mongoTemplate.find(q, Track.class);
        List<Map<String, Object>> result = new ArrayList<>();

        List<String> trackIds = list.stream().flatMap(t -> java.util.stream.Stream.of(t.getId(), t.getSpotifyId())).filter(Objects::nonNull).distinct().toList();
        Map<String, Long> playsMap = new HashMap<>();
        Map<String, Long> likesMap = new HashMap<>();

        if (!trackIds.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(trackIds.size(), "?"));
            {
                String playsSql = "SELECT track_id, COUNT(*) as cnt FROM listening_history WHERE track_id IN (" + inSql + ") GROUP BY track_id";
                jdbcTemplate.query(playsSql, trackIds.toArray(), (rs, rowNum) -> {
                    playsMap.put(rs.getString("track_id"), rs.getLong("cnt"));
                    return null;
                });
            }

            {
                String likesSql = "SELECT f.track_id, COUNT(*) as cnt FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f WHERE f.track_id IN (" + inSql + ") GROUP BY f.track_id";
                jdbcTemplate.query(likesSql, trackIds.toArray(), (rs, rowNum) -> {
                    likesMap.put(rs.getString("track_id"), rs.getLong("cnt"));
                    return null;
                });
            }
        }

        for (Track t : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("spotifyId", t.getSpotifyId() != null ? t.getSpotifyId() : t.getId());
            map.put("title", t.getName() != null ? t.getName() : "Không tên");
            map.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
            map.put("album", t.getAlbumName() != null ? t.getAlbumName() : "Chưa có thông tin album");
            map.put("genre", resolveTrackGenre(t));
            map.put("duration", formatTrackDuration(t));
            map.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
            map.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            map.put("plays", playsMap.getOrDefault(t.getId(), 0L) + (!Objects.equals(t.getId(), t.getSpotifyId()) ? playsMap.getOrDefault(t.getSpotifyId(), 0L) : 0L));
            map.put("likes", likesMap.getOrDefault(t.getId(), 0L) + (!Objects.equals(t.getId(), t.getSpotifyId()) ? likesMap.getOrDefault(t.getSpotifyId(), 0L) : 0L));

            // Moderation & Audio Features
            String modStatus = t.getModerationStatus() != null ? t.getModerationStatus().toLowerCase().trim() : "published";
            if (modStatus.equals("taken_down")) {
                map.put("status", "taken_down");
            } else if (modStatus.equals("flagged") || modStatus.equals("pending")) {
                map.put("status", "flagged");
            } else {
                map.put("status", "published");
            }

            map.put("moderationScore", t.getModerationScore());
            Track.AudioFeatures af = t.getAudioFeatures();
            map.put("bpm", af != null ? af.getBpm() : null);
            map.put("energy", af != null ? af.getEnergy() : null);
            map.put("danceability", af != null ? af.getDanceability() : null);
            map.put("valence", af != null ? af.getValence() : null);
            map.put("acousticness", af != null ? af.getAcousticness() : null);
            map.put("keySignature", af != null ? af.getKeySignature() : null);
            map.put("vibeCategory", null);

            map.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
            result.add(map);
        }

        return result;
    }

    public void takedownTrack(String trackId) {
        Query q = new Query(Criteria.where("_id").is(trackId));
        Update u = new Update().set("moderation_status", "taken_down");
        mongoTemplate.updateFirst(q, u, Track.class);
    }

    public void restoreTrack(String trackId) {
        Query q = new Query(Criteria.where("_id").is(trackId));
        Update u = new Update().set("moderation_status", "approved");
        mongoTemplate.updateFirst(q, u, Track.class);
    }

    public void updateTrackGenre(String trackId, String genre) {
        if (genre == null || genre.isBlank()) return;
        Query q = new Query(Criteria.where("_id").is(trackId));
        Update u = new Update().set("genres", List.of(genre.trim()));
        mongoTemplate.updateFirst(q, u, Track.class);
    }

    @Transactional
    public void deleteTrack(String trackId) {
        if(catalogIntegrity==null)throw new IllegalStateException("Catalog integrity service unavailable");
        catalogIntegrity.retire(trackId);
    }

    private String resolveTrackGenre(Track t) {
        if (t.getGenres() != null) {
            for (String g : t.getGenres()) {
                if (g != null && !g.equalsIgnoreCase("other") && !g.isBlank()) {
                    return formatGenreName(g);
                }
            }
        }
        if (t.getGenresRaw() != null) {
            for (String r : t.getGenresRaw()) {
                if (r != null && !r.equalsIgnoreCase("other") && !r.isBlank()) {
                    return formatGenreName(r);
                }
            }
        }
        return "Chưa phân loại";
    }

    private String formatGenreName(String raw) {
        if (raw == null || raw.isBlank()) return "Chưa phân loại";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("vpop") || lower.contains("v-pop") || lower.equals("vietnamese pop")) return "V-Pop";
        if (lower.contains("rap") || lower.contains("hiphop") || lower.contains("hip-hop")) return "Rap / Hip-Hop";
        if (lower.contains("indie")) return "Indie";
        if (lower.contains("r&b") || lower.contains("rnb")) return "R&B / Soul";
        if (lower.contains("edm") || lower.contains("remix") || lower.contains("dance")) return "EDM / Remix";
        if (lower.contains("ballad")) return "Ballad";
        if (lower.contains("lo-fi") || lower.contains("lofi")) return "Lo-Fi";
        if (lower.contains("rock")) return "Rock";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }


    // ==========================================
    // 4. CONTENT MODERATION QUEUE (MySQL + Mongo)
    // ==========================================
    public List<Map<String, Object>> getModerationQueue() {
        String sql =
                "SELECT r.id, r.artist_user_id, u.full_name as artist_name, r.content_type, r.content_id, " +
                "r.request_type, r.status, r.submitted_at, r.resolved_at " +
                "FROM content_review_requests r " +
                "JOIN users u ON r.artist_user_id = u.id " +
                "ORDER BY r.submitted_at DESC LIMIT 100";

        List<Map<String, Object>> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            map.put("artistUserId", rs.getLong("artist_user_id"));
            map.put("artistName", rs.getString("artist_name"));
            map.put("contentType", rs.getString("content_type"));
            String contentId = rs.getString("content_id");
            map.put("contentId", contentId);
            map.put("requestType", rs.getString("request_type"));
            map.put("status", rs.getString("status"));
            map.put("submittedAt", rs.getTimestamp("submitted_at") != null
                    ? rs.getTimestamp("submitted_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            map.put("resolvedAt", rs.getTimestamp("resolved_at") != null
                    ? rs.getTimestamp("resolved_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : null);

            // Fetch Mongo track metadata
            Track track = findAdminTrack(contentId);
            if (track != null) {
                map.put("title", track.getName());
                map.put("coverUrl", resolveAdminImageUrl(track.getImageUrl()));
                map.put("genre", resolveTrackGenre(track));
                map.put("lyricsPlain", track.getLyricsPlain());
                map.put("audioUrl", resolveAudioUrl(track.getLocalPath()));

                if (track.getAudioFeatures() != null) {
                    Map<String, Object> af = new HashMap<>();
                    af.put("bpm", track.getAudioFeatures().getBpm());
                    af.put("energy", track.getAudioFeatures().getEnergy());
                    af.put("danceability", track.getAudioFeatures().getDanceability());
                    af.put("valence", track.getAudioFeatures().getValence());
                    af.put("acousticness", track.getAudioFeatures().getAcousticness());
                    af.put("keySignature", track.getAudioFeatures().getKeySignature());
                    map.put("audioFeatures", af);
                }
            } else {
                map.put("title", "Không tìm thấy thông tin bài hát");
                map.put("coverUrl", "/images/cover-placeholder.svg");
                map.put("genre", "Chưa phân loại");
            }
            return map;
        });

        return list;
    }

    @Transactional
    public void submitReviewDecision(Long requestId, String action, String reason, String moderatorUsername) {
        String newStatus;
        if ("APPROVE".equalsIgnoreCase(action)) {
            newStatus = "APPROVED";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            newStatus = "REJECTED";
        } else {
            newStatus = "PENDING";
        }

        // 1. Update MySQL review request
        jdbcTemplate.update(
                "UPDATE content_review_requests SET status = ?, resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                newStatus, requestId
        );

        // 2. Find moderator ID
        Long modId = 3L;
        try {
            Long foundMod = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE username = ? LIMIT 1", Long.class, moderatorUsername);
            if (foundMod != null) modId = foundMod;
        } catch (Exception ignored) {}

        // 3. Insert into content_review_actions
        jdbcTemplate.update(
                "INSERT INTO content_review_actions (review_request_id, moderator_user_id, action, reason) " +
                "VALUES (?, ?, ?, ?)",
                requestId, modId, action.toUpperCase().trim(), reason != null ? reason : "Kiểm duyệt bởi Admin"
        );

        // 4. Update track in MongoDB if track id found
        try {
            String contentId = jdbcTemplate.queryForObject(
                    "SELECT content_id FROM content_review_requests WHERE id = ?", String.class, requestId);
            if (contentId != null) {
                String mongoModStatus = "APPROVED".equals(newStatus) ? "approved" : "rejected";
                Query q = new Query(Criteria.where("_id").is(contentId));
                Update u = new Update().set("moderation_status", mongoModStatus);
                mongoTemplate.updateFirst(q, u, Track.class);
            }
        } catch (Exception ignored) {}
    }

    public List<Map<String, Object>> getModerationActions() {
        String sql =
                "SELECT a.id, a.review_request_id, a.moderator_user_id, u.full_name as moderator_name, " +
                "a.action, a.reason, a.created_at " +
                "FROM content_review_actions a " +
                "LEFT JOIN users u ON a.moderator_user_id = u.id " +
                "ORDER BY a.created_at DESC LIMIT 100";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            map.put("reviewRequestId", rs.getLong("review_request_id"));
            map.put("moderatorUserId", rs.getLong("moderator_user_id"));
            map.put("moderatorName", rs.getString("moderator_name") != null ? rs.getString("moderator_name") : "Hệ thống");
            map.put("action", rs.getString("action"));
            map.put("reason", rs.getString("reason"));
            map.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "01/09/2026 12:00");
            return map;
        });
    }

    // ==========================================
    // ==========================================
    // 5. PACKAGES & MONETIZATION (MySQL)
    // ==========================================
    public List<Map<String, Object>> getPackages() {
        String sql =
                "SELECT p.id, p.name, p.description, p.price, p.duration_days, p.display_order, p.status, p.tier_id, " +
                "       t.name as tier_name, t.description as tier_description, t.ad_policy, t.ad_free_daily_limit, " +
                "       t.skip_policy, t.skip_daily_limit, t.offline_allowed, t.offline_max_tracks, " +
                "       t.max_devices, t.synced_lyrics, t.vip_badge, t.family_sharing, t.family_members, " +
                "       COUNT(s.id) as subscribers_count " +
                "FROM service_packages p " +
                "LEFT JOIN subscription_tiers t ON p.tier_id = t.id " +
                "LEFT JOIN subscriptions s ON p.id = s.service_package_id AND s.status = 'ACTIVE' " +
                "GROUP BY p.id, t.id " +
                "ORDER BY p.display_order ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> p = new HashMap<>();
            p.put("id", rs.getLong("id"));
            p.put("name", rs.getString("name"));
            p.put("description", rs.getString("description"));
            p.put("price", rs.getDouble("price"));
            p.put("durationDays", rs.getInt("duration_days"));
            p.put("displayOrder", rs.getInt("display_order"));
            p.put("status", rs.getString("status"));
            p.put("tierId", rs.getString("tier_id"));
            p.put("tierName", rs.getString("tier_name"));
            p.put("subscribersCount", rs.getInt("subscribers_count"));

            // Ánh xạ quyền lợi từ bảng subscription_tiers
            Map<String, Object> tierRow = new HashMap<>();
            tierRow.put("id", rs.getString("tier_id"));
            tierRow.put("tier_name", rs.getString("tier_name"));
            tierRow.put("ad_policy", rs.getString("ad_policy"));
            tierRow.put("ad_free_daily_limit", rs.getInt("ad_free_daily_limit"));
            tierRow.put("skip_policy", rs.getString("skip_policy"));
            tierRow.put("skip_daily_limit", rs.getInt("skip_daily_limit"));
            tierRow.put("offline_allowed", rs.getBoolean("offline_allowed"));
            tierRow.put("offline_max_tracks", rs.getInt("offline_max_tracks"));
            tierRow.put("max_devices", rs.getInt("max_devices"));
            tierRow.put("synced_lyrics", rs.getBoolean("synced_lyrics"));
            tierRow.put("vip_badge", rs.getBoolean("vip_badge"));
            tierRow.put("family_sharing", rs.getBoolean("family_sharing"));
            tierRow.put("family_members", rs.getInt("family_members"));

            Map<String, Object> entitlements = SubscriptionService.mapTierToEntitlements(tierRow);
            p.put("entitlements", entitlements);

            try {
                p.put("featuresJson", objectMapper.writeValueAsString(entitlements));
            } catch (Exception e) {
                p.put("featuresJson", "{}");
            }

            return p;
        });
    }

    public void updatePackagePrice(Long packageId, Double newPrice) {
        jdbcTemplate.update("UPDATE service_packages SET price = ?, updated_at = NOW() WHERE id = ?", newPrice, packageId);
    }

    public void createPackage(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "Gói Cước Mới");
        String description = (String) data.getOrDefault("description", "");

        Double price = 0.0;
        if (data.get("price") instanceof Number) {
            price = ((Number) data.get("price")).doubleValue();
        } else if (data.get("price") != null) {
            try { price = Double.parseDouble(data.get("price").toString().trim()); } catch (Exception ignored) {}
        }

        Integer durationDays = 30;
        if (data.get("durationDays") instanceof Number) {
            durationDays = ((Number) data.get("durationDays")).intValue();
        } else if (data.get("durationDays") != null) {
            try { durationDays = Integer.parseInt(data.get("durationDays").toString().trim()); } catch (Exception ignored) {}
        }

        Integer displayOrder = 1;
        if (data.get("displayOrder") instanceof Number) {
            displayOrder = ((Number) data.get("displayOrder")).intValue();
        } else if (data.get("displayOrder") != null) {
            try { displayOrder = Integer.parseInt(data.get("displayOrder").toString().trim()); } catch (Exception ignored) {}
        }

        String status = (String) data.getOrDefault("status", "ACTIVE");
        String tierId = (String) data.get("tierId");
        if (tierId == null || tierId.isBlank()) {
            // Thử lấy từ entitlements nếu có
            if (data.get("entitlements") instanceof Map) {
                tierId = (String) ((Map<?, ?>) data.get("entitlements")).get("tier");
            }
        }
        if (tierId == null || tierId.isBlank()) {
            tierId = "INDIVIDUAL_BASIC";
        }

        String sql = "INSERT INTO service_packages (name, description, price, duration_days, display_order, status, tier_id, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        jdbcTemplate.update(sql, name, description, price, durationDays, displayOrder, status.toUpperCase(), tierId);
    }

    public void updatePackageDetails(Long packageId, Map<String, Object> data) {
        String name = (String) data.get("name");
        String description = (String) data.get("description");

        Double price = null;
        if (data.get("price") instanceof Number) {
            price = ((Number) data.get("price")).doubleValue();
        } else if (data.get("price") != null) {
            try { price = Double.parseDouble(data.get("price").toString().trim()); } catch (Exception ignored) {}
        }

        Integer durationDays = null;
        if (data.get("durationDays") instanceof Number) {
            durationDays = ((Number) data.get("durationDays")).intValue();
        } else if (data.get("durationDays") != null) {
            try { durationDays = Integer.parseInt(data.get("durationDays").toString().trim()); } catch (Exception ignored) {}
        }

        Integer displayOrder = null;
        if (data.get("displayOrder") instanceof Number) {
            displayOrder = ((Number) data.get("displayOrder")).intValue();
        } else if (data.get("displayOrder") != null) {
            try { displayOrder = Integer.parseInt(data.get("displayOrder").toString().trim()); } catch (Exception ignored) {}
        }

        String status = (String) data.get("status");
        String tierId = (String) data.get("tierId");
        if (tierId == null || tierId.isBlank()) {
            if (data.get("entitlements") instanceof Map) {
                tierId = (String) ((Map<?, ?>) data.get("entitlements")).get("tier");
            }
        }

        String sql = "UPDATE service_packages SET " +
                     "name = COALESCE(?, name), " +
                     "description = COALESCE(?, description), " +
                     "price = COALESCE(?, price), " +
                     "duration_days = COALESCE(?, duration_days), " +
                     "display_order = COALESCE(?, display_order), " +
                     "status = COALESCE(?, status), " +
                     "tier_id = COALESCE(?, tier_id), " +
                     "updated_at = NOW() " +
                     "WHERE id = ?";
        jdbcTemplate.update(sql, name, description, price, durationDays, displayOrder, status != null ? status.toUpperCase() : null, tierId, packageId);
    }

    public void togglePackageStatus(Long packageId) {
        String sql = "UPDATE service_packages SET status = CASE WHEN status = 'ACTIVE' THEN 'INACTIVE' ELSE 'ACTIVE' END, updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, packageId);
    }

    /**
     * Lấy danh sách toàn bộ các tầng quyền hạn (subscription_tiers) đã chuẩn hóa.
     */
    private boolean toBooleanValue(Object val, boolean fallback) {
        if (val == null) return fallback;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        String s = val.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private int toIntValue(Object val, int fallback) {
        if (val == null) return fallback;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double toDoubleValue(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private long toLongValue(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public List<Map<String, Object>> getSubscriptionTiers() {
        String sql = "SELECT * FROM subscription_tiers ORDER BY FIELD(id, 'FREE', 'INDIVIDUAL_BASIC', 'INDIVIDUAL_FULL', 'FAMILY'), id ASC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("id", row.get("id"));
            tier.put("name", row.get("name"));
            tier.put("description", row.get("description") != null ? row.get("description") : "");
            tier.put("adPolicy", row.get("ad_policy") != null ? row.get("ad_policy") : "NO_ADS");
            tier.put("adFreeDailyLimit", toIntValue(row.get("ad_free_daily_limit"), 0));
            tier.put("skipPolicy", row.get("skip_policy") != null ? row.get("skip_policy") : "UNLIMITED");
            tier.put("skipDailyLimit", toIntValue(row.get("skip_daily_limit"), 0));
            tier.put("offlineAllowed", toBooleanValue(row.get("offline_allowed"), false));
            tier.put("offlineMaxTracks", toIntValue(row.get("offline_max_tracks"), 0));
            tier.put("maxDevices", Math.max(1, toIntValue(row.get("max_devices"), 1)));
            tier.put("syncedLyrics", toBooleanValue(row.get("synced_lyrics"), false));
            tier.put("vipBadge", toBooleanValue(row.get("vip_badge"), false));
            tier.put("familySharing", toBooleanValue(row.get("family_sharing"), false));
            tier.put("familyMembers", toIntValue(row.get("family_members"), 0));
            tier.put("entitlements", SubscriptionService.mapTierToEntitlements(row));
            result.add(tier);
        }
        return result;
    }

    /**
     * Cập nhật trực tiếp quyền lợi và giới hạn của một tầng dịch vụ (subscription_tiers).
     * Mọi gói cước gắn tier_id này sẽ ngay lập tức thừa hưởng cấu hình mới nhất mà không bị lệch dữ liệu.
     */
    public void updateSubscriptionTier(String tierId, Map<String, Object> data) {
        String name = data.get("name") != null ? data.get("name").toString().trim() : null;
        String description = data.get("description") != null ? data.get("description").toString().trim() : null;
        String adPolicy = data.get("adPolicy") != null ? data.get("adPolicy").toString().trim() : null;
        Integer adFreeDailyLimit = data.get("adFreeDailyLimit") != null ? ((Number) data.get("adFreeDailyLimit")).intValue() : null;
        String skipPolicy = data.get("skipPolicy") != null ? data.get("skipPolicy").toString().trim() : null;
        Integer skipDailyLimit = data.get("skipDailyLimit") != null ? ((Number) data.get("skipDailyLimit")).intValue() : null;
        Boolean offlineAllowed = data.get("offlineAllowed") != null ? Boolean.parseBoolean(data.get("offlineAllowed").toString()) : null;
        Integer offlineMaxTracks = data.get("offlineMaxTracks") != null ? ((Number) data.get("offlineMaxTracks")).intValue() : null;
        Integer maxDevices = data.get("maxDevices") != null ? ((Number) data.get("maxDevices")).intValue() : null;
        Boolean syncedLyrics = data.get("syncedLyrics") != null ? Boolean.parseBoolean(data.get("syncedLyrics").toString()) : null;
        Boolean vipBadge = data.get("vipBadge") != null ? Boolean.parseBoolean(data.get("vipBadge").toString()) : null;
        Boolean familySharing = data.get("familySharing") != null ? Boolean.parseBoolean(data.get("familySharing").toString()) : null;
        Integer familyMembers = data.get("familyMembers") != null ? ((Number) data.get("familyMembers")).intValue() : null;

        if (maxDevices != null && maxDevices < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số thiết bị đồng thời tối thiểu là 1");
        }
        if ((adFreeDailyLimit != null && adFreeDailyLimit < 0)
                || (skipDailyLimit != null && skipDailyLimit < 0)
                || (offlineMaxTracks != null && offlineMaxTracks < 0)
                || (familyMembers != null && familyMembers < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Các giới hạn số lượng không được âm");
        }

        String sql = "UPDATE subscription_tiers SET " +
                "name = COALESCE(?, name), " +
                "description = COALESCE(?, description), " +
                "ad_policy = COALESCE(?, ad_policy), " +
                "ad_free_daily_limit = COALESCE(?, ad_free_daily_limit), " +
                "skip_policy = COALESCE(?, skip_policy), " +
                "skip_daily_limit = COALESCE(?, skip_daily_limit), " +
                "offline_allowed = COALESCE(?, offline_allowed), " +
                "offline_max_tracks = COALESCE(?, offline_max_tracks), " +
                "max_devices = COALESCE(?, max_devices), " +
                "synced_lyrics = COALESCE(?, synced_lyrics), " +
                "vip_badge = COALESCE(?, vip_badge), " +
                "family_sharing = COALESCE(?, family_sharing), " +
                "family_members = COALESCE(?, family_members), " +
                "updated_at = NOW() " +
                "WHERE id = ?";

        int updated = jdbcTemplate.update(sql, name, description, adPolicy, adFreeDailyLimit, skipPolicy, skipDailyLimit,
                offlineAllowed, offlineMaxTracks, maxDevices, syncedLyrics, vipBadge,
                familySharing, familyMembers, tierId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tầng quyền lợi: " + tierId);
        }
    }

    /**
     * Xóa gói cước an toàn: nếu gói đã phát sinh lịch sử thanh toán thì từ chối
     * xóa cứng (tránh mất dữ liệu doanh thu), admin nên chuyển gói sang INACTIVE.
     */
    @Transactional
    public void deletePackage(Long packageId) {
        Long paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions t " +
                "JOIN subscriptions s ON t.subscription_id = s.id " +
                "WHERE s.service_package_id = ?", Long.class, packageId);
        if (paymentCount != null && paymentCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Gói này đã phát sinh " + paymentCount + " giao dịch thanh toán nên không thể xóa. " +
                    "Vui lòng chuyển gói sang trạng thái INACTIVE để ngừng bán thay vì xóa.");
        }
        jdbcTemplate.update("DELETE FROM subscriptions WHERE service_package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM service_packages WHERE id = ?", packageId);
    }

    public List<Map<String, Object>> getTransactions() {
        String sql =
                "SELECT t.id, t.subscription_id, u.full_name as user_name, u.email as user_email, " +
                "p.name as package_name, t.amount, t.payment_method, t.provider, " +
                "t.provider_transaction_id, t.status, t.paid_at, t.created_at " +
                "FROM payment_transactions t " +
                "JOIN subscriptions s ON t.subscription_id = s.id " +
                "JOIN users u ON s.user_id = u.id " +
                "JOIN service_packages p ON s.service_package_id = p.id " +
                "ORDER BY t.created_at DESC LIMIT 500";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> t = new HashMap<>();
            t.put("id", rs.getLong("id"));
            t.put("subscriptionId", rs.getLong("subscription_id"));
            t.put("userName", rs.getString("user_name"));
            t.put("userEmail", rs.getString("user_email"));
            t.put("packageName", rs.getString("package_name"));
            t.put("amount", rs.getDouble("amount"));
            t.put("paymentMethod", rs.getString("payment_method") != null ? rs.getString("payment_method") : "Chưa có thông tin");
            t.put("provider", rs.getString("provider") != null ? rs.getString("provider") : "Chưa có thông tin");
            t.put("providerTransactionId", rs.getString("provider_transaction_id") != null ? rs.getString("provider_transaction_id") : "");
            t.put("status", rs.getString("status"));
            java.sql.Timestamp paidTs = rs.getTimestamp("paid_at");
            t.put("paidAt", paidTs != null
                    ? paidTs.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            java.sql.Timestamp createdTs = rs.getTimestamp("created_at");
            t.put("createdAt", createdTs != null
                    ? createdTs.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            return t;
        });
    }

    public Map<String, Object> getRevenueStats(int days) {
        int safeDays = (days == 7 || days == 30 || days == 90) ? days : 30;
        int intervalOffset = safeDays - 1;
        int previousIntervalStart = (safeDays * 2) - 1;

        double totalRevenue = toDoubleValue(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_transactions WHERE status = 'SUCCESS'",
                Double.class
        ));

        Map<String, Object> periodSummary = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(amount), 0) as period_revenue, COUNT(*) as period_success_count " +
                "FROM payment_transactions WHERE status = 'SUCCESS' " +
                "AND DATE(COALESCE(paid_at, created_at)) >= DATE_SUB(CURDATE(), INTERVAL ? DAY)",
                intervalOffset
        );
        double periodRevenue = toDoubleValue(periodSummary.get("period_revenue"));
        long periodSuccessCount = toLongValue(periodSummary.get("period_success_count"));

        double previousPeriodRevenue = toDoubleValue(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_transactions WHERE status = 'SUCCESS' " +
                "AND DATE(COALESCE(paid_at, created_at)) >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "AND DATE(COALESCE(paid_at, created_at)) < DATE_SUB(CURDATE(), INTERVAL ? DAY)",
                Double.class,
                previousIntervalStart,
                intervalOffset
        ));

        double growthPercent = 0.0;
        if (previousPeriodRevenue > 0) {
            growthPercent = Math.round(((periodRevenue - previousPeriodRevenue) / previousPeriodRevenue) * 1000.0) / 10.0;
        } else if (periodRevenue > 0) {
            growthPercent = 100.0;
        }

        List<Map<String, Object>> statusRows = jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) as cnt FROM payment_transactions GROUP BY status"
        );
        long successCount = 0;
        long pendingCount = 0;
        long failedCount = 0;
        long refundedCount = 0;
        for (Map<String, Object> row : statusRows) {
            String st = row.get("status") != null ? row.get("status").toString().toUpperCase(Locale.ROOT) : "";
            long cnt = toLongValue(row.get("cnt"));
            if ("SUCCESS".equals(st)) {
                successCount += cnt;
            } else if ("PENDING".equals(st)) {
                pendingCount += cnt;
            } else if ("FAILED".equals(st) || "CANCELLED".equals(st)) {
                failedCount += cnt;
            } else if ("REFUNDED".equals(st)) {
                refundedCount += cnt;
            }
        }
        long totalTransactions = successCount + pendingCount + failedCount + refundedCount;
        double avgOrderValue = periodSuccessCount > 0
                ? Math.round(periodRevenue / periodSuccessCount)
                : (successCount > 0 ? Math.round(totalRevenue / successCount) : 0.0);

        long activeSubscriptionsCount = toLongValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM subscriptions WHERE status = 'ACTIVE'",
                Long.class
        ));
        long paidUsersCount = toLongValue(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT s.user_id) FROM payment_transactions t " +
                "JOIN subscriptions s ON t.subscription_id = s.id WHERE t.status = 'SUCCESS'",
                Long.class
        ));

        List<Map<String, Object>> rawDailyRows = jdbcTemplate.queryForList(
                "SELECT DATE(COALESCE(t.paid_at, t.created_at)) as tx_date, " +
                "COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.amount ELSE 0 END), 0) as revenue, " +
                "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) as success_count, " +
                "SUM(CASE WHEN t.status = 'PENDING' THEN 1 ELSE 0 END) as pending_count, " +
                "SUM(CASE WHEN t.status IN ('FAILED', 'CANCELLED') THEN 1 ELSE 0 END) as failed_count, " +
                "SUM(CASE WHEN t.status = 'REFUNDED' THEN 1 ELSE 0 END) as refunded_count " +
                "FROM payment_transactions t " +
                "WHERE DATE(COALESCE(t.paid_at, t.created_at)) >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "GROUP BY DATE(COALESCE(t.paid_at, t.created_at)) " +
                "ORDER BY tx_date ASC",
                intervalOffset
        );

        Map<String, Map<String, Object>> dailyMap = new HashMap<>();
        for (Map<String, Object> row : rawDailyRows) {
            Object dateObj = row.get("tx_date");
            if (dateObj == null) continue;
            String dateStr = dateObj.toString();
            String isoKey = dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr;
            dailyMap.put(isoKey, row);
        }

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(intervalOffset);
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("dd/MM");
        List<Map<String, Object>> dailySeries = new ArrayList<>(safeDays);
        for (LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            String isoKey = d.toString();
            Map<String, Object> match = dailyMap.get(isoKey);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("isoDate", isoKey);
            point.put("date", d.format(labelFmt));
            point.put("revenue", match != null ? toDoubleValue(match.get("revenue")) : 0.0);
            point.put("successCount", match != null ? toLongValue(match.get("success_count")) : 0L);
            point.put("pendingCount", match != null ? toLongValue(match.get("pending_count")) : 0L);
            point.put("failedCount", match != null ? toLongValue(match.get("failed_count")) : 0L);
            point.put("refundedCount", match != null ? toLongValue(match.get("refunded_count")) : 0L);
            dailySeries.add(point);
        }

        List<Map<String, Object>> rawPackageRows = jdbcTemplate.queryForList(
                "SELECT p.id as package_id, p.name as package_name, p.tier_id, p.price as package_price, p.status as package_status, " +
                "COUNT(CASE WHEN t.status = 'SUCCESS' THEN 1 END) as success_count, " +
                "COUNT(t.id) as total_count, " +
                "COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.amount ELSE 0 END), 0) as revenue, " +
                "(SELECT COUNT(*) FROM subscriptions s2 WHERE s2.service_package_id = p.id AND s2.status = 'ACTIVE') as active_subs " +
                "FROM service_packages p " +
                "LEFT JOIN subscriptions s ON s.service_package_id = p.id " +
                "LEFT JOIN payment_transactions t ON t.subscription_id = s.id " +
                "GROUP BY p.id, p.name, p.tier_id, p.price, p.status " +
                "ORDER BY revenue DESC, active_subs DESC, p.display_order ASC"
        );
        List<Map<String, Object>> byPackage = new ArrayList<>(rawPackageRows.size());
        for (Map<String, Object> row : rawPackageRows) {
            double pkgRevenue = toDoubleValue(row.get("revenue"));
            double sharePercent = totalRevenue > 0
                    ? Math.round((pkgRevenue / totalRevenue) * 1000.0) / 10.0
                    : 0.0;
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("packageId", toLongValue(row.get("package_id")));
            pkg.put("packageName", row.get("package_name"));
            pkg.put("tierId", row.get("tier_id"));
            pkg.put("packagePrice", toDoubleValue(row.get("package_price")));
            pkg.put("packageStatus", row.get("package_status"));
            pkg.put("successCount", toLongValue(row.get("success_count")));
            pkg.put("totalCount", toLongValue(row.get("total_count")));
            pkg.put("revenue", pkgRevenue);
            pkg.put("activeSubscriptions", toLongValue(row.get("active_subs")));
            pkg.put("sharePercent", sharePercent);
            byPackage.add(pkg);
        }

        List<Map<String, Object>> rawProviderRows = jdbcTemplate.queryForList(
                "SELECT COALESCE(NULLIF(TRIM(t.provider), ''), 'OTHER') as provider_name, " +
                "COALESCE(NULLIF(TRIM(t.payment_method), ''), 'OTHER') as payment_method, " +
                "COUNT(CASE WHEN t.status = 'SUCCESS' THEN 1 END) as success_count, " +
                "COUNT(t.id) as total_count, " +
                "COALESCE(SUM(CASE WHEN t.status = 'SUCCESS' THEN t.amount ELSE 0 END), 0) as revenue " +
                "FROM payment_transactions t " +
                "GROUP BY COALESCE(NULLIF(TRIM(t.provider), ''), 'OTHER'), COALESCE(NULLIF(TRIM(t.payment_method), ''), 'OTHER') " +
                "ORDER BY revenue DESC, total_count DESC"
        );
        List<Map<String, Object>> byProvider = new ArrayList<>(rawProviderRows.size());
        for (Map<String, Object> row : rawProviderRows) {
            double provRevenue = toDoubleValue(row.get("revenue"));
            double sharePercent = totalRevenue > 0
                    ? Math.round((provRevenue / totalRevenue) * 1000.0) / 10.0
                    : 0.0;
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("provider", row.get("provider_name"));
            prov.put("paymentMethod", row.get("payment_method"));
            prov.put("successCount", toLongValue(row.get("success_count")));
            prov.put("totalCount", toLongValue(row.get("total_count")));
            prov.put("revenue", provRevenue);
            prov.put("sharePercent", sharePercent);
            byProvider.add(prov);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("periodDays", safeDays);
        res.put("totalRevenue", totalRevenue);
        res.put("periodRevenue", periodRevenue);
        res.put("previousPeriodRevenue", previousPeriodRevenue);
        res.put("growthPercent", growthPercent);
        res.put("avgOrderValue", avgOrderValue);
        res.put("totalTransactions", totalTransactions);
        res.put("successCount", successCount);
        res.put("periodSuccessCount", periodSuccessCount);
        res.put("pendingCount", pendingCount);
        res.put("failedCount", failedCount);
        res.put("refundedCount", refundedCount);
        res.put("activeSubscriptionsCount", activeSubscriptionsCount);
        res.put("paidUsersCount", paidUsersCount);
        res.put("dailySeries", dailySeries);
        res.put("byPackage", byPackage);
        res.put("byProvider", byProvider);
        return res;
    }

    public List<Map<String, Object>> getSubscriptions() {
        String sql =
                "SELECT s.id, s.user_id, s.service_package_id, s.start_at, s.end_at, s.auto_renew, s.status, s.created_at, " +
                "u.full_name as user_name, u.email as user_email, " +
                "p.name as package_name, p.tier_id, p.price, t.name as tier_name " +
                "FROM subscriptions s " +
                "JOIN users u ON s.user_id = u.id " +
                "JOIN service_packages p ON s.service_package_id = p.id " +
                "LEFT JOIN subscription_tiers t ON p.tier_id = t.id " +
                "ORDER BY s.created_at DESC LIMIT 100";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> sub = new HashMap<>();
            sub.put("id", rs.getLong("id"));
            sub.put("userId", rs.getLong("user_id"));
            sub.put("userName", rs.getString("user_name"));
            sub.put("userEmail", rs.getString("user_email"));
            sub.put("servicePackageId", rs.getLong("service_package_id"));
            sub.put("packageName", rs.getString("package_name"));
            sub.put("tierId", rs.getString("tier_id") != null ? rs.getString("tier_id") : "");
            sub.put("tierName", rs.getString("tier_name") != null ? rs.getString("tier_name") : "Chưa có thông tin tầng");
            sub.put("price", rs.getDouble("price"));
            sub.put("autoRenew", rs.getBoolean("auto_renew"));
            sub.put("status", rs.getString("status"));
            sub.put("startAt", rs.getTimestamp("start_at") != null
                    ? rs.getTimestamp("start_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            sub.put("endAt", rs.getTimestamp("end_at") != null
                    ? rs.getTimestamp("end_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            sub.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            return sub;
        });
    }

    public List<Map<String, Object>> getUserSubscriptions(Long userId) {
        String sql =
                "SELECT s.id, s.user_id, s.service_package_id, s.start_at, s.end_at, s.auto_renew, s.status, s.created_at, " +
                "p.name as package_name, p.tier_id, p.price, t.name as tier_name " +
                "FROM subscriptions s " +
                "JOIN service_packages p ON s.service_package_id = p.id " +
                "LEFT JOIN subscription_tiers t ON p.tier_id = t.id " +
                "WHERE s.user_id = ? " +
                "ORDER BY s.created_at DESC";

        return jdbcTemplate.query(sql, new Object[]{userId}, (rs, rowNum) -> {
            Map<String, Object> sub = new HashMap<>();
            sub.put("id", rs.getLong("id"));
            sub.put("userId", rs.getLong("user_id"));
            sub.put("servicePackageId", rs.getLong("service_package_id"));
            sub.put("packageName", rs.getString("package_name"));
            sub.put("tierId", rs.getString("tier_id") != null ? rs.getString("tier_id") : "");
            sub.put("tierName", rs.getString("tier_name") != null ? rs.getString("tier_name") : "Chưa có thông tin tầng");
            sub.put("price", rs.getDouble("price"));
            sub.put("autoRenew", rs.getBoolean("auto_renew"));
            sub.put("status", rs.getString("status"));
            sub.put("startAt", rs.getTimestamp("start_at") != null
                    ? rs.getTimestamp("start_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            sub.put("endAt", rs.getTimestamp("end_at") != null
                    ? rs.getTimestamp("end_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            sub.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");
            return sub;
        });
    }

    /**
     * Hủy gói dịch vụ người dùng (chuyển trạng thái sang CANCELLED).
     */
    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        int updated = jdbcTemplate.update(
                "UPDATE subscriptions SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?",
                subscriptionId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói đăng ký #" + subscriptionId);
        }
    }

    // ==========================================
    // 6. LICENSING & DISTRIBUTORS (MySQL)
    // ==========================================
    public Map<String, Object> getLicensingData() {
        Map<String, Object> result = new HashMap<>();

        // Distributors
        String sqlDist =
                "SELECT d.id, d.company_name, d.country, d.contact_name, d.contact_email, d.contact_phone, d.status, " +
                "COUNT(c.id) as contract_count " +
                "FROM distributors d " +
                "LEFT JOIN distribution_contracts c ON d.id = c.distributor_id " +
                "GROUP BY d.id ORDER BY d.id ASC";

        List<Map<String, Object>> distList = jdbcTemplate.query(sqlDist, (rs, rowNum) -> {
            Map<String, Object> d = new HashMap<>();
            d.put("id", rs.getLong("id"));
            d.put("companyName", rs.getString("company_name"));
            d.put("country", rs.getString("country"));
            d.put("contactName", rs.getString("contact_name") != null ? rs.getString("contact_name") : "Đại diện pháp lý");
            d.put("contactEmail", rs.getString("contact_email") != null ? rs.getString("contact_email") : "");
            d.put("contactPhone", rs.getString("contact_phone") != null ? rs.getString("contact_phone") : "+84 901 000 000");
            d.put("status", rs.getString("status"));
            d.put("contractCount", rs.getInt("contract_count"));
            return d;
        });

        // Contracts
        String sqlContracts =
                "SELECT c.id, c.distributor_id, d.company_name as distributor_name, c.contract_code, " +
                "c.title, c.signed_date, c.effective_from, c.effective_to, c.revenue_share, c.status, c.document_url " +
                "FROM distribution_contracts c " +
                "JOIN distributors d ON c.distributor_id = d.id " +
                "ORDER BY c.id ASC";

        List<Map<String, Object>> contractList = jdbcTemplate.query(sqlContracts, (rs, rowNum) -> {
            Map<String, Object> c = new HashMap<>();
            c.put("id", rs.getLong("id"));
            c.put("distributorId", rs.getLong("distributor_id"));
            c.put("distributorName", rs.getString("distributor_name"));
            c.put("contractCode", rs.getString("contract_code"));
            c.put("title", rs.getString("title"));
            c.put("signedDate", rs.getDate("signed_date") != null ? rs.getDate("signed_date").toString() : "");
            c.put("effectiveFrom", rs.getDate("effective_from") != null ? rs.getDate("effective_from").toString() : "");
            c.put("effectiveTo", rs.getDate("effective_to") != null ? rs.getDate("effective_to").toString() : "");
            c.put("revenueShare", rs.getDouble("revenue_share"));
            c.put("status", rs.getString("status"));
            c.put("documentUrl", rs.getString("document_url") != null ? rs.getString("document_url") : "");
            return c;
        });

        // Licenses (joined with Track name if available)
        String sqlLicenses =
                "SELECT l.id, l.track_id, d.company_name as distributor_name, l.license_type, " +
                "l.copyright_owner, l.issue_date, l.expiry_date, l.status, l.document_songlicenses_url " +
                "FROM song_licenses l " +
                "LEFT JOIN distributors d ON l.distributor_id = d.id " +
                "ORDER BY l.id ASC LIMIT 150";

        List<Map<String, Object>> licenseList = jdbcTemplate.query(sqlLicenses, (rs, rowNum) -> {
            Map<String, Object> l = new HashMap<>();
            l.put("id", rs.getLong("id"));
            String trackId = rs.getString("track_id");
            l.put("trackId", trackId);
            l.put("distributorName", rs.getString("distributor_name") != null ? rs.getString("distributor_name") : "Phát hành trực tiếp");
            l.put("licenseType", rs.getString("license_type"));
            l.put("copyrightOwner", rs.getString("copyright_owner") != null ? rs.getString("copyright_owner") : "Độc quyền Moodify");
            l.put("issueDate", rs.getDate("issue_date") != null ? rs.getDate("issue_date").toString() : "");
            l.put("expiryDate", rs.getDate("expiry_date") != null ? rs.getDate("expiry_date").toString() : "Chưa có thông tin");
            l.put("status", rs.getString("status"));
            l.put("documentUrl", rs.getString("document_songlicenses_url"));

            // Track title from MongoDB
            Track t = findAdminTrack(trackId);
            l.put("trackTitle", t != null ? t.getName() : "Bài hát đã lưu");
            return l;
        });

        result.put("distributors", distList);
        result.put("contracts", contractList);
        result.put("licenses", licenseList);

        return result;
    }

    public void createDistributor(Map<String, Object> data) {
        String companyName = (String) data.getOrDefault("companyName", "Đối Tác Mới");
        String country = (String) data.getOrDefault("country", "Việt Nam");
        String contactName = (String) data.get("contactName");
        String contactEmail = (String) data.get("contactEmail");
        String contactPhone = (String) data.get("contactPhone");
        String status = (String) data.getOrDefault("status", "ACTIVE");

        String sql = "INSERT INTO distributors (company_name, country, contact_name, contact_email, contact_phone, status, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())";
        jdbcTemplate.update(sql, companyName, country, contactName, contactEmail, contactPhone, status.toUpperCase());
    }

    public void updateDistributor(Long id, Map<String, Object> data) {
        String sql = "UPDATE distributors SET " +
                     "company_name = COALESCE(?, company_name), " +
                     "country = COALESCE(?, country), " +
                     "contact_name = COALESCE(?, contact_name), " +
                     "contact_email = COALESCE(?, contact_email), " +
                     "contact_phone = COALESCE(?, contact_phone), " +
                     "status = COALESCE(?, status), " +
                     "updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql,
                data.get("companyName"),
                data.get("country"),
                data.get("contactName"),
                data.get("contactEmail"),
                data.get("contactPhone"),
                data.get("status") != null ? data.get("status").toString().toUpperCase() : null,
                id);
    }

    public void toggleDistributorStatus(Long id) {
        jdbcTemplate.update("UPDATE distributors SET status = CASE WHEN status = 'ACTIVE' THEN 'INACTIVE' ELSE 'ACTIVE' END, updated_at = NOW() WHERE id = ?", id);
    }

    public void createContract(Map<String, Object> data) {
        Long distributorId = Long.parseLong(data.get("distributorId").toString());
        String contractCode = (String) data.getOrDefault("contractCode", "CTR-" + System.currentTimeMillis());
        String title = (String) data.getOrDefault("title", "Hợp đồng phân phối");
        String signedDate = (String) data.getOrDefault("signedDate", java.time.LocalDate.now().toString());
        String effectiveFrom = (String) data.getOrDefault("effectiveFrom", signedDate);
        String effectiveTo = (String) data.getOrDefault("effectiveTo", java.time.LocalDate.now().plusYears(1).toString());
        Double revenueShare = data.get("revenueShare") != null ? Double.parseDouble(data.get("revenueShare").toString()) : 0.70;
        String status = (String) data.getOrDefault("status", "ACTIVE");
        String documentUrl = (String) data.get("documentUrl");

        String sql = "INSERT INTO distribution_contracts (distributor_id, contract_code, title, signed_date, effective_from, effective_to, revenue_share, status, document_url, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        jdbcTemplate.update(sql, distributorId, contractCode, title, signedDate, effectiveFrom, effectiveTo, revenueShare, status.toUpperCase(), documentUrl);
    }

    public void updateContract(Long id, Map<String, Object> data) {
        String sql = "UPDATE distribution_contracts SET " +
                     "title = COALESCE(?, title), " +
                     "effective_from = COALESCE(?, effective_from), " +
                     "effective_to = COALESCE(?, effective_to), " +
                     "revenue_share = COALESCE(?, revenue_share), " +
                     "status = COALESCE(?, status), " +
                     "document_url = COALESCE(?, document_url), " +
                     "updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql,
                data.get("title"),
                data.get("effectiveFrom"),
                data.get("effectiveTo"),
                data.get("revenueShare") != null ? Double.parseDouble(data.get("revenueShare").toString()) : null,
                data.get("status") != null ? data.get("status").toString().toUpperCase() : null,
                data.get("documentUrl"),
                id);
    }

    public void updateContractStatus(Long id, String status) {
        jdbcTemplate.update("UPDATE distribution_contracts SET status = ?, updated_at = NOW() WHERE id = ?", status.toUpperCase(), id);
    }



    // ==========================================
    // 8. FAVORITES MANAGEMENT (MySQL + MongoDB)
    // ==========================================
    public Map<String, Object> getFavorites(String type, String query, int page, int size) {
        String favType = (type != null && !type.isBlank()) ? type.toUpperCase().trim() : "SONG";
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if ("ARTIST".equals(favType)) {
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.artist_id as target_id, 'ARTIST' as item_type, 'FAVORITE_ARTISTS' as fav_source, f.created_at " +
                "FROM favorite_artists f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        } else if ("ALBUM".equals(favType)) {
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.album_id as target_id, 'ALBUM' as item_type, 'FAVORITE_ALBUMS' as fav_source, f.created_at " +
                "FROM favorite_albums f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        } else {
            favType = "SONG";
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.track_id as target_id, 'SONG' as item_type, f.fav_source as fav_source, f.created_at " +
                "FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        }

        if (query != null && !query.isBlank()) {
            String trimmedQuery = query.trim();
            String q = "%" + trimmedQuery.toLowerCase(Locale.ROOT) + "%";
            Set<String> matchedTargetIds = new LinkedHashSet<>();
            try {
                if ("ALBUM".equals(favType)) {
                    if (albumRepository != null) {
                        List<Album> matchedAlbums = albumRepository.findByNameContainingIgnoreCase(trimmedQuery);
                        for (Album a : matchedAlbums) {
                            if (a.getId() != null && !a.getId().isBlank()) {
                                matchedTargetIds.add(a.getId());
                                matchedTargetIds.add("mongo:" + a.getId());
                            }
                            if (a.getSpotifyId() != null && !a.getSpotifyId().isBlank()) {
                                matchedTargetIds.add(a.getSpotifyId());
                                matchedTargetIds.add("mongo:" + a.getSpotifyId());
                            }
                        }
                    }
                    try {
                        List<String> mysqlIds = jdbcTemplate.query(
                            "SELECT a.id FROM albums a LEFT JOIN users u ON a.user_id = u.id WHERE LOWER(a.title) LIKE ? OR LOWER(u.full_name) LIKE ?",
                            (rs, rowNum) -> String.valueOf(rs.getLong("id")),
                            q, q
                        );
                        for (String mysqlId : mysqlIds) {
                            if (mysqlId != null && !mysqlId.isBlank()) {
                                matchedTargetIds.add(mysqlId);
                                matchedTargetIds.add("sql:" + mysqlId);
                            }
                        }
                    } catch (Exception ignored) {}
                } else if ("ARTIST".equals(favType)) {
                    if (artistRepository != null) {
                        List<Artist> matchedArtists = artistRepository.findByNameContainingIgnoreCase(trimmedQuery);
                        for (Artist a : matchedArtists) {
                            if (a.getId() != null && !a.getId().isBlank()) matchedTargetIds.add(a.getId());
                            if (a.getSpotifyId() != null && !a.getSpotifyId().isBlank()) matchedTargetIds.add(a.getSpotifyId());
                        }
                    }
                } else {
                    if (trackRepository != null) {
                        List<Track> matchedTracks = trackRepository.findByNameContainingIgnoreCase(trimmedQuery);
                        for (Track t : matchedTracks) {
                            if (t.getId() != null && !t.getId().isBlank()) matchedTargetIds.add(t.getId());
                            if (t.getSpotifyId() != null && !t.getSpotifyId().isBlank()) matchedTargetIds.add(t.getSpotifyId());
                        }
                    }
                }
            } catch (Exception ignored) {}

            String targetCol = "ALBUM".equals(favType) ? "f.album_id" : "ARTIST".equals(favType) ? "f.artist_id" : "f.track_id";
            if (!matchedTargetIds.isEmpty()) {
                String inSql = matchedTargetIds.stream().map(id -> "?").collect(Collectors.joining(", "));
                sql.append("AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(" + targetCol + ") LIKE ? OR " + targetCol + " IN (" + inSql + ")) ");
                params.add(q);
                params.add(q);
                params.add(q);
                params.addAll(matchedTargetIds);
            } else {
                sql.append("AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(" + targetCol + ") LIKE ?) ");
                params.add(q);
                params.add(q);
                params.add(q);
            }
        }

        String countSql = "SELECT COUNT(*) FROM (" + sql.toString() + ") AS c_tbl";
        Long total = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);

        sql.append("ORDER BY f.created_at DESC LIMIT ? OFFSET ?");
        params.add(safeSize);
        params.add(safePage * safeSize);

        List<Map<String, Object>> items = jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("user_id"));
            m.put("userName", rs.getString("user_name"));
            m.put("userEmail", rs.getString("user_email"));
            m.put("userAvatar", rs.getString("user_avatar"));
            String targetId = rs.getString("target_id");
            m.put("targetId", targetId);
            String itType = rs.getString("item_type");
            m.put("type", itType);
            m.put("source", rs.getString("fav_source"));
            m.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "");

            if ("SONG".equals(itType)) {
                Track t = findAdminTrack(targetId);
                if (t != null) {
                    m.put("targetTitle", t.getName());
                    m.put("targetSubtitle", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ Moodify");
                    m.put("targetCoverUrl", resolveAdminImageUrl(t.getImageUrl()));
                    m.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
                } else {
                    m.put("targetTitle", "Bài hát đã lưu");
                    m.put("targetSubtitle", "Chưa có thông tin");
                    m.put("targetCoverUrl", "/images/cover-placeholder.svg");
                    m.put("audioUrl", resolveAudioUrl(null));
                }
            } else if ("ARTIST".equals(itType)) {
                Artist a = null;
                if (artistRepository != null && targetId != null && !targetId.isBlank()) {
                    a = artistRepository.findById(targetId).orElse(null);
                    if (a == null) {
                        a = artistRepository.findBySpotifyId(targetId).orElse(null);
                    }
                }
                if (a != null) {
                    m.put("targetTitle", a.getName());
                    String subtitle = (a.getGenres() != null && !a.getGenres().isEmpty())
                            ? String.join(", ", a.getGenres())
                            : "Chưa có thông tin nghệ sĩ";
                    if (a.getFollowers() != null && a.getFollowers() > 0) {
                        subtitle += " • " + a.getFollowers() + " người theo dõi";
                    }
                    m.put("targetSubtitle", subtitle);
                    m.put("targetCoverUrl", resolveAdminImageUrl(a.getImageUrl()));
                } else {
                    m.put("targetTitle", "Không tìm thấy thông tin nghệ sĩ");
                    m.put("targetSubtitle", "Chưa có thông tin nghệ sĩ");
                    m.put("targetCoverUrl", "/images/cover-placeholder.svg");
                }
            } else { // ALBUM
                Map<String, Object> albInfo = resolveAlbumDetail(targetId);
                m.put("targetTitle", albInfo.get("title"));
                m.put("targetSubtitle", albInfo.get("subtitle"));
                m.put("targetCoverUrl", albInfo.get("coverUrl"));
            }
            return m;
        });

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total != null ? total : 0);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("type", favType);
        return result;
    }

    public List<Map<String, Object>> getFavoriteLeaderboard() {
        return getFavoriteLeaderboard("SONG");
    }

    public List<Map<String, Object>> getFavoriteLeaderboard(String type) {
        String rawType = (type != null && !type.isBlank()) ? type.toUpperCase(Locale.ROOT).trim() : "SONG";
        String favType = "ALBUM".equals(rawType) ? "ALBUM" : "ARTIST".equals(rawType) ? "ARTIST" : "SONG";
        String targetCol = "f.track_id";
        String fromExpr = COMBINED_SONG_FAVORITES_SUBQUERY + " f";

        if ("ALBUM".equals(favType)) {
            targetCol = "f.album_id";
            fromExpr = "favorite_albums f";
        } else if ("ARTIST".equals(favType)) {
            targetCol = "f.artist_id";
            fromExpr = "favorite_artists f";
        }

        String sql = "SELECT " + targetCol + " as target_id, COUNT(*) as fav_count FROM " + fromExpr + " GROUP BY " + targetCol + " ORDER BY fav_count DESC LIMIT 50";
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            String targetId = rs.getString("target_id");
            m.put("targetId", targetId);
            m.put("favoriteCount", rs.getLong("fav_count"));
            m.put("type", favType);
            return m;
        });

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String targetId = Objects.toString(r.get("targetId"), "");
            long count = r.get("favoriteCount") instanceof Number n ? n.longValue() : 0L;
            String canonicalKey;

            if ("SONG".equals(favType)) {
                Track t = findAdminTrack(targetId);
                if (t != null) {
                    canonicalKey = (t.getId() != null && !t.getId().isBlank()) ? "track:" + t.getId() : "raw:" + targetId;
                    r.put("targetTitle", t.getName());
                    r.put("targetSubtitle", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ Moodify");
                    r.put("targetCoverUrl", resolveAdminImageUrl(t.getImageUrl()));
                    r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
                } else {
                    canonicalKey = "raw:" + targetId;
                    r.put("targetTitle", "Bài hát đã lưu");
                    r.put("targetSubtitle", "Chưa có thông tin nghệ sĩ");
                    r.put("targetCoverUrl", "/images/cover-placeholder.svg");
                    r.put("audioUrl", resolveAudioUrl(null));
                }
            } else if ("ARTIST".equals(favType)) {
                Artist a = null;
                if (artistRepository != null && !targetId.isBlank()) {
                    a = artistRepository.findById(targetId).orElse(null);
                    if (a == null) a = artistRepository.findBySpotifyId(targetId).orElse(null);
                }
                if (a != null) {
                    canonicalKey = (a.getId() != null && !a.getId().isBlank()) ? "artist:" + a.getId() : "raw:" + targetId;
                    r.put("targetTitle", a.getName());
                    String sub = (a.getGenres() != null && !a.getGenres().isEmpty()) ? String.join(", ", a.getGenres()) : "Chưa có thông tin nghệ sĩ";
                    if (a.getFollowers() != null && a.getFollowers() > 0) {
                        sub += " • " + a.getFollowers() + " người theo dõi";
                    }
                    r.put("targetSubtitle", sub);
                    r.put("targetCoverUrl", resolveAdminImageUrl(a.getImageUrl()));
                } else {
                    canonicalKey = "raw:" + targetId;
                    r.put("targetTitle", "Không tìm thấy thông tin nghệ sĩ");
                    r.put("targetSubtitle", "Chưa có thông tin nghệ sĩ");
                    r.put("targetCoverUrl", "/images/cover-placeholder.svg");
                }
            } else { // ALBUM
                Map<String, Object> albInfo = resolveAlbumDetail(targetId);
                canonicalKey = Objects.toString(albInfo.get("canonicalKey"), targetId);
                r.put("targetTitle", albInfo.get("title"));
                r.put("targetSubtitle", albInfo.get("subtitle"));
                r.put("targetCoverUrl", albInfo.get("coverUrl"));
            }

            Map<String, Object> existing = merged.get(canonicalKey);
            if (existing == null) {
                merged.put(canonicalKey, r);
            } else {
                long prev = existing.get("favoriteCount") instanceof Number n ? n.longValue() : 0L;
                existing.put("favoriteCount", prev + count);
                String existingTargetId = Objects.toString(existing.get("targetId"), "");
                if (!existingTargetId.contains(":") && targetId.contains(":")) {
                    existing.put("targetId", targetId);
                }
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Long.compare(
                        b.get("favoriteCount") instanceof Number nb ? nb.longValue() : 0L,
                        a.get("favoriteCount") instanceof Number na ? na.longValue() : 0L
                ))
                .limit(10)
                .toList();
    }

    private Map<String, Object> resolveAlbumDetail(String targetId) {
        Map<String, Object> detail = new HashMap<>();
        if (targetId == null || targetId.isBlank()) {
            detail.put("canonicalKey", "");
            detail.put("title", "Album tuyển chọn");
            detail.put("subtitle", "Album tuyển chọn");
            detail.put("coverUrl", "/images/cover-placeholder.svg");
            return detail;
        }

        String raw = targetId.trim();
        boolean sqlOnly = raw.startsWith("sql:");
        boolean mongoOnly = raw.startsWith("mongo:");
        String strippedId = sqlOnly ? raw.substring(4).trim() : mongoOnly ? raw.substring(6).trim() : raw;

        // 1. Check MongoDB Album
        if (!sqlOnly && albumRepository != null && !strippedId.isBlank()) {
            try {
                Album alb = albumRepository.findById(strippedId).orElse(null);
                if (alb == null) {
                    alb = albumRepository.findBySpotifyId(strippedId).orElse(null);
                }
                if (alb != null) {
                    String canonicalId = (alb.getId() != null && !alb.getId().isBlank()) ? alb.getId() : strippedId;
                    detail.put("canonicalKey", "mongo:" + canonicalId);
                    detail.put("title", (alb.getName() != null && !alb.getName().isBlank()) ? alb.getName() : "Album tuyển chọn");
                    String subtitle = (alb.getArtistName() != null && !alb.getArtistName().isBlank()) ? alb.getArtistName() : "Album tuyển chọn";
                    int trackCount = (alb.getTotalTracks() != null && alb.getTotalTracks() > 0)
                            ? alb.getTotalTracks()
                            : (alb.getTrackIds() != null ? alb.getTrackIds().size() : 0);
                    if (trackCount > 0) {
                        subtitle += " • " + trackCount + " bài hát";
                    }
                    if (alb.getReleaseDate() != null && !alb.getReleaseDate().isBlank()) {
                        subtitle += " (" + alb.getReleaseDate() + ")";
                    }
                    detail.put("subtitle", subtitle);
                    detail.put("coverUrl", resolveAdminImageUrl(alb.getImageUrl()));
                    return detail;
                }
            } catch (Exception ignored) {}
        }

        // 2. Check MySQL albums table
        if (!mongoOnly && strippedId.matches("\\d{1,18}")) {
            try {
                long numericId = Long.parseLong(strippedId);
                List<Map<String, Object>> mysqlAlbums = jdbcTemplate.query(
                    "SELECT a.id, a.title, a.description, a.cover_url, a.release_date, u.full_name as artist_name, " +
                    "(SELECT COUNT(*) FROM album_tracks at WHERE at.album_id = a.id) as track_count " +
                    "FROM albums a " +
                    "LEFT JOIN users u ON a.user_id = u.id " +
                    "WHERE a.id = ?",
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("canonicalKey", "sql:" + rs.getLong("id"));
                        m.put("title", rs.getString("title"));
                        String artistName = rs.getString("artist_name") != null ? rs.getString("artist_name") : "Chưa có thông tin nghệ sĩ";
                        int trackCount = rs.getInt("track_count");
                        String subtitle = artistName;
                        if (trackCount > 0) {
                            subtitle += " • " + trackCount + " bài hát";
                        }
                        java.sql.Date relDate = rs.getDate("release_date");
                        if (relDate != null) {
                            subtitle += " (" + relDate.toString() + ")";
                        }
                        m.put("subtitle", subtitle);
                        m.put("coverUrl", resolveAdminImageUrl(rs.getString("cover_url")));
                        return m;
                    },
                    numericId
                );
                if (!mysqlAlbums.isEmpty()) {
                    return mysqlAlbums.get(0);
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback
        detail.put("canonicalKey", raw);
        detail.put("title", "Album #" + (strippedId.length() > 6 ? strippedId.substring(strippedId.length() - 6) : strippedId));
        detail.put("subtitle", "Album tuyển chọn");
        detail.put("coverUrl", "/images/cover-placeholder.svg");
        return detail;
    }

    public void deleteFavorite(Long id) {
        deleteFavorite(id, null, null);
    }

    public void deleteFavorite(Long id, String type) {
        deleteFavorite(id, type, null);
    }

    public void deleteFavorite(Long id, String type, String source) {
        String normalized = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        String normalizedSource = source != null ? source.trim().toUpperCase(Locale.ROOT) : "";

        if ("SONG".equals(normalized) || "TRACK".equals(normalized)) {
            if ("LIBRARY".equals(normalizedSource)) {
                if (deleteUserLibrarySongById(id) == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite song record not found");
                }
                return;
            }
            if ("LEGACY_SONG".equals(normalizedSource)) {
                if (jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", id) == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite song record not found");
                }
                return;
            }
            int deleted = deleteUserLibrarySongById(id);
            if (deleted == 0) {
                deleted = jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", id);
            }
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite song record not found");
            }
            return;
        }
        if ("ARTIST".equals(normalized)) {
            int deleted = jdbcTemplate.update("DELETE FROM favorite_artists WHERE id = ?", id);
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite artist record not found");
            }
            return;
        }
        if ("ALBUM".equals(normalized)) {
            int deleted = jdbcTemplate.update("DELETE FROM favorite_albums WHERE id = ?", id);
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite album record not found");
            }
            return;
        }
        int deleted = deleteUserLibrarySongById(id);
        if (deleted == 0) {
            deleted = jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", id);
        }
        if (deleted == 0) {
            deleted = jdbcTemplate.update("DELETE FROM favorite_artists WHERE id = ?", id);
        }
        if (deleted == 0) {
            deleted = jdbcTemplate.update("DELETE FROM favorite_albums WHERE id = ?", id);
        }
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite record not found");
        }
    }

    private int deleteUserLibrarySongById(Long id) {
        Map<String, Object> row = null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, track_id FROM user_library_tracks WHERE id = ?",
                id
            );
            if (!rows.isEmpty()) {
                row = rows.get(0);
            }
        } catch (Exception ignored) {}

        int deleted = jdbcTemplate.update("DELETE FROM user_library_tracks WHERE id = ?", id);
        if (deleted > 0 && row != null && row.get("user_id") != null && row.get("track_id") != null) {
            try {
                jdbcTemplate.update(
                    "DELETE FROM favorite_songs WHERE user_id = ? AND track_id = ?",
                    ((Number) row.get("user_id")).longValue(),
                    row.get("track_id").toString()
                );
            } catch (Exception ignored) {}
        }
        return deleted;
    }

    // ==========================================
    // LISTENING HISTORY & BEHAVIOR TELEMETRY
    // ==========================================
    public Map<String, Object> getListeningHistory(
            int page, int size, String search, String deviceType, String source, Long userId
    ) {
        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            whereClause.append(" AND lh.user_id = ? ");
            params.add(userId);
        }
        if (deviceType != null && !deviceType.isBlank() && !"ALL".equalsIgnoreCase(deviceType)) {
            whereClause.append(" AND lh.device_type = ? ");
            params.add(deviceType.trim().toUpperCase());
        }
        if (source != null && !source.isBlank() && !"ALL".equalsIgnoreCase(source)) {
            whereClause.append(" AND lh.source = ? ");
            params.add(source.trim().toUpperCase());
        }
        if (search != null && !search.isBlank()) {
            whereClause.append(" AND (u.username LIKE ? OR u.full_name LIKE ? OR u.email LIKE ? OR lh.track_id LIKE ?) ");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        String countSql = "SELECT COUNT(*) FROM listening_history lh LEFT JOIN users u ON lh.user_id = u.id " + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);
        if (totalElements == null) totalElements = 0L;

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = safePage * safeSize;

        String selectSql = """
            SELECT lh.id, lh.user_id, u.username, u.full_name, u.email,
                   lh.track_id, lh.started_at, lh.ended_at,
                   lh.listened_duration_ms, lh.last_position_ms,
                   lh.source, lh.source_id, lh.device_type,
                   (SELECT COUNT(*) FROM playback_events pe WHERE pe.listening_history_id = lh.id) as event_count
            FROM listening_history lh
            LEFT JOIN users u ON lh.user_id = u.id
        """ + whereClause + " ORDER BY lh.started_at DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safeSize);
        queryParams.add(offset);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Map<String, Object>> items = jdbcTemplate.query(selectSql, queryParams.toArray(), (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getObject("user_id") != null ? rs.getLong("user_id") : null);
            m.put("username", rs.getString("username") != null ? rs.getString("username") : "Khách vãng lai");
            m.put("fullName", rs.getString("full_name") != null ? rs.getString("full_name") : "Người nghe ẩn danh");
            m.put("email", rs.getString("email") != null ? rs.getString("email") : "");
            
            String trackId = rs.getString("track_id");
            m.put("trackId", trackId);
            
            String rawStartedAt = rs.getString("started_at");
            if (rawStartedAt != null && rawStartedAt.length() > 19) {
                rawStartedAt = rawStartedAt.substring(0, 19);
            }
            java.sql.Timestamp startedAtTs = rs.getTimestamp("started_at");
            m.put("startedAt", rawStartedAt != null && !rawStartedAt.isBlank() ? rawStartedAt : (startedAtTs != null ? startedAtTs.toLocalDateTime().format(dtf) : ""));

            String rawEndedAt = rs.getString("ended_at");
            if (rawEndedAt != null && rawEndedAt.length() > 19) {
                rawEndedAt = rawEndedAt.substring(0, 19);
            }
            java.sql.Timestamp endedAtTs = rs.getTimestamp("ended_at");
            m.put("endedAt", rawEndedAt != null && !rawEndedAt.isBlank() ? rawEndedAt : (endedAtTs != null ? endedAtTs.toLocalDateTime().format(dtf) : ""));
            
            long durMs = rs.getLong("listened_duration_ms");
            long posMs = rs.getLong("last_position_ms");
            m.put("listenedDurationMs", durMs);
            m.put("listenedDurationSeconds", Math.round(durMs / 1000.0));
            m.put("lastPositionMs", posMs);
            m.put("lastPositionSeconds", Math.round(posMs / 1000.0));
            m.put("source", rs.getString("source"));
            m.put("sourceId", rs.getString("source_id"));
            m.put("deviceType", rs.getString("device_type"));
            m.put("eventCount", rs.getInt("event_count"));
            return m;
        });

        // Enrich with MongoDB Track Metadata
        for (Map<String, Object> item : items) {
            String trackId = (String) item.get("trackId");
            if (trackId != null) {
                Track t = findAdminTrack(trackId);
                if (t != null) {
                    item.put("trackTitle", t.getName());
                    item.put("artistName", t.getArtistName() != null ? t.getArtistName() : "Chưa có thông tin nghệ sĩ");
                    item.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
                    item.put("totalDuration", formatTrackDuration(t));
                    item.put("genre", resolveTrackGenre(t));
                } else {
                    item.put("trackTitle", "Không tìm thấy thông tin bài hát");
                    item.put("artistName", "Chưa có thông tin nghệ sĩ");
                    item.put("coverUrl", "/images/cover-placeholder.svg");
                    item.put("totalDuration", "N/A");
                    item.put("genre", "Chưa phân loại");
                }
            }
        }

        int totalPages = (int) Math.ceil((double) totalElements / safeSize);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages);
        result.put("currentPage", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    public List<Map<String, Object>> getPlaybackEventsForSession(Long listeningHistoryId) {
        String sql = """
            SELECT id, listening_history_id, event_type, position_ms, target_position_ms, occurred_at
            FROM playback_events
            WHERE listening_history_id = ?
            ORDER BY occurred_at ASC, id ASC
        """;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        return jdbcTemplate.query(sql, new Object[]{listeningHistoryId}, (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("listeningHistoryId", rs.getLong("listening_history_id"));
            m.put("eventType", rs.getString("event_type"));
            long posMs = rs.getLong("position_ms");
            long targetPosMs = rs.getLong("target_position_ms");
            m.put("positionMs", posMs);
            m.put("positionSeconds", Math.round(posMs / 1000.0));
            m.put("targetPositionMs", targetPosMs);
            m.put("targetPositionSeconds", Math.round(targetPosMs / 1000.0));
            String rawOccurredAt = rs.getString("occurred_at");
            if (rawOccurredAt != null && rawOccurredAt.length() > 19) {
                rawOccurredAt = rawOccurredAt.substring(0, 19);
            }
            java.sql.Timestamp occurredTs = rs.getTimestamp("occurred_at");
            m.put("occurredAt", rawOccurredAt != null && !rawOccurredAt.isBlank() ? rawOccurredAt : (occurredTs != null ? occurredTs.toLocalDateTime().format(dtf) : ""));
            return m;
        });
    }

    public Map<String, Object> getListeningSummaryMetrics() {
        Map<String, Object> summary = new HashMap<>();

        Long totalSessions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM listening_history", Long.class);
        Long totalDurationMs = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(listened_duration_ms), 0) FROM listening_history", Long.class);
        Long sessionsLast24h = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM listening_history WHERE started_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)", Long.class
        );
        Long completeEvents = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT listening_history_id) FROM playback_events WHERE event_type = 'COMPLETE'", Long.class
        );

        summary.put("totalSessions", totalSessions != null ? totalSessions : 0L);
        summary.put("totalDurationMs", totalDurationMs != null ? totalDurationMs : 0L);
        summary.put("totalHours", totalDurationMs != null ? Math.round((totalDurationMs / 3600000.0) * 100.0) / 100.0 : 0.0);
        summary.put("totalDurationFormatted", formatTotalDuration(totalDurationMs != null ? totalDurationMs : 0L));
        summary.put("sessionsLast24h", sessionsLast24h != null ? sessionsLast24h : 0L);
        summary.put("completeEvents", completeEvents != null ? completeEvents : 0L);
        
        double completionRate = (totalSessions != null && totalSessions > 0)
            ? Math.round(((double) (completeEvents != null ? completeEvents : 0) / totalSessions) * 1000.0) / 10.0
            : 0.0;
        summary.put("completionRatePercent", completionRate);

        return summary;
    }

    public List<Map<String, Object>> getTrackPerformanceMetrics(int limit) {
        String sql = """
            SELECT 
                lh.track_id, 
                COUNT(*) as stream_count,
                COUNT(DISTINCT lh.user_id) as unique_listeners,
                COALESCE(AVG(lh.listened_duration_ms), 0) as avg_duration_ms,
                COALESCE(SUM(CASE WHEN lh.listened_duration_ms < 30000 THEN 1 ELSE 0 END), 0) as early_drop_count
            FROM listening_history lh
            GROUP BY lh.track_id
            ORDER BY stream_count DESC
            LIMIT ?
        """;

        List<Map<String, Object>> rows = jdbcTemplate.query(sql, new Object[]{limit}, (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("trackId", rs.getString("track_id"));
            m.put("streamCount", rs.getLong("stream_count"));
            m.put("uniqueListeners", rs.getLong("unique_listeners"));
            m.put("avgDurationMs", Math.round(rs.getDouble("avg_duration_ms")));
            m.put("earlyDropCount", rs.getLong("early_drop_count"));
            return m;
        });

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String trackId = (String) r.get("trackId");
            long streamCount = (long) r.get("streamCount");
            long earlyDropCount = (long) r.get("earlyDropCount");
            long avgDurationMs = (long) r.get("avgDurationMs");

            Long favCount = 0L;
            try {
                favCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + COMBINED_SONG_FAVORITES_SUBQUERY + " f WHERE f.track_id = ?",
                    new Object[]{trackId},
                    Long.class
                );
            } catch (Exception ignored) {}
            r.put("favoriteCount", favCount != null ? favCount : 0L);

            Track t = findAdminTrack(trackId);
            int trackDurationMs = resolveTrackDurationMs(t);

            Double completionRate = trackDurationMs > 0 ? Math.min(100.0, Math.round(((double) avgDurationMs / trackDurationMs * 100.0) * 10.0) / 10.0) : null;
            double skipRate = (streamCount > 0)
                ? Math.min(100.0, Math.round(((double) earlyDropCount / streamCount * 100.0) * 10.0) / 10.0)
                : 0.0;

            r.put("trackDurationMs", trackDurationMs);
            r.put("completionRatePercent", completionRate);
            r.put("skipRatePercent", skipRate);
            r.put("avgDurationFormatted", formatDurationSeconds((int) (avgDurationMs / 1000)));

            if (t != null) {
                r.put("title", t.getName());
                r.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
                r.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
                r.put("duration", formatTrackDuration(t));
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Không tìm thấy thông tin bài hát");
                r.put("artist", "Chưa có thông tin nghệ sĩ");
                r.put("coverUrl", "/images/cover-placeholder.svg");
                r.put("duration", trackDurationMs > 0 ? formatDurationSeconds(trackDurationMs / 1000) : "—");
                r.put("genre", "Chưa phân loại");
                r.put("audioUrl", resolveAudioUrl(null));
            }

            result.add(r);
        }
        return result;
    }

    public Map<String, Object> getAllPlaybackEvents(int page, int size, String eventType, String search) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = safePage * safeSize;

        StringBuilder whereSql = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (eventType != null && !eventType.isBlank() && !"ALL".equalsIgnoreCase(eventType)) {
            whereSql.append(" AND pe.event_type = ? ");
            params.add(eventType.trim().toUpperCase());
        }

        if (search != null && !search.isBlank()) {
            whereSql.append(" AND (lh.track_id LIKE ? OR u.username LIKE ? OR u.full_name LIKE ?) ");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }

        String countSql = "SELECT COUNT(*) FROM playback_events pe JOIN listening_history lh ON pe.listening_history_id = lh.id LEFT JOIN users u ON lh.user_id = u.id " + whereSql;
        Long totalElements = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);
        if (totalElements == null) totalElements = 0L;

        String selectSql = """
            SELECT 
                pe.id, pe.listening_history_id, pe.event_type, pe.position_ms, pe.target_position_ms, pe.occurred_at,
                lh.track_id, lh.user_id, lh.device_type, lh.source,
                u.username, u.full_name
            FROM playback_events pe
            JOIN listening_history lh ON pe.listening_history_id = lh.id
            LEFT JOIN users u ON lh.user_id = u.id
        """ + whereSql + " ORDER BY pe.occurred_at DESC, pe.id DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safeSize);
        queryParams.add(offset);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Map<String, Object>> items = jdbcTemplate.query(selectSql, queryParams.toArray(), (rs, rowNum) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("listeningHistoryId", rs.getLong("listening_history_id"));
            item.put("eventType", rs.getString("event_type"));
            long posMs = rs.getLong("position_ms");
            long targetPosMs = rs.getLong("target_position_ms");
            item.put("positionMs", posMs);
            item.put("positionFormatted", formatDurationSeconds((int) (posMs / 1000)));
            item.put("targetPositionMs", targetPosMs);
            item.put("targetPositionFormatted", formatDurationSeconds((int) (targetPosMs / 1000)));

            String rawOccurredAt = rs.getString("occurred_at");
            if (rawOccurredAt != null && rawOccurredAt.length() > 19) {
                rawOccurredAt = rawOccurredAt.substring(0, 19);
            }
            java.sql.Timestamp occurredTs = rs.getTimestamp("occurred_at");
            item.put("occurredAt", rawOccurredAt != null && !rawOccurredAt.isBlank() ? rawOccurredAt : (occurredTs != null ? occurredTs.toLocalDateTime().format(dtf) : ""));

            item.put("trackId", rs.getString("track_id"));
            item.put("userId", rs.getLong("user_id"));
            item.put("username", rs.getString("username") != null ? rs.getString("username") : "guest");
            item.put("fullName", rs.getString("full_name") != null ? rs.getString("full_name") : "Khách vãng lai");
            item.put("deviceType", rs.getString("device_type"));
            item.put("source", rs.getString("source"));

            return item;
        });

        for (Map<String, Object> it : items) {
            String trackId = (String) it.get("trackId");
            Track t = findAdminTrack(trackId);
            if (t != null) {
                it.put("trackTitle", t.getName());
                it.put("artistName", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ");
                it.put("coverUrl", resolveAdminImageUrl(t.getImageUrl()));
            } else {
                it.put("trackTitle", "Không tìm thấy thông tin bài hát");
                it.put("artistName", "Chưa có thông tin nghệ sĩ");
                it.put("coverUrl", "/images/cover-placeholder.svg");
            }
        }

        Map<String, Long> distribution = new HashMap<>();
        try {
            jdbcTemplate.query("SELECT event_type, COUNT(*) as cnt FROM playback_events GROUP BY event_type", (rs) -> {
                distribution.put(rs.getString("event_type"), rs.getLong("cnt"));
            });
        } catch (Exception ignored) {}

        Map<String, Object> resp = new HashMap<>();
        resp.put("items", items);
        resp.put("totalElements", totalElements);
        resp.put("page", safePage);
        resp.put("size", safeSize);
        resp.put("totalPages", (int) Math.ceil((double) totalElements / safeSize));
        resp.put("distribution", distribution);

        return resp;
    }

    private String formatDurationSeconds(int seconds) {
        if (seconds <= 0) return "0:00";
        int mins = seconds / 60;
        int secs = seconds % 60;
        return String.format("%d:%02d", mins, secs);
    }

    public String formatTotalDuration(long totalMs) {
        if (totalMs <= 0) return "0 giây";
        long totalSecs = totalMs / 1000;
        long hours = totalSecs / 3600;
        long minutes = (totalSecs % 3600) / 60;
        long seconds = totalSecs % 60;
        if (hours > 0) {
            return hours + " giờ " + minutes + " phút";
        } else if (minutes > 0) {
            return minutes + " phút " + seconds + " giây";
        } else {
            return seconds + " giây";
        }
    }
}
