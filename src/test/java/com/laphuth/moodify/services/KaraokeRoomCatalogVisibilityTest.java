package com.laphuth.moodify.services;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KaraokeRoomCatalogVisibilityTest {
 @Test void archivedTracksAreNotUsableInRooms() {
  assertTrue(KaraokeRoomService.isCatalogVisible(new Document("status","published").append("visibility","public").append("moderation_status","approved").append("local_path","data/audio/test.mp3").append("local_path","data/audio/valid.mp3")));
  assertFalse(KaraokeRoomService.isCatalogVisible(new Document("status","archived")));
  assertFalse(KaraokeRoomService.isCatalogVisible(new Document("visibility","private")));
 }
}
