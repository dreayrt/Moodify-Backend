package com.laphuth.moodify.services;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quản lý quảng cáo của Moodify: danh mục quảng cáo (ad categories)
 * và chiến dịch quảng cáo dạng âm thanh (audio ad campaigns) phát trong player.
 *
 * Dữ liệu lưu trong MongoDB (collections "ad_categories" và "ad_campaigns",
 * tự tạo khi chạy) — KHÔNG thay đổi schema MySQL.
 */
@Service
public class AdService {

    private final MongoTemplate mongoTemplate;

    private static final String CAMPAIGN_COLLECTION = "ad_campaigns";
    private static final String CATEGORY_COLLECTION = "ad_categories";

    public AdService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==========================================
    // DANH MỤC QUẢNG CÁO (Ad Categories)
    // ==========================================
    public List<Map<String, Object>> getCategories() {
        Query query = Query.query(new Criteria()).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        List<Document> docs = mongoTemplate.find(query, Document.class, CATEGORY_COLLECTION);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            String categoryId = doc.getObjectId("_id").toHexString();
            long campaignCount = mongoTemplate.count(
                    Query.query(Criteria.where("categoryId").is(categoryId)), CAMPAIGN_COLLECTION);
            Map<String, Object> c = new HashMap<>();
            c.put("id", categoryId);
            c.put("name", doc.getString("name"));
            c.put("description", doc.getString("description"));
            c.put("status", doc.getString("status") != null ? doc.getString("status") : "ACTIVE");
            c.put("campaignCount", campaignCount);
            result.add(c);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createCategory(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên danh mục quảng cáo là bắt buộc.");
        }
        Document existing = mongoTemplate.findOne(
                Query.query(Criteria.where("name").regex("^" + java.util.regex.Pattern.quote(name.trim()) + "$", "i")),
                Document.class, CATEGORY_COLLECTION);
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã tồn tại danh mục với tên này.");
        }

        Document doc = new Document();
        doc.put("name", name.trim());
        doc.put("description", (description != null && !description.isBlank()) ? description.trim() : null);
        doc.put("status", "ACTIVE");
        doc.put("createdAt", new Date());
        doc.put("updatedAt", new Date());
        mongoTemplate.insert(doc, CATEGORY_COLLECTION);

        return Map.of("success", true, "id", doc.getObjectId("_id").toHexString(), "message", "Đã tạo danh mục quảng cáo.");
    }

    @Transactional
    public void updateCategory(String id, String name, String description, String status) {
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update().set("updatedAt", new Date());
        if (name != null && !name.isBlank()) update.set("name", name.trim());
        if (description != null && !description.isBlank()) update.set("description", description.trim());
        if (status != null && !status.isBlank()) {
            update.set("status", status.toUpperCase().trim());
            // Đồng bộ trạng thái danh mục xuống các chiến dịch thuộc danh mục đó
            mongoTemplate.updateMulti(
                    Query.query(Criteria.where("categoryId").is(id)),
                    Update.update("categoryStatus", status.toUpperCase().trim()),
                    CAMPAIGN_COLLECTION);
        }
        mongoTemplate.updateFirst(query, update, CATEGORY_COLLECTION);
    }

    @Transactional
    public void deleteCategory(String id) {
        long used = mongoTemplate.count(Query.query(Criteria.where("categoryId").is(id)), CAMPAIGN_COLLECTION);
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Danh mục đang chứa " + used + " chiến dịch, không thể xóa. Hãy chuyển các chiến dịch sang danh mục khác trước.");
        }
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), CATEGORY_COLLECTION);
    }

    // ==========================================
    // CHIẾN DỊCH QUẢNG CÁO (Ad Campaigns)
    // ==========================================
    public List<Map<String, Object>> getCampaigns(String status, String categoryId) {
        List<Criteria> conditions = new ArrayList<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            conditions.add(Criteria.where("status").is(status.toUpperCase().trim()));
        }
        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            conditions.add(Criteria.where("categoryId").is(categoryId));
        }

        Query query = conditions.isEmpty()
                ? Query.query(new Criteria())
                : Query.query(new Criteria().andOperator(conditions.toArray(new Criteria[0])));
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Document> docs = mongoTemplate.find(query, Document.class, CAMPAIGN_COLLECTION);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(mapCampaign(doc));
        }
        return result;
    }

    private Map<String, Object> mapCampaign(Document doc) {
        Map<String, Object> a = new HashMap<>();
        a.put("id", doc.getObjectId("_id").toHexString());
        a.put("categoryId", doc.getString("categoryId"));
        a.put("categoryName", doc.getString("categoryName") != null ? doc.getString("categoryName") : "Chưa phân loại");
        a.put("title", doc.getString("title"));
        a.put("advertiser", doc.getString("advertiser"));
        a.put("audioUrl", doc.getString("audioUrl"));
        a.put("durationSeconds", doc.getInteger("durationSeconds"));
        a.put("weight", doc.getInteger("weight") != null ? doc.getInteger("weight") : 1);
        a.put("status", doc.getString("status") != null ? doc.getString("status") : "ACTIVE");
        a.put("startDate", doc.getDate("startDate") != null ? doc.getDate("startDate").toString() : null);
        a.put("endDate", doc.getDate("endDate") != null ? doc.getDate("endDate").toString() : null);
        a.put("impressionCount", doc.getLong("impressionCount") != null ? doc.getLong("impressionCount") : 0L);
        a.put("createdAt", doc.getDate("createdAt") != null ? doc.getDate("createdAt").toString() : null);
        return a;
    }

    @Transactional
    public Map<String, Object> createCampaign(Map<String, Object> data) {
        String title = strOrNull(data.get("title"));
        String audioUrl = strOrNull(data.get("audioUrl"));
        if (title == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề chiến dịch quảng cáo là bắt buộc.");
        }
        if (audioUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cần tải lên tệp âm thanh quảng cáo (hoặc cung cấp audioUrl).");
        }

        Document doc = new Document();
        applyCampaignFields(doc, data);
        doc.put("title", title);
        doc.put("audioUrl", audioUrl);
        doc.put("impressionCount", 0L);
        doc.put("createdAt", new Date());
        doc.put("updatedAt", new Date());
        mongoTemplate.insert(doc, CAMPAIGN_COLLECTION);

        return Map.of("success", true, "id", doc.getObjectId("_id").toHexString());
    }

    @Transactional
    public void updateCampaign(String id, Map<String, Object> data) {
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update().set("updatedAt", new Date());
        if (strOrNull(data.get("title")) != null) update.set("title", strOrNull(data.get("title")));
        if (strOrNull(data.get("audioUrl")) != null) update.set("audioUrl", strOrNull(data.get("audioUrl")));
        if (strOrNull(data.get("advertiser")) != null) update.set("advertiser", strOrNull(data.get("advertiser")));
        if (strOrNull(data.get("categoryId")) != null) {
            String categoryId = strOrNull(data.get("categoryId"));
            update.set("categoryId", categoryId);
            update.set("categoryName", resolveCategoryName(categoryId));
        }
        if (intOrNull(data.get("durationSeconds")) != null) update.set("durationSeconds", intOrNull(data.get("durationSeconds")));
        if (intOrNull(data.get("weight")) != null) update.set("weight", intOrNull(data.get("weight")));
        if (strOrNull(data.get("status")) != null) update.set("status", strOrNull(data.get("status")).toUpperCase());
        if (dateOrNull(data.get("startDate")) != null) update.set("startDate", dateOrNull(data.get("startDate")));
        if (dateOrNull(data.get("endDate")) != null) update.set("endDate", dateOrNull(data.get("endDate")));
        mongoTemplate.updateFirst(query, update, CAMPAIGN_COLLECTION);
    }

    @Transactional
    public void toggleCampaignStatus(String id) {
        Document doc = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, CAMPAIGN_COLLECTION);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chiến dịch quảng cáo.");
        }
        String next = "ACTIVE".equalsIgnoreCase(doc.getString("status")) ? "INACTIVE" : "ACTIVE";
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                Update.update("status", next).set("updatedAt", new Date()),
                CAMPAIGN_COLLECTION);
    }

    @Transactional
    public void deleteCampaign(String id) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), CAMPAIGN_COLLECTION);
    }

    /** Tải tệp âm thanh quảng cáo lên thư mục uploads/ads và trả về URL public. */
    public Map<String, Object> uploadAdAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tệp âm thanh quảng cáo là bắt buộc.");
        }
        String contentType = file.getContentType();
        boolean isAudio = contentType != null && contentType.startsWith("audio/");
        boolean isMp3ByName = file.getOriginalFilename() != null
                && file.getOriginalFilename().toLowerCase().endsWith(".mp3");
        if (!isAudio && !isMp3ByName) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ chấp nhận tệp âm thanh (audio/mp3).");
        }

        try {
            Path uploadDir = Paths.get("uploads", "ads");
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            String original = file.getOriginalFilename();
            String extension = ".mp3";
            if (original != null && original.contains(".")) {
                extension = original.substring(original.lastIndexOf("."));
            }
            String fileName = "ad_" + UUID.randomUUID() + extension;
            Path filePath = uploadDir.resolve(fileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            return Map.of("success", true, "url", "/uploads/ads/" + fileName);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể lưu tệp âm thanh: " + e.getMessage());
        }
    }

    // ==========================================
    // PHÁT QUẢNG CÁO CHO PLAYER (public)
    // ==========================================

    /** Danh sách quảng cáo đang hiệu lực để player phát ngẫu nhiên có trọng số. */
    public List<Map<String, Object>> getActiveAds() {
        Date now = new Date();
        Criteria effective = new Criteria().andOperator(
                Criteria.where("status").is("ACTIVE"),
                new Criteria().orOperator(
                        Criteria.where("startDate").is(null),
                        Criteria.where("startDate").lte(now)),
                new Criteria().orOperator(
                        Criteria.where("endDate").is(null),
                        Criteria.where("endDate").gte(now)));

        Query query = Query.query(effective)
                .with(Sort.by(Sort.Direction.DESC, "weight"))
                .limit(20);
        List<Document> docs = mongoTemplate.find(query, Document.class, CAMPAIGN_COLLECTION);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> a = new HashMap<>();
            a.put("id", doc.getObjectId("_id").toHexString());
            a.put("title", doc.getString("title"));
            a.put("advertiser", doc.getString("advertiser"));
            a.put("audioUrl", doc.getString("audioUrl"));
            a.put("durationSeconds", doc.getInteger("durationSeconds"));
            result.add(a);
        }
        return result;
    }

    @Transactional
    public void recordImpression(String adId) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(adId)),
                new Update().inc("impressionCount", 1),
                CAMPAIGN_COLLECTION);
    }

    // ---------- helpers ----------
    private void applyCampaignFields(Document doc, Map<String, Object> data) {
        String categoryId = strOrNull(data.get("categoryId"));
        doc.put("categoryId", categoryId);
        doc.put("categoryName", categoryId != null ? resolveCategoryName(categoryId) : null);
        doc.put("advertiser", strOrNull(data.get("advertiser")));
        doc.put("durationSeconds", intOrNull(data.get("durationSeconds")));
        doc.put("weight", intOrNull(data.get("weight")) != null ? intOrNull(data.get("weight")) : 1);
        String status = strOrNull(data.get("status"));
        doc.put("status", (status != null && "INACTIVE".equalsIgnoreCase(status)) ? "INACTIVE" : "ACTIVE");
        doc.put("startDate", dateOrNull(data.get("startDate")));
        doc.put("endDate", dateOrNull(data.get("endDate")));
    }

    private String resolveCategoryName(String categoryId) {
        Document cat = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(categoryId)), Document.class, CATEGORY_COLLECTION);
        return cat != null ? cat.getString("name") : null;
    }

    private String strOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Integer intOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Date dateOrNull(Object v) {
        String s = strOrNull(v);
        if (s == null) return null;
        try {
            String normalized = s.replace("T", " ");
            if (normalized.length() > 19) normalized = normalized.substring(0, 19);
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(normalized));
        } catch (Exception e) {
            try {
                return java.sql.Date.valueOf(s.substring(0, 10));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
