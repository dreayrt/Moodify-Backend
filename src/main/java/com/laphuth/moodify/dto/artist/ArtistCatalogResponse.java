package com.laphuth.moodify.dto.artist;

import java.util.List;

public record ArtistCatalogResponse(
    ArtistProfileResponse artist,
    List<ArtistTrackResponse> tracks,
    List<ArtistAlbumResponse> albums,
    int page,
    int size,
    long totalTracks,
    int totalTrackPages
) {
}
