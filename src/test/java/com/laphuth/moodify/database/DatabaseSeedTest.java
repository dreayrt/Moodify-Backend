package com.laphuth.moodify.database;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSeedTest {
    @Test
    void freshDatabaseContainsNoSampleAccountsOrBusinessActivity() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("database/Moodify.sql"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?i)INSERT INTO\\s+(\\w+)").matcher(sql);
        while (matcher.find()) {
            assertThat(matcher.group(1)).isEqualTo("subscription_tiers");
        }
        assertThat(sql).doesNotContain("moodify.local", "example.com", "$2a$");
    }

    @Test
    void relationalSchemaShouldNotStoreDerivedDuplicateColumns() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("database/Moodify.sql"), StandardCharsets.UTF_8);

        String playbackEvents = createTableBlock(sql, "playback_events");
        assertThat(playbackEvents)
            .contains("listening_history_id BIGINT NOT NULL")
            .doesNotContain("user_id BIGINT")
            .doesNotContain("track_id VARCHAR");

        String paymentTransactions = createTableBlock(sql, "payment_transactions");
        assertThat(paymentTransactions)
            .contains("subscription_id BIGINT NOT NULL")
            .doesNotContain("user_id BIGINT");

        String offlineDownloads = createTableBlock(sql, "offline_downloads");
        assertThat(offlineDownloads)
            .contains("device_id BIGINT NOT NULL")
            .doesNotContain("user_id BIGINT");

        String songLicenses = createTableBlock(sql, "song_licenses");
        assertThat(songLicenses)
            .contains("distributor_id BIGINT NULL")
            .contains("copyright_owner VARCHAR(200) NULL")
            .contains("CONSTRAINT chk_song_license_owner");

        assertThat(sql)
            .doesNotContain("CREATE TABLE rights_holders")
            .doesNotContain("INSERT INTO rights_holders");
    }

    private static String createTableBlock(String sql, String tableName) {
        Pattern tablePattern = Pattern.compile(
            "(?s)CREATE TABLE (?:IF NOT EXISTS )?" + tableName + " \\(.*?\\R\\)\\s*(?:ENGINE=[^;]+)?;"
        );
        Matcher matcher = tablePattern.matcher(sql);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }
}
