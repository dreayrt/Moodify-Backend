package com.laphuth.moodify.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AudioUrlResolver {
    public static final String DEFAULT_ONLINE_AUDIO_BASE_URL = "http://158.178.247.33/";
    private static String baseUrl = DEFAULT_ONLINE_AUDIO_BASE_URL;

    public AudioUrlResolver(@Value("${oracle.audio.base-url:}") String configuredBaseUrl) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            String clean = configuredBaseUrl.trim();
            if (!clean.endsWith("/")) {
                clean += "/";
            }
            baseUrl = clean;
        } else {
            baseUrl = DEFAULT_ONLINE_AUDIO_BASE_URL;
        }
    }

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static String resolve(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return baseUrl + "data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3";
        }
        String clean = localPath.trim().replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        if (clean.startsWith("uploads/")) {
            return "/" + clean;
        }
        return baseUrl + clean;
    }
}
