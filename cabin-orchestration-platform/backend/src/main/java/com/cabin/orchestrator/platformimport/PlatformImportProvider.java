package com.cabin.orchestrator.platformimport;

import java.util.List;

/**
 * One external-platform import source (SmartThings, Ring, ...). D10's own
 * name for this concept ("parallel to LocalCatalogProvider" -- see
 * cabin-discovery's Zigbee-catalog-enrichment path, a different pipeline
 * for a different problem: identifying an already-paired local device, not
 * importing a device from an external cloud platform). WSJF #9 is the
 * first time this interface actually gets implemented.
 */
public interface PlatformImportProvider {

    /** Stable platform key, e.g. "smartthings" | "ring" -- matches RawImportRecord.platform() and the /api/platform-import/{platform}/... path segment. */
    String platform();

    /** Fetches this platform's current device list and maps it to RawImportRecords. Never writes anything -- see PlatformImportController for the dedup/persist step. */
    List<RawImportRecord> listDevices();
}
