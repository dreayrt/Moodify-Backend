package com.laphuth.moodify.services;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashSet;
import java.util.Set;

@Component
public class KaraokeRoomCleanupScheduler {
    private final KaraokeRoomService roomService;
    private final KaraokeRoomRealtimeGateway realtimeGateway;
    private final long reconnectGraceMs;

    public KaraokeRoomCleanupScheduler(KaraokeRoomService roomService,
                                       KaraokeRoomRealtimeGateway realtimeGateway,
                                       @Value("${karaoke.rooms.member-reconnect-grace-ms:30000}") long reconnectGraceMs) {
        this.roomService = roomService;
        this.realtimeGateway = realtimeGateway;
        this.reconnectGraceMs = Math.max(0, reconnectGraceMs);
    }

    @Scheduled(fixedDelayString = "${karaoke.rooms.cleanup-interval-ms:5000}")
    public void expireDisconnectedMembersAndRooms() {
        Set<Long> changedRooms = new HashSet<>();
        for (KaraokeRoomRealtimeGateway.DisconnectedMember member : realtimeGateway.disconnectedMembersOlderThan(reconnectGraceMs)) {
            Long roomId = member.roomId();
            if (!realtimeGateway.hasRoomMemberSession(roomId, member.userId())
                    && roomService.markMemberDisconnected(roomId, member.userId())) {
                changedRooms.add(roomId);
            }
            realtimeGateway.forgetDisconnectedMember(roomId, member.userId());
        }
        changedRooms.forEach(roomId -> realtimeGateway.publishRoomChanged(roomId, "member.left"));
        if (roomService.closeEmptyRoomsAfterInactivity()) realtimeGateway.publishRoomsChanged();
    }
}
