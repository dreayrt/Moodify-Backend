package com.laphuth.moodify.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import java.time.LocalDate;
import java.util.List;
import com.laphuth.moodify.repositories.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;
import org.junit.jupiter.api.Test;

class RecommendationCatalogTest {
    final LocalDate today = LocalDate.of(2026,10,27);
    Track track() {
        Track track = RecommendationEngineTest.track("id","artist","pop",.3);
        track.setStatus("published"); track.setVisibility("public"); track.setModerationStatus("approved");
        track.setLocalPath("data/audio/song.mp3"); return track;
    }
    SongLicense license() { SongLicense license = new SongLicense(); license.setStatus(LicenseStatus.ACTIVE); return license; }
    @Test void strictPolicyRequiresAnActiveLicense() {
        assertThat(RecommendationCatalog.eligible(track(),null,today,true)).isFalse();
        assertThat(RecommendationCatalog.eligible(track(),license(),today,true)).isTrue();
    }
    @Test void futureExpiredAndRevokedLicensesAreRejected() {
        SongLicense license = license(); license.setIssueDate(today.plusDays(1));
        assertThat(RecommendationCatalog.eligible(track(),license,today,true)).isFalse();
        license.setIssueDate(null); license.setExpiryDate(today.minusDays(1));
        assertThat(RecommendationCatalog.eligible(track(),license,today,true)).isFalse();
        license.setExpiryDate(null); license.setStatus(LicenseStatus.REVOKED);
        assertThat(RecommendationCatalog.eligible(track(),license,today,false)).isFalse();
    }
    @Test void pendingPrivateAndUnplayableContentNeverQualifies() {
        Track track = track(); track.setModerationStatus("pending");
        assertThat(RecommendationCatalog.eligible(track,license(),today,true)).isFalse();
        track.setModerationStatus("approved"); track.setVisibility("private");
        assertThat(RecommendationCatalog.eligible(track,license(),today,true)).isFalse();
        track.setVisibility("public"); track.setLocalPath(null);
        assertThat(RecommendationCatalog.eligible(track,license(),today,true)).isFalse();
    }

    @Test void ambiguousExternalAliasesAreNeverGuessed() {
        Track one = track(), two = track(); two.setId("other");
        var aliases = RecommendationCatalog.aliases(List.of(one,two));
        assertThat(aliases).containsEntry("id","id").containsEntry("other","other");
        assertThat(aliases).doesNotContainKey(one.getSpotifyId());
    }

    @Test void canonicalRevocationTakesPrecedenceOverLegacyAliasLicense() {
        Track track = track();
        TrackRepository tracks = mock(TrackRepository.class);
        SongLicenseRepository licenses = mock(SongLicenseRepository.class);
        when(tracks.findAll()).thenReturn(List.of(track));
        SongLicense alias = license(); alias.setTrackId(track.getSpotifyId()); alias.setId(1L);
        when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(alias));
        RecommendationCatalog catalog = new RecommendationCatalog(tracks,licenses,mock(PlaylistRepository.class),mock(UserRepository.class),true);
        assertThat(catalog.snapshot().tracks()).containsKey(track.getId());
        SongLicense revoked = license(); revoked.setTrackId(track.getId()); revoked.setStatus(LicenseStatus.REVOKED); revoked.setId(2L);
        when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(alias,revoked));
        assertThat(catalog.snapshot().tracks()).doesNotContainKey(track.getId());
    }

    @Test void targetedPlaybackChecksLicenseWithoutScanningCatalog() {
        Track track=track();TrackRepository tracks=mock(TrackRepository.class);SongLicenseRepository licenses=mock(SongLicenseRepository.class);
        when(tracks.findById("id")).thenReturn(java.util.Optional.of(track));
        when(tracks.findBySpotifyIdIn(anyList())).thenReturn(List.of(track));
        SongLicense license=license();license.setTrackId("id");
        when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(license));
        var catalog=new RecommendationCatalog(tracks,licenses,mock(PlaylistRepository.class),mock(UserRepository.class),true);
        assertThat(catalog.playableTrack("id")).isSameAs(track);
        verify(tracks,never()).findAll();
        license.setStatus(LicenseStatus.REVOKED);
        assertThatThrownBy(()->catalog.playableTrack("id")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void targetedLookupNeverBorrowsLicenseOfAnAliasThatIsAnotherTracksInternalId() {
        Track track=track(),other=track();other.setId(track.getSpotifyId());other.setSpotifyId("different-external");
        TrackRepository tracks=mock(TrackRepository.class);SongLicenseRepository licenses=mock(SongLicenseRepository.class);
        when(tracks.findById("id")).thenReturn(java.util.Optional.of(track));
        when(tracks.findById(other.getId())).thenReturn(java.util.Optional.of(other));
        when(tracks.findBySpotifyIdIn(anyList())).thenReturn(List.of(track));
        SongLicense otherLicense=license();otherLicense.setTrackId(other.getId());
        when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(otherLicense));
        var catalog=new RecommendationCatalog(tracks,licenses,mock(PlaylistRepository.class),mock(UserRepository.class),true);
        assertThatThrownBy(()->catalog.playableTrack("id")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(tracks,never()).findAll();
    }
}
