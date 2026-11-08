package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.KaraokePlaybackStatus;
import com.laphuth.moodify.repositories.KaraokeRoomRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KaraokeRoomPlaybackScheduler {
    private final KaraokeRoomRepository roomRepository;
    private final KaraokeRoomService roomService;
    private final KaraokeRoomRealtimeGateway realtimeGateway;

    public KaraokeRoomPlaybackScheduler(KaraokeRoomRepository roomRepository,
                                        KaraokeRoomService roomService,
                                        KaraokeRoomRealtimeGateway realtimeGateway) {
        this.roomRepository = roomRepository;
        this.roomService = roomService;
        this.realtimeGateway = realtimeGateway;
    }

    @Scheduled(fixedDelayString = "${karaoke.rooms.playback-sweep-interval-ms:1000}")
    public void advanceExpiredTracks() {
        for (KaraokePlaybackStatus status : List.of(KaraokePlaybackStatus.PLAYING, KaraokePlaybackStatus.PAUSED)) {
            for (Long roomId : roomRepository.findAllIdsByPlaybackStatus(status)) {
                if (roomService.advanceIfExpired(roomId)) {
                    realtimeGateway.publishRoomChanged(roomId, "playback.updated");
                }
            }
        }
    }
}
