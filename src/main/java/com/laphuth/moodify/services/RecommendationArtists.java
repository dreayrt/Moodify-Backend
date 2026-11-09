package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import java.util.*;

/** Verified catalog identities only; display credits are never identity keys. */
public final class RecommendationArtists {
    private RecommendationArtists() {}
    public static Set<String> ids(Track track) {
        Set<String> result = new LinkedHashSet<>();
        if (track.getArtistSpotifyId() != null && !track.getArtistSpotifyId().isBlank()) result.add(track.getArtistSpotifyId());
        if (track.getArtistIds() != null) result.addAll(track.getArtistIds().stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList());
        return result;
    }
    public static boolean blocked(Track track, Set<String> ids) {
        return !Collections.disjoint(ids(track), ids);
    }
}
