package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.karaoke.rooms.QueueKaraokeSongRequest;
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
        service.addQueueItem(9L, new QueueKaraokeSongRequest(FIRST), authentication);
        service.addQueueItem(9L, new QueueKaraokeSongRequest(SECOND), authentication);
        var saved = org.mockito.ArgumentCaptor.forClass(KaraokeRoomQueueItem.class);
        verify(queue, times(2)).saveAndFlush(saved.capture());
        assertEquals(java.util.List.of(FIRST, SECOND), saved.getAllValues().stream()
                .map(KaraokeRoomQueueItem::getTrackId).toList());
        assertTrue(saved.getAllValues().stream().allMatch(item -> item.getRequester() == user));
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
}
