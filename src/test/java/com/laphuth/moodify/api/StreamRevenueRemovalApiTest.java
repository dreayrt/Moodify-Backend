package com.laphuth.moodify.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Standalone controller tests: do not start the application or its auto-DDL/database connections.
class StreamRevenueRemovalApiTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ContentLeadRevenueApi(), new AdminRevenueApi()).build();
    }

    @Test
    void oldContentLeadReportCannotReturnRevenueForAnyPeriod() throws Exception {
        for (String period : new String[]{"today", "week", "month", "year", "invalid"}) {
            mvc.perform(get("/api/content-lead/me/analytics/revenue").param("period", period))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("STREAM_REVENUE_REMOVED"))
                .andExpect(jsonPath("$.grossVnd").doesNotExist());
        }
    }

    @Test
    void oldAdminClientCannotReadPendingStreams() throws Exception {
        mvc.perform(get("/api/admin/revenue/pending"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error").value("STREAM_REVENUE_REMOVED"));
    }

    @Test
    void oldAdminClientCannotApproveOrRejectHistoricalStreams() throws Exception {
        for (String decision : new String[]{"APPROVED", "REJECTED"}) {
            mvc.perform(post("/api/admin/revenue/7/review")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"" + decision + "\",\"reason\":\"old client\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("STREAM_REVENUE_REMOVED"));
        }
    }
}
