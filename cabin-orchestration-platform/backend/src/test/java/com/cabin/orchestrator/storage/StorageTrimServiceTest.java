package com.cabin.orchestrator.storage;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageTrimServiceTest {

    @TempDir Path spool;
    StorageMonitorService monitor;
    EventPublisher publisher;
    StorageTrimService service;

    @BeforeEach
    void setUp() {
        monitor = mock(StorageMonitorService.class);
        when(monitor.snapshot()).thenReturn(List.of(
            Map.of("mount", "storage-hdd", "available", true, "usedPercent", 99, "freeGb", 18.0, "totalGb", 916.0),
            Map.of("mount", "root-nvme", "available", true, "usedPercent", 40, "freeGb", 500.0, "totalGb", 938.0)));
        publisher = mock(EventPublisher.class);
        service = new StorageTrimService(monitor, publisher, spool.toString());
    }

    private void writePlan(Instant at, boolean measured) throws IOException {
        String json = "{\"plannedAt\":\"" + at + "\",\"sizesMeasured\":" + measured + ",\"auto\":{\"enabled\":true,\"aboveFull\":70,\"downTo\":55,\"floorKeep\":3},\"targets\":["
            + "{\"id\":\"backups-home\",\"kind\":\"dated-dirs\",\"path\":\"/storage/backups/home\",\"status\":\"ok\",\"keep\":3,"
            + "\"snapshots\":[{\"name\":\"2026-01-01\",\"bytes\":100},{\"name\":\"2026-01-02\",\"bytes\":200},{\"name\":\"2026-01-03\",\"bytes\":300},{\"name\":\"2026-01-04\",\"bytes\":400}]},"
            + "{\"id\":\"backups-data\",\"kind\":\"dated-dirs\",\"path\":\"/storage/backups/data\",\"status\":\"ok\",\"keep\":3,"
            + "\"snapshots\":[{\"name\":\"2026-01-01\",\"bytes\":10},{\"name\":\"2026-01-02\",\"bytes\":20}]},"
            + "{\"id\":\"system-copy\",\"kind\":\"whole-dir\",\"path\":\"/storage/backups/system\",\"status\":\"ok\"},"
            + "{\"id\":\"backups-gone\",\"kind\":\"dated-dirs\",\"path\":\"/x\",\"status\":\"missing\"}]}";
        Files.writeString(spool.resolve("plan.json"), json);
    }

    @Test
    void withoutAPlanItSaysTheHostServiceIsNotInstalledAndStillReportsTheDisk() {
        Map<String, Object> v = service.view();
        assertEquals(false, v.get("installed"));
        assertTrue(String.valueOf(v.get("message")).contains("not produced a plan"));
        assertEquals(99, ((Map<?, ?>) v.get("disk")).get("usedPercent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewOffersOnlyMeasuredDatedFolderTargetsWithTheirSnapshots() throws Exception {
        writePlan(Instant.now(), true);
        Map<String, Object> v = service.view();
        assertEquals(true, v.get("installed"));
        assertEquals(false, v.get("stale"));
        assertEquals(true, v.get("sizesMeasured"));
        List<Map<String, Object>> targets = (List<Map<String, Object>>) v.get("targets");
        assertEquals(List.of("backups-home", "backups-data"), targets.stream().map(t -> t.get("id")).toList());
        assertEquals(4, targets.get(0).get("count"));
        assertEquals(3, targets.get(0).get("keep"));
        assertEquals(4, ((List<?>) targets.get(0).get("snapshots")).size());
        assertEquals(false, v.get("pending"));
        assertNull(v.get("lastResult"));
        assertEquals(70, ((Map<?, ?>) v.get("auto")).get("aboveFull"));
    }

    @Test
    void anOldPlanIsFlaggedStale() throws Exception {
        writePlan(Instant.now().minusSeconds(13 * 3600), true);
        assertEquals(true, service.view().get("stale"));
    }

    @Test
    void theLastResultIsPassedThrough() throws Exception {
        writePlan(Instant.now(), true);
        Files.writeString(spool.resolve("result.json"), "{\"requestId\":\"r1\",\"ok\":true,\"removed\":3,\"freedBytes\":12345,\"output\":\"done\"}");
        Map<?, ?> r = (Map<?, ?>) service.view().get("lastResult");
        assertEquals("r1", r.get("requestId"));
        assertEquals(true, r.get("ok"));
    }

    @Test
    void aValidRequestWritesTheEnvFileTheAgentReads() throws Exception {
        writePlan(Instant.now(), true);
        String id = service.request(Map.of("backups-home", 2));
        String body = Files.readString(spool.resolve("request.env"));
        assertTrue(body.contains("requestId=" + id + "\n"));
        assertTrue(body.contains("keep.backups-home=2\n"));
        assertTrue(body.contains("requestedAt="));
        assertEquals(true, service.view().get("pending"));
        try (var files = Files.list(spool)) {
            assertEquals(List.of("plan.json", "request.env"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void rejectsWhatTheAgentWouldRefuseOrWhatRemovesNothing() throws Exception {
        writePlan(Instant.now(), true);
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of())).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-home", 0))).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-home", 5))).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-home", 4))).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("system-copy", 1))).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("../etc", 1))).status().value());
        assertEquals(400, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-gone", 1))).status().value());
        assertFalse(Files.exists(spool.resolve("request.env")));
    }

    @Test
    void onlyOneRequestAtATime() throws Exception {
        writePlan(Instant.now(), true);
        service.request(Map.of("backups-home", 2));
        assertEquals(409, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-data", 1))).status().value());
    }

    @Test
    void noPlanMeansNoRequest() {
        assertEquals(503, assertThrows(StorageTrimService.TrimException.class, () -> service.request(Map.of("backups-home", 1))).status().value());
        assertNotNull(service);
    }

    private void writeAutoResult(Instant at, String id, boolean ok, boolean reached, int removed) throws IOException {
        Files.writeString(spool.resolve("result.json"), "{\"requestId\":\"" + id + "\",\"finishedAt\":\"" + at + "\",\"ok\":" + ok
            + ",\"removed\":" + removed + ",\"freedBytes\":" + (70L * 1024 * 1024 * 1024) + ",\"auto\":true,\"reachedTarget\":" + reached + ",\"output\":\"auto-result\"}");
    }

    @Test
    void anAutomaticTrimIsAnnouncedOnceAsInfoWhenItReachedItsTarget() throws Exception {
        writeAutoResult(Instant.now(), "auto-1", true, true, 12);
        service.announceAutoResult();
        service.announceAutoResult();
        ArgumentCaptor<CabinEvent> c = ArgumentCaptor.forClass(CabinEvent.class);
        verify(publisher, times(1)).publish(c.capture());
        CabinEvent e = c.getValue();
        assertEquals("STORAGE_AUTO_TRIM", e.eventType());
        assertEquals("INFO", e.severity());
        assertEquals("storage-storage-hdd", e.sourceDeviceId());
        assertTrue(String.valueOf(e.payload().get("see")).contains("removed 12 old backup copies"));
        assertEquals(false, e.payload().get("notify"));
    }

    @Test
    void anAutomaticTrimThatCouldNotGetUnderItsLineIsAWarningThatNotifies() throws Exception {
        writeAutoResult(Instant.now(), "auto-2", true, false, 0);
        service.announceAutoResult();
        ArgumentCaptor<CabinEvent> c = ArgumentCaptor.forClass(CabinEvent.class);
        verify(publisher).publish(c.capture());
        assertEquals("WARN", c.getValue().severity());
        assertEquals(true, c.getValue().payload().get("notify"));
        assertTrue(String.valueOf(c.getValue().payload().get("think")).contains("already down to its minimum"));
    }

    @Test
    void aFailedAutomaticTrimIsAWarning() throws Exception {
        writeAutoResult(Instant.now(), "auto-3", false, false, 0);
        service.announceAutoResult();
        ArgumentCaptor<CabinEvent> c = ArgumentCaptor.forClass(CabinEvent.class);
        verify(publisher).publish(c.capture());
        assertEquals("WARN", c.getValue().severity());
        assertTrue(String.valueOf(c.getValue().payload().get("see")).contains("failed"));
    }

    @Test
    void manualResultsOldResultsAndNoResultAreNotAnnounced() throws Exception {
        service.announceAutoResult();
        Files.writeString(spool.resolve("result.json"), "{\"requestId\":\"m1\",\"finishedAt\":\"" + Instant.now() + "\",\"ok\":true,\"removed\":3,\"auto\":false}");
        service.announceAutoResult();
        writeAutoResult(Instant.now().minusSeconds(3600), "auto-old", true, true, 5);
        service.announceAutoResult();
        verify(publisher, never()).publish(any());
    }
}
