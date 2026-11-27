package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.monitoring.*;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MonitoringPreferenceControllerTest {
    @Test void readOnlyTokenCannotWritePreferences() throws Exception {
        var service = mock(MonitoringPreferenceService.class);
        var tokens = mock(CabinAccessTokenService.class);
        when(tokens.validate("fixture")).thenReturn(Optional.of(new CabinAccessToken("id", "fixture", "test",
            List.of("device_states"), null, null, "owner", Instant.EPOCH)));
        var mvc = MockMvcBuilders.standaloneSetup(new MonitoringPreferenceController(service))
            .addInterceptors(new GoogleAuthInterceptor(tokens, null, null)).build();
        mvc.perform(patch("/api/devices/monitoring-preferences?location=cabin")
            .header("Authorization", "CabinToken fixture").contentType("application/json")
            .content("{\"expectedRevision\":0,\"cards\":[]}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void anonymousWriteIsDeniedBeforeServiceAndPublicReadHasOnlyPreferences() throws Exception {
        var service = mock(MonitoringPreferenceService.class);
        when(service.read("cabin")).thenReturn(new MonitoringPreferences("cabin", 0, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new MonitoringPreferenceController(service))
            .addInterceptors(new GoogleAuthInterceptor(null, null, null)).build();
        mvc.perform(patch("/api/devices/monitoring-preferences?location=cabin")
            .contentType("application/json").content("{\"expectedRevision\":0,\"cards\":[]}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(get("/api/devices/monitoring-preferences?location=cabin"))
            .andExpect(status().isOk()).andExpect(content().json("{\"location\":\"cabin\",\"revision\":0,\"cards\":[]}", true));
        verify(service).read("cabin");
        verifyNoMoreInteractions(service);
    }

    @Test void actorComesFromVerifiedRequestAndMissingRevisionIsRejected() throws Exception {
        var service = mock(MonitoringPreferenceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MonitoringPreferenceController(service)).build();
        mvc.perform(patch("/api/devices/monitoring-preferences?location=home")
            .requestAttr(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "member@example.test")
            .contentType("application/json").content("{\"cards\":[]}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(patch("/api/devices/monitoring-preferences?location=home")
            .requestAttr(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "member@example.test")
            .contentType("application/json").content("{\"expectedRevision\":2,\"cards\":[]}"))
            .andExpect(status().isOk());
        verify(service).replace("home", 2, List.of(), "member@example.test");
    }

    @Test void omittedInclusionIsNotSilentlyFalse() {
        assertThrows(Exception.class, () -> new ObjectMapper().readValue(
            "{\"deviceId\":\"a\"}", MonitoringPreferences.Card.class));
    }

    @Test void clientCannotSupplyAuditActorOrLockedMetadata() throws Exception {
        var service = mock(MonitoringPreferenceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MonitoringPreferenceController(service)).build();
        for (String body : List.of(
            "{\"expectedRevision\":0,\"cards\":[],\"actor\":\"forged\"}",
            "{\"expectedRevision\":0,\"cards\":[{\"deviceId\":\"a\",\"included\":true,\"enabled\":true}]}")) {
            mvc.perform(patch("/api/devices/monitoring-preferences?location=cabin")
                .requestAttr(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "member@example.test")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
