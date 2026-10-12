package com.cabin.orchestrator.storage;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.storage.StorageMonitorService.DiskProbe;
import com.cabin.orchestrator.storage.StorageMonitorService.Level;
import com.cabin.orchestrator.storage.StorageMonitorService.MountUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-11. The monitor decides from a percentage and a clock, so both are injected: no real disk is read
 * and no test sleeps. The numbers mirror the real incident: the 916 GB HDD at 94% with 57 GB free.
 */
class StorageMonitorServiceTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");

    private EventPublisher publisher;
    private CabinEventService events;
    private final AtomicInteger usedPercent = new AtomicInteger(50);
    private final DiskProbe probe = (label, path) -> {
        long total = 1000 * GB;
        long avail = total * (100 - usedPercent.get()) / 100;
        return Optional.of(new MountUsage(label, path, total, avail, avail));
    };

    @BeforeEach
    void setUp() {
        publisher = mock(EventPublisher.class);
        events = mock(CabinEventService.class);
        when(events.recent(anyString(), anyInt(), any(Instant.class))).thenReturn(List.of());
    }

    private StorageMonitorService service() {
        return new StorageMonitorService(publisher, events, probe, true, Map.of("storage-hdd", "/app/archives/cabin_event"),
            80, 90, 3, 24);
    }

    private List<CabinEvent> published(int expected) {
        ArgumentCaptor<CabinEvent> captor = ArgumentCaptor.forClass(CabinEvent.class);
        verify(publisher, times(expected)).publish(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void usedPercentMatchesDfAndRoundsUp() {
        // The real 2026-10-10 reading: 916 GB total, 813 GB used, 57 GB available -> df said 94%.
        MountUsage hdd = new MountUsage("storage-hdd", "/storage", 916 * GB, 103 * GB, 57 * GB);
        assertThat(hdd.usedPercent()).isEqualTo(94);
        assertThat(new MountUsage("x", "/", 0, 0, 0).usedPercent()).isZero();
    }

    @Test
    void belowTheWarningLineNothingIsPublished() {
        usedPercent.set(79);
        service().evaluate(T0);
        verify(publisher, never()).publish(any());
    }

    @Test
    void crossingEightyPublishesOneWarnEventThatAsksForAPush() {
        usedPercent.set(81);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.eventType()).isEqualTo("STORAGE_THRESHOLD");
        assertThat(e.severity()).isEqualTo("WARN");
        assertThat(e.sourceDeviceId()).isEqualTo("storage-storage-hdd");
        assertThat(e.payload()).containsEntry("level", "WARN").containsEntry("usedPercent", 81)
            .containsEntry("notify", true).containsEntry("reminder", false);
        assertThat((String) e.payload().get("see")).contains("81% full");
        assertThat((String) e.payload().get("act")).contains("prune-storage-backups");
    }

    @Test
    void crossingNinetyPublishesACriticalEvent() {
        usedPercent.set(94);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(e.payload()).containsEntry("level", "CRITICAL").containsEntry("notify", true);
    }

    @Test
    void climbingFromWarnToCriticalPublishesASecondEvent() {
        StorageMonitorService s = service();
        usedPercent.set(82);
        s.evaluate(T0);
        usedPercent.set(91);
        s.evaluate(T0.plusSeconds(300));

        List<CabinEvent> all = published(2);
        assertThat(all.get(0).severity()).isEqualTo("WARN");
        assertThat(all.get(1).severity()).isEqualTo("CRITICAL");
    }

    @Test
    void aDiskHoveringAtTheLineDoesNotFlap() {
        StorageMonitorService s = service();
        usedPercent.set(80);
        s.evaluate(T0);                          // WARN
        usedPercent.set(79);
        s.evaluate(T0.plusSeconds(300));         // within the 3-point margin: stays WARN, no event
        usedPercent.set(80);
        s.evaluate(T0.plusSeconds(600));         // still WARN, still no event
        published(1);
    }

    @Test
    void fallingWellBelowTheLinePublishesAnInfoClearedEventWithoutAPush() {
        StorageMonitorService s = service();
        usedPercent.set(85);
        s.evaluate(T0);
        usedPercent.set(60);
        s.evaluate(T0.plusSeconds(300));

        CabinEvent cleared = published(2).get(1);
        assertThat(cleared.eventType()).isEqualTo("STORAGE_CLEARED");
        assertThat(cleared.severity()).isEqualTo("INFO");
        assertThat(cleared.payload()).doesNotContainKey("notify");
    }

    @Test
    void stayingAboveTheLineRepeatsTheNoticeOnlyAfterTheRenotifyWindow() {
        StorageMonitorService s = service();
        usedPercent.set(93);
        s.evaluate(T0);                          // first notice
        s.evaluate(T0.plusSeconds(3600));        // one hour later: nothing
        s.evaluate(T0.plusSeconds(24 * 3600));   // 24 h later: repeat

        List<CabinEvent> all = published(2);
        assertThat(all.get(0).payload()).containsEntry("reminder", false);
        assertThat(all.get(1).payload()).containsEntry("reminder", true);
        assertThat((String) all.get(1).payload().get("think")).contains("repeat");
    }

    @Test
    void afterARestartTheLastEventRestoresTheLevelSoADeployDoesNotRePage() {
        CabinEvent last = new CabinEvent("e1", "storage-storage-hdd", "STORAGE_THRESHOLD", "CRITICAL",
            T0.minusSeconds(3600), Map.of("level", "CRITICAL"));
        when(events.recent(anyString(), anyInt(), any(Instant.class))).thenReturn(List.of(last));

        usedPercent.set(94);
        service().evaluate(T0);                  // fresh instance, as after a deploy

        verify(publisher, never()).publish(any());
    }

    @Test
    void anUnreadableMountIsSkippedNotFatal() {
        StorageMonitorService s = new StorageMonitorService(publisher, events, (l, p) -> Optional.empty(), true,
            Map.of("gone", "/nope"), 80, 90, 3, 24);
        s.evaluate(T0);
        verify(publisher, never()).publish(any());
        assertThat(s.snapshot().get(0)).containsEntry("available", false);
    }

    @Test
    void disabledMonitorDoesNothing() {
        usedPercent.set(99);
        StorageMonitorService s = new StorageMonitorService(publisher, events, probe, false,
            Map.of("storage-hdd", "/x"), 80, 90, 3, 24);
        s.check();
        verify(publisher, never()).publish(any());
    }

    @Test
    void snapshotReportsUsageAndTheConfiguredLines() {
        usedPercent.set(94);
        Map<String, Object> row = service().snapshot().get(0);
        assertThat(row).containsEntry("mount", "storage-hdd").containsEntry("available", true)
            .containsEntry("usedPercent", 94).containsEntry("level", "CRITICAL")
            .containsEntry("warnPercent", 80).containsEntry("criticalPercent", 90);
        assertThat((Double) row.get("freeGb")).isEqualTo(60.0);
    }

    @Test
    void hysteresisTable() {
        StorageMonitorService s = service();
        assertThat(s.nextLevel(Level.NORMAL, 79)).isEqualTo(Level.NORMAL);
        assertThat(s.nextLevel(Level.NORMAL, 80)).isEqualTo(Level.WARN);
        assertThat(s.nextLevel(Level.WARN, 77)).isEqualTo(Level.WARN);
        assertThat(s.nextLevel(Level.WARN, 76)).isEqualTo(Level.NORMAL);
        assertThat(s.nextLevel(Level.CRITICAL, 87)).isEqualTo(Level.CRITICAL);
        assertThat(s.nextLevel(Level.CRITICAL, 86)).isEqualTo(Level.WARN);
        assertThat(s.nextLevel(Level.WARN, 90)).isEqualTo(Level.CRITICAL);
    }

    @Test
    void parsesTheMountSpecAndIgnoresMalformedPairs() {
        assertThat(StorageMonitorService.parseMounts("a=/x, b=/y ,bad,=,c="))
            .containsOnlyKeys("a", "b").containsEntry("a", "/x").containsEntry("b", "/y");
    }

    @Test
    void warnMustBeBelowCritical() {
        assertThatThrownBy(() -> new StorageMonitorService(publisher, events, probe, true, Map.of(), 90, 80, 3, 24))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
