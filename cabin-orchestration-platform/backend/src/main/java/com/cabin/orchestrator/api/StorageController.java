package com.cabin.orchestrator.api;

import com.cabin.orchestrator.storage.StorageMonitorService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * W-11: current disk usage for the mounts StorageMonitorService watches (the HDD cold tier and the
 * NVMe hot tier). Read-only and open like the other GET /api/system routes: it reports capacity,
 * not who is home. The alerts themselves arrive as STORAGE_THRESHOLD events.
 */
@RestController
@RequestMapping("/api/system/storage")
@CrossOrigin
public class StorageController {

    private final StorageMonitorService monitor;

    public StorageController(StorageMonitorService monitor) {
        this.monitor = monitor;
    }

    @GetMapping
    public Map<String, Object> storage() {
        List<Map<String, Object>> mounts = monitor.snapshot();
        return Map.of("mounts", mounts);
    }
}
