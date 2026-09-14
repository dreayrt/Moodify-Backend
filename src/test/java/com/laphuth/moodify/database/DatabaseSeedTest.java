package com.laphuth.moodify.database;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSeedTest {
    private static final Pattern BCRYPT_HASH = Pattern.compile(
        "\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}"
    );

    @Test
    void allSeedUsersShouldUseBcrypt() throws Exception {
        String sql = new ClassPathResource("db/moodify-reset.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).doesNotContainIgnoringCase("SHA2(");

        Matcher matcher = BCRYPT_HASH.matcher(sql);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        int hashCount = 0;

        while (matcher.find()) {
            assertThat(encoder.matches("123456", matcher.group())).isTrue();
            hashCount++;
        }

        assertThat(hashCount).isEqualTo(5);
    }

    @Test
    void relationalSchemaShouldNotStoreDerivedDuplicateColumns() throws Exception {
        String sql = new ClassPathResource("db/moodify-reset.sql")
            .getContentAsString(StandardCharsets.UTF_8);

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
            "(?s)CREATE TABLE " + tableName + " \\(.*?\\R\\) ;"
        );
        Matcher matcher = tablePattern.matcher(sql);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }
}
