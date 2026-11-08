package com.laphuth.moodify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** A dedicated internal key authenticates these endpoints in KaraokeProcessingWorkerApi. */
@Configuration
public class KaraokeProcessingSecurity {
    @Bean @Order(0)
    SecurityFilterChain karaokeProcessingChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/internal/karaoke/processing/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
