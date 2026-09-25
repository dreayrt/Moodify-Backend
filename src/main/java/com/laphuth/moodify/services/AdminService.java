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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private static final String AUDIO_SERVER_BASE_URL = "https://musiccollector.kandes.io.vn/";
    private static final String DEFAULT_REAL_AUDIO_URL = "https://musiccollector.kandes.io.vn/data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3";

    private String resolveAudioUrl(String localPath) {
        if (localPath == null || localPath.trim().isEmpty()) {
            return DEFAULT_REAL_AUDIO_URL;
        }
        String clean = localPath.trim().replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        return AUDIO_SERVER_BASE_URL + clean;
    }

    public AdminService(
            JdbcTemplate jdbcTemplate,
            TrackRepository trackRepository,
            MongoTemplate mongoTemplate,
            UserRepository userRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.trackRepository = trackRepository;
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
    }

    // ==========================================
    // 1. OVERVIEW & KPI METRICS
    // ==========================================
    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new HashMap<>();

        // 1. MySQL metrics
        Long totalUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        Long activeUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'", Long.class);
        Long bannedUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'BANNED'", Long.class);
        Long artistUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ARTIST'", Long.class);
        Long moderatorUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'MODERATOR'", Long.class);

        Double totalRevenue = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_transactions WHERE status = 'SUCCESS'", Double.class);
        Long activeSubscriptions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM subscriptions WHERE status = 'ACTIVE'", Long.class);

        // 2. MongoDB metrics
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

        return overview;
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
            sql.append("AND u.role = ? ");
            params.add(role.toUpperCase().trim());
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
    public void updateUserStatus(Long userId, String newStatus, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        UserStatus status = UserStatus.valueOf(newStatus.toUpperCase().trim());
        user.setStatus(status);
        userRepository.save(user);

        // Record audit if table exists
        try {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (operator_user_id, target_entity, target_id, action, details) " +
                    "VALUES (4, 'USER', ?, ?, ?)",
                    userId, status.name(), reason != null ? reason : "Status changed by admin"
            );
        } catch (Exception ignored) {}
    }

    @Transactional
    public void updateUserRole(Long userId, String newRole, String staffCode, String artistSpotifyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        UserRole role = UserRole.valueOf(newRole.toUpperCase().trim());
        user.setRole(role);
        if (artistSpotifyId != null && !artistSpotifyId.isBlank()) {
            user.setArtistSpotifyId(artistSpotifyId.trim());
        }
        userRepository.save(user);
    }

    @Transactional
    public void resetUserPassword(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        // Default password '123456'
        user.setPassword("$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS");
        userRepository.save(user);

        try {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (operator_user_id, target_entity, target_id, action, details) " +
                    "VALUES (4, 'USER', ?, 'RESET_PASSWORD', 'Mật khẩu đã được đặt lại về 123456')",
                    userId
            );
        } catch (Exception ignored) {}
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

        UserRole role = UserRole.valueOf(roleStr.toUpperCase().trim());
        UserStatus status = UserStatus.valueOf(statusStr.toUpperCase().trim());

        User user = new User();
        user.setUsername(username.trim());
        user.setFullname(fullName.trim());
        user.setEmail(email.trim());
        user.setPhone(phone);
        user.setRole(role);
        user.setStatus(status);
        user.setPassword("$2a$10$.oU5/HfO7k7j8ceKwXF1F.r0d0IhVmxG0ifw1qWYJLxQ4.Eh59UKS");
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        try {
            jdbcTemplate.update("DELETE FROM moderation_reviews WHERE moderator_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM payment_transactions WHERE subscription_id IN (SELECT id FROM subscriptions WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM subscriptions WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", userId);
        } catch (Exception ignored) {}
        userRepository.deleteById(userId);
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

        for (Track t : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("spotifyId", t.getSpotifyId() != null ? t.getSpotifyId() : t.getId());
            map.put("title", t.getName() != null ? t.getName() : "Không tên");
            map.put("artist", t.getArtistName() != null ? t.getArtistName() : "Nghệ sĩ ẩn danh");
            map.put("album", t.getAlbumName() != null ? t.getAlbumName() : "Single");
            map.put("genre", (t.getGenres() != null && !t.getGenres().isEmpty()) ? t.getGenres().get(0) : "V-Pop");
            map.put("duration", t.getDurationFormatted() != null ? t.getDurationFormatted() : "3:30");
            map.put("coverUrl", t.getImageUrl() != null ? t.getImageUrl() : "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=240");
            map.put("audioUrl", resolveAudioUrl(t.getLocalPath()));
            map.put("plays", t.getPopularity() != null ? t.getPopularity() * 1250 : 5000);
            map.put("likes", t.getPopularity() != null ? t.getPopularity() * 85 : 420);

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
                map.put("genre", track.getGenres() != null && !track.getGenres().isEmpty() ? track.getGenres().get(0) : "V-Pop");
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

    // ==========================================
    // 5. PACKAGES & MONETIZATION (MySQL)
    // ==========================================
    public List<Map<String, Object>> getPackages() {
        String sql =
                "SELECT p.id, p.name, p.description, p.price, p.duration_days, p.display_order, p.status, " +
                "COUNT(s.id) as subscribers_count " +
                "FROM service_packages p " +
                "LEFT JOIN subscriptions s ON p.id = s.service_package_id AND s.status = 'ACTIVE' " +
                "GROUP BY p.id " +
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
            p.put("subscribersCount", rs.getInt("subscribers_count"));
            return p;
        });
    }

    public void updatePackagePrice(Long packageId, Double newPrice) {
        jdbcTemplate.update("UPDATE service_packages SET price = ?, updated_at = NOW() WHERE id = ?", newPrice, packageId);
    }

    public void createPackage(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "Gói Cước Mới");
        String description = (String) data.getOrDefault("description", "");
        Double price = data.get("price") instanceof Number ? ((Number) data.get("price")).doubleValue() : 0.0;
        Integer durationDays = data.get("durationDays") instanceof Number ? ((Number) data.get("durationDays")).intValue() : 30;
        Integer displayOrder = data.get("displayOrder") instanceof Number ? ((Number) data.get("displayOrder")).intValue() : 1;
        String status = (String) data.getOrDefault("status", "ACTIVE");

        String sql = "INSERT INTO service_packages (name, description, price, duration_days, display_order, status, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())";
        jdbcTemplate.update(sql, name, description, price, durationDays, displayOrder, status.toUpperCase());
    }

    public void updatePackageDetails(Long packageId, Map<String, Object> data) {
        String name = (String) data.get("name");
        String description = (String) data.get("description");
        Double price = data.get("price") instanceof Number ? ((Number) data.get("price")).doubleValue() : null;
        Integer durationDays = data.get("durationDays") instanceof Number ? ((Number) data.get("durationDays")).intValue() : null;
        Integer displayOrder = data.get("displayOrder") instanceof Number ? ((Number) data.get("displayOrder")).intValue() : null;
        String status = (String) data.get("status");

        String sql = "UPDATE service_packages SET " +
                     "name = COALESCE(?, name), " +
                     "description = COALESCE(?, description), " +
                     "price = COALESCE(?, price), " +
                     "duration_days = COALESCE(?, duration_days), " +
                     "display_order = COALESCE(?, display_order), " +
                     "status = COALESCE(?, status), " +
                     "updated_at = NOW() " +
                     "WHERE id = ?";
        jdbcTemplate.update(sql, name, description, price, durationDays, displayOrder, status != null ? status.toUpperCase() : null, packageId);
    }

    public void togglePackageStatus(Long packageId) {
        String sql = "UPDATE service_packages SET status = CASE WHEN status = 'ACTIVE' THEN 'INACTIVE' ELSE 'ACTIVE' END, updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, packageId);
    }

    @Transactional
    public void deletePackage(Long packageId) {
        try {
            jdbcTemplate.update("DELETE FROM payment_transactions WHERE subscription_id IN (SELECT id FROM subscriptions WHERE service_package_id = ?)", packageId);
            jdbcTemplate.update("DELETE FROM subscriptions WHERE service_package_id = ?", packageId);
        } catch (Exception ignored) {}
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

    public void refundTransaction(Long transactionId) {
        jdbcTemplate.update("UPDATE payment_transactions SET status = 'REFUNDED' WHERE id = ?", transactionId);
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
}
