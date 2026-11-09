package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RecommendationMonitorStatus {
    private final Path path;
    private final ObjectMapper json=new ObjectMapper();
    public RecommendationMonitorStatus(@Value("${recommendations.monitor-status-path:/var/run/recommendation-monitor/status.json}") String path) {
        this.path=Path.of(path);
    }
    public Map<String,Object> read() {
        try {
            if(!Files.isRegularFile(path)||Files.size(path)>32768)return Map.of("available",false);
            var data=json.readTree(Files.readString(path));
            Instant at=Instant.parse(data.path("at").asText());
            List<String> alerts=new ArrayList<>();
            data.path("alerts").forEach(value->{String code=value.asText();if(code.matches("[A-Z_]{1,60}"))alerts.add(code);});
            return Map.of("available",true,"stale",at.isBefore(Instant.now().minusSeconds(180)),"at",at,
                "alerts",alerts,"consecutiveFailures",data.path("consecutive_failures").asInt());
        } catch(Exception unavailable) { return Map.of("available",false); }
    }
}
