package com.laphuth.moodify.api;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KaraokeScoreDiagnosticsTest {
    @Test void diagnosticsSurviveStorageAndCachedResponsesWithoutRawFeatures() {
        Document upstream = new Document("status", "completed")
                .append("score", new Document("overall", 45).append("pitch", 20).append("rhythm", 50).append("coverage", 50))
                .append("scoring_version", "pitch-rhythm-v3-30-20-50")
                .append("quality", new Document("status", "sufficient").append("warnings", List.of("Experimental")))
                .append("diagnostics", new Document("schema_version", 1).append("rms_dbfs", -20)
                        .append("flags", List.of("low_coverage"))
                        .append("f0_hz", List.of(220, 330))
                        .append("windows", List.of(new Document("start_seconds", 0).append("end_seconds", 10)
                                .append("coverage", 50).append("pitch", 20).append("private", "secret"))))
                .append("private", "secret");
        Document stored = KaraokeController.publicScoreDocument(upstream);
        Map<String, Object> response = KaraokeController.publicScoreResult(Document.parse(stored.toJson()));
        assertEquals(new java.util.LinkedHashMap<>(stored), response);
        assertEquals(20d, ((Map<?, ?>) response.get("score")).get("pitch"));
        Map<?, ?> diagnostics = (Map<?, ?>) response.get("diagnostics");
        assertEquals(List.of("low_coverage"), diagnostics.get("flags"));
        assertFalse(diagnostics.containsKey("f0_hz"));
        assertFalse(response.containsKey("private"));
        assertFalse(((Map<?, ?>) ((List<?>) diagnostics.get("windows")).getFirst()).containsKey("private"));
    }

    @Test void legacyAndInsufficientAudioResultsRemainReadable() {
        Map<String, Object> legacy = KaraokeController.publicScoreResult(
                new Document("status", "completed").append("score", new Document("overall", 45)));
        assertEquals(Map.of("overall", 45d), legacy.get("score"));
        Document insufficient = KaraokeController.publicScoreDocument(new Document("status", "completed")
                .append("score", null).append("quality", new Document("status", "insufficient_audio"))
                .append("diagnostics", new Document("clipped_fraction", 0.1).append("rms_dbfs", Double.NaN)));
        assertNull(insufficient.get("score"));
        assertEquals("insufficient_audio", ((Map<?, ?>) insufficient.get("quality")).get("status"));
        assertFalse(((Map<?, ?>) insufficient.get("diagnostics")).containsKey("rms_dbfs"));
    }
}
