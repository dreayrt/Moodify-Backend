package com.laphuth.moodify.services;

import static org.mockito.Mockito.*;
import com.laphuth.moodify.recommendation.*;
import com.laphuth.moodify.repositories.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RecommendationOutcomeTransactionTest {
    final RecommendationOutcomeDispatcher dispatcher=mock(RecommendationOutcomeDispatcher.class);
    final RevenuePlaybackService service=new RevenuePlaybackService(mock(JdbcTemplate.class),mock(TrackRepository.class),
        mock(SongLicenseRepository.class),dispatcher);
    @Test void rolledBackSessionNeverEnqueuesAnOutcome() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(service,"recordRecommendationAfterCommit",1L,"event",null,Instant.now(),"session",1L);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(dispatcher);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void commitOnlyEnqueuesAfterCommitAndDoesNotWriteMongoInline() {
        TransactionSynchronizationManager.initSynchronization();
        Instant now=Instant.now();
        try {
            ReflectionTestUtils.invokeMethod(service,"recordRecommendationAfterCommit",1L,"event",null,now,"session",1L);
            verifyNoInteractions(dispatcher);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            verify(dispatcher).submit(1L,"event",null,now,"session",1L);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
}
