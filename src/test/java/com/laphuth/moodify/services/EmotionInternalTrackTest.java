package com.laphuth.moodify.services;
import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmotionInternalTrackTest {
    Object select(List<Track> tracks) {
        var catalog=mock(RecommendationCatalog.class);
        Map<String,Track> eligible=new LinkedHashMap<>();tracks.forEach(t->eligible.put(t.getId(),t));
        when(catalog.snapshot()).thenReturn(new RecommendationCatalog.Snapshot(eligible,Map.of()));
        var service=new EmotionService("http://127.0.0.1:1",.35,mock(MongoTemplate.class),mock(UserRepository.class),mock(SearchHistoryRepository.class),catalog);
        var profile=new MusicRecommendationInfo();profile.setTargetValence(.5);profile.setTargetEnergy(.5);
        return ReflectionTestUtils.invokeMethod(service,"findMatchingTracks",profile,"neutral",20);
    }
    Track track() {
        Track t=new Track();t.setId("internal");t.setName("Local");t.setArtistName("Artist");
        t.setStatus("published");t.setVisibility("public");t.setLocalPath("data/audio/local.mp3");
        var af=new Track.AudioFeatures();af.setValence(.5);af.setEnergy(.5);af.setDanceability(.5);af.setAcousticness(.5);af.setBpm(110.0);t.setAudioFeatures(af);return t;
    }
    @Test void licensedInternalTrackIsScored() {
        var result=select(List.of(track()));
        assertEquals(1,ReflectionTestUtils.getField(result,"totalMatched"));
        assertEquals("MATCHED",ReflectionTestUtils.getField(result,"matchStatus"));
    }
    @Test void emptyRightsCatalogReturnsNoCandidates() {
        assertEquals("NO_CANDIDATES",ReflectionTestUtils.getField(select(List.of()),"matchStatus"));
    }
    @Test void technicalMetadataAloneIsNeverAMoodMatch() {
        Track t=track();t.setAudioFeatures(new Track.AudioFeatures());var result=select(List.of(t));
        assertEquals("NO_FEATURES",ReflectionTestUtils.getField(result,"matchStatus"));
        assertEquals(List.of(),ReflectionTestUtils.getField(result,"tracks"));
    }
    @Test void partialFeatureVectorCannotClaimAMoodMatch() {
        Track t=track();t.getAudioFeatures().setEnergy(null);
        assertEquals("NO_FEATURES",ReflectionTestUtils.getField(select(List.of(t)),"matchStatus"));
    }
    @Test void distantEligibleTrackIsLimited() {
        Track t=track();t.getAudioFeatures().setValence(1.0);t.getAudioFeatures().setEnergy(1.0);
        assertEquals("LIMITED",ReflectionTestUtils.getField(select(List.of(t)),"matchStatus"));
    }
}
