package com.laphuth.moodify.api;

import com.laphuth.moodify.config.SecurityConfig;
import com.laphuth.moodify.dto.contentlead.ContentLeadCatalogResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadProfileResponse;
import com.laphuth.moodify.dto.contentlead.ContentLeadTracksPageResponse;
import com.laphuth.moodify.security.JwtAuthenticationFilter;
import com.laphuth.moodify.services.ContentLeadCatalogService;
import com.laphuth.moodify.services.TrackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ContentLeadCatalogApi.class)
@Import(SecurityConfig.class)
class ContentLeadCatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentLeadCatalogService contentLeadCatalogService;

    @MockitoBean
    private TrackService trackService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void getMyCatalogWithContentLeadRouteShouldReturnOk() throws Exception {
        ContentLeadProfileResponse profile = new ContentLeadProfileResponse(
            "mongo-id-1",
            "spotify-artist-1",
            "Sơn Tùng M-TP",
            "https://image.url/art.jpg",
            1000000,
            85,
            List.of("v-pop"),
            List.of("pop"),
            null,
            null
        );
        ContentLeadCatalogResponse catalogResponse = new ContentLeadCatalogResponse(
            profile,
            List.of(),
            List.of(),
            0,
            50,
            0L,
            0
        );

        when(contentLeadCatalogService.getCurrentCatalog(eq("contentlead01"), anyInt(), anyInt(), any()))
            .thenReturn(catalogResponse);

        mockMvc.perform(get("/api/content-lead/me/catalog").with(user("contentlead01").roles("CONTENT_LEAD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artist.name").value("Sơn Tùng M-TP"))
            .andExpect(jsonPath("$.artist.spotifyId").value("spotify-artist-1"));
    }

    @Test
    void getMyCatalogWithLegacyArtistsMeShouldReturnOkForContentLead() throws Exception {
        ContentLeadProfileResponse profile = new ContentLeadProfileResponse(
            "mongo-id-1",
            "spotify-artist-1",
            "Sơn Tùng M-TP",
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null
        );
        ContentLeadCatalogResponse catalogResponse = new ContentLeadCatalogResponse(
            profile,
            List.of(),
            List.of(),
            0,
            50,
            0L,
            0
        );

        when(contentLeadCatalogService.getCurrentCatalog(eq("contentlead01"), anyInt(), anyInt(), any()))
            .thenReturn(catalogResponse);

        mockMvc.perform(get("/api/artists/me/catalog").with(user("contentlead01").roles("CONTENT_LEAD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artist.name").value("Sơn Tùng M-TP"));
    }

    @Test
    void getMyTracksShouldReturnPagedTracks() throws Exception {
        ContentLeadTracksPageResponse tracksResponse = new ContentLeadTracksPageResponse(
            List.of(),
            0,
            20,
            0L,
            0
        );

        when(contentLeadCatalogService.getCurrentTracks(eq("contentlead01"), anyInt(), anyInt(), any()))
            .thenReturn(tracksResponse);

        mockMvc.perform(get("/api/content-lead/me/tracks?page=0&size=20").with(user("contentlead01").roles("CONTENT_LEAD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void getMyCatalogShouldRejectNormalUserWithForbidden() throws Exception {
        mockMvc.perform(get("/api/content-lead/me/catalog").with(user("listener01").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void getMyCatalogWithoutAuthShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/content-lead/me/catalog"))
            .andExpect(status().isUnauthorized());
    }
}
