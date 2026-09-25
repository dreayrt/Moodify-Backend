package com.laphuth.moodify.dto.dashboard;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.entities.enums.UserRole;

import java.util.Locale;

public record DashboardAccessResponse(
    String dashboard,
    String role,
    String message,
    UserProfileResponse user
) {
    private static String resolveDashboardPath(UserRole role) {
        if (role == UserRole.CONTENT_LEAD) {
            return "/dashboard/content-lead";
        }
        return "/dashboard/" + role.name().toLowerCase(Locale.ROOT);
    }

    public static DashboardAccessResponse forRole(
        UserRole role,
        UserProfileResponse currentUser
    ) {
        return new DashboardAccessResponse(
            resolveDashboardPath(role),
            role.name(),
            "Authenticated successfully",
            currentUser
        );
    }

    public static DashboardAccessResponse forDashboard(
        UserRole role,
        UserProfileResponse currentUser
    ) {
        return new DashboardAccessResponse(
            resolveDashboardPath(role),
            role.name(),
            "Access granted to " + role.name() + " dashboard",
            currentUser
        );
    }
}
