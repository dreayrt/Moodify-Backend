package com.laphuth.moodify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public endpoint cung cấp thông tin phiên bản mới nhất của ứng dụng Android
 * phục vụ cơ chế In-App Auto-Update, tự động đồng bộ từ GitHub Releases và cache theo TTL.
 */
@RestController
@RequestMapping("/api/app")
public class AppVersionApi {

    private static final Pattern PUBSPEC_VERSION_PATTERN =
            Pattern.compile("^version:\\s*[^\\s+#]+\\+(\\d+)", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_HEADING_PATTERN =
            Pattern.compile("^\\s*#{1,6}\\s*");

    @FunctionalInterface
    interface RemoteFetcher {
        RemoteResponse get(String url, Map<String, String> headers) throws Exception;
    }

    record RemoteResponse(int statusCode, String body) {}

    private record CachedPayload(Map<String, Object> body, Instant expiresAt, boolean fromGitHub) {}

    @Value("${app.update.github-repo:Kandesfx/moodify-mobile}")
    private String githubRepo = "Kandesfx/moodify-mobile";

    @Value("${app.update.github-token:}")
    private String githubToken = "";

    @Value("${app.update.github-enabled:true}")
    private boolean githubEnabled = true;

    @Value("${app.update.cache-ttl-seconds:300}")
    private long cacheTtlSeconds = 300;

    @Value("${app.update.latest-version:0.1.4}")
    private String latestVersion = "0.1.4";

    @Value("${app.update.latest-build-number:5}")
    private int latestBuildNumber = 5;

    @Value("${app.update.download-url:https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.4/moodify-release.apk}")
    private String downloadUrl =
            "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.4/moodify-release.apk";

    @Value("${app.update.release-notes:• Khắc phục lỗi Trang chủ khi chưa đăng nhập và nâng cấp tự động cập nhật từ GitHub}")
    private String releaseNotes =
            "• Khắc phục lỗi Trang chủ khi chưa đăng nhập và nâng cấp tự động cập nhật từ GitHub";

    @Value("${app.update.force-update:false}")
    private boolean forceUpdate = false;

    private final RemoteFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object cacheLock = new Object();
    private final Map<String, Integer> pubspecBuildCache = new ConcurrentHashMap<>();
    private volatile CachedPayload cachedPayload;

    @Autowired
    public AppVersionApi() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.fetcher = defaultFetcher(httpClient);
        this.objectMapper = new ObjectMapper();
        this.clock = Clock.systemUTC();
    }

    AppVersionApi(
            RemoteFetcher fetcher,
            Clock clock,
            String githubRepo,
            String githubToken,
            boolean githubEnabled,
            long cacheTtlSeconds,
            String fallbackVersion,
            int fallbackBuildNumber,
            String fallbackDownloadUrl,
            String fallbackReleaseNotes,
            boolean forceUpdate
    ) {
        this.fetcher = fetcher;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.githubRepo = githubRepo;
        this.githubToken = githubToken;
        this.githubEnabled = githubEnabled;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.latestVersion = fallbackVersion;
        this.latestBuildNumber = fallbackBuildNumber;
        this.downloadUrl = fallbackDownloadUrl;
        this.releaseNotes = fallbackReleaseNotes;
        this.forceUpdate = forceUpdate;
    }

    private static RemoteFetcher defaultFetcher(HttpClient httpClient) {
        return (url, headers) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET();
            headers.forEach(builder::header);
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new RemoteResponse(response.statusCode(), response.body());
        };
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getLatestVersion() {
        Instant now = clock.instant();
        CachedPayload current = cachedPayload;
        if (current != null && now.isBefore(current.expiresAt())) {
            return ResponseEntity.ok(new LinkedHashMap<>(current.body()));
        }

        synchronized (cacheLock) {
            now = clock.instant();
            current = cachedPayload;
            if (current != null && now.isBefore(current.expiresAt())) {
                return ResponseEntity.ok(new LinkedHashMap<>(current.body()));
            }
            CachedPayload refreshed = resolvePayload(now, current);
            cachedPayload = refreshed;
            return ResponseEntity.ok(new LinkedHashMap<>(refreshed.body()));
        }
    }

    private CachedPayload resolvePayload(Instant now, CachedPayload previous) {
        long ttl = effectiveTtlSeconds();
        String repo = githubRepo == null ? "" : githubRepo.trim();

        if (!githubEnabled || repo.isEmpty()) {
            return new CachedPayload(buildFallbackBody(), now.plusSeconds(ttl), false);
        }

        CachedPayload fetched = fetchLatestRelease(now, repo, ttl);
        if (fetched != null) {
            return fetched;
        }

        long retrySeconds = Math.min(ttl, 60L);
        if (previous != null && previous.fromGitHub()) {
            return new CachedPayload(previous.body(), now.plusSeconds(retrySeconds), true);
        }

        return new CachedPayload(buildFallbackBody(), now.plusSeconds(retrySeconds), false);
    }

    private CachedPayload fetchLatestRelease(Instant now, String repo, long ttl) {
        try {
            String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
            RemoteResponse res = fetcher.get(apiUrl, buildGitHubHeaders(true));
            if (res == null || res.statusCode() != 200 || res.body() == null || res.body().isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(res.body());
            if (root.path("draft").asBoolean(false) || root.path("prerelease").asBoolean(false)) {
                return null;
            }

            String tagName = root.path("tag_name").asText("").trim();
            String version = normalizeVersion(tagName);
            if (version.isEmpty()) {
                return null;
            }

            String apkUrl = pickApkDownloadUrl(root.path("assets"));
            if (apkUrl == null) {
                return null;
            }

            Integer resolvedBuild = resolveBuildNumber(repo, tagName);
            long effectiveExpiry = ttl;
            int buildNumber;
            if (resolvedBuild != null && resolvedBuild > 0) {
                buildNumber = resolvedBuild;
            } else {
                buildNumber = version.equals(normalizeVersion(latestVersion)) ? latestBuildNumber : 0;
                effectiveExpiry = Math.min(ttl, 30L);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("latestVersion", version);
            body.put("latestBuildNumber", buildNumber);
            body.put("downloadUrl", apkUrl);
            body.put("releaseNotes", cleanReleaseNotes(root.path("body").asText("")));
            body.put("forceUpdate", forceUpdate);
            body.put("source", "github");
            return new CachedPayload(body, now.plusSeconds(effectiveExpiry), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer resolveBuildNumber(String repo, String tagName) {
        if (tagName.isEmpty() || tagName.contains("..") || tagName.contains("?") || tagName.contains("#")) {
            return null;
        }
        Integer cached = pubspecBuildCache.get(tagName);
        if (cached != null) {
            return cached;
        }

        try {
            String pubspecUrl = "https://raw.githubusercontent.com/" + repo + "/" + tagName + "/pubspec.yaml";
            RemoteResponse res = fetcher.get(pubspecUrl, buildGitHubHeaders(false));
            if (res == null || res.statusCode() != 200 || res.body() == null) {
                return null;
            }
            Matcher matcher = PUBSPEC_VERSION_PATTERN.matcher(res.body());
            if (matcher.find()) {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed > 0) {
                    pubspecBuildCache.put(tagName, parsed);
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Map<String, String> buildGitHubHeaders(boolean apiRequest) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Moodify-Backend");
        if (apiRequest) {
            headers.put("Accept", "application/vnd.github+json");
        }
        if (githubToken != null && !githubToken.isBlank()) {
            headers.put("Authorization", "Bearer " + githubToken.trim());
        }
        return headers;
    }

    private static String pickApkDownloadUrl(JsonNode assetsNode) {
        if (!assetsNode.isArray()) {
            return null;
        }
        String moodifyUrl = null;
        String appReleaseUrl = null;
        String firstApkUrl = null;

        for (JsonNode asset : assetsNode) {
            String name = asset.path("name").asText("").trim().toLowerCase();
            String url = asset.path("browser_download_url").asText("").trim();
            if (!name.endsWith(".apk") || !isHttpsUrl(url)) {
                continue;
            }
            if ("moodify-release.apk".equals(name)) {
                moodifyUrl = url;
                break;
            } else if ("app-release.apk".equals(name) && appReleaseUrl == null) {
                appReleaseUrl = url;
            } else if (firstApkUrl == null) {
                firstApkUrl = url;
            }
        }

        if (moodifyUrl != null) return moodifyUrl;
        if (appReleaseUrl != null) return appReleaseUrl;
        return firstApkUrl;
    }

    private static boolean isHttpsUrl(String raw) {
        if (raw.isEmpty()) return false;
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        int idx = 0;
        while (idx < trimmed.length() && (trimmed.charAt(idx) == 'v' || trimmed.charAt(idx) == 'V')) {
            idx++;
        }
        return trimmed.substring(idx).trim();
    }

    private static String cleanReleaseNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(MARKDOWN_HEADING_PATTERN.matcher(lines[i]).replaceFirst(""));
        }
        return out.toString().trim();
    }

    private Map<String, Object> buildFallbackBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latestVersion", normalizeVersion(latestVersion));
        body.put("latestBuildNumber", latestBuildNumber);
        body.put("downloadUrl", downloadUrl);
        body.put("releaseNotes", releaseNotes);
        body.put("forceUpdate", forceUpdate);
        body.put("source", "fallback");
        return body;
    }

    private long effectiveTtlSeconds() {
        return Math.max(cacheTtlSeconds, 5L);
    }
}
