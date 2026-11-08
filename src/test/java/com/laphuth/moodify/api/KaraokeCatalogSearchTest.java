package com.laphuth.moodify.api;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KaraokeCatalogSearchTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final KaraokeController controller = new KaraokeController(mongo, "http://scoring", "");
    private final Document luuNien = track("Lưu Niên", "Đặng Luân");
    private final Document banDoi = track("Bạn Đời", "Karik");

    @BeforeEach
    void prepareCatalog() {
        Document hidden = track("Lưu Niên (private)", "Đặng Luân").append("visibility", "private");
        Document unprepared = track("Lưu Niên (unprepared)", "Đặng Luân");
        when(mongo.find(any(Query.class), eq(Document.class), eq("karaoke_assets")))
                .thenReturn(List.of(asset(luuNien), asset(banDoi), asset(hidden)));
        // Keep this fixture constrained by the IDs requested from Mongo, so the
        // search is also checked against tracks without prepared Karaoke assets.
        when(mongo.find(any(Query.class), eq(Document.class), eq("tracks")))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    Document idFilter = query.getQueryObject().get("_id", Document.class);
                    List<?> ids = idFilter.getList("$in", Object.class);
                    return List.of(luuNien, banDoi, hidden, unprepared).stream()
                            .filter(track -> ids.contains(track.getObjectId("_id"))).toList();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Luu Nien", "Lưu Niên", "LUU NIEN", "  luu nien  ", "Lưu Nien", "nien"})
    void findsTitleWithOrWithoutAccents(String query) {
        assertOnlyTrack(query, luuNien, "Lưu Niên", "Đặng Luân");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dang Luan", "ĐẶNG LUÂN", "dang luân", "luan"})
    void findsArtistWithOrWithoutAccents(String query) {
        assertOnlyTrack(query, luuNien, "Lưu Niên", "Đặng Luân");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ban Doi", "BẠN ĐỜI", "doi"})
    void matchesVietnameseDInTitle(String query) {
        assertOnlyTrack(query, banDoi, "Bạn Đời", "Karik");
    }

    @Test
    void matchesDecomposedUnicodeInBothQueryAndCatalog() {
        assertOnlyTrack(Normalizer.normalize("Lưu Niên", Normalizer.Form.NFD),
                luuNien, "Lưu Niên", "Đặng Luân");
        luuNien.put("name", Normalizer.normalize("Lưu Niên", Normalizer.Form.NFD));
        assertOnlyTrack("Luu Nien", luuNien, luuNien.getString("name"), "Đặng Luân");
    }

    @Test
    void filtersBeforeCountingAndPaginating() {
        // "a" matches Đặng Luân as well as Bạn Đời/Karik after folding accents.
        Map<String, Object> first = controller.getTracks(0, 1, "a");
        Map<String, Object> second = controller.getTracks(1, 1, "a");
        assertEquals(2L, first.get("totalElements"));
        assertEquals(2, first.get("totalPages"));
        assertEquals(banDoi.getObjectId("_id").toHexString(), content(first).getFirst().get("songId"));
        assertEquals(luuNien.getObjectId("_id").toHexString(), content(second).getFirst().get("songId"));
        assertTrue(content(controller.getTracks(2, 1, "a")).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void emptyQueryListsAvailableTracks(String query) {
        assertEquals(2L, controller.getTracks(0, 8, query).get("totalElements"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a song", ".*", "["})
    void unmatchedOrRegexTextDoesNotMatchEverything(String query) {
        Map<String, Object> result = controller.getTracks(0, 8, query);
        assertEquals(0L, result.get("totalElements"));
        assertEquals(0, result.get("totalPages"));
        assertTrue(content(result).isEmpty());
    }

    private void assertOnlyTrack(String query, Document track, String title, String artist) {
        Map<String, Object> result = controller.getTracks(0, 8, query);
        assertEquals(1L, result.get("totalElements"));
        assertEquals(1, result.get("totalPages"));
        List<Map<String, Object>> content = content(result);
        assertEquals(1, content.size());
        assertEquals(track.getObjectId("_id").toHexString(), content.getFirst().get("songId"));
        assertEquals(title, content.getFirst().get("title"));
        assertEquals(artist, content.getFirst().get("artist"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("content");
    }

    private static Document track(String title, String artist) {
        return new Document("_id", new ObjectId()).append("name", title).append("artist_name", artist);
    }

    private static Document asset(Document track) {
        String songId = track.getObjectId("_id").toHexString();
        String prefix = "karaoke/" + songId + "/" + new ObjectId().toHexString() + "/";
        return new Document("songId", songId)
                .append("status", "ready")
                .append("assets", new Document("instrumental", new Document("storageKey", prefix + "instrumental.wav"))
                        .append("vocals", new Document("storageKey", prefix + "vocals.wav")));
    }
}
