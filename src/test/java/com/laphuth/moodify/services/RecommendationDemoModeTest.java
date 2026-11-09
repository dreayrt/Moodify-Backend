package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RecommendationDemoModeTest {
    @Test void simulationOnlyServesInExplicitDemoModeAndStillRequiresPublicationAndEffectiveLicense() {
        var tracks=mock(TrackRepository.class);var licenses=mock(SongLicenseRepository.class);
        Track track=new Track();track.setId("demo");track.setStatus("published");track.setVisibility("public");
        track.setModerationStatus("approved");track.setLocalPath("data/audio/demo.mp3");track.setRecommendationDemo(Map.of("simulated",true));
        SongLicense license=new SongLicense();license.setId(7L);license.setTrackId("demo");license.setStatus(LicenseStatus.ACTIVE);license.setLicenseType("DEMO_SIMULATED");
        when(tracks.findAll()).thenReturn(List.of(track));when(licenses.findByTrackIdIn(anyList())).thenReturn(List.of(license));
        var normal=new RecommendationCatalog(tracks,licenses,mock(PlaylistRepository.class),mock(UserRepository.class),true,false);
        var demo=new RecommendationCatalog(tracks,licenses,mock(PlaylistRepository.class),mock(UserRepository.class),true,true);
        assertThat(normal.snapshot().tracks()).isEmpty();assertThat(demo.snapshot().tracks()).containsKey("demo");
        assertThat(demo.readiness().standardEligibleTracks()).isZero();assertThat(demo.readiness().demoCatalog()).isTrue();
        track.setVisibility("private");assertThat(demo.snapshot().tracks()).isEmpty();track.setVisibility("public");
        license.setExpiryDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1));assertThat(demo.snapshot().tracks()).isEmpty();
    }
    @Test void homeResponseLabelsSimulationExplicitly() throws Exception {
        var home=new com.laphuth.moodify.dto.recommendation.RecommendationTypes.Home("request",Instant.now(),Instant.now(),"baseline","strict-rights-v1.3-demo",true,List.of());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readTree(mapper.writeValueAsString(home)).get("demoCatalog").asBoolean()).isTrue();
    }
}
