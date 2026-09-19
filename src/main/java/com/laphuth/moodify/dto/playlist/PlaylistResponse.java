package com.laphuth.moodify.dto.playlist;

import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Playlist;

import java.time.Instant;
import java.util.List;

public record PlaylistResponse(
    String id,
    String name,
    String description,
    String coverUrl,
    String username,
    Boolean isPublic,
    int trackCount,
    List<TrackResponse> tracks,
    Instant createdAt,
    Instant updatedAt
) {
    public static PlaylistResponse from(Playlist playlist, List<TrackResponse> tracks) {
        String effectiveCover = playlist.getCoverUrl();
        if ((effectiveCover == null || effectiveCover.isBlank()) && tracks != null && !tracks.isEmpty()) {
            effectiveCover = tracks.get(0).imageUrl();
        }

        return new PlaylistResponse(
            playlist.getId(),
            playlist.getName(),
            playlist.getDescription(),
            effectiveCover,
            playlist.getUsername(),
            playlist.getIsPublic(),
            playlist.getTrackSpotifyIds() != null ? playlist.getTrackSpotifyIds().size() : 0,
            tracks,
            playlist.getCreatedAt(),
            playlist.getUpdatedAt()
        );
    }
}
