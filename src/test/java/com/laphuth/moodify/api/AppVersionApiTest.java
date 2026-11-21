package com.laphuth.moodify.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Standalone controller test: does not start Spring Boot or touch DB/auto-DDL.
class AppVersionApiTest {

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void fetchesLatestGitHubReleaseAndPubspecBuildNumberAndStripsMarkdownHeading() throws Exception {
        AppVersionApi.RemoteFetcher fetcher = (url, headers) -> {
            if (url.endsWith("/releases/latest")) {
                return new AppVersionApi.RemoteResponse(200, """
                        {
                          "tag_name": "v0.1.5",
                          "draft": false,
                          "prerelease": false,
                          "body": "## Có gì mới trong bản v0.1.5:\\n• Tự động đồng bộ từ Git",
                          "assets": [
                            {
                              "name": "app-release.apk",
                              "browser_download_url": "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.5/app-release.apk"
                            },
                            {
                              "name": "moodify-release.apk",
                              "browser_download_url": "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.5/moodify-release.apk"
                            }
                          ]
                        }
                        """);
            }
            if (url.endsWith("/v0.1.5/pubspec.yaml")) {
                return new AppVersionApi.RemoteResponse(200, """
                        name: moodify_flutter
                        description: Moodify mobile application
                        publish_to: none
                        version: 0.1.5+6
                        """);
            }
            return new AppVersionApi.RemoteResponse(404, "");
        };

        AppVersionApi api = new AppVersionApi(
                fetcher,
                new MutableClock(Instant.parse("2026-11-21T12:00:00Z")),
                "Kandesfx/moodify-mobile",
                "",
                true,
                300,
                "0.1.4",
                5,
                "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.4/moodify-release.apk",
                "Fallback notes",
                false
        );

        MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();
        mvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("github"))
                .andExpect(jsonPath("$.latestVersion").value("0.1.5"))
                .andExpect(jsonPath("$.latestBuildNumber").value(6))
                .andExpect(jsonPath("$.downloadUrl").value(
                        "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.5/moodify-release.apk"))
                .andExpect(jsonPath("$.releaseNotes").value(
                        "Có gì mới trong bản v0.1.5:\n• Tự động đồng bộ từ Git"));
    }

    @Test
    void cachesWithinTtlRefreshesAfterExpiryAndPreservesStaleGitHubCacheOnError() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
        AtomicInteger releaseFetchCount = new AtomicInteger(0);
        AtomicReference<AppVersionApi.RemoteResponse> releaseResponse = new AtomicReference<>(
                new AppVersionApi.RemoteResponse(200, """
                        {
                          "tag_name": "v0.1.4",
                          "draft": false,
                          "prerelease": false,
                          "body": "Bản v0.1.4",
                          "assets": [
                            {
                              "name": "moodify-release.apk",
                              "browser_download_url": "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.4/moodify-release.apk"
                            }
                          ]
                        }
                        """)
        );

        AppVersionApi.RemoteFetcher fetcher = (url, headers) -> {
            if (url.endsWith("/releases/latest")) {
                releaseFetchCount.incrementAndGet();
                return releaseResponse.get();
            }
            if (url.endsWith("/pubspec.yaml")) {
                return new AppVersionApi.RemoteResponse(200, "version: 0.1.4+5\n");
            }
            return new AppVersionApi.RemoteResponse(404, "");
        };

        AppVersionApi api = new AppVersionApi(
                fetcher,
                clock,
                "Kandesfx/moodify-mobile",
                "",
                true,
                300,
                "0.1.2",
                3,
                "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.2/moodify-release.apk",
                "Old fallback",
                false
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();

        // First call populates cache
        mvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value("0.1.4"))
                .andExpect(jsonPath("$.latestBuildNumber").value(5))
                .andExpect(jsonPath("$.source").value("github"));
        assertEquals(1, releaseFetchCount.get());

        // Second call within TTL hits cache without calling GitHub again
        clock.advanceSeconds(120);
        mvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value("0.1.4"));
        assertEquals(1, releaseFetchCount.get());

        // Past TTL + GitHub temporarily fails -> serves previous stale GitHub payload instead of downgrading
        clock.advanceSeconds(200);
        releaseResponse.set(new AppVersionApi.RemoteResponse(503, "rate limited"));
        mvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value("0.1.4"))
                .andExpect(jsonPath("$.latestBuildNumber").value(5))
                .andExpect(jsonPath("$.source").value("github"));
        assertEquals(2, releaseFetchCount.get());
    }

    @Test
    void servesStaticFallbackOnInitialGitHubFailure() throws Exception {
        AppVersionApi.RemoteFetcher failingFetcher = (url, headers) -> {
            throw new IllegalStateException("network unreachable");
        };

        AppVersionApi api = new AppVersionApi(
                failingFetcher,
                new MutableClock(Instant.parse("2026-11-21T12:00:00Z")),
                "Kandesfx/moodify-mobile",
                "",
                true,
                300,
                "0.1.4",
                5,
                "https://github.com/Kandesfx/moodify-mobile/releases/download/v0.1.4/moodify-release.apk",
                "Fallback notes",
                false
        );

        MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();
        mvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fallback"))
                .andExpect(jsonPath("$.latestVersion").value("0.1.4"))
                .andExpect(jsonPath("$.latestBuildNumber").value(5));
    }
}
