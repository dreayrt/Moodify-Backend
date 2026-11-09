package com.laphuth.moodify.api;

import com.laphuth.moodify.dto.auth.GoogleAuthRequest;
import com.laphuth.moodify.services.AuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
}
