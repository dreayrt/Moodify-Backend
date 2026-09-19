package com.laphuth.moodify.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class ImportTracks147Test {

    @Test
    void import147TracksToLocalMongo() throws Exception {
        File jsonFile = new File("src/main/resources/db/tracks147.json");
        assertThat(jsonFile.exists()).isTrue();

        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rawList = mapper.readValue(
            jsonFile,
            new TypeReference<List<Map<String, Object>>>() {}
        );

        System.out.println("Loaded " + rawList.size() + " raw tracks from JSON.");

        try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:27017")) {
            MongoDatabase db = client.getDatabase("music_streaming");
            MongoCollection<Document> collection = db.getCollection("tracks");

            // Clear old demo data
            collection.deleteMany(new Document());
            System.out.println("Cleared existing tracks in music_streaming.tracks");

            List<Document> docsToInsert = new ArrayList<>();

            for (Map<String, Object> map : rawList) {
                String jsonString = mapper.writeValueAsString(map);
                Document doc = Document.parse(jsonString);

                // Ensure genres array is enriched for proper search & vibe mapping
                List<String> currentGenres = doc.getList("genres", String.class, new ArrayList<>());
                List<String> rawGenres = doc.getList("genres_raw", String.class, new ArrayList<>());
                Set<String> enriched = new LinkedHashSet<>(currentGenres);

                // Add base v-pop tags
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

                // Check name for remix/edm
                String name = doc.getString("name");
                if (name != null && (name.toLowerCase().contains("remix") || name.toLowerCase().contains("edm"))) {
                    enriched.add("edm");
                    enriched.add("dance");
                    enriched.add("remix");
                }

                doc.put("genres", new ArrayList<>(enriched));

                // Add _class for Spring Data MongoDB mapping
                doc.put("_class", "com.laphuth.moodify.entities.Track");

                docsToInsert.add(doc);
            }

            collection.insertMany(docsToInsert);
            long total = collection.countDocuments();
            System.out.println("Successfully inserted " + total + " tracks into MongoDB music_streaming.tracks!");
            assertThat(total).isEqualTo(147);

            // Sample some tracks to verify
            for (Document d : collection.find().limit(5)) {
                System.out.println("  Track: " + d.getString("name") + " | Artist: " + d.getString("artist_name") + " | Genres: " + d.get("genres"));
            }
        }
    }
}
