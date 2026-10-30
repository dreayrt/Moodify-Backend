package com.laphuth.moodify.dto.karaoke.rooms;

import jakarta.validation.constraints.Size;

public record JoinKaraokeRoomRequest(@Size(max = 64) String inviteCode) {}
