package com.laphuth.moodify;

import com.laphuth.moodify.api.SeedDataApi;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class MoodifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoodifyApplication.class, args);
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(SeedDataApi.class)
    CommandLineRunner seedOnStartup(SeedDataApi seedDataApi) {
        return args -> {
            // Auto-seed tracks on startup if database is empty
            try {
                seedDataApi.seedTracksOnStartup();
            } catch (Exception e) {
                System.err.println("Warning: Could not seed tracks: " + e.getMessage());
            }
        };
    }
}
