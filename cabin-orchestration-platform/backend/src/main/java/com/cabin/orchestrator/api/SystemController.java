package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
@CrossOrigin
public class SystemController {

    private final DeviceHealthMonitor healthMonitor;

    public SystemController(DeviceHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    /** Overall platform health: device counts, Z2M bridge state, stale device list. */
    @GetMapping("/health")
    public Map<String, Object> systemHealth() {
        return healthMonitor.getSystemHealth();
    }
}
