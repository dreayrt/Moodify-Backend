package com.laphuth.moodify.repositories;

import java.time.LocalDateTime;

public interface KaraokeRoomSummaryProjection {
    Long getId();
    String getName();
    String getHostName();
    String getHostAvatarUrl();
    Integer getMaxMembers();
    String getVisibility();
    LocalDateTime getCreatedAt();
    Long getMemberCount();
}
