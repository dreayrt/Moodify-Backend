package com.laphuth.moodify.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class KaraokeRoomMemberId implements Serializable {
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "user_id")
    private Long userId;

    public KaraokeRoomMemberId() {}

    public KaraokeRoomMemberId(Long roomId, Long userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KaraokeRoomMemberId that)) return false;
        return Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, userId);
    }
}
