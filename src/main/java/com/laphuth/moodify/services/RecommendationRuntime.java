package com.laphuth.moodify.services;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Per-process controls/metrics: bounded labels, no users, tokens or durable configuration. */
@Component
public class RecommendationRuntime {
    public record Flags(boolean enabled, boolean collaborative, boolean campaigns, long revision) {}
    private final AtomicReference<Flags> flags;
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    public RecommendationRuntime(@Value("${recommendations.enabled:true}") boolean enabled,
        @Value("${recommendations.collaborative-enabled:true}") boolean collaborative,
        @Value("${recommendations.campaigns-enabled:true}") boolean campaigns) {
        flags = new AtomicReference<>(new Flags(enabled, collaborative, campaigns, 1));
    }

    public Flags flags() { return flags.get(); }
    public String rankingKey() { return flags.get().toString(); }
    public void requireGeneration() {
        if (!flags().enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recommendation generation is disabled");
    }
    public Flags update(boolean enabled, boolean collaborative, boolean campaigns, long expectedRevision) {
        Flags previous = flags.get();
        if (previous.revision() != expectedRevision)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recommendation controls changed; read the current revision");
        Flags next = new Flags(enabled, collaborative, campaigns, previous.revision() + 1);
        if (!flags.compareAndSet(previous, next))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recommendation controls changed concurrently");
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Recommendation controls updated: {}", next);
        return next;
    }

    // Only internal, fixed labels may be passed. Never use IDs or exception messages as labels.
    void count(String label) { counters.computeIfAbsent(label, ignored -> new LongAdder()).increment(); }
    public void observe(String operation, int status, long elapsedNanos) {
        count(operation + ".requests");
        if (status >= 500) count(operation + ".server_errors");
        if (status == 503) count(operation + ".unavailable");
        if (status == 429) count(operation + ".throttled");
        long ms = elapsedNanos / 1_000_000;
        String bucket = ms <= 25 ? "le25" : ms <= 100 ? "le100" : ms <= 500 ? "le500" : ms <= 1000 ? "le1000" : ms <= 5000 ? "le5000" : "gt5000";
        count(operation + ".latency_ms." + bucket);
    }
    public Map<String,Object> snapshot() {
        Map<String,Long> values = new TreeMap<>();
        counters.forEach((key,value) -> values.put(key, value.sum()));
        return Map.of("scope", "THIS_PROCESS", "startedAt", startedAt, "flags", flags(), "counters", values);
    }
}
