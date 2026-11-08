package com.laphuth.moodify.entities;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackAudioFeaturesMappingTest {
    @Test
    void preservesExtendedAudioFieldsAndProvenanceWhenTrackIsSaved() throws Exception {
        MongoMappingContext context = new MongoMappingContext();
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> {});
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        Document features = new Document("success", true).append("spotify_id", "track123")
            .append("duration_sec", 201.59).append("bitrate_kbps", 192)
            .append("sample_rate", 48000).append("channels", 2).append("bpm", 91.5)
            .append("key_signature", "C Major").append("tonic", "C")
            .append("key_confidence", 0.7).append("loudness_lufs", -10.2)
            .append("energy", 0.5).append("danceability", 0.4).append("valence", 0.6)
            .append("acousticness", 0.3).append("waveform_peaks", List.of(0.0, 0.5, 1.0));
        Document input = new Document("name", "test").append("audio_features", features)
            .append("audio_external_feature_provenance", new Document("provider", "ReccoBeats"))
            .append("audio_analysis_v2", new Document("source_sha256", "hash"));
        Track track = converter.read(Track.class, input);
        Document output = new Document();
        converter.write(track, output);
        Document saved = output.get("audio_features", Document.class);
        for (String field : features.keySet()) assertTrue(saved.containsKey(field), field);
        assertEquals(192.0, saved.getDouble("bitrate_kbps"));
        assertEquals(List.of(0.0, 0.5, 1.0), saved.get("waveform_peaks"));
        assertEquals("ReccoBeats", output.get("audio_external_feature_provenance", Document.class).getString("provider"));
        assertEquals("hash", output.get("audio_analysis_v2", Document.class).getString("source_sha256"));

        features.put("spotify_id", null);
        features.put("key_confidence", null);
        Document unknownOutput = new Document();
        converter.write(converter.read(Track.class, input), unknownOutput);
        Document unknownFeatures = unknownOutput.get("audio_features", Document.class);
        assertTrue(unknownFeatures.containsKey("spotify_id"));
        assertNull(unknownFeatures.get("spotify_id"));
        assertTrue(unknownFeatures.containsKey("key_confidence"));
        assertNull(unknownFeatures.get("key_confidence"));
    }
}
