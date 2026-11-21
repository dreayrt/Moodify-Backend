package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminServiceDataIntegrityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final AdminService service = new AdminService(jdbc, tracks, null, null, null, null, null);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void listeningSession() {
        Map<String, Object> row = new HashMap<>();
        row.put("trackId", "spotify-track-id");
        row.put("streamCount", 3L);
        row.put("uniqueListeners", 2L);
        row.put("avgDurationMs", 60000L);
        row.put("earlyDropCount", 1L);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
            .thenAnswer(invocation -> new ArrayList<>(List.of(new HashMap<>(row))));
        when(tracks.findById("spotify-track-id")).thenReturn(Optional.empty());
    }

    @Test
    void resolvesSpotifyIdToRealMetadataAndDuration() {
        Track track = new Track();
        track.setName("Một bài hát có dấu");
        track.setArtistName("Nghệ sĩ thật");
        track.setDurationMs(120000);
        when(tracks.findBySpotifyId("spotify-track-id")).thenReturn(Optional.of(track));
        Map<String, Object> result = service.getTrackPerformanceMetrics(10).get(0);
        assertThat(result.get("title")).isEqualTo("Một bài hát có dấu");
        assertThat(result.get("artist")).isEqualTo("Nghệ sĩ thật");
        assertThat(result.get("completionRatePercent")).isEqualTo(50.0);
        assertThat(result.get("audioUrl")).isEqualTo("");
        assertThat(result.get("genre")).isEqualTo("Chưa phân loại");
    }

    @Test
    void missingTrackDoesNotFabricateDurationCompletionOrAudio() {
        when(tracks.findBySpotifyId("spotify-track-id")).thenReturn(Optional.empty());
        Map<String, Object> result = service.getTrackPerformanceMetrics(10).get(0);
        assertThat(result.get("title")).isEqualTo("Không tìm thấy thông tin bài hát");
        assertThat(result.get("trackDurationMs")).isEqualTo(0);
        assertThat(result.get("completionRatePercent")).isNull();
        assertThat(result.get("audioUrl")).isEqualTo("");
    }

    @Test
    void readsPersistedFormattedDurationWhenMillisecondsAreMissing() {
        Track track = new Track();
        track.setName("Bài hát có thời lượng");
        track.setDurationFormatted("2:00");
        when(tracks.findBySpotifyId("spotify-track-id")).thenReturn(Optional.of(track));

        Map<String, Object> result = service.getTrackPerformanceMetrics(10).get(0);

        assertThat(result.get("trackDurationMs")).isEqualTo(120000);
        assertThat(result.get("duration")).isEqualTo("2:00");
        assertThat(result.get("completionRatePercent")).isEqualTo(50.0);
    }

    @Test
    void zeroDurationIsShownAsMissingAndHasNoCompletionRate() {
        Track track = new Track();
        track.setDurationMs(0);
        track.setDurationFormatted("0:00");
        track.setImageUrl(" ");
        when(tracks.findBySpotifyId("spotify-track-id")).thenReturn(Optional.of(track));

        Map<String, Object> result = service.getTrackPerformanceMetrics(10).get(0);

        assertThat(result.get("duration")).isEqualTo("—");
        assertThat(result.get("completionRatePercent")).isNull();
        assertThat(result.get("coverUrl")).isEqualTo("/images/cover-placeholder.svg");
    }
    @Test
    void catalogDoesNotInventAnalysisOrCreationDate() {
        org.springframework.data.mongodb.core.MongoTemplate mongo = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        JdbcTemplate catalogJdbc = mock(JdbcTemplate.class);
        AdminService catalog = new AdminService(catalogJdbc, tracks, mongo, null, null, null, null);
        Track track = new Track();
        track.setId("mongo-id");
        track.setSpotifyId("spotify-id");
        track.setName("Tên bài hát thật");
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), eq(Track.class)))
            .thenReturn(List.of(track));
        Map<String, Object> result = catalog.getCatalogTracks(null, null).get(0);
        assertThat(result.get("title")).isEqualTo("Tên bài hát thật");
        assertThat(result.get("bpm")).isNull();
        assertThat(result.get("moderationScore")).isNull();
        assertThat(result.get("vibeCategory")).isNull();
        assertThat(result.get("createdAt")).isEqualTo("");
        assertThat(result.get("duration")).isEqualTo("—");
        assertThat(result.get("audioUrl")).isEqualTo("");
    }

}
