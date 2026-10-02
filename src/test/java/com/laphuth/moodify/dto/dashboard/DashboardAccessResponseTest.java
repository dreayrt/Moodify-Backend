package com.laphuth.moodify.dto.dashboard;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAccessResponseTest {
    @Test
    void shouldResolveContentLeadDashboardFromRole() {
        DashboardAccessResponse response = DashboardAccessResponse.forRole(
            UserRole.CONTENT_LEAD,
            profile("contentlead01", "CONTENT_LEAD")
        );

        assertThat(response.dashboard()).isEqualTo("/dashboard/content-lead");
        assertThat(response.role()).isEqualTo("CONTENT_LEAD");
    }

    @Test
    void shouldBuildAdminDashboardAccessMessage() {
        DashboardAccessResponse response = DashboardAccessResponse.forDashboard(
            UserRole.ADMIN,
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
