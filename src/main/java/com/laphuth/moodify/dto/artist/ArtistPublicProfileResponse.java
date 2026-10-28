package com.laphuth.moodify.dto.artist;

import com.laphuth.moodify.dto.album.AlbumResponse;
import com.laphuth.moodify.dto.track.TrackResponse;

import java.util.List;

public record ArtistPublicProfileResponse(
    String artistId,
    String name,
    String imageUrl,
    String bannerUrl,
    String bio,
    List<String> genres,
    boolean verified,
    String facebookUrl,
    String instagramUrl,
    String youtubeUrl,
    long followersCount,
    boolean isFollowed,
    double averageRating,
    long reviewsCount,
    List<TrackResponse> topTracks,
    List<AlbumResponse> albums,
    List<TrackResponse> appearsOn
) {}
