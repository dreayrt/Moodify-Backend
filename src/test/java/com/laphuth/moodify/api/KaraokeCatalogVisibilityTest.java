package com.laphuth.moodify.api;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KaraokeCatalogVisibilityTest {
    @Test void onlyAvailableCatalogTracksAppear() {
        assertTrue(KaraokeController.isCatalogVisible(new Document()));
        assertTrue(KaraokeController.isCatalogVisible(new Document("status", "published")));
        assertFalse(KaraokeController.isCatalogVisible(new Document("status", "ARCHIVED")));
        assertFalse(KaraokeController.isCatalogVisible(new Document("status", "draft")));
        assertFalse(KaraokeController.isCatalogVisible(new Document("visibility", "private")));
        assertFalse(KaraokeController.isCatalogVisible(new Document("moderation_status", "rejected")));
        assertFalse(KaraokeController.isCatalogVisible(null));
    }
}
