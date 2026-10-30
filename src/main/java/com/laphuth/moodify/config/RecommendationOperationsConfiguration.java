package com.laphuth.moodify.config;

import com.laphuth.moodify.security.RecommendationOperationsFilter;
import com.laphuth.moodify.services.RecommendationRuntime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendationOperationsConfiguration {
    @Bean
    RecommendationOperationsFilter recommendationOperationsFilter(RecommendationRuntime runtime) {
        return new RecommendationOperationsFilter(runtime);
    }
}
