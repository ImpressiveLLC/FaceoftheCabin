package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDisplayLabelSeederTest {

    /** Minimal in-memory stand-in -- only the two methods the seeder actually calls. */
    private static class FakeRepository implements DeviceReportingRelationshipRepository {
        final List<DeviceReportingRelationship> rows = new ArrayList<>();

        @Override public void upsert(DeviceReportingRelationship relationship) { rows.add(relationship); }

        @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) {
            return rows.stream().filter(r -> r.deviceId().equals(deviceId)).toList();
        }

        @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }

        @Override public void setDisplayLabel(String deviceId, String semanticField, String displayLabel) {
            for (int i = 0; i < rows.size(); i++) {
                DeviceReportingRelationship r = rows.get(i);
                if (r.deviceId().equals(deviceId) && r.semanticField().equals(semanticField)) {
                    rows.set(i, new DeviceReportingRelationship(
                        r.deviceId(), r.semanticField(), r.measurementType(), r.confirmationSource(), r.confirmedAt(), displayLabel));
                }
            }
        }
    }

    @Test
    void labelsARealConfirmedServiceEntityThatHasNoLabelYet() {
        FakeRepository repo = new FakeRepository();
        repo.upsert(new DeviceReportingRelationship("z2m-temp_kitchen", "humidity", "humidity",
            ConfirmationSource.VENDOR_SPEC, Instant.now()));

        new ServiceDisplayLabelSeeder(repo).seedIfMissing();

        assertEquals("Kitchen Humidity", repo.findByDevice("z2m-temp_kitchen").stream()
            .filter(r -> r.semanticField().equals("humidity")).findFirst().orElseThrow().displayLabel());
    }

    @Test
    void neverClobbersADisplayLabelAnAdminAlreadyCurated() {
        FakeRepository repo = new FakeRepository();
        repo.upsert(new DeviceReportingRelationship("z2m-temp_kitchen", "humidity", "humidity",
            ConfirmationSource.VENDOR_SPEC, Instant.now(), "Nate's Custom Label"));

        new ServiceDisplayLabelSeeder(repo).seedIfMissing();

        assertEquals("Nate's Custom Label", repo.findByDevice("z2m-temp_kitchen").stream()
            .filter(r -> r.semanticField().equals("humidity")).findFirst().orElseThrow().displayLabel(),
            "a human's own curation must survive every restart, matching CredentialPointerSeeder's own rule");
    }

    // D15 (Energy Device Ontology, 2026-09-05): the two real smart plugs'
    // "power" row is auto-upserted by Zigbee2MqttAdapter's vendor_spec path
    // before this seeder ever runs, same as z2m-temp_kitchen's humidity row
    // above -- confirms the fix for kpiTileFor's hardcoded "Energy" label
    // actually has a real curated label to find.
    @Test
    void labelsBothRealSmartPlugsPowerRows() {
        FakeRepository repo = new FakeRepository();
        repo.upsert(new DeviceReportingRelationship("z2m-heater_mech_room", "power", "power",
            ConfirmationSource.VENDOR_SPEC, Instant.now()));
        repo.upsert(new DeviceReportingRelationship("z2m-smart_switch_breaker_box", "power", "power",
            ConfirmationSource.VENDOR_SPEC, Instant.now()));

        new ServiceDisplayLabelSeeder(repo).seedIfMissing();

        assertEquals("Mech Room Heater Power", repo.findByDevice("z2m-heater_mech_room").stream()
            .filter(r -> r.semanticField().equals("power")).findFirst().orElseThrow().displayLabel());
        assertEquals("Breaker Box Switch Power", repo.findByDevice("z2m-smart_switch_breaker_box").stream()
            .filter(r -> r.semanticField().equals("power")).findFirst().orElseThrow().displayLabel());
    }

    @Test
    void isANoOpWhenTheUnderlyingRowDoesNotExistYet() {
        FakeRepository repo = new FakeRepository();
        // The Kidde CO row specifically won't exist until Bug #3's classification fix
        // has been deployed and a telemetry poll has confirmed it at least once.

        assertDoesNotThrow(() -> new ServiceDisplayLabelSeeder(repo).seedIfMissing());
        assertTrue(repo.rows.isEmpty(), "setDisplayLabel must never fabricate a row that hasn't been confirmed");
    }
}
