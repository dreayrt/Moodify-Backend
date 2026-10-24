package com.laphuth.moodify.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RevenuePlaybackServiceTest {
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
