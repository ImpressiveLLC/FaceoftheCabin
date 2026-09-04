package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.platforminfo.PlatformInfoService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
@CrossOrigin
public class SystemController {

    private final DeviceHealthMonitor healthMonitor;
    private final PlatformInfoService platformInfoService;

    public SystemController(DeviceHealthMonitor healthMonitor, PlatformInfoService platformInfoService) {
        this.healthMonitor = healthMonitor;
        this.platformInfoService = platformInfoService;
    }

    /** Overall platform health: device counts, Z2M bridge state, stale device list. */
    @GetMapping("/health")
    public Map<String, Object> systemHealth() {
        return healthMonitor.getSystemHealth();
    }

    /**
     * Bug #5 -- integration versions, static hardware catalog, and the
     * AI-inference disclosure. ADMINISTRATOR only: role gate follows the
     * exact per-route pattern CrossDomainController established for WSJF #6
     * (commit a2e3ef1) -- read HouseholdRole from the request attribute
     * GoogleAuthInterceptor already set, never re-derive it.
     */
    @GetMapping("/platform-info")
    public ResponseEntity<?> platformInfo(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This role cannot view platform info"));
        }
        return ResponseEntity.ok(platformInfoService.get());
    }
}
