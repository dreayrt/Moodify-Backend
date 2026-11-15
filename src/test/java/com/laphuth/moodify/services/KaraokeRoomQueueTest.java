package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.karaoke.rooms.QueueKaraokeSongRequest;
import com.laphuth.moodify.dto.karaoke.rooms.MoveKaraokeQueueItemRequest;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.laphuth.moodify.dto.karaoke.rooms.MoveKaraokeQueueItemRequest.Direction.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KaraokeRoomQueueTest {
    private final KaraokeRoomRepository rooms = mock(KaraokeRoomRepository.class);
    private final KaraokeRoomMemberRepository members = mock(KaraokeRoomMemberRepository.class);
    private final KaraokeRoomQueueRepository queue = mock(KaraokeRoomQueueRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final Authentication authentication = mock(Authentication.class);
    private final KaraokeRoomService service = new KaraokeRoomService(rooms, members, queue, users,
            mongo, mock(KaraokeRoomRealtimeGateway.class));
    private KaraokeRoom room;
    private User user;
    private static final String FIRST = "0123456789abcdef01234567";
    private static final String SECOND = "0123456789abcdef01234568";

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(2L);
        user.setFullName("Người hát");
        user.setStatus(UserStatus.ACTIVE);
        room = new KaraokeRoom();
        ReflectionTestUtils.setField(room, "id", 9L);
        room.setHostUser(user);
        room.setMaxMembers(6);
        room.setVisibility(KaraokeRoomVisibility.PUBLIC);
        when(authentication.getName()).thenReturn("singer");
        when(users.findByUsername("singer")).thenReturn(Optional.of(user));
        when(rooms.findByIdForUpdate(9L)).thenReturn(Optional.of(room));
        when(members.findByRoom_IdAndUser_Id(9L, 2L)).thenReturn(Optional.of(new KaraokeRoomMember()));
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("karaoke_assets")))
                .thenReturn(new Document("assets", new Document("instrumental", new Document("storageKey", "i.wav"))
                        .append("vocals", new Document("storageKey", "v.wav"))));
        when(mongo.findById(any(ObjectId.class), eq(Document.class), eq("tracks")))
                .thenAnswer(call -> new Document("_id", call.getArgument(0)).append("name", "Bài hát"));
        when(queue.saveAndFlush(any(KaraokeRoomQueueItem.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void sameMemberCanQueueMultipleDifferentSongsInOrder() {
        when(queue.findLastQueuePosition(9L)).thenReturn(-1L, 0L);
        service.addQueueItem(9L, new QueueKaraokeSongRequest(FIRST), authentication);
        service.addQueueItem(9L, new QueueKaraokeSongRequest(SECOND), authentication);
        var saved = org.mockito.ArgumentCaptor.forClass(KaraokeRoomQueueItem.class);
        verify(queue, times(2)).saveAndFlush(saved.capture());
        assertEquals(java.util.List.of(FIRST, SECOND), saved.getAllValues().stream()
                .map(KaraokeRoomQueueItem::getTrackId).toList());
        assertTrue(saved.getAllValues().stream().allMatch(item -> item.getRequester() == user));
        assertEquals(List.of(0L, 1L), saved.getAllValues().stream()
                .map(KaraokeRoomQueueItem::getQueuePosition).toList());
        verify(rooms, times(2)).findByIdForUpdate(9L);
    }

    @Test
    void sameMemberCannotQueueSameSongTwiceIncludingUppercaseIds() {
        when(queue.existsByRoom_IdAndRequester_IdAndTrackId(9L, 2L, FIRST)).thenReturn(true);
        var error = assertThrows(ResponseStatusException.class, () -> service.addQueueItem(9L,
                new QueueKaraokeSongRequest(FIRST.toUpperCase()), authentication));
        assertEquals(409, error.getStatusCode().value());
        assertEquals("QUEUE_TRACK_ALREADY_REQUESTED", error.getReason());
        verify(queue, never()).saveAndFlush(any());
    }

    @Test
    void nonMemberCannotQueueSongs() {
        when(members.findByRoom_IdAndUser_Id(9L, 2L)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class, () -> service.addQueueItem(9L,
                new QueueKaraokeSongRequest(FIRST), authentication));
        assertEquals(403, error.getStatusCode().value());
        verify(queue, never()).saveAndFlush(any());
    }

    @Test
    void removingOneSongLeavesOtherQueueItemsUntouched() {
        var item = new KaraokeRoomQueueItem();
        item.setRoom(room);
        item.setRequester(user);
        when(queue.findByIdAndRoom_Id(11L, 9L)).thenReturn(Optional.of(item));
        assertTrue(service.removeQueueItem(9L, 11L, authentication));
        verify(queue).delete(item);
        verify(queue, never()).deleteAll(anyList());
    }

    @Test
    void hostCanMoveSecondSongAboveFirstAndPlaybackUsesNewOrder() {
        var first = queuedItem(11L, FIRST, 0);
        var second = queuedItem(12L, SECOND, 0); // legacy rows have equal positions
        var items = new ArrayList<>(List.of(first, second));
        when(queue.findAllByRoom_IdOrderByQueuePositionAscCreatedAtAscIdAsc(9L))
                .thenAnswer(call -> items.stream().sorted(Comparator.comparingLong(
                        KaraokeRoomQueueItem::getQueuePosition)).toList());

        assertTrue(service.moveQueueItem(9L, 12L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(0, second.getQueuePosition());
        assertEquals(1, first.getQueuePosition());
        verify(queue).saveAllAndFlush(List.of(second, first));
        when(mongo.find(any(Query.class), eq(Document.class), eq("tracks"))).thenReturn(List.of());
        when(mongo.findById(anyString(), eq(Document.class), eq("karaoke_room_asset_pins")))
                .thenReturn(new Document());
        service.startPlayback(9L, 0, authentication);
        assertEquals(SECOND, room.getCurrentTrackId());
        verify(queue).delete(second);
        verify(queue, never()).delete(first);
    }

    @Test
    void hostCanMoveSongDownAndNewRequestsAppendAfterReorderedSongs() {
        var first = queuedItem(11L, FIRST, 8);
        var second = queuedItem(12L, SECOND, 20);
        var third = queuedItem(13L, "0123456789abcdef01234569", 25);
        when(queue.findAllByRoom_IdOrderByQueuePositionAscCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(first, second, third));
        assertTrue(service.moveQueueItem(9L, 11L, new MoveKaraokeQueueItemRequest(DOWN), authentication));
        assertEquals(0, second.getQueuePosition());
        assertEquals(1, first.getQueuePosition());
        assertEquals(2, third.getQueuePosition());
        verify(queue).saveAllAndFlush(List.of(second, first, third));
        when(queue.findLastQueuePosition(9L)).thenReturn(2L);
        service.addQueueItem(9L, new QueueKaraokeSongRequest("0123456789abcdef01234570"), authentication);
        var saved = org.mockito.ArgumentCaptor.forClass(KaraokeRoomQueueItem.class);
        verify(queue).saveAndFlush(saved.capture());
        assertEquals(3, saved.getValue().getQueuePosition());
    }

    @Test
    void ordinaryMemberCannotReorderEvenTheirOwnSongs() {
        var host = new User();
        host.setId(3L);
        room.setHostUser(host);
        var error = assertThrows(ResponseStatusException.class, () -> service.moveQueueItem(
                9L, 11L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(403, error.getStatusCode().value());
        assertEquals("ROOM_HOST_REQUIRED", error.getReason());
        verify(queue, never()).saveAllAndFlush(any());
    }

    @Test
    void reorderingWaitingSongsDoesNotInterruptCurrentPlayback() {
        room.setCurrentTrackId(FIRST);
        room.setPlaybackStatus(KaraokePlaybackStatus.PLAYING);
        room.setPlaybackVersion(7L);
        room.setPlaybackRunId("current-run");
        when(queue.findAllByRoom_IdOrderByQueuePositionAscCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(queuedItem(11L, SECOND, 0),
                        queuedItem(12L, "0123456789abcdef01234569", 1)));
        assertTrue(service.moveQueueItem(9L, 12L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(FIRST, room.getCurrentTrackId());
        assertEquals(KaraokePlaybackStatus.PLAYING, room.getPlaybackStatus());
        assertEquals(7L, room.getPlaybackVersion());
        assertEquals("current-run", room.getPlaybackRunId());
        verify(queue, never()).delete(any(KaraokeRoomQueueItem.class));
    }

    @Test
    void hostMustStillBeActiveMemberToReorder() {
        when(members.findByRoom_IdAndUser_Id(9L, 2L)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class, () -> service.moveQueueItem(
                9L, 11L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(403, error.getStatusCode().value());
        verify(queue, never()).saveAllAndFlush(any());
    }

    @Test
    void boundaryMovesDoNotChangeQueue() {
        when(queue.findAllByRoom_IdOrderByQueuePositionAscCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(queuedItem(11L, FIRST, 0), queuedItem(12L, SECOND, 1)));
        assertFalse(service.moveQueueItem(9L, 11L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertFalse(service.moveQueueItem(9L, 12L, new MoveKaraokeQueueItemRequest(DOWN), authentication));
        verify(queue, never()).saveAllAndFlush(any());
    }

    @Test
    void missingOrOtherRoomItemCannotBeMoved() {
        when(queue.findAllByRoom_IdOrderByQueuePositionAscCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(queuedItem(11L, FIRST, 0)));
        var error = assertThrows(ResponseStatusException.class, () -> service.moveQueueItem(
                9L, 99L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(404, error.getStatusCode().value());
        assertEquals("QUEUE_ITEM_NOT_FOUND", error.getReason());
        verify(queue, never()).saveAllAndFlush(any());
    }

    @Test
    void closedRoomCannotBeReordered() {
        room.setClosedAt(java.time.LocalDateTime.now());
        var error = assertThrows(ResponseStatusException.class, () -> service.moveQueueItem(
                9L, 11L, new MoveKaraokeQueueItemRequest(UP), authentication));
        assertEquals(410, error.getStatusCode().value());
        verify(queue, never()).saveAllAndFlush(any());
    }

    private KaraokeRoomQueueItem queuedItem(long id, String trackId, long position) {
        var item = new KaraokeRoomQueueItem();
        ReflectionTestUtils.setField(item, "id", id);
        item.setRoom(room);
        item.setRequester(user);
        item.setTrackId(trackId);
        item.setQueuePosition(position);
        return item;
    }
}
