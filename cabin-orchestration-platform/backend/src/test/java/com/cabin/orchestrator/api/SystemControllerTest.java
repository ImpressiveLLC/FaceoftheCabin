package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.platforminfo.PlatformInfoService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #5 success criterion: /api/system/platform-info is ADMINISTRATOR only,
 * same per-route policy pattern as PlatformImportControllerTest/
 * CrossDomainControllerTest -- one denial test per non-admin role class,
 * one confirmation the admin path actually returns data.
 */
class SystemControllerTest {

    private SystemController newController() {
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of());
        Zigbee2MqttAdapter z2m = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        DeviceHealthMonitor monitor = new DeviceHealthMonitor(registry, z2m);
        HomeAssistantAdapter ha = new HomeAssistantAdapter();
        ReflectionTestUtils.setField(ha, "cabinHaToken", "");
        ReflectionTestUtils.setField(ha, "homeHaToken", "");
        ObjectProvider<BuildProperties> emptyBuildProperties = new ObjectProvider<>() {
            @Override public BuildProperties getObject() { throw new NoSuchBeanDefinitionException(BuildProperties.class); }
            @Override public BuildProperties getObject(Object... args) { throw new NoSuchBeanDefinitionException(BuildProperties.class); }
            @Override public BuildProperties getIfAvailable() { return null; }
            @Override public BuildProperties getIfUnique() { return null; }
        };
        OllamaClient ollama = new OllamaClient() {
            @Override public Optional<String> generate(String prompt) { return Optional.empty(); }
        };
        PlatformInfoService platformInfoService = new PlatformInfoService(ha, z2m, ollama, emptyBuildProperties);
        return new SystemController(monitor, platformInfoService);
    }

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/platform-info");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void anAdultHouseholdMemberIsDeniedPlatformInfo() {
        ResponseEntity<?> result = newController().platformInfo(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void aChildRoleIsDeniedPlatformInfo() {
        ResponseEntity<?> result = newController().platformInfo(requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void noRoleAttributeAtAllIsDeniedPlatformInfo() {
        ResponseEntity<?> result = newController().platformInfo(requestWithRole(null));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void anAdministratorReceivesPlatformInfo() {
        ResponseEntity<?> result = newController().platformInfo(requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
    }
}
