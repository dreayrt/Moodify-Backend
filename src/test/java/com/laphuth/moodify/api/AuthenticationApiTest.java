package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.GoogleAuthRequest;
import com.laphuth.moodify.services.AuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.laphuth.moodify.dto.auth.UpdateProfileRequest;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationApiTest {
    private AuthenticationService authenticationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticationApi(authenticationService)).build();
    }

    @Test
    void facebookTokensCanNoLongerBeExchangedForASession() throws Exception {
        mvc.perform(post("/api/auth/facebook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"old-client-token\"}"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(authenticationService);
    }

    @Test
    void googleLoginStillPassesTheIdTokenAndDeviceToTheService() throws Exception {
        mvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"google-token\",\"deviceUuid\":\"device-1\",\"platform\":\"ANDROID\"}"))
            .andExpect(status().isOk());
        verify(authenticationService).loginWithGoogle(
            new GoogleAuthRequest("google-token", "device-1", null, "ANDROID")
        );
    }

    @Test
    void moderatorCanSubmitProfilePatch() throws Exception {
        mvc.perform(patch("/api/auth/me")
                .principal(new UsernamePasswordAuthenticationToken("moderator01", null,
                    List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nguyễn Văn Moderator\",\"phone\":\"0901234567\"}"))
            .andExpect(status().isOk());
        verify(authenticationService).updateProfile("moderator01",
            new UpdateProfileRequest("Nguyễn Văn Moderator", "0901234567"));
    }

    @Test
    void moderatorCanSubmitAuthenticatedPasswordChange() throws Exception {
        when(authenticationService.changePassword("moderator01", "current-password", "new-password"))
            .thenReturn(Map.of("success", true));
        mvc.perform(post("/api/auth/change-password")
                .principal(new UsernamePasswordAuthenticationToken("moderator01", null,
                    List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"current-password\",\"newPassword\":\"new-password\"}"))
            .andExpect(status().isOk());
        verify(authenticationService).changePassword("moderator01", "current-password", "new-password");
    }

    @Test
    void passwordChangeWithoutAuthenticationIsRejected() throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(authenticationService);
    }
}
