package com.laphuth.moodify.api;

import com.laphuth.moodify.services.RecommendationCatalog;
import com.laphuth.moodify.services.RecommendationRuntime;
import com.laphuth.moodify.services.RecommendationOutcomeDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** No users, tokens, text or controls. Separate read-only machine credential. */
@RestController
public class RecommendationMonitorApi {
    private final RecommendationCatalog catalog;
    private final RecommendationRuntime runtime;
    private final RecommendationOutcomeDispatcher outcomes;
    private final byte[] secret;
    public RecommendationMonitorApi(RecommendationCatalog catalog,RecommendationRuntime runtime,
        RecommendationOutcomeDispatcher outcomes,@Value("${recommendations.monitor-secret:}") String secret) {
        this.catalog=catalog;this.runtime=runtime;this.outcomes=outcomes;
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/api/internal/recommendations/monitor")
    public Map<String,Object> monitor(@RequestHeader(value="X-Recommendation-Monitor-Key",required=false) String key) {
        if(secret.length<32)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(key==null||key.length()>256||!MessageDigest.isEqual(secret,key.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return Map.of("at",Instant.now(),"readiness",catalog.readiness(),
            "operations",Map.of("runtime",runtime.snapshot(),"outcomeQueue",outcomes.status()));
    }
}
