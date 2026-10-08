package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.entities.enums.UserStatus;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AdminService {

    private final JdbcTemplate jdbcTemplate;
    private final TrackRepository trackRepository;
    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String resolveAudioUrl(String localPath) {
        return AudioUrlResolver.resolve(localPath);
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
            PasswordEncoder passwordEncoder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.trackRepository = trackRepository;
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
            totalFavorites = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorite_songs", Long.class);
        } catch (Exception ignored) {}

        Long totalListenedMs = 0L;
        try {
            totalListenedMs = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(listened_duration_ms), 0) FROM listening_history", Long.class);
        } catch (Exception ignored) {}
        double listenedHours = totalListenedMs != null ? Math.round((totalListenedMs / 3600000.0) * 10.0) / 10.0 : 0.0;

        Long activeDevices = 0L;
        try {
            activeDevices = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_devices WHERE status = 'ACTIVE'", Long.class);
        } catch (Exception ignored) {}

        List<Map<String, Object>> streamsByPlatform = new ArrayList<>();
        try {
            String platSql = "SELECT device_type, COUNT(*) as cnt FROM listening_history GROUP BY device_type ORDER BY cnt DESC";
            streamsByPlatform = jdbcTemplate.query(platSql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("platform", rs.getString("device_type"));
                m.put("count", rs.getLong("cnt"));
                return m;
            });
        } catch (Exception ignored) {}

        List<Map<String, Object>> streamsBySource = new ArrayList<>();
        try {
            String srcSql = "SELECT source, COUNT(*) as cnt FROM listening_history GROUP BY source ORDER BY cnt DESC";
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

        // 6. Listening trend by date from real listening_history
        List<Map<String, Object>> trend = getListeningTrend();
        overview.put("listeningTrend", trend);

        // 7. Recent platform activities (real transactions, reviews, favorites)
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
            Track t = trackRepository.findById(trackId).orElse(null);
            if (t != null) {
                r.put("title", t.getName());
                r.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
                r.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", t.getDurationFormatted() != null ? t.getDurationFormatted() : "3:30");
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                r.put("artist", "Moodify Artist");
                r.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", "3:30");
                r.put("genre", "V-Pop");
                r.put("audioUrl", resolveAudioUrl(null));
            }
            result.add(r);
        }
        return result;
    }

    public List<Map<String, Object>> getTopFavoritedTracks(int limit) {
        String sql = "SELECT track_id, COUNT(*) as favorite_count FROM favorite_songs GROUP BY track_id ORDER BY favorite_count DESC LIMIT ?";
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
            Track t = trackRepository.findById(trackId).orElse(null);
            if (t != null) {
                r.put("title", t.getName());
                r.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
                r.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", t.getDurationFormatted() != null ? t.getDurationFormatted() : "3:30");
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                r.put("artist", "Moodify Artist");
                r.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", "3:30");
                r.put("genre", "V-Pop");
                r.put("audioUrl", resolveAudioUrl(null));
            }
            result.add(r);
        }
        return result;
    }

    public List<Map<String, Object>> getListeningTrend() {
        String sql = "SELECT DATE_FORMAT(started_at, '%d/%m') as day_label, DATE(started_at) as day_date, COUNT(*) as count_val " +
                     "FROM listening_history " +
                     "GROUP BY DATE(started_at), DATE_FORMAT(started_at, '%d/%m') " +
                     "ORDER BY day_date ASC LIMIT 14";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", rs.getString("day_date"));
                m.put("label", rs.getString("day_label"));
                m.put("streams", rs.getInt("count_val"));
                return m;
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getRecentActivities() {
        List<Map<String, Object>> list = new ArrayList<>();

        // Recent payments
        try {
            String paySql = "SELECT t.id, u.full_name, p.name as pkg_name, t.amount, t.paid_at " +
                            "FROM payment_transactions t " +
                            "JOIN subscriptions s ON t.subscription_id = s.id " +
                            "JOIN users u ON s.user_id = u.id " +
                            "JOIN service_packages p ON s.service_package_id = p.id " +
                            "ORDER BY t.created_at DESC LIMIT 3";
            jdbcTemplate.query(paySql, (rs, rowNum) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("id", "pay-" + rs.getLong("id"));
                a.put("type", "PAYMENT");
                a.put("title", rs.getString("full_name") + " đã đăng ký gói " + rs.getString("pkg_name"));
                a.put("detail", "Thanh toán thành công: " + String.format("%,.0f đ", rs.getDouble("amount")));
                a.put("timestamp", rs.getTimestamp("paid_at") != null
                        ? rs.getTimestamp("paid_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                        : "Hôm nay");
                list.add(a);
                return null;
            });
        } catch (Exception ignored) {}

        // Recent favorites
        try {
            String favSql = "SELECT f.id, u.full_name, f.track_id, f.created_at " +
                            "FROM favorite_songs f " +
                            "JOIN users u ON f.user_id = u.id " +
                            "ORDER BY f.created_at DESC LIMIT 3";
            jdbcTemplate.query(favSql, (rs, rowNum) -> {
                Map<String, Object> a = new HashMap<>();
                a.put("id", "fav-" + rs.getLong("id"));
                a.put("type", "FAVORITE");
                String trackId = rs.getString("track_id");
                Track t = trackRepository.findById(trackId).orElse(null);
                String trackName = t != null ? t.getName() : "Bài hát";
                a.put("title", rs.getString("full_name") + " đã yêu thích " + trackName);
                a.put("detail", "Thêm vào danh sách yêu thích cá nhân");
                a.put("timestamp", rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                        : "Hôm nay");
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

        sql.append("GROUP BY u.id ORDER BY u.id ASC LIMIT 200");

        return jdbcTemplate.query(sql.toString(), params.toArray(), this::mapUserRow);
    }

    private Map<String, Object> mapUserRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> u = new HashMap<>();
        u.put("id", rs.getLong("id"));
        u.put("fullName", rs.getString("full_name"));
        u.put("username", rs.getString("username"));
        u.put("email", rs.getString("email"));
        u.put("phone", rs.getString("phone") != null ? rs.getString("phone") : "Chưa cập nhật");
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
                : "01/01/2026");
        return u;
    }

    @Transactional
    public void updateUserStatus(Long userId, String newStatus, String reason, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        UserStatus status = UserStatus.valueOf(newStatus.toUpperCase().trim());
        user.setStatus(status);
        userRepository.save(user);

        recordAudit(principal, "USER", userId, status.name(),
                reason != null ? reason : "Status changed by admin");
    }

    @Transactional
    public void updateUserRole(Long userId, String newRole, String staffCode, String artistSpotifyId, String principal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String normalizedRole = newRole.toUpperCase().trim();
        if ("ARTIST".equals(normalizedRole)) {
            normalizedRole = "CONTENT_LEAD";
        }
        UserRole role = UserRole.valueOf(normalizedRole);
        user.setRole(role);
        if (artistSpotifyId != null && !artistSpotifyId.isBlank()) {
            user.setArtistSpotifyId(artistSpotifyId.trim());
        }
        userRepository.save(user);

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

        String tempPassword = generateTempPassword();
        user.setPassword(passwordEncoder.encode(tempPassword));
        userRepository.save(user);

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
            user.setFullname(data.get("fullName").trim());
        }
        if (data.containsKey("email") && data.get("email") != null) {
            user.setEmail(data.get("email").trim());
        }
        if (data.containsKey("phone")) {
            user.setPhone(data.get("phone"));
        }
        userRepository.save(user);
    }

    @Transactional
    public void createUser(Map<String, Object> data) {
        String username = (String) data.getOrDefault("username", "user_" + System.currentTimeMillis());
        String fullName = (String) data.getOrDefault("fullName", "User " + username);
        String email = (String) data.getOrDefault("email", username + "@moodify.com");
        String phone = (String) data.getOrDefault("phone", "0900000000");
        String roleStr = (String) data.getOrDefault("role", "USER");
        String statusStr = (String) data.getOrDefault("status", "ACTIVE");
        String password = (String) data.get("password");

        String r = roleStr.toUpperCase().trim();
        if ("ARTIST".equals(r)) {
            r = "CONTENT_LEAD";
        }
        UserRole role = UserRole.valueOf(r);
        UserStatus status = UserStatus.valueOf(statusStr.toUpperCase().trim());

        User user = new User();
        user.setUsername(username.trim());
        user.setFullname(fullName.trim());
        user.setEmail(email.trim());
        user.setPhone(phone);
        user.setRole(role);
        user.setStatus(status);
        // Mật khẩu do admin cung cấp; nếu bỏ trống thì sinh mật khẩu tạm ngẫu nhiên
        user.setPassword(passwordEncoder.encode(
                (password != null && !password.isBlank()) ? password.trim() : generateTempPassword()));
        userRepository.save(user);
    }

    public List<Map<String, Object>> getUserDevices(Long userId) {
        String sql = "SELECT id, user_id, device_uuid, platform, device_name, status, created_at FROM user_devices WHERE user_id = ?";
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
                    : "01/01/2026");
            return d;
        });
    }

    public void revokeDevice(Long deviceId) {
        jdbcTemplate.update("UPDATE user_devices SET status = 'REVOKED' WHERE id = ?", deviceId);
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
        q.limit(200);

        List<Track> list = mongoTemplate.find(q, Track.class);
        List<Map<String, Object>> result = new ArrayList<>();

        List<String> trackIds = list.stream().map(Track::getId).filter(Objects::nonNull).toList();
        Map<String, Long> playsMap = new HashMap<>();
        Map<String, Long> likesMap = new HashMap<>();

        if (!trackIds.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(trackIds.size(), "?"));
            try {
                String playsSql = "SELECT track_id, COUNT(*) as cnt FROM listening_history WHERE track_id IN (" + inSql + ") GROUP BY track_id";
                jdbcTemplate.query(playsSql, trackIds.toArray(), (rs, rowNum) -> {
                    playsMap.put(rs.getString("track_id"), rs.getLong("cnt"));
                    return null;
                });
            } catch (Exception ignored) {}

            try {
                String likesSql = "SELECT track_id, COUNT(*) as cnt FROM favorite_songs WHERE track_id IN (" + inSql + ") GROUP BY track_id";
                jdbcTemplate.query(likesSql, trackIds.toArray(), (rs, rowNum) -> {
                    likesMap.put(rs.getString("track_id"), rs.getLong("cnt"));
                    return null;
                });
            } catch (Exception ignored) {}
        }

        for (Track t : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("spotifyId", t.getSpotifyId() != null ? t.getSpotifyId() : t.getId());
            map.put("title", t.getName() != null ? t.getName() : "Không tên");
            map.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
            map.put("album", t.getAlbumName() != null ? t.getAlbumName() : "Single");
            map.put("genre", resolveTrackGenre(t));
            map.put("duration", t.getDurationFormatted() != null ? t.getDurationFormatted() : "3:30");
            map.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
            map.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            map.put("plays", playsMap.getOrDefault(t.getId(), 0L));
            map.put("likes", likesMap.getOrDefault(t.getId(), 0L));

            // Moderation & Audio Features
            String modStatus = t.getModerationStatus() != null ? t.getModerationStatus().toLowerCase().trim() : "published";
            if (modStatus.equals("taken_down")) {
                map.put("status", "taken_down");
            } else if (modStatus.equals("flagged") || modStatus.equals("pending")) {
                map.put("status", "flagged");
            } else {
                map.put("status", "published");
            }

            map.put("moderationScore", t.getModerationScore() != null ? t.getModerationScore().intValue() : 88);

            Track.AudioFeatures af = t.getAudioFeatures();
            if (af != null) {
                map.put("bpm", af.getBpm() != null ? af.getBpm().intValue() : 120);
                map.put("energy", af.getEnergy() != null ? af.getEnergy() : 0.72);
                map.put("danceability", af.getDanceability() != null ? af.getDanceability() : 0.65);
                map.put("valence", af.getValence() != null ? af.getValence() : 0.58);
                map.put("acousticness", af.getAcousticness() != null ? af.getAcousticness() : 0.25);
                map.put("keySignature", af.getKeySignature() != null ? af.getKeySignature() : "C Major");
            } else {
                map.put("bpm", 120);
                map.put("energy", 0.70);
                map.put("danceability", 0.65);
                map.put("valence", 0.55);
                map.put("acousticness", 0.30);
                map.put("keySignature", "C Major");
            }

            // Vibe Category determination
            double valence = map.get("valence") instanceof Number ? ((Number) map.get("valence")).doubleValue() : 0.5;
            double energy = map.get("energy") instanceof Number ? ((Number) map.get("energy")).doubleValue() : 0.5;
            if (energy > 0.75) map.put("vibeCategory", "Energetic");
            else if (valence < 0.45) map.put("vibeCategory", "Sadness");
            else if (energy < 0.55 && valence > 0.6) map.put("vibeCategory", "Chill");
            else if (energy < 0.5) map.put("vibeCategory", "Focus");
            else map.put("vibeCategory", "Romance");

            map.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "2026-09-01T00:00:00Z");
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
        try {
            jdbcTemplate.update("DELETE FROM favorite_songs WHERE track_id = ?", trackId);
            jdbcTemplate.update("DELETE FROM user_library_tracks WHERE track_id = ?", trackId);
            jdbcTemplate.update("DELETE FROM offline_downloads WHERE track_id = ?", trackId);
            jdbcTemplate.update("DELETE FROM platform_traffic_events WHERE target_id = ?", trackId);
            jdbcTemplate.update("DELETE FROM song_licenses WHERE track_id = ?", trackId);
            // listening_history tự động cascade xóa playback_events
            jdbcTemplate.update("DELETE FROM listening_history WHERE track_id = ?", trackId);
        } catch (Exception ignored) {}

        Query q = new Query(Criteria.where("_id").is(trackId));
        mongoTemplate.remove(q, Track.class);
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
        return inferGenreFromMetadata(t.getName(), t.getArtistName());
    }

    private String formatGenreName(String raw) {
        if (raw == null) return "V-Pop";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("vpop") || lower.contains("v-pop") || lower.equals("pop")) return "V-Pop";
        if (lower.contains("rap") || lower.contains("hiphop") || lower.contains("hip-hop")) return "Rap / Hip-Hop";
        if (lower.contains("indie")) return "Indie";
        if (lower.contains("r&b") || lower.contains("rnb")) return "R&B / Soul";
        if (lower.contains("edm") || lower.contains("remix") || lower.contains("dance")) return "EDM / Remix";
        if (lower.contains("ballad")) return "Ballad";
        if (lower.contains("lo-fi") || lower.contains("lofi")) return "Lo-Fi";
        if (lower.contains("rock")) return "Rock";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private String inferGenreFromMetadata(String title, String artist) {
        String text = ((title != null ? title : "") + " " + (artist != null ? artist : "")).toLowerCase(Locale.ROOT);
        if (text.contains("remix") || text.contains("dj ") || text.contains("wrc") || text.contains("drum") || text.contains("edm")) return "EDM / Remix";
        if (text.contains("đen") || text.contains("b ray") || text.contains("binz") || text.contains("pháp kiều") || text.contains("coldzy") || text.contains("bigdaddy") || text.contains("hieuthuhai") || text.contains("rap") || text.contains("dick")) return "Rap / Hip-Hop";
        if (text.contains("ngọt") || text.contains("the flob") || text.contains("indiek") || text.contains("lucidrari") || text.contains("ronboogz") || text.contains("yedira") || text.contains("ashen") || text.contains("t.r.i") || text.contains("vẫn thế") || text.contains("trong bao nỗi buồn")) return "Indie";
        if (text.contains("wren evans") || text.contains("kimmese") || text.contains("grey d")) return "R&B / Soul";
        if (text.contains("phạm hồng phước") || text.contains("hà nhi") || text.contains("duongg") || text.contains("buồn") || text.contains("mưa")) return "Ballad";
        return "V-Pop";
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
                    : "01/09/2026 08:00");
            map.put("resolvedAt", rs.getTimestamp("resolved_at") != null
                    ? rs.getTimestamp("resolved_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : null);

            // Fetch Mongo track metadata
            Track track = trackRepository.findById(contentId).orElse(null);
            if (track != null) {
                map.put("title", track.getName());
                map.put("coverUrl", track.getImageUrl() != null ? track.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                map.put("genre", resolveTrackGenre(track));
                map.put("lyricsPlain", track.getLyricsPlain());
                map.put("audioUrl", resolveAudioUrl(track.getLocalPath()));

                if (track.getAudioFeatures() != null) {
                    Map<String, Object> af = new HashMap<>();
                    af.put("bpm", track.getAudioFeatures().getBpm() != null ? track.getAudioFeatures().getBpm().intValue() : 120);
                    af.put("energy", track.getAudioFeatures().getEnergy() != null ? track.getAudioFeatures().getEnergy() : 0.7);
                    af.put("danceability", track.getAudioFeatures().getDanceability() != null ? track.getAudioFeatures().getDanceability() : 0.6);
                    af.put("valence", track.getAudioFeatures().getValence() != null ? track.getAudioFeatures().getValence() : 0.5);
                    af.put("acousticness", track.getAudioFeatures().getAcousticness() != null ? track.getAudioFeatures().getAcousticness() : 0.2);
                    af.put("keySignature", track.getAudioFeatures().getKeySignature() != null ? track.getAudioFeatures().getKeySignature() : "C Major");
                    map.put("audioFeatures", af);
                }
            } else {
                map.put("title", "Bài hát #" + contentId.substring(Math.max(0, contentId.length() - 6)));
                map.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                map.put("genre", "V-Pop");
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
    public List<Map<String, Object>> getSubscriptionTiers() {
        String sql = "SELECT * FROM subscription_tiers ORDER BY FIELD(id, 'FREE', 'INDIVIDUAL_BASIC', 'INDIVIDUAL_FULL', 'FAMILY'), id ASC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            row.put("entitlements", SubscriptionService.mapTierToEntitlements(row));
        }
        return rows;
    }

    /**
     * Cập nhật trực tiếp quyền lợi và giới hạn của một tầng dịch vụ (subscription_tiers).
     * Mọi gói cước gắn tier_id này sẽ ngay lập tức thừa hưởng cấu hình mới nhất mà không bị lệch dữ liệu.
     */
    public void updateSubscriptionTier(String tierId, Map<String, Object> data) {
        String name = (String) data.get("name");
        String description = (String) data.get("description");
        String adPolicy = (String) data.get("adPolicy");
        Integer adFreeDailyLimit = data.get("adFreeDailyLimit") != null ? ((Number) data.get("adFreeDailyLimit")).intValue() : null;
        String skipPolicy = (String) data.get("skipPolicy");
        Integer skipDailyLimit = data.get("skipDailyLimit") != null ? ((Number) data.get("skipDailyLimit")).intValue() : null;
        Boolean offlineAllowed = data.get("offlineAllowed") != null ? Boolean.parseBoolean(data.get("offlineAllowed").toString()) : null;
        Integer offlineMaxTracks = data.get("offlineMaxTracks") != null ? ((Number) data.get("offlineMaxTracks")).intValue() : null;
        Integer maxDevices = data.get("maxDevices") != null ? ((Number) data.get("maxDevices")).intValue() : null;
        Boolean syncedLyrics = data.get("syncedLyrics") != null ? Boolean.parseBoolean(data.get("syncedLyrics").toString()) : null;
        Boolean vipBadge = data.get("vipBadge") != null ? Boolean.parseBoolean(data.get("vipBadge").toString()) : null;
        Boolean familySharing = data.get("familySharing") != null ? Boolean.parseBoolean(data.get("familySharing").toString()) : null;
        Integer familyMembers = data.get("familyMembers") != null ? ((Number) data.get("familyMembers")).intValue() : null;

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

        jdbcTemplate.update(sql, name, description, adPolicy, adFreeDailyLimit, skipPolicy, skipDailyLimit,
                offlineAllowed, offlineMaxTracks, maxDevices, syncedLyrics, vipBadge,
                familySharing, familyMembers, tierId);
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
                "t.provider_transaction_id, t.status, t.paid_at " +
                "FROM payment_transactions t " +
                "JOIN subscriptions s ON t.subscription_id = s.id " +
                "JOIN users u ON s.user_id = u.id " +
                "JOIN service_packages p ON s.service_package_id = p.id " +
                "ORDER BY t.created_at DESC LIMIT 100";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> t = new HashMap<>();
            t.put("id", rs.getLong("id"));
            t.put("subscriptionId", rs.getLong("subscription_id"));
            t.put("userName", rs.getString("user_name"));
            t.put("userEmail", rs.getString("user_email"));
            t.put("packageName", rs.getString("package_name"));
            t.put("amount", rs.getDouble("amount"));
            t.put("paymentMethod", rs.getString("payment_method") != null ? rs.getString("payment_method") : "Online");
            t.put("provider", rs.getString("provider") != null ? rs.getString("provider") : "VNPAY");
            t.put("providerTransactionId", rs.getString("provider_transaction_id") != null ? rs.getString("provider_transaction_id") : "TXN-" + rs.getLong("id"));
            t.put("status", rs.getString("status"));
            t.put("paidAt", rs.getTimestamp("paid_at") != null
                    ? rs.getTimestamp("paid_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "15/09/2026 14:00");
            return t;
        });
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
            sub.put("tierId", rs.getString("tier_id") != null ? rs.getString("tier_id") : "INDIVIDUAL_BASIC");
            sub.put("tierName", rs.getString("tier_name") != null ? rs.getString("tier_name") : "Gói Tiết Kiệm");
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
            sub.put("tierId", rs.getString("tier_id") != null ? rs.getString("tier_id") : "INDIVIDUAL_BASIC");
            sub.put("tierName", rs.getString("tier_name") != null ? rs.getString("tier_name") : "Gói Tiết Kiệm");
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
            d.put("contactEmail", rs.getString("contact_email") != null ? rs.getString("contact_email") : "contact@distributor.com");
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
            c.put("signedDate", rs.getDate("signed_date") != null ? rs.getDate("signed_date").toString() : "2026-01-01");
            c.put("effectiveFrom", rs.getDate("effective_from") != null ? rs.getDate("effective_from").toString() : "2026-01-01");
            c.put("effectiveTo", rs.getDate("effective_to") != null ? rs.getDate("effective_to").toString() : "2027-12-31");
            c.put("revenueShare", rs.getDouble("revenue_share"));
            c.put("status", rs.getString("status"));
            c.put("documentUrl", rs.getString("document_url") != null ? rs.getString("document_url") : "https://example.com/contract.pdf");
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
            l.put("issueDate", rs.getDate("issue_date") != null ? rs.getDate("issue_date").toString() : "01/01/2026");
            l.put("expiryDate", rs.getDate("expiry_date") != null ? rs.getDate("expiry_date").toString() : "Vĩnh viễn");
            l.put("status", rs.getString("status"));
            l.put("documentUrl", rs.getString("document_songlicenses_url"));

            // Track title from MongoDB
            Track t = trackRepository.findById(trackId).orElse(null);
            l.put("trackTitle", t != null ? t.getName() : "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
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
    // 8. FAVORITES MANAGEMENT (MySQL)
    // ==========================================
    public Map<String, Object> getFavorites(String type, String query, int page, int size) {
        String favType = (type != null && !type.isBlank()) ? type.toUpperCase().trim() : "SONG";
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if ("ARTIST".equals(favType)) {
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.artist_id as target_id, 'ARTIST' as item_type, f.created_at " +
                "FROM favorite_artists f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        } else if ("ALBUM".equals(favType)) {
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.album_id as target_id, 'ALBUM' as item_type, f.created_at " +
                "FROM favorite_albums f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        } else {
            sql.append(
                "SELECT f.id, f.user_id, u.full_name as user_name, u.email as user_email, u.avatar_url as user_avatar, " +
                "f.track_id as target_id, 'SONG' as item_type, f.created_at " +
                "FROM favorite_songs f " +
                "JOIN users u ON f.user_id = u.id " +
                "WHERE 1=1 "
            );
        }

        if (query != null && !query.isBlank()) {
            sql.append("AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?) ");
            String q = "%" + query.toLowerCase().trim() + "%";
            params.add(q);
            params.add(q);
        }

        String countSql = "SELECT COUNT(*) FROM (" + sql.toString() + ") AS c_tbl";
        Long total = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);

        sql.append("ORDER BY f.created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

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
            m.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    : "Hôm nay");

            if ("SONG".equals(itType)) {
                Track t = trackRepository.findById(targetId).orElse(null);
                if (t != null) {
                    m.put("targetTitle", t.getName());
                    m.put("targetSubtitle", t.getArtistName());
                    m.put("targetCoverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                    m.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
                } else {
                    m.put("targetTitle", "Bài hát #" + targetId.substring(Math.max(0, targetId.length() - 6)));
                    m.put("targetSubtitle", "V-Pop");
                    m.put("targetCoverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                    m.put("audioUrl", resolveAudioUrl(null));
                }
            } else if ("ARTIST".equals(itType)) {
                m.put("targetTitle", "Nghệ sĩ #" + targetId.substring(Math.max(0, targetId.length() - 6)));
                m.put("targetSubtitle", "Nghệ sĩ đã xác minh");
                m.put("targetCoverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
            } else {
                m.put("targetTitle", "Album #" + targetId.substring(Math.max(0, targetId.length() - 6)));
                m.put("targetSubtitle", "Album tuyển chọn");
                m.put("targetCoverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
            }
            return m;
        });

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total != null ? total : 0);
        result.put("page", page);
        result.put("size", size);
        result.put("type", favType);
        return result;
    }

    public List<Map<String, Object>> getFavoriteLeaderboard() {
        String sql = "SELECT track_id, COUNT(*) as fav_count FROM favorite_songs GROUP BY track_id ORDER BY fav_count DESC LIMIT 10";
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            String trackId = rs.getString("track_id");
            m.put("targetId", trackId);
            m.put("favoriteCount", rs.getLong("fav_count"));
            m.put("type", "SONG");
            return m;
        });

        for (Map<String, Object> r : rows) {
            String trackId = (String) r.get("targetId");
            Track t = trackRepository.findById(trackId).orElse(null);
            if (t != null) {
                r.put("targetTitle", t.getName());
                r.put("targetSubtitle", t.getArtistName());
                r.put("targetCoverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("targetTitle", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                r.put("targetSubtitle", "Moodify Artist");
                r.put("targetCoverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("audioUrl", resolveAudioUrl(null));
            }
        }
        return rows;
    }

    public void deleteFavorite(Long id) {
        int deleted = jdbcTemplate.update("DELETE FROM favorite_songs WHERE id = ?", id);
        if (deleted == 0) {
            deleted = jdbcTemplate.update("DELETE FROM favorite_artists WHERE id = ?", id);
        }
        if (deleted == 0) {
            jdbcTemplate.update("DELETE FROM favorite_albums WHERE id = ?", id);
        }
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
            
            java.sql.Timestamp startedAtTs = rs.getTimestamp("started_at");
            java.sql.Timestamp endedAtTs = rs.getTimestamp("ended_at");
            m.put("startedAt", startedAtTs != null ? startedAtTs.toLocalDateTime().format(dtf) : "");
            m.put("endedAt", endedAtTs != null ? endedAtTs.toLocalDateTime().format(dtf) : "");
            
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
                Track t = trackRepository.findById(trackId).orElse(null);
                if (t != null) {
                    item.put("trackTitle", t.getName());
                    item.put("artistName", t.getArtistName() != null ? t.getArtistName() : "Moodify Artist");
                    item.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                    item.put("totalDuration", t.getDurationFormatted() != null ? t.getDurationFormatted() : "N/A");
                    item.put("genre", resolveTrackGenre(t));
                } else {
                    item.put("trackTitle", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                    item.put("artistName", "Moodify Artist");
                    item.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                    item.put("totalDuration", "N/A");
                    item.put("genre", "V-Pop");
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
            java.sql.Timestamp occurredTs = rs.getTimestamp("occurred_at");
            m.put("occurredAt", occurredTs != null ? occurredTs.toLocalDateTime().format(dtf) : "");
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
            "SELECT COUNT(*) FROM playback_events WHERE event_type = 'COMPLETE'", Long.class
        );

        summary.put("totalSessions", totalSessions != null ? totalSessions : 0L);
        summary.put("totalDurationMs", totalDurationMs != null ? totalDurationMs : 0L);
        summary.put("totalHours", totalDurationMs != null ? Math.round((totalDurationMs / 3600000.0) * 10.0) / 10.0 : 0.0);
        summary.put("sessionsLast24h", sessionsLast24h != null ? sessionsLast24h : 0L);
        summary.put("completeEvents", completeEvents != null ? completeEvents : 0L);
        
        double completionRate = (totalSessions != null && totalSessions > 0)
            ? Math.round(((double) (completeEvents != null ? completeEvents : 0) / totalSessions) * 100.0)
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
                    "SELECT COUNT(*) FROM favorite_songs WHERE track_id = ?",
                    new Object[]{trackId},
                    Long.class
                );
            } catch (Exception ignored) {}
            r.put("favoriteCount", favCount != null ? favCount : 0L);

            Track t = trackRepository.findById(trackId).orElse(null);
            int trackDurationMs = (t != null && t.getDurationMs() != null && t.getDurationMs() > 0)
                ? t.getDurationMs()
                : 210000;

            double completionRate = Math.min(100.0, Math.round(((double) avgDurationMs / trackDurationMs * 100.0) * 10.0) / 10.0);
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
                r.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", t.getDurationFormatted() != null ? t.getDurationFormatted() : formatDurationSeconds(trackDurationMs / 1000));
                r.put("genre", resolveTrackGenre(t));
                r.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            } else {
                r.put("title", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                r.put("artist", "Moodify Artist");
                r.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
                r.put("duration", formatDurationSeconds(trackDurationMs / 1000));
                r.put("genre", "V-Pop");
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

            java.sql.Timestamp occurredTs = rs.getTimestamp("occurred_at");
            item.put("occurredAt", occurredTs != null ? occurredTs.toLocalDateTime().format(dtf) : "");

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
            Track t = trackRepository.findById(trackId).orElse(null);
            if (t != null) {
                it.put("trackTitle", t.getName());
                it.put("artistName", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ");
                it.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
            } else {
                it.put("trackTitle", "Bài hát #" + trackId.substring(Math.max(0, trackId.length() - 6)));
                it.put("artistName", "Moodify Artist");
                it.put("coverUrl", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
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
}
