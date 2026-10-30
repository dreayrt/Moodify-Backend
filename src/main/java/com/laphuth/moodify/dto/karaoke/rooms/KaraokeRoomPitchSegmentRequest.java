package com.laphuth.moodify.dto.karaoke.rooms;

import com.laphuth.moodify.dto.karaoke.KaraokePitchFeatureRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

public record KaraokeRoomPitchSegmentRequest(
        @JsonProperty("offset_ms") double offsetMs,
        KaraokePitchFeatureRequest features) {}
