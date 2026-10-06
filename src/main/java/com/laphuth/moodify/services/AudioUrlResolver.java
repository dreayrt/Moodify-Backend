package com.laphuth.moodify.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AudioUrlResolver {
    private static String baseUrl = "";
    private static String backendBaseUrl = "";

    public AudioUrlResolver(
        @Value("${oracle.audio.base-url:}") String configuredBaseUrl,
        @Value("${app.backend-base-url:}") String configuredBackendBaseUrl
    ) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            String clean = configuredBaseUrl.trim();
            if (!clean.endsWith("/")) {
                clean += "/";
            }
            baseUrl = clean;
        } else {
            baseUrl = "";
        }

        if (configuredBackendBaseUrl != null && !configuredBackendBaseUrl.isBlank()) {
            String cleanBackend = configuredBackendBaseUrl.trim();
            while (cleanBackend.endsWith("/")) {
                cleanBackend = cleanBackend.substring(0, cleanBackend.length() - 1);
            }
            backendBaseUrl = cleanBackend;
        } else {
            backendBaseUrl = "";
        }
    }

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static String getBackendBaseUrl() {
        return backendBaseUrl;
    }

    public static String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String clean = imageUrl.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("blob:") || clean.startsWith("data:")) {
            return clean;
        }
        String normalized = clean.replace("\\", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("uploads/")) {
            return backendBaseUrl.isEmpty() ? ("/" + normalized) : (backendBaseUrl + "/" + normalized);
        }
        if (normalized.startsWith("data/")) {
            return baseUrl.isEmpty() ? ("/" + normalized) : (baseUrl + normalized);
        }
        return backendBaseUrl.isEmpty() ? ("/" + normalized) : (backendBaseUrl + "/" + normalized);
    }

    public static String resolve(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return baseUrl.isEmpty() ? "" : (baseUrl + "data/audio/xesi-hoaprox/3b2kCFZhX9GYnQ58qL1cAM_vo-tinh.mp3");
        }
        String clean = localPath.trim().replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        if (clean.startsWith("uploads/")) {
            return backendBaseUrl.isEmpty() ? ("/" + clean) : (backendBaseUrl + "/" + clean);
        }
        return baseUrl.isEmpty() ? ("/" + clean) : (baseUrl + clean);
    }
}
