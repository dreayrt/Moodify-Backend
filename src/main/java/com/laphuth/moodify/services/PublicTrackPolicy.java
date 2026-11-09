package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;

/** Fail closed: missing publication metadata is never public catalog content. */
public final class PublicTrackPolicy {
    private PublicTrackPolicy() {}
    public static boolean isPublishedAudioTrack(Track track) {
        if (track == null || !"published".equalsIgnoreCase(track.getStatus())
            || !"public".equalsIgnoreCase(track.getVisibility())) return false;
        return hasAudioPath(track.getLocalPath());
    }
    public static boolean hasAudioPath(String path) {
        if (path == null || path.isBlank()) return false;
        String clean = path.trim().replace('\\', '/');
        if (clean.indexOf('\0') >= 0 || java.util.Arrays.asList(clean.split("/")).contains("..")) return false;
        return !clean.equalsIgnoreCase("null") && !clean.equalsIgnoreCase("undefined");
    }
}
