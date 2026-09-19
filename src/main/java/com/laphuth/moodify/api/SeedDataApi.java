package com.laphuth.moodify.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.repositories.TrackRepository;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/seed")
public class SeedDataApi {

    private final TrackRepository trackRepository;
    private final MongoTemplate mongoTemplate;

    public SeedDataApi(TrackRepository trackRepository, MongoTemplate mongoTemplate) {
        this.trackRepository = trackRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSeedStatus() {
        long count = trackRepository.count();
        Map<String, Object> res = new HashMap<>();
        res.put("totalTracks", count);
        res.put("status", count >= 147 ? "ready" : "need_seed");
        return ResponseEntity.ok(res);
    }

    @PostMapping("/tracks")
    public ResponseEntity<String> seedTracks() {
        long existing = trackRepository.count();
        if (existing > 0) {
            return ResponseEntity.ok("Database already has " + existing + " tracks. Use /api/seed/tracks/force to reset and reseed.");
        }
        doSeed();
        return ResponseEntity.ok("Successfully seeded " + getTrackCount() + " Vietnamese tracks!");
    }

    @PostMapping("/tracks/force")
    public ResponseEntity<String> forceSeedTracks() {
        // Clear all existing tracks first, then seed 147 Vietnamese tracks
        mongoTemplate.getCollection("tracks").deleteMany(new Document());
        doSeed();
        return ResponseEntity.ok("Force-seeded " + getTrackCount() + " Vietnamese tracks!");
    }

    @PostMapping("/tracks/vietnamese")
    public ResponseEntity<String> seedVietnameseOnly() {
        long count = trackRepository.count();
        if (count < 147) {
            forceSeedTracks();
        }
        return ResponseEntity.ok("Total Vietnamese tracks in database: " + getTrackCount());
    }

    // Called on startup - no auth needed
    public void seedTracksOnStartup() {
        long count = trackRepository.count();
        if (count == 0) {
            System.out.println("Seeding 147 Vietnamese tracks on startup...");
            doSeed();
            System.out.println("Seeded " + getTrackCount() + " Vietnamese tracks successfully!");
        } else {
            System.out.println("Database already has " + count + " tracks, skipping seed.");
        }
    }

    private void doSeed() {
        if (trackRepository.count() > 0) {
            System.out.println("Database already contains tracks. Skipping seed to protect existing data.");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("db/tracks147.json");
            if (!resource.exists()) {
                System.err.println("db/tracks147.json not found on classpath!");
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> rawList = mapper.readValue(
                resource.getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {}
            );

            List<Document> docsToInsert = new ArrayList<>();
            for (Map<String, Object> map : rawList) {
                String jsonString = mapper.writeValueAsString(map);
                Document doc = Document.parse(jsonString);

                List<String> currentGenres = doc.getList("genres", String.class, new ArrayList<>());
                List<String> rawGenres = doc.getList("genres_raw", String.class, new ArrayList<>());
                Set<String> enriched = new LinkedHashSet<>(currentGenres);

                enriched.add("v-pop");
                enriched.add("vpop");
                enriched.add("vietnamese");

                for (String raw : rawGenres) {
                    if (raw == null) continue;
                    String r = raw.toLowerCase(Locale.ROOT).trim();
                    enriched.add(r);
                    if (r.contains("hiphop") || r.contains("rap")) {
                        enriched.add("hiphop");
                        enriched.add("hip-hop");
                        enriched.add("rap");
                    } else if (r.contains("indie")) {
                        enriched.add("indie");
                        enriched.add("indie pop");
                    } else if (r.contains("pop") || r.contains("vpop")) {
                        enriched.add("pop");
                        enriched.add("dance pop");
                    }
                }

                String name = doc.getString("name");
                if (name != null && (name.toLowerCase().contains("remix") || name.toLowerCase().contains("edm"))) {
                    enriched.add("edm");
                    enriched.add("dance");
                    enriched.add("remix");
                }

                doc.put("genres", new ArrayList<>(enriched));
                doc.put("_class", "com.laphuth.moodify.entities.Track");
                docsToInsert.add(doc);
            }

            mongoTemplate.getCollection("tracks").insertMany(docsToInsert);
            System.out.println("Successfully seeded " + docsToInsert.size() + " Vietnamese tracks!");
        } catch (Exception e) {
            System.err.println("Error seeding 147 tracks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private long getTrackCount() {
        return trackRepository.count();
    }
}
