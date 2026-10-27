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
        ensureArtistProfiles();
    }

    /**
     * The tracks collection carries artist_spotify_id / artist_name on every track, but the
     * artists collection is what Content Lead catalog lookups resolve against
     * (ContentLeadCatalogService). Derive one artist profile per distinct artist_spotify_id
     * and insert only the missing ones so repeated startups never duplicate data.
     */
    private void ensureArtistProfiles() {
        try {
            Set<String> existingSpotifyIds = new HashSet<>();
            mongoTemplate.getCollection("artists")
                .find()
                .projection(new Document("spotify_id", 1))
                .forEach(doc -> {
                    Object spotifyId = doc.get("spotify_id");
                    if (spotifyId != null && !spotifyId.toString().isBlank()) {
                        existingSpotifyIds.add(spotifyId.toString());
                    }
                });

            Map<String, List<Document>> tracksByArtist = new LinkedHashMap<>();
            mongoTemplate.getCollection("tracks")
                .find()
                .projection(new Document("artist_spotify_id", 1)
                    .append("artist_name", 1)
                    .append("image_url", 1)
                    .append("genres", 1))
                .forEach(doc -> {
                    Object spotifyId = doc.get("artist_spotify_id");
                    if (spotifyId == null || spotifyId.toString().isBlank()) {
                        return;
                    }
                    tracksByArtist
                        .computeIfAbsent(spotifyId.toString(), k -> new ArrayList<>())
                        .add(doc);
                });

            java.util.Date now = new java.util.Date();
            List<Document> artistsToInsert = new ArrayList<>();
            for (Map.Entry<String, List<Document>> entry : tracksByArtist.entrySet()) {
                String spotifyId = entry.getKey();
                if (existingSpotifyIds.contains(spotifyId)) {
                    continue;
                }
                List<Document> artistTracks = entry.getValue();

                Map<String, Integer> nameTally = new HashMap<>();
                String preferredImage = null;
                String fallbackImage = null;
                Set<String> genresRaw = new LinkedHashSet<>();
                for (Document track : artistTracks) {
                    Object artistName = track.get("artist_name");
                    if (artistName != null && !artistName.toString().isBlank()) {
                        nameTally.merge(artistName.toString().trim(), 1, Integer::sum);
                    }
                    Object imageUrl = track.get("image_url");
                    if (imageUrl != null && !imageUrl.toString().isBlank()) {
                        String url = imageUrl.toString();
                        if (url.contains("i.scdn.co")) {
                            if (preferredImage == null) preferredImage = url;
                        } else if (fallbackImage == null) {
                            fallbackImage = url;
                        }
                    }
                    List<String> trackGenres = track.getList("genres", String.class);
                    if (trackGenres != null) {
                        trackGenres.stream()
                            .filter(g -> g != null && !g.isBlank())
                            .forEach(genresRaw::add);
                    }
                }

                List<String> genresRawList = new ArrayList<>(genresRaw);
                List<String> genres = genresRawList.stream()
                    .map(g -> g.toLowerCase(Locale.ROOT).trim())
                    .distinct()
                    .toList();

                artistsToInsert.add(new Document()
                    .append("_class", "com.laphuth.moodify.entities.Artist")
                    .append("spotify_id", spotifyId)
                    .append("name", resolveArtistName(nameTally, spotifyId))
                    .append("image_url", preferredImage != null ? preferredImage : fallbackImage)
                    .append("followers", 0)
                    .append("popularity", 0)
                    .append("genres", genres)
                    .append("genres_raw", genresRawList)
                    .append("created_at", now)
                    .append("updated_at", now));
            }

            if (artistsToInsert.isEmpty()) {
                System.out.println("Artist profiles are up to date, nothing to insert.");
                return;
            }
            mongoTemplate.getCollection("artists").insertMany(artistsToInsert);
            System.out.println("Derived " + artistsToInsert.size() + " artist profiles from tracks!");
        } catch (Exception e) {
            System.err.println("Warning: Could not derive artist profiles: " + e.getMessage());
        }
    }

    private String resolveArtistName(Map<String, Integer> nameTally, String spotifyId) {
        String bestName = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> tally : nameTally.entrySet()) {
            String candidate = tally.getKey();
            // Prefer solo names: "X feat. Y" entries belong to a collaboration, not the profile.
            boolean solo = !candidate.toLowerCase(Locale.ROOT).contains("feat.");
            boolean better = tally.getValue() > bestCount
                || (tally.getValue() == bestCount && bestName != null
                    && solo && bestName.toLowerCase(Locale.ROOT).contains("feat."));
            if (bestName == null || better) {
                bestName = candidate;
                bestCount = tally.getValue();
            }
        }
        if (bestName == null) {
            return spotifyId;
        }
        int featIndex = bestName.toLowerCase(Locale.ROOT).indexOf("feat.");
        return featIndex > 0 ? bestName.substring(0, featIndex).trim() : bestName;
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
            ensureArtistProfiles();
        } catch (Exception e) {
            System.err.println("Error seeding 147 tracks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private long getTrackCount() {
        return trackRepository.count();
    }
}
