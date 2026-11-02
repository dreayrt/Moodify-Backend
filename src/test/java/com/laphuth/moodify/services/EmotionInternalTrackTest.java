package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.emotion.MusicRecommendationInfo;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.SearchHistoryRepository;
import com.laphuth.moodify.repositories.UserRepository;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmotionInternalTrackTest {
    @Test void moodCandidatesIncludePlayableTracksWithoutSpotifyId() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        Track track=new Track();track.setId("507f1f77bcf86cd799439011");track.setName("Local");track.setArtistName("Artist");track.setLocalPath("data/audio/local.mp3");
        when(mongo.stream(any(Query.class),eq(Track.class))).thenReturn(Stream.of(track));
        EmotionService service=new EmotionService("http://127.0.0.1:1",mongo,mock(UserRepository.class),mock(SearchHistoryRepository.class));
        MusicRecommendationInfo profile=new MusicRecommendationInfo();profile.setTargetValence(.5);profile.setTargetEnergy(.5);
        Object result=ReflectionTestUtils.invokeMethod(service,"findMatchingTracks",profile,"neutral",20);
        @SuppressWarnings("unchecked") List<TrackResponse> responses=(List<TrackResponse>)ReflectionTestUtils.getField(result,"tracks");
        assertNotNull(responses);assertEquals(1,responses.size());assertEquals(track.getId(),responses.get(0).spotifyId());
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);verify(mongo).stream(query.capture(),eq(Track.class));
        assertTrue(query.getValue().getQueryObject().containsKey("localPath"));
        assertFalse(query.getValue().getQueryObject().containsKey("spotifyId"));
    }
}
