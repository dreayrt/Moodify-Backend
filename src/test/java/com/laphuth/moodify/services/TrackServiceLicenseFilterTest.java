package com.laphuth.moodify.services;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class TrackServiceLicenseFilterTest {
    TrackRepository tracks=mock(TrackRepository.class);
    SongLicenseRepository licenses=mock(SongLicenseRepository.class);
    TrackService service=new TrackService(tracks,licenses);
    Track track(String id) { Track t=new Track();t.setId(id);t.setName(id);t.setStatus("published");t.setVisibility("public");t.setModerationStatus("approved");t.setLocalPath("data/audio/"+id+".mp3");return t; }
    SongLicense license(String id) { SongLicense l=new SongLicense();l.setId(1L);l.setTrackId(id);l.setStatus(LicenseStatus.ACTIVE);l.setIssueDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1));l.setExpiryDate(LocalDate.now().plusDays(1));l.setLicenseType("DIGITAL_STREAMING");l.setRightsEvidenceStatus("VERIFIED");l.setLicenseType("DIGITAL_STREAMING");l.setRightsVerifiedBy(1L);l.setRightsVerifiedAt(java.time.LocalDateTime.now());l.setRightsReviewNote("Fixture document review");l.setDocumentSha256("a".repeat(64));l.setDocumentSonglicensesUrl("/uploads/licenses/a.pdf");return l; }
    @Test void missingLicenseAndApprovalCannotBypassDirectDetail() {
        Track t=track("unlicensed");when(tracks.findById(t.getId())).thenReturn(Optional.of(t));
        assertThatThrownBy(()->service.getTrack(t.getId())).isInstanceOf(TrackService.TrackNotFoundException.class);
        t.setModerationStatus(null);assertThat(service.isTrackPubliclyAvailable(t)).isFalse();
    }
    @Test void datesAreCheckedWithoutWaitingForSchedulerAndRevocationHasNoStaleCache() {
        Track t=track("one");var l=license("one");when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(l));
        assertThat(service.isTrackPubliclyAvailable(t)).isTrue();l.setStatus(LicenseStatus.REVOKED);
        assertThat(service.isTrackPubliclyAvailable(t)).isFalse();l.setStatus(LicenseStatus.ACTIVE);l.setExpiryDate(LocalDate.now().minusDays(1));
        assertThat(service.isTrackPubliclyAvailable(t)).isFalse();
    }
    @Test void databaseFailureNeverReturnsAnAllowAllSet() {
        when(tracks.findAll()).thenReturn(List.of(track("one")));when(licenses.findByTrackIdIn(anyList())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(service::getIneligibleTrackIds).isInstanceOf(IllegalStateException.class);
    }
    @Test void paginationFiltersBeforeCountingAndSlicing() {
        Track hidden=track("a-hidden"),one=track("b-one"),two=track("c-two");
        when(tracks.findAll()).thenReturn(List.of(hidden,one,two));when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(license("b-one"),license("c-two")));
        var first=service.getTracks(0,1,null);var last=service.getTracks(1,1,null);
        assertThat(first.totalElements()).isEqualTo(2);assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content().getFirst().id()).isEqualTo("b-one");assertThat(last.content().getFirst().id()).isEqualTo("c-two");
    }
    @Test void coverImageResolutionRemainsCorrect() {
        new AudioUrlResolver("http://test-cdn.com","http://test-server:9000");Track t=track("one");t.setImageUrl("/uploads/covers/a.png");
        when(tracks.findById("one")).thenReturn(Optional.of(t));when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(license("one")));
        assertThat(service.getTrack("one").imageUrl()).isEqualTo("http://test-server:9000/uploads/covers/a.png");
    }
}
