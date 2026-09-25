package com.laphuth.moodify.dto.contentlead;

import java.util.List;

public record ContentLeadCatalogResponse(
    ContentLeadProfileResponse artist,
    List<ContentLeadTrackResponse> tracks,
    List<ContentLeadAlbumResponse> albums,
    int page,
    int size,
    long totalTracks,
    int totalTrackPages
) {
}
