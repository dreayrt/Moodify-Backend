package com.laphuth.moodify.dto.dashboard;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.enums.userRole;

import java.util.Locale;

public record DashboardAccessResponse(
    String dashboard,
    String role,
    String message,
    UserProfileResponse user
) {
    public static DashboardAccessResponse forRole(
        userRole role,
        UserProfileResponse currentUser
    ) {
        return new DashboardAccessResponse(
            "/dashboard/" + role.name().toLowerCase(Locale.ROOT),
            role.name(),
            "Authenticated successfully",
            currentUser
        );
    }

    public static DashboardAccessResponse forDashboard(
        userRole role,
        UserProfileResponse currentUser
    ) {
        return new DashboardAccessResponse(
            "/dashboard/" + role.name().toLowerCase(Locale.ROOT),
            role.name(),
            "Access granted to " + role.name() + " dashboard",
            currentUser
        );
    }
}
