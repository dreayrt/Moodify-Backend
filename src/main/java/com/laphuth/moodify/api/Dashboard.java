package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.dto.dashboard.DashboardAccessResponse;
import com.laphuth.moodify.entities.enums.UserRole;
import com.laphuth.moodify.services.AuthenticationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class Dashboard {
    private final AuthenticationService authenticationService;

    public Dashboard(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/me")
    public ResponseEntity<DashboardAccessResponse> getAssignedDashboard(
        Authentication authentication
    ) {
        UserProfileResponse currentUser = authenticationService.getCurrentUserProfile(
            authentication.getName()
        );

        return ResponseEntity.ok(
            DashboardAccessResponse.forRole(
                UserRole.valueOf(currentUser.role()),
                currentUser
            )
        );
    }

    @GetMapping("/user")
    public ResponseEntity<DashboardAccessResponse> getUserDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, UserRole.USER));
    }

    @GetMapping({"/content-lead", "/artist"})
    public ResponseEntity<DashboardAccessResponse> getContentLeadDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, UserRole.CONTENT_LEAD));
    }

    @GetMapping("/moderator")
    public ResponseEntity<DashboardAccessResponse> getModeratorDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, UserRole.MODERATOR));
    }

    @GetMapping("/admin")
    public ResponseEntity<DashboardAccessResponse> getAdminDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, UserRole.ADMIN));
    }

    private DashboardAccessResponse buildDashboardResponse(
        Authentication authentication,
        UserRole role
    ) {
        UserProfileResponse currentUser = authenticationService.getCurrentUserProfile(
            authentication.getName()
        );
        return DashboardAccessResponse.forDashboard(role, currentUser);
    }
}
