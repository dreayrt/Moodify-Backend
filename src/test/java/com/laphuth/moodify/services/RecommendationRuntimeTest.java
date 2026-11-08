package com.laphuth.moodify.services;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecommendationRuntimeTest {
    @Test void controlsAreRevisionCheckedAndCanDisableGenerationWithoutADatabase() {
        var runtime=new RecommendationRuntime(true,true,true);
        String original=runtime.rankingKey();
        runtime.update(false,false,true,1);
        assertThat(runtime.rankingKey()).isNotEqualTo(original);
        assertThatThrownBy(runtime::requireGeneration).isInstanceOfSatisfying(ResponseStatusException.class,
            e->assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThatThrownBy(()->runtime.update(true,true,true,1)).isInstanceOfSatisfying(ResponseStatusException.class,
            e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(runtime.flags().enabled()).isFalse();
        runtime.update(true,true,true,2);runtime.requireGeneration();
    }
    @Test void metricsUseFixedHistogramBucketsAndDistinctErrorAndEmptyCounts() {
        var runtime=new RecommendationRuntime(true,true,true);
        runtime.observe("recommendation",503,10_000_000);
        runtime.observe("recommendation",200,800_000_000);
        runtime.count("recommendation.empty");
        var counters=(java.util.Map<?,?>)runtime.snapshot().get("counters");
        assertThat(counters.get("recommendation.requests")).isEqualTo(2L);
        assertThat(counters.get("recommendation.server_errors")).isEqualTo(1L);
        assertThat(counters.get("recommendation.empty")).isEqualTo(1L);
        assertThat(counters.get("recommendation.latency_ms.le25")).isEqualTo(1L);
        assertThat(counters.get("recommendation.latency_ms.le1000")).isEqualTo(1L);
    }
}
