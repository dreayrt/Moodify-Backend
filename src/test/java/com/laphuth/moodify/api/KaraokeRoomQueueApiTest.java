package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.karaoke.rooms.MoveKaraokeQueueItemRequest;
import com.laphuth.moodify.services.KaraokeMediaTicketService;
import com.laphuth.moodify.services.KaraokeRoomRealtimeGateway;
import com.laphuth.moodify.services.KaraokeRoomScoringService;
import com.laphuth.moodify.services.KaraokeRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KaraokeRoomQueueApiTest {
    private final KaraokeRoomService service = mock(KaraokeRoomService.class);
    private final KaraokeRoomRealtimeGateway realtime = mock(KaraokeRoomRealtimeGateway.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KaraokeRoomsApi(service, realtime,
            mock(KaraokeMediaTicketService.class), mock(KaraokeRoomScoringService.class))).build();

    @Test
    void successfulMoveNotifiesTheWholeRoom() throws Exception {
        var request = new MoveKaraokeQueueItemRequest(MoveKaraokeQueueItemRequest.Direction.UP);
        when(service.moveQueueItem(eq(9L), eq(12L), eq(request), isNull())).thenReturn(true);
        mvc.perform(put("/api/karaoke/rooms/9/queue/12/position")
                .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"UP\"}"))
                .andExpect(status().isNoContent());
        verify(service).moveQueueItem(9L, 12L, request, null);
        verify(realtime).publishRoomChanged(9L, "queue.updated");
    }

    @Test
    void boundaryNoOpDoesNotBroadcastAnUpdate() throws Exception {
        mvc.perform(put("/api/karaoke/rooms/9/queue/12/position")
                .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"DOWN\"}"))
                .andExpect(status().isNoContent());
        verifyNoInteractions(realtime);
    }

    @Test
    void missingOrInvalidDirectionIsRejectedBeforeMutation() throws Exception {
        for (String json : new String[]{"{}", "{\"direction\":null}", "{\"direction\":\"SIDEWAYS\"}"}) {
            mvc.perform(put("/api/karaoke/rooms/9/queue/12/position")
                    .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, realtime);
    }
}
