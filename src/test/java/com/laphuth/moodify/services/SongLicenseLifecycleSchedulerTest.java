package com.laphuth.moodify.services;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import com.mongodb.client.result.UpdateResult;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SongLicenseLifecycleSchedulerTest {
    SongLicenseRepository licenses=mock(SongLicenseRepository.class);TrackRepository tracks=mock(TrackRepository.class);MongoTemplate mongo=mock(MongoTemplate.class);
    SongLicenseLifecycleScheduler scheduler=new SongLicenseLifecycleScheduler(licenses,tracks);
    Track track() { Track t=new Track();t.setId("one");t.setStatus("published");t.setVisibility("public");return t; }
    SongLicense license(long id,boolean expired) { SongLicense l=new SongLicense();l.setId(id);l.setTrackId("one");l.setStatus(LicenseStatus.ACTIVE);l.setIssueDate(LocalDate.now().minusDays(20));l.setExpiryDate(LocalDate.now().plusDays(expired?-1:5));l.setRightsEvidenceStatus("VERIFIED");l.setLicenseType("DIGITAL_STREAMING");l.setRightsVerifiedBy(1L);l.setRightsVerifiedAt(java.time.LocalDateTime.now());l.setRightsReviewNote("Fixture document review");l.setDocumentSha256("a".repeat(64));l.setDocumentSonglicensesUrl("a.pdf");return l; }
    @Test void allExpiredRowsAreConsideredAndMongoUsesPartialUpdates() {
        scheduler.setMongo(mongo);var old=license(1,true);old.setStatus(LicenseStatus.EXPIRED);var newly=license(2,true);
        when(licenses.findAllExpiredLicenses(any())).thenReturn(List.of(old,newly));when(tracks.findById("one")).thenReturn(Optional.of(track()));
        when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(old,newly));when(mongo.updateFirst(any(Query.class),any(Update.class),eq(Track.class))).thenReturn(UpdateResult.acknowledged(1,1L,null));
        assertThat(scheduler.processExpiredLicenses()).isEqualTo(1);verify(licenses).expireIfActive(eq(2L),any());verify(tracks,never()).save(any());
    }
    @Test void anExpiredOldAgreementDoesNotArchiveRenewal() {
        scheduler.setMongo(mongo);var old=license(1,true);old.setStatus(LicenseStatus.EXPIRED);var current=license(2,false);
        when(licenses.findAllExpiredLicenses(any())).thenReturn(List.of(old));when(tracks.findById("one")).thenReturn(Optional.of(track()));when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(old,current));
        assertThat(scheduler.processExpiredLicenses()).isZero();verifyNoInteractions(mongo);
    }
    @Test void failedMongoUpdateIsRetriedEvenAfterSqlExpiry() {
        scheduler.setMongo(mongo);var expired=license(1,true);expired.setStatus(LicenseStatus.EXPIRED);
        when(licenses.findAllExpiredLicenses(any())).thenReturn(List.of(expired));when(tracks.findById("one")).thenReturn(Optional.of(track()));when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(expired));
        when(mongo.updateFirst(any(Query.class),any(Update.class),eq(Track.class))).thenThrow(new IllegalStateException("temporary")).thenReturn(UpdateResult.acknowledged(1,1L,null));
        assertThat(scheduler.processExpiredLicenses()).isZero();assertThat(scheduler.processExpiredLicenses()).isEqualTo(1);
    }
}
