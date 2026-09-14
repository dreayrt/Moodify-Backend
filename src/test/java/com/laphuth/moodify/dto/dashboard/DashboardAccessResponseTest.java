package com.laphuth.moodify.dto.dashboard;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.enums.userRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAccessResponseTest {
    @Test
    void shouldResolveArtistDashboardFromRole() {
        DashboardAccessResponse response = DashboardAccessResponse.forRole(
            userRole.ARTIST,
            profile("artist", "ARTIST")
        );

        assertThat(response.dashboard()).isEqualTo("/dashboard/artist");
        assertThat(response.role()).isEqualTo("ARTIST");
    }

    @Test
    void shouldBuildAdminDashboardAccessMessage() {
        DashboardAccessResponse response = DashboardAccessResponse.forDashboard(
            userRole.ADMIN,
            profile("admin", "ADMIN")
        );

        assertThat(response.dashboard()).isEqualTo("/dashboard/admin");
        assertThat(response.message()).isEqualTo("Access granted to ADMIN dashboard");
    }

    private UserProfileResponse profile(String username, String role) {
        return new UserProfileResponse(
            1L,
            "Moodify " + role,
            "0900000000",
            username + "@example.com",
            username,
            null,
            role,
            null,
            "ACTIVE",
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }
}
