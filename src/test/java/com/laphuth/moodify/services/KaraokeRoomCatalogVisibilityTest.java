package com.laphuth.moodify.services;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KaraokeRoomCatalogVisibilityTest {
 @Test void archivedTracksAreNotUsableInRooms() {
  assertTrue(KaraokeRoomService.isCatalogVisible(new Document("status","published")));
  assertFalse(KaraokeRoomService.isCatalogVisible(new Document("status","archived")));
  assertFalse(KaraokeRoomService.isCatalogVisible(new Document("visibility","private")));
 }
}
