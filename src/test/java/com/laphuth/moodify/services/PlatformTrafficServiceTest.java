package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformTrafficServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final PlatformTrafficService service = new PlatformTrafficService(jdbc, tracks);

    @Test
    void anonymousListeningCannotBeAttributedToAnArbitraryAccount() {
        var result = service.recordListeningSession(null, "track", 1000L, 1000L, "HOME", null, "WEB", "PLAY");
        assertThat(result.get("success")).isEqualTo(false);
        verifyNoInteractions(jdbc, tracks);
    }

    @Test
    void nonexistentTracksCannotCreateListeningHistory() {
        when(tracks.findById("missing")).thenReturn(Optional.empty());
        when(tracks.findBySpotifyId("missing")).thenReturn(Optional.empty());
        var result = service.recordListeningSession(92L, "missing", 1000L, 1000L, "HOME", null, "WEB", "PLAY");
        assertThat(result.get("success")).isEqualTo(false);
        verifyNoInteractions(jdbc);
    }

    @Test
    void realListeningUsesCanonicalTrackAndDoesNotInventACompleteEvent() throws Exception {
        Track track = new Track();
        track.setId("canonical-track");
        when(tracks.findById("spotify-alias")).thenReturn(Optional.empty());
        when(tracks.findBySpotifyId("spotify-alias")).thenReturn(Optional.of(track));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            creator.createPreparedStatement(connection);
            KeyHolder keys = invocation.getArgument(1);
            keys.getKeyList().add(Map.of("id", 123L));
            return 1;
        });

        var result = service.recordListeningSession(92L, "spotify-alias", 1000L, 1000L, "HOME", null, "WEB", null);

        assertThat(result).containsEntry("success", true).containsEntry("historyId", 123L);
        verify(statement).setLong(1, 92L);
        verify(statement).setString(2, "canonical-track");
        verify(jdbc).update(contains("INSERT INTO playback_events"), eq(123L), eq("PLAY"), eq(1000L), eq(1000L));
    }
}
