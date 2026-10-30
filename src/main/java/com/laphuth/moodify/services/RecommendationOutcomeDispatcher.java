package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.RecommendationStore;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Best-effort post-commit analytics. Rejection MUST never run work on the HTTP thread. */
@Component
public class RecommendationOutcomeDispatcher implements AutoCloseable {
    private final RecommendationStore store;
    private final RecommendationRuntime runtime;
    private final ThreadPoolExecutor executor;
    private final LongSupplier nanoTime;
    private final long cooldownNanos;
    private final int failureThreshold;
    private final int capacity;
    private volatile long openUntil;
    private int consecutiveFailures;

    @Autowired
    public RecommendationOutcomeDispatcher(RecommendationStore store, RecommendationRuntime runtime,
        @Value("${recommendations.outcome-queue-capacity:128}") int capacity,
        @Value("${recommendations.outcome-failure-threshold:3}") int threshold,
        @Value("${recommendations.outcome-cooldown-ms:30000}") long cooldownMs) {
        this(store,runtime,capacity,threshold,cooldownMs,System::nanoTime);
    }
    RecommendationOutcomeDispatcher(RecommendationStore store, RecommendationRuntime runtime, int capacity,
        int threshold, long cooldownMs, LongSupplier nanoTime) {
        if (capacity < 1 || capacity > 10000 || threshold < 1 || cooldownMs < 1 || cooldownMs > 3600000)
            throw new IllegalArgumentException("Invalid recommendation outcome queue/circuit limits");
        this.store=store; this.runtime=runtime; this.nanoTime=nanoTime;
        this.failureThreshold=threshold; this.cooldownNanos=TimeUnit.MILLISECONDS.toNanos(cooldownMs);
        this.capacity=capacity;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread thread=new Thread(runnable,"recommendation-outcome");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private boolean circuitOpen() { return openUntil != 0 && nanoTime.getAsLong() - openUntil < 0; }
    public void submit(long userId, String eventId, TokenClaims claims, Instant at, String sessionKey, long historyId) {
        if (circuitOpen()) { runtime.count("outcome.dropped_circuit"); return; }
        try {
            executor.execute(() -> write(userId,eventId,claims,at,sessionKey,historyId));
            runtime.count("outcome.enqueued");
        } catch (RejectedExecutionException fullOrStopped) { runtime.count("outcome.dropped_queue"); }
    }
    private void write(long userId, String eventId, TokenClaims claims, Instant at, String sessionKey, long historyId) {
        if (circuitOpen()) { runtime.count("outcome.dropped_circuit"); return; }
        try {
            boolean inserted=store.event(userId,eventId,"PLAY_START",claims,at,sessionKey,historyId,Map.of());
            runtime.count(inserted ? "outcome.stored" : "outcome.duplicate");
            consecutiveFailures=0;openUntil=0;
        } catch (RuntimeException unavailable) {
            runtime.count("outcome.failed");
            if (++consecutiveFailures >= failureThreshold) {
                openUntil=nanoTime.getAsLong()+cooldownNanos;
                runtime.count("outcome.circuit_opened");
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Recommendation outcome circuit opened: {}", unavailable.getClass().getSimpleName());
            }
        }
    }
    public Map<String,Object> status() {
        return Map.of("capacity",capacity,
            "queued",executor.getQueue().size(),"inFlight",executor.getActiveCount(),"circuitOpen",circuitOpen());
    }
    @PreDestroy public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(2,TimeUnit.SECONDS)) return;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (Runnable ignored:executor.shutdownNow()) runtime.count("outcome.dropped_shutdown");
    }
}
