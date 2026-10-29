package com.laphuth.moodify.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RecommendationOutcomeDispatcherTest {
    final RecommendationStore store=mock(RecommendationStore.class);
    final RecommendationRuntime runtime=new RecommendationRuntime(true,true,true);
    long count(String key) { return ((Map<?,?>)runtime.snapshot().get("counters")).get(key) instanceof Number n?n.longValue():0; }
    void submit(RecommendationOutcomeDispatcher dispatcher) { dispatcher.submit(1,"event",null,Instant.now(),"session",1); }
    static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @Test void slowWritesNeverRunOnCallerAndSaturationDropsInsteadOfBlocking() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(store.event(anyLong(),anyString(),anyString(),isNull(),any(),anyString(),anyLong(),anyMap()))
            .thenAnswer(call->{entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return true;});
        try(var dispatcher=new RecommendationOutcomeDispatcher(store,runtime,1,3,1000)) {
            submit(dispatcher);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            submit(dispatcher);submit(dispatcher);
            assertThat(count("outcome.dropped_queue")).isEqualTo(1);
            verify(store,times(1)).event(anyLong(),anyString(),anyString(),isNull(),any(),anyString(),anyLong(),anyMap());
            release.countDown();await(()->count("outcome.stored")==2);
        } finally {release.countDown();}
    }
    @Test void circuitDropsDuringCooldownAndProbesAgainAfterCooldown() throws Exception {
        AtomicLong clock=new AtomicLong(1);
        when(store.event(anyLong(),anyString(),anyString(),isNull(),any(),anyString(),anyLong(),anyMap()))
            .thenThrow(new IllegalStateException("fixture unavailable"));
        try(var dispatcher=new RecommendationOutcomeDispatcher(store,runtime,2,2,1000,clock::get)) {
            submit(dispatcher);await(()->count("outcome.failed")==1);
            submit(dispatcher);await(()->Boolean.TRUE.equals(dispatcher.status().get("circuitOpen")));
            submit(dispatcher);assertThat(count("outcome.dropped_circuit")).isEqualTo(1);
            verify(store,times(2)).event(anyLong(),anyString(),anyString(),isNull(),any(),anyString(),anyLong(),anyMap());
            doReturn(true).when(store).event(anyLong(),anyString(),anyString(),isNull(),any(),anyString(),anyLong(),anyMap());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));submit(dispatcher);
            await(()->count("outcome.stored")==1);
            assertThat(dispatcher.status().get("circuitOpen")).isEqualTo(false);
        }
    }
    @Test void duplicateDoesNotCountAsFailureAndShutdownRejectsSafely() throws Exception {
        var dispatcher=new RecommendationOutcomeDispatcher(store,runtime,1,1,1000);
        try {submit(dispatcher);await(()->count("outcome.duplicate")==1);}
        finally {dispatcher.close();}
        submit(dispatcher);
        assertThat(count("outcome.failed")).isZero();
        assertThat(count("outcome.dropped_queue")).isEqualTo(1);
    }
}
