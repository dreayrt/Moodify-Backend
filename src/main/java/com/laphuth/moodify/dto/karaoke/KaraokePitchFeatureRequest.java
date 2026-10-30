package com.laphuth.moodify.dto.karaoke;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KaraokePitchFeatureRequest(
        @JsonProperty("duration_seconds") double durationSeconds,
        @JsonProperty("f0_hz") List<Double> f0Hz,
        @JsonProperty("voiced_probability") List<Double> voicedProbability,
        @JsonProperty("rms_dbfs") double rmsDbfs,
        @JsonProperty("clipped_fraction") double clippedFraction,
        @JsonProperty("recording_start_offset_ms") double recordingStartOffsetMs,
        @JsonProperty("capture_latency_ms") double captureLatencyMs,
        @JsonProperty("allow_octave_shift") boolean allowOctaveShift) {
}
