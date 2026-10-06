package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SongLicenseLifecycleSchedulerTest {

    @Mock
    private SongLicenseRepository songLicenseRepository;

    @Mock
    private TrackRepository trackRepository;

    @InjectMocks
    private SongLicenseLifecycleScheduler scheduler;

    @Test
    void processExpiredLicensesShouldDisableExpiredTracks() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        SongLicense expiredLicense = new SongLicense();
        expiredLicense.setId(101L);
        expiredLicense.setTrackId("mongo-track-expired");
        expiredLicense.setStatus(LicenseStatus.ACTIVE);
        expiredLicense.setExpiryDate(yesterday);

        Track activeTrack = new Track();
        activeTrack.setStatus("published");
        activeTrack.setVisibility("public");

        when(songLicenseRepository.findExpiredLicenses())
            .thenReturn(List.of(expiredLicense));
        when(trackRepository.findById("mongo-track-expired"))
            .thenReturn(Optional.of(activeTrack));

        int processed = scheduler.processExpiredLicenses();

        assertThat(processed).isEqualTo(1);
        assertThat(expiredLicense.getStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(activeTrack.getStatus()).isEqualTo("archived");
        assertThat(activeTrack.getVisibility()).isEqualTo("private");

        verify(songLicenseRepository).save(expiredLicense);
        verify(trackRepository).save(activeTrack);
    }

    @Test
    void processExpiredLicensesShouldDoNothingWhenNoExpiredLicenses() {
        when(songLicenseRepository.findExpiredLicenses())
            .thenReturn(List.of());
        when(songLicenseRepository.findAllExpiredLicenses(any(LocalDate.class)))
            .thenReturn(List.of());

        int processed = scheduler.processExpiredLicenses();

        assertThat(processed).isEqualTo(0);
        verify(songLicenseRepository, never()).save(any());
        verify(trackRepository, never()).save(any());
    }
}
