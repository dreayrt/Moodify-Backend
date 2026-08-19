package com.laphuth.moodify.controller;

import com.laphuth.moodify.dto.auth.UserProfileResponse;
import com.laphuth.moodify.dto.dashboard.DashboardAccessResponse;
import com.laphuth.moodify.entities.enums.userRole;
import com.laphuth.moodify.services.authenticationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final authenticationService authenticationService;

    public DashboardController(authenticationService authenticationService) {
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
                userRole.valueOf(currentUser.role()),
                currentUser
            )
        );
    }

    @GetMapping("/user")
    public ResponseEntity<DashboardAccessResponse> getUserDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, userRole.USER));
    }

    @GetMapping("/artist")
    public ResponseEntity<DashboardAccessResponse> getArtistDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, userRole.ARTIST));
    }

    @GetMapping("/admin")
    public ResponseEntity<DashboardAccessResponse> getAdminDashboard(
        Authentication authentication
    ) {
        return ResponseEntity.ok(buildDashboardResponse(authentication, userRole.ADMIN));
    }

    private DashboardAccessResponse buildDashboardResponse(
        Authentication authentication,
        userRole role
    ) {
        UserProfileResponse currentUser = authenticationService.getCurrentUserProfile(
            authentication.getName()
        );
        return DashboardAccessResponse.forDashboard(role, currentUser);
    }
}
