package com.laphuth.moodify.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;

public class MongoInspectTest {
    @Test
    void inspectMongo() {
        try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:27017")) {
            System.out.println("=== MONGO DATABASES ===");
            for (String dbName : client.listDatabaseNames()) {
                System.out.println("Database: " + dbName);
                MongoDatabase db = client.getDatabase(dbName);
                for (String collName : db.listCollectionNames()) {
                    long count = db.getCollection(collName).countDocuments();
                    System.out.println("   Collection: " + collName + " -> " + count + " docs");
                    if (count > 0 && (collName.toLowerCase().contains("track") || collName.toLowerCase().contains("music") || collName.toLowerCase().contains("song"))) {
                        Document sample = db.getCollection(collName).find().first();
                        System.out.println("      Sample doc keys: " + (sample != null ? sample.keySet() : "null"));
                        if (sample != null && sample.containsKey("name")) {
                            System.out.println("      Sample track name: " + sample.get("name") + ", artist: " + sample.get("artist_name") + " / " + sample.get("artistName"));
                        }
                    }
                }
            }
            System.out.println("=======================");
        }
    }
}
