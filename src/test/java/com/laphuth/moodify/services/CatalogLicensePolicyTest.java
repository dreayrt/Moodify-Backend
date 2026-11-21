package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CatalogLicensePolicyTest {
    private final LocalDate today=LocalDate.of(2026,11,16);
    Track track() { Track t=new Track();t.setId("canonical");t.setSpotifyId("provider");t.setStatus("published");t.setVisibility("public");t.setModerationStatus("approved");t.setLocalPath("data/audio/a.mp3");return t; }
    SongLicense license(long id) { SongLicense l=new SongLicense();l.setId(id);l.setTrackId("canonical");l.setStatus(LicenseStatus.ACTIVE);l.setIssueDate(today.minusDays(1));l.setExpiryDate(today.plusDays(1));l.setLicenseType("DIGITAL_STREAMING");l.setDocumentSonglicensesUrl("/uploads/licenses/a.pdf");l.setDocumentSha256("a".repeat(64));l.setRightsEvidenceStatus("VERIFIED");l.setLicenseType("DIGITAL_STREAMING");l.setRightsVerifiedBy(1L);l.setRightsVerifiedAt(java.time.LocalDateTime.now());l.setRightsReviewNote("Fixture document review");return l; }
    @Test void missingUnverifiedAndPendingRightsNeverServe() {
        Track t=track();assertThat(CatalogLicensePolicy.canServe(t,null,today,true)).isFalse();
        SongLicense l=license(1);l.setRightsEvidenceStatus("UNVERIFIED");assertThat(CatalogLicensePolicy.canServe(t,l,today,true)).isFalse();
        l.setRightsEvidenceStatus("VERIFIED");l.setLicenseType("DIGITAL_STREAMING");l.setRightsVerifiedBy(1L);l.setRightsVerifiedAt(java.time.LocalDateTime.now());l.setRightsReviewNote("Fixture document review");l.setStatus(LicenseStatus.PENDING);assertThat(CatalogLicensePolicy.canServe(t,l,today,true)).isFalse();
    }
    @Test void simulationRequiresBothExplicitEvidenceAndMode() {
        var l=license(1);l.setLicenseType("DEMO_SIMULATED");l.setRightsEvidenceStatus("SIMULATED");
        assertThat(CatalogLicensePolicy.canServe(track(),l,today,false)).isFalse();
        assertThat(CatalogLicensePolicy.canServe(track(),l,today,true)).isTrue();
        l.setLicenseType("DIGITAL_STREAMING");assertThat(CatalogLicensePolicy.canServe(track(),l,today,true)).isFalse();
    }
    @Test void expiryDateIncludesWholeVietnameseCalendarDay() {
        var l=license(1);l.setExpiryDate(today);assertThat(CatalogLicensePolicy.canServe(track(),l,today,false)).isTrue();
        assertThat(CatalogLicensePolicy.canServe(track(),l,today.plusDays(1),false)).isFalse();
        l.setIssueDate(today.plusDays(1));assertThat(CatalogLicensePolicy.canServe(track(),l,today,false)).isFalse();
    }
    @Test void missingStartDateAndRejectedContentFailClosed() {
        var t=track();var l=license(1);l.setIssueDate(null);assertThat(CatalogLicensePolicy.canServe(t,l,today,false)).isFalse();
        l.setIssueDate(today);t.setModerationStatus(null);assertThat(CatalogLicensePolicy.canServe(t,l,today,false)).isFalse();
    }
    @Test void expiredOldAgreementDoesNotHideRenewedTrack() {
        var old=license(1);old.setStatus(LicenseStatus.EXPIRED);old.setExpiryDate(today.minusDays(5));var renewal=license(2);
        assertThat(CatalogLicensePolicy.select(track(),List.of(old,renewal),today)).isSameAs(renewal);
    }
    @Test void futureRenewalKeepsCurrentAgreementUntilBoundary() {
        var current=license(1);var future=license(2);future.setIssueDate(today.plusDays(1));future.setExpiryDate(today.plusYears(1));
        assertThat(CatalogLicensePolicy.select(track(),List.of(current,future),today)).isSameAs(current);
        assertThat(CatalogLicensePolicy.select(track(),List.of(current,future),today.plusDays(1))).isSameAs(future);
    }
    @Test void latestRevocationOverridesAnOlderActiveAgreement() {
        var active=license(1);var revoked=license(2);revoked.setStatus(LicenseStatus.REVOKED);
        assertThat(CatalogLicensePolicy.select(track(),List.of(active,revoked),today)).isSameAs(revoked);
        assertThat(CatalogLicensePolicy.canServe(track(),revoked,today,true)).isFalse();
    }
    @Test void canonicalAgreementOverridesConflictingProviderAlias() {
        var canonical=license(1);canonical.setStatus(LicenseStatus.REVOKED);var alias=license(99);alias.setTrackId("provider");
        assertThat(CatalogLicensePolicy.select(track(),List.of(canonical,alias),today)).isSameAs(canonical);
    }
}
