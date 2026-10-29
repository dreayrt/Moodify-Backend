package com.laphuth.moodify.recommendation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendationOperationsConfiguration {
    @Bean
    RecommendationOperationsFilter recommendationOperationsFilter(RecommendationRuntime runtime) {
        return new RecommendationOperationsFilter(runtime);
    }
}
