package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListeningPlaybackServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final RecommendationOutcomeDispatcher outcomes = mock(RecommendationOutcomeDispatcher.class);
    private final ListeningPlaybackService service = new ListeningPlaybackService(jdbc, tracks, outcomes);
    private final String sessionKey = UUID.randomUUID().toString();
    private final Map<String, Object> session = new HashMap<>();

    @BeforeEach
    void setUp() {
        Track track = new Track();
        track.setId("track");
        track.setStatus("published");
        track.setModerationStatus("approved");
        track.setDurationMs(600_000);
        when(tracks.findById("track")).thenReturn(Optional.of(track));
        session.putAll(Map.of(
            "session_key", sessionKey, "listening_history_id", 7L,
            "last_seen_at", LocalDateTime.now(ZoneOffset.UTC), "continuous_ms", 0L,
            "playing", true, "closed", false, "user_id", 1L, "track_id", "track",
            "last_position_ms", 0L, "listened_duration_ms", 0L
        ));
        when(jdbc.queryForList(anyString(), eq(sessionKey))).thenReturn(List.of(session));
        // Model the two authoritative updates so subsequent observations use the saved state.
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] args = invocation.getArguments();
            if (sql.startsWith("UPDATE revenue_playback_sessions")) {
                session.put("last_seen_at", args[1]);
                session.put("continuous_ms", args[2]);
                session.put("playing", args[3]);
                session.put("closed", args[4]);
            } else if (sql.startsWith("UPDATE listening_history")) {
                session.put("listened_duration_ms", args[1]);
                session.put("last_position_ms", args[2]);
                session.put("ended_at", args[3]);
            }
            return 1;
        });
    }

    private Map<String, Object> record(String event, long position) {
        return service.record(1L, "track", sessionKey, event, position, position);
    }

    private void fiveSecondsAgo() {
        session.put("last_seen_at", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
    }

    private void assertNoFinancialSql() {
        var sql = mockingDetails(jdbc).getInvocations().stream()
            .filter(call -> call.getArguments().length > 0 && call.getArgument(0) instanceof String)
            .map(call -> (String) call.getArgument(0)).toList();
        assertThat(sql).isNotEmpty().allSatisfy(statement ->
            assertThat(statement).doesNotContain("qualified_streams", "gross_vnd", "content_lead_vnd", "platform_vnd"));
    }

    @Test
    void severalMinutesAndRepeatedSessionsStillRecordHistoryWithoutReadingOrWritingMoney() {
        for (int round = 0; round < 3; round++) {
            session.put("continuous_ms", 0L);
            session.put("last_position_ms", 0L);
            session.put("listened_duration_ms", 0L);
            for (int heartbeat = 1; heartbeat <= 36; heartbeat++) {
                fiveSecondsAgo();
                assertThat(record("HEARTBEAT", heartbeat * 5_000L))
                    .containsEntry("success", true).containsEntry("historyId", 7L)
                    .containsEntry("qualified", false);
            }
            assertThat((long) session.get("continuous_ms")).isGreaterThanOrEqualTo(180_000);
            assertThat((long) session.get("listened_duration_ms")).isGreaterThanOrEqualTo(180_000);
        }
        assertNoFinancialSql();
        verifyNoInteractions(outcomes);
    }

    @Test
    void pauseResumeSeekAndCompletionPreserveHistoryAndEventOrder() {
        fiveSecondsAgo();
        record("PAUSE", 5_000);
        long playedBeforePause = (long) session.get("listened_duration_ms");
        assertThat(playedBeforePause).isGreaterThanOrEqualTo(5_000);
        assertThat(session).containsEntry("playing", false).containsEntry("continuous_ms", 0L);

        session.put("last_seen_at", LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        record("RESUME", 5_000);
        assertThat(session.get("listened_duration_ms")).isEqualTo(playedBeforePause);
        assertThat(session.get("playing")).isEqualTo(true);

        service.record(1L, "track", sessionKey, "SEEK", 5_000, 90_000);
        assertThat(session).containsEntry("playing", true).containsEntry("continuous_ms", 0L);
        record("HEARTBEAT", 90_000); // The seek jump itself must not count as listened time.
        assertThat(session.get("listened_duration_ms")).isEqualTo(playedBeforePause);
        fiveSecondsAgo();
        record("HEARTBEAT", 95_000);
        assertThat((long) session.get("listened_duration_ms")).isGreaterThanOrEqualTo(playedBeforePause + 5_000);
        fiveSecondsAgo();
        record("COMPLETE", 100_000);
        assertThat(session).containsEntry("playing", false).containsEntry("closed", true);
        assertThat(session.get("ended_at")).isNotNull();
        var events = mockingDetails(jdbc).getInvocations().stream()
            .filter(call -> call.getArgument(0) instanceof String sql && sql.startsWith("INSERT INTO playback_events"))
            .map(call -> (String) call.getArgument(2)).toList();
        assertThat(events).containsExactly("PAUSE", "RESUME", "SEEK", "COMPLETE");
        assertNoFinancialSql();
    }

    @Test
    void duplicateCallbacksAndClosedSessionsDoNotWriteOrConsultOldRevenue() {
        assertThat(record("PLAY", 0)).containsEntry("duplicate", true).containsEntry("qualified", false);
        session.put("playing", false);
        assertThat(record("PAUSE", 0)).containsEntry("duplicate", true);
        assertThat(record("SEEK", 0)).containsEntry("duplicate", true);
        session.put("closed", true);
        assertThat(record("COMPLETE", 0)).containsEntry("success", true).containsEntry("qualified", false);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertNoFinancialSql();
    }

    @Test
    void concurrentSessionKeepsItsOwnHistoryAndCannotBeUsedByAnotherUserOrTrack() {
        String otherSession = UUID.randomUUID().toString();
        Map<String, Object> other = new HashMap<>(session);
        other.put("listening_history_id", 8L);
        when(jdbc.queryForList(anyString(), eq(otherSession))).thenReturn(List.of(other));
        assertThat(service.record(1L, "track", otherSession, "PLAY", 0, 0)).containsEntry("historyId", 8L);
        assertThat(record("PLAY", 0)).containsEntry("historyId", 7L);
        session.put("user_id", 2L);
        assertThatThrownBy(() -> record("HEARTBEAT", 5_000))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        session.put("user_id", 1L);
        session.put("track_id", "other-track");
        assertThatThrownBy(() -> record("HEARTBEAT", 5_000)).isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertNoFinancialSql();
    }

    @Test
    void newSessionStillCreatesHistoryAndInitialPlayWithTheExistingPayload() throws Exception {
        when(jdbc.queryForList(anyString(), eq(sessionKey))).thenReturn(List.of());
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            creator.createPreparedStatement(connection);
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("id", 7L));
            return 1;
        });
        assertThat(service.record(1L, "track", sessionKey, "PLAY", 0, 0, "SEARCH", "query", "ANDROID", null))
            .containsEntry("success", true).containsEntry("historyId", 7L)
            .containsEntry("created", true).containsEntry("qualified", false);
        verify(connection).prepareStatement(startsWith("INSERT INTO listening_history"), eq(Statement.RETURN_GENERATED_KEYS));
        verify(statement).setString(5, "SEARCH");
        verify(statement).setString(6, "query");
        verify(statement).setString(7, "ANDROID");
        verify(jdbc).update(startsWith("INSERT INTO revenue_playback_sessions"), eq(sessionKey), eq(7L), any(LocalDateTime.class));
        verify(jdbc).update(startsWith("INSERT INTO playback_events"), eq(7L), eq(0L), any(LocalDateTime.class));
        assertNoFinancialSql();
    }

    @Test
    void absentSessionRequiresAStartEvent() {
        when(jdbc.queryForList(anyString(), eq(sessionKey))).thenReturn(List.of());
        assertThat(record("PAUSE", 0)).containsEntry("success", false);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void malformedSessionsAndPositionsAreRejectedBeforeAnyDatabaseWrite() {
        assertThatThrownBy(() -> service.record(1L, "track", "bad-key", "PLAY", 0, 0))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> record("INVALID", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> record("HEARTBEAT", 700_000)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void timestampSessionsAndStalledOrJumpedPlaybackKeepCorrectDuration() {
        session.put("last_seen_at", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5)));
        record("HEARTBEAT", 0);
        assertThat(session.get("listened_duration_ms")).isEqualTo(0L);
        fiveSecondsAgo();
        record("HEARTBEAT", 60_000);
        assertThat(session.get("listened_duration_ms")).isEqualTo(0L);
        assertThat(ListeningPlaybackService.advanceContinuousMs(55_000, 20_000, 20_000)).isZero();
        assertThat(ListeningPlaybackService.advanceContinuousMs(55_000, 5_000, 5_000)).isEqualTo(60_000);
        assertNoFinancialSql();
    }
}
