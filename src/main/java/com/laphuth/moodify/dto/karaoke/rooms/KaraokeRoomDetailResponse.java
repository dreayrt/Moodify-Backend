package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.entities.KaraokeRoomVisibility;

import java.time.LocalDateTime;
import java.util.List;

public record KaraokeRoomDetailResponse(
        Long id,
        String name,
        Long hostUserId,
        String hostName,
        String hostAvatarUrl,
        Integer maxMembers,
        KaraokeRoomVisibility visibility,
        String inviteCode,
        LocalDateTime createdAt,
        long memberCount,
        boolean canManage,
        List<KaraokeRoomMemberResponse> members,
        List<KaraokeRoomQueueItemResponse> queue,
        KaraokeRoomPlaybackResponse playback
) {}
