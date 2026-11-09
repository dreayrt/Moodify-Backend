package com.laphuth.moodify.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RevenuePlaybackServiceTest {
    @Test void simulatedLicenseNeverCreatesRealRevenue() {
        var licenses=org.mockito.Mockito.mock(com.laphuth.moodify.repositories.SongLicenseRepository.class);
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new RevenuePlaybackService(jdbc,org.mockito.Mockito.mock(com.laphuth.moodify.repositories.TrackRepository.class),licenses,org.mockito.Mockito.mock(RecommendationOutcomeDispatcher.class));
        var track=new com.laphuth.moodify.entities.Track();track.setId("demo");track.setStatus("published");track.setModerationStatus("approved");track.setDurationMs(180000);
        var license=new com.laphuth.moodify.entities.SongLicense();license.setStatus(com.laphuth.moodify.entities.enums.LicenseStatus.ACTIVE);license.setLicenseType("DEMO_SIMULATED");
        org.mockito.Mockito.when(licenses.findFirstByTrackIdOrderByIdDesc("demo")).thenReturn(java.util.Optional.of(license));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"qualify",1L,track,"demo",java.time.LocalDateTime.now());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
    @Test
    void qualificationSelectsLatestLicenseAndRevocationWinsWithoutChangingRevenuePolicy() {
        var licenses=org.mockito.Mockito.mock(com.laphuth.moodify.repositories.SongLicenseRepository.class);
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new RevenuePlaybackService(jdbc,org.mockito.Mockito.mock(com.laphuth.moodify.repositories.TrackRepository.class),licenses,org.mockito.Mockito.mock(RecommendationOutcomeDispatcher.class));
        var track=new com.laphuth.moodify.entities.Track();track.setId("track");track.setStatus("published");track.setModerationStatus("approved");track.setDurationMs(180000);
        var license=new com.laphuth.moodify.entities.SongLicense();license.setStatus(com.laphuth.moodify.entities.enums.LicenseStatus.REVOKED);
        org.mockito.Mockito.when(licenses.findFirstByTrackIdOrderByIdDesc("track")).thenReturn(java.util.Optional.of(license));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"qualify",1L,track,"fixture",java.time.LocalDateTime.now());
        org.mockito.Mockito.verify(licenses).findFirstByTrackIdOrderByIdDesc("track");
        org.mockito.Mockito.verify(licenses,org.mockito.Mockito.never()).findByTrackId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
    @Test
    void continuousMinuteNeedsTwelveRealFiveSecondIntervals() {
        long continuous = 0;
        for (int i = 0; i < 11; i++) {
            continuous = RevenuePlaybackService.advanceContinuousMs(continuous, 5_000, 5_000);
        }
        assertThat(continuous).isLessThan(60_000);
        assertThat(RevenuePlaybackService.advanceContinuousMs(continuous, 5_000, 5_000)).isEqualTo(60_000);
    }

    @Test
    void stalledOrJumpedPlaybackBreaksContinuousMinute() {
        assertThat(RevenuePlaybackService.advanceContinuousMs(55_000, 5_000, 0)).isZero();
        assertThat(RevenuePlaybackService.advanceContinuousMs(55_000, 5_000, 60_000)).isZero();
        assertThat(RevenuePlaybackService.advanceContinuousMs(55_000, 20_000, 20_000)).isZero();
    }
}
