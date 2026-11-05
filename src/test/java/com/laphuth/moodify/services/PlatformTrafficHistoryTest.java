package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformTrafficHistoryTest {
    @Test
    void historyCarriesTrackIdIntoMetadataLookup() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TrackRepository tracks = mock(TrackRepository.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("track_id")).thenReturn("track-123");
        when(row.getString("started_at")).thenReturn("2026-11-06 12:00:00");
        when(jdbc.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        });
        Track track = new Track();
        track.setName("Tên bài hát");
        track.setArtistName("Nghệ sĩ");
        track.setLocalPath("song.mp3");
        when(tracks.findById("track-123")).thenReturn(Optional.of(track));

        Map<String, Object> result = new PlatformTrafficService(jdbc, tracks)
            .getUserListeningHistory(1L, 0, 20, null);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertThat(item.get("trackId")).isEqualTo("track-123");
        assertThat(item.get("title")).isEqualTo("Tên bài hát");
        assertThat(item.get("artist")).isEqualTo("Nghệ sĩ");
        assertThat(item.get("audioUrl")).isNotNull();
        verify(tracks).findById("track-123");
    }
}
