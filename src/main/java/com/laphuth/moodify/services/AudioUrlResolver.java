package com.laphuth.moodify.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AudioUrlResolver {
    private static String baseUrl = "";
    private static CatalogPreviewTickets previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(CatalogPreviewTickets value) { previews=value; }
    private static String withPreview(String url,String path) { return previews==null?url:previews.previewForCurrentStaff(url,path); }
    public static String resolveProtectedDocumentUrl(String path) {
        if(path==null||path.isBlank())return path;
        String normalized=path.startsWith("/")?path:"/"+path;
        if(!normalized.startsWith("/uploads/licenses/"))return path;
        return withPreview(backendBaseUrl+normalized,normalized);
    }

    private static String backendBaseUrl = "";

    public AudioUrlResolver(
        @Value("${audio.server.base-url:}") String configuredBaseUrl,
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
            return "";
        }
        String clean = localPath.trim().replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        if (clean.startsWith("uploads/")) {
            return withPreview(backendBaseUrl.isEmpty() ? ("/" + clean) : (backendBaseUrl + "/" + clean),"/"+clean);
        }
        return withPreview(baseUrl.isEmpty() ? ("/" + clean) : (baseUrl + clean),"/"+clean);
    }
}
