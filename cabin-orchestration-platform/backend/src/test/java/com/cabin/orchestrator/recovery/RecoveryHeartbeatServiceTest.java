package com.cabin.orchestrator.recovery;

import com.cabin.orchestrator.alerts.ActiveAlert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecoveryHeartbeatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @TempDir Path dir;

    private RecoveryHeartbeatService service(boolean enabled) {
        return new RecoveryHeartbeatService(enabled, dir.toString(), 300);
    }

    private void write(String json) throws IOException {
        Files.writeString(dir.resolve("state.json"), json);
    }

    private static String state(String status, String writtenAt, String components) {
        return "{\"schemaVersion\":1,\"writtenAt\":\"" + writtenAt + "\",\"host\":\"m920q\",\"intervalSeconds\":60,\"missLimit\":5,"
            + "\"status\":\"" + status + "\",\"bootAt\":\"2026-11-02T23:54:00Z\","
            + "\"notify\":{\"ntfy\":\"configured\",\"lastResult\":\"ok\",\"lastOkAt\":null},\"deadman\":{\"configured\":true,\"lastResult\":\"ok\"},"
            + "\"boot\":{\"checkedAt\":\"2026-11-02T23:58:00Z\",\"severity\":\"info\",\"uncleanShutdown\":\"true\",\"recoveredMinutes\":4,"
            + "\"telemetryGapSeconds\":720,\"summary\":\"Cabin restarted\",\"problems\":[]},\"components\":[" + components + "]}";
    }

    private static String comp(String name, String status, String lastOk) {
        return "{\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"lastOkAt\":\"" + lastOk + "\",\"ageSeconds\":10,\"misses\":"
            + ("down".equals(status) ? 6 : 0) + ",\"downSince\":" + ("down".equals(status) ? "\"2026-11-03T11:50:00Z\"" : "null")
            + ",\"detail\":\"container " + name + " not running\"}";
    }

    @Test
    void disabledInstanceRaisesNothingEvenWithoutAFile() {
        assertTrue(service(false).activeAlerts(NOW).isEmpty());
        assertEquals("disabled", service(false).view(NOW).get("state"));
    }

    @Test
    void enabledButNoFileIsAWarnNotACritical() {
        List<ActiveAlert> alerts = service(true).activeAlerts(NOW);
        assertEquals(1, alerts.size());
        assertEquals("WARN", alerts.get(0).severity());
        assertEquals("recovery:not-installed", alerts.get(0).alertId());
        Map<String, Object> view = service(true).view(NOW);
        assertEquals("not-installed", view.get("state"));
        assertTrue(String.valueOf(view.get("installCommands")).contains("systemctl enable --now cabin-recovery-heartbeat"));
    }

    @Test
    void freshHealthyReportRaisesNothing() throws IOException {
        write(state("running", "2026-11-03T11:59:30Z", comp("backend", "ok", "2026-11-03T11:59:30Z")));
        assertTrue(service(true).activeAlerts(NOW).isEmpty());
        Map<String, Object> view = service(true).view(NOW);
        assertEquals("ok", view.get("state"));
        assertEquals(30L, view.get("ageSeconds"));
    }

    @Test
    void aDownComponentIsACriticalNamingIt() throws IOException {
        write(state("running", "2026-11-03T11:59:30Z",
            comp("backend", "ok", "2026-11-03T11:59:30Z") + "," + comp("kafka", "down", "2026-11-03T11:49:00Z")));
        List<ActiveAlert> alerts = service(true).activeAlerts(NOW);
        assertEquals(1, alerts.size());
        ActiveAlert a = alerts.get(0);
        assertEquals("recovery:component:kafka", a.alertId());
        assertEquals("CRITICAL", a.severity());
        assertEquals("kafka is down", a.title());
        assertEquals(Instant.parse("2026-11-03T11:50:00Z"), a.evidenceAt());
        assertEquals("cabin", a.location());
        assertEquals("degraded", service(true).view(NOW).get("state"));
    }

    @Test
    void heartbeatAgeIsRecomputedAtReadTimeNotTrustedFromTheFile() throws IOException {
        write(state("running", "2026-11-03T11:59:30Z", comp("backend", "ok", "2026-11-03T11:55:00Z")));
        List<?> comps = (List<?>) service(true).view(NOW).get("components");
        assertEquals(300L, ((Map<?, ?>) comps.get(0)).get("ageSeconds"));
    }

    @Test
    void aStaleFileIsCriticalRecoveryServiceNotReportingAndComponentAlertsAreNotTrusted() throws IOException {
        write(state("running", "2026-11-03T11:50:00Z", comp("kafka", "down", "2026-11-03T11:40:00Z")));
        List<ActiveAlert> alerts = service(true).activeAlerts(NOW);
        assertEquals(1, alerts.size());
        assertEquals("recovery:service-silent", alerts.get(0).alertId());
        assertEquals("CRITICAL", alerts.get(0).severity());
        assertEquals("Recovery service not reporting", alerts.get(0).title());
        Map<String, Object> view = service(true).view(NOW);
        assertEquals("silent", view.get("state"));
        assertTrue(Boolean.TRUE.equals(view.get("stale")));
    }

    @Test
    void justInsideTheStaleLimitIsStillFresh() throws IOException {
        write(state("running", "2026-11-03T11:55:00Z", comp("backend", "ok", "2026-11-03T11:55:00Z")));
        assertTrue(service(true).activeAlerts(NOW).isEmpty());
    }

    @Test
    void aDeliberateStopIsCriticalAndShownEvenWhenTheFileIsOld() throws IOException {
        write(state("stopped", "2026-11-03T10:00:00Z", comp("backend", "ok", "2026-11-03T10:00:00Z")));
        List<ActiveAlert> alerts = service(true).activeAlerts(NOW);
        assertEquals(1, alerts.size());
        assertEquals("recovery:stopped", alerts.get(0).alertId());
        assertEquals("CRITICAL", alerts.get(0).severity());
        assertEquals("stopped", service(true).view(NOW).get("state"));
    }

    @Test
    void garbageEmptyAndTimestamplessFilesAreTreatedAsNotReporting() throws IOException {
        for (String bad : new String[] {"not json", "", "{}", "[1,2]", "{\"writtenAt\":\"yesterday\"}"}) {
            write(bad);
            List<ActiveAlert> alerts = service(true).activeAlerts(NOW);
            assertEquals(1, alerts.size(), bad);
            assertEquals("recovery:service-silent", alerts.get(0).alertId(), bad);
        }
    }

    @Test
    void anOversizedFileIsRefusedUnread() throws IOException {
        write("{\"writtenAt\":\"2026-11-03T11:59:30Z\",\"pad\":\"" + "x".repeat((int) RecoveryHeartbeatService.MAX_BYTES) + "\"}");
        assertEquals("recovery:service-silent", service(true).activeAlerts(NOW).get(0).alertId());
    }

    @Test
    void aSymlinkedStateFileIsRefused() throws IOException {
        Path real = dir.resolve("elsewhere.json");
        Files.writeString(real, state("running", "2026-11-03T11:59:30Z", comp("backend", "ok", "2026-11-03T11:59:30Z")));
        try {
            Files.createSymbolicLink(dir.resolve("state.json"), real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks not available here");
        }
        assertEquals("recovery:service-silent", service(true).activeAlerts(NOW).get(0).alertId());
    }

    @Test
    void aSymlinkedStateFolderIsRefused() throws IOException {
        Path real = Files.createDirectory(dir.resolve("real"));
        Files.writeString(real.resolve("state.json"), state("running", "2026-11-03T11:59:30Z", comp("backend", "ok", "2026-11-03T11:59:30Z")));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks not available here");
        }
        assertEquals("recovery:service-silent", new RecoveryHeartbeatService(true, link.toString(), 300).activeAlerts(NOW).get(0).alertId());
    }

    @Test
    void untrustedFieldsAreWhitelistedAndBounded() throws IOException {
        String evil = "{\"name\":\"../etc/passwd\",\"status\":\"down\"},{\"name\":\"BAD NAME\",\"status\":\"down\"},"
            + "{\"name\":\"ok-one\",\"status\":\"down\",\"detail\":\"" + "d".repeat(2000) + "\",\"extra\":\"ignored\"}";
        write(state("running", "2026-11-03T11:59:30Z", evil).replace("\"deadman\"", "\"surprise\":{\"a\":1},\"deadman\""));
        Map<String, Object> view = service(true).view(NOW);
        List<?> comps = (List<?>) view.get("components");
        assertEquals(1, comps.size());
        Map<?, ?> c = (Map<?, ?>) comps.get(0);
        assertEquals("ok-one", c.get("name"));
        assertTrue(String.valueOf(c.get("detail")).length() <= 400);
        assertFalse(c.containsKey("extra"));
        assertFalse(view.containsKey("surprise"));
    }

    @Test
    void viewCarriesTheBootSummaryAndDeliveryStatus() throws IOException {
        write(state("running", "2026-11-03T11:59:30Z", comp("backend", "ok", "2026-11-03T11:59:30Z")));
        Map<String, Object> view = service(true).view(NOW);
        Map<?, ?> boot = (Map<?, ?>) view.get("boot");
        assertEquals("info", boot.get("severity"));
        assertEquals(720L, boot.get("telemetryGapSeconds"));
        assertEquals("true", boot.get("uncleanShutdown"));
        assertEquals("configured", ((Map<?, ?>) view.get("notify")).get("ntfy"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) view.get("deadman")).get("configured"));
        assertNull(view.get("message"));
    }
}
