package com.cabin.orchestrator.storage;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.storage.StorageMonitorService.DiskProbe;
import com.cabin.orchestrator.storage.StorageMonitorService.MountUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nate, 2026-10-31: the storage-hdd critical alert should not read as a true emergency when the host's
 * automatic backup trim will clear it, but must still escalate when it will not. The monitor's severity,
 * text and repeat behaviour follow {@link AutoTrimStatus}, which is injected here; the status reader and the
 * Trim control's view of it are tested against a real spool folder.
 */
class StorageAutoTrimAlertTest {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");

    @TempDir Path spool;
    private EventPublisher publisher;
    private CabinEventService events;
    private final AtomicInteger usedPercent = new AtomicInteger(50);
    private final AtomicReference<AutoTrimStatus> status = new AtomicReference<>(AutoTrimStatus.ABSENT);
    private final DiskProbe probe = (label, path) -> {
        long total = 1000 * GB;
        long avail = total * (100 - usedPercent.get()) / 100;
        return Optional.of(new MountUsage(label, path, total, avail, avail));
    };

    private static AutoTrimStatus healthy() {
        return new AutoTrimStatus(true, true, false, false, 70, 55, 3);
    }

    @BeforeEach
    void setUp() {
        publisher = mock(EventPublisher.class);
        events = mock(CabinEventService.class);
        when(events.recent(anyString(), anyInt(), any(Instant.class))).thenReturn(List.of());
    }

    private StorageMonitorService service() {
        return new StorageMonitorService(publisher, events, probe, true, Map.of("storage-hdd", "/app/archives/cabin_event"),
            60, 75, 3, 24, status::get);
    }

    private List<CabinEvent> published(int expected) {
        ArgumentCaptor<CabinEvent> captor = ArgumentCaptor.forClass(CabinEvent.class);
        verify(publisher, times(expected)).publish(captor.capture());
        return captor.getAllValues();
    }

    private static String act(CabinEvent e) {
        return (String) e.payload().get("act");
    }

    // ---- alert wording and severity -----------------------------------------------------------

    @Test
    void whenAutoTrimIsHealthyTheCriticalLineIsAWarnWithNoPushAndSaysItIsNotAnEmergency() {
        status.set(healthy());
        usedPercent.set(80);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("WARN");
        assertThat(e.payload()).containsEntry("level", "CRITICAL").containsEntry("notify", false).containsEntry("softened", true);
        assertThat(act(e)).contains("Not a true emergency").contains("70%").contains("55%").contains("at least 3 per folder")
            .contains("Trim old nightly backups");
    }

    @Test
    void theAutoThresholdsInTheTextComeFromThePlanNotFromConstants() {
        status.set(new AutoTrimStatus(true, true, false, false, 66, 44, 5));
        usedPercent.set(80);
        service().evaluate(T0);

        assertThat(act(published(1).get(0))).contains("66%").contains("44%").contains("at least 5 per folder");
    }

    @Test
    void whenAutoTrimIsNotInstalledItIsCriticalPushesAndSaysManualActionIsNeeded() {
        usedPercent.set(80);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(e.payload()).containsEntry("notify", true).containsEntry("softened", false);
        assertThat(act(e)).contains("not installed").contains("needs you").contains("Trim old nightly backups");
        assertThat(act(e)).doesNotContain("Not a true emergency");
    }

    @Test
    void whenAutoTrimIsSwitchedOffItIsCriticalAndSaysSo() {
        status.set(new AutoTrimStatus(true, false, false, false, 70, 55, 3));
        usedPercent.set(80);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(act(e)).contains("switched off").contains("needs you");
    }

    @Test
    void whenTheAgentHasStoppedUpdatingItsPlanItIsCritical() {
        status.set(new AutoTrimStatus(true, true, true, false, 70, 55, 3));
        usedPercent.set(80);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(act(e)).contains("stopped updating");
    }

    @Test
    void whenTheLastAutomaticRunCouldNotGetUnderItsLineItIsCritical() {
        status.set(new AutoTrimStatus(true, true, false, true, 70, 55, 3));
        usedPercent.set(80);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(act(e)).contains("could not get the disk under its line").contains("needs you");
    }

    @Test
    void theWarningLineIsUnchangedByAutoTrim() {
        status.set(healthy());
        usedPercent.set(65);
        service().evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("WARN");
        assertThat(e.payload()).containsEntry("notify", true);
        assertThat(act(e)).doesNotContain("Not a true emergency");
    }

    @Test
    void theNvmeHasNoAutomaticRemedyAndStaysCritical() {
        status.set(healthy());
        usedPercent.set(80);
        new StorageMonitorService(publisher, events, probe, true, Map.of("root-nvme", "/"), 60, 75, 3, 24, status::get)
            .evaluate(T0);

        CabinEvent e = published(1).get(0);
        assertThat(e.severity()).isEqualTo("CRITICAL");
        assertThat(act(e)).contains("docker system df");
    }

    // ---- no spam, and escalation --------------------------------------------------------------

    @Test
    void aSoftenedAlertIsPublishedOnceNotEveryCycle() {
        status.set(healthy());
        usedPercent.set(80);
        StorageMonitorService s = service();
        for (int i = 0; i < 10; i++) s.evaluate(T0.plusSeconds(300L * i));

        published(1);
    }

    @Test
    void ifAutoTrimGoesAwayWhileStillCriticalItEscalatesOnceWithAPush() {
        status.set(healthy());
        usedPercent.set(80);
        StorageMonitorService s = service();
        s.evaluate(T0);
        status.set(new AutoTrimStatus(true, true, false, true, 70, 55, 3));   // ran, hit the floor
        s.evaluate(T0.plusSeconds(300));
        s.evaluate(T0.plusSeconds(600));
        s.evaluate(T0.plusSeconds(900));

        List<CabinEvent> all = published(2);
        assertThat(all.get(0).severity()).isEqualTo("WARN");
        assertThat(all.get(1).severity()).isEqualTo("CRITICAL");
        assertThat(all.get(1).payload()).containsEntry("notify", true);
        assertThat(act(all.get(1))).contains("needs you");
    }

    @Test
    void ifTheDiskIsStillCriticalADayLaterTheRepeatNoticeIsCriticalEvenWithAutoTrimOn() {
        status.set(healthy());
        usedPercent.set(80);
        StorageMonitorService s = service();
        s.evaluate(T0);
        s.evaluate(T0.plusSeconds(23 * 3600));
        s.evaluate(T0.plusSeconds(24 * 3600));

        List<CabinEvent> all = published(2);
        assertThat(all.get(1).severity()).isEqualTo("CRITICAL");
        assertThat(all.get(1).payload()).containsEntry("reminder", true);
        assertThat(act(all.get(1))).contains("still this full a day later");
    }

    @Test
    void aSoftenedAlertSurvivesARestartWithoutRepagingOrEscalatingWhileAutoTrimIsStillHealthy() {
        status.set(healthy());
        usedPercent.set(80);
        StorageMonitorService first = service();
        first.evaluate(T0);
        CabinEvent sent = published(1).get(0);
        when(events.recent(anyString(), anyInt(), any(Instant.class))).thenReturn(List.of(sent));

        service().evaluate(T0.plusSeconds(600));

        published(1);                       // nothing new after the restart
        status.set(AutoTrimStatus.ABSENT);
        service().evaluate(T0.plusSeconds(900));
        assertThat(published(2).get(1).severity()).isEqualTo("CRITICAL");   // and it still escalates if the agent is gone
    }

    // ---- reading the spool, and the Trim control's view of it ---------------------------------

    private void plan(Instant at, boolean autoEnabled) throws IOException {
        Files.writeString(spool.resolve("plan.json"), "{\"plannedAt\":\"" + at + "\",\"sizesMeasured\":true,"
            + "\"auto\":{\"enabled\":" + autoEnabled + ",\"aboveFull\":70,\"downTo\":55,\"floorKeep\":3},\"targets\":[]}");
    }

    private void autoResult(Instant at, boolean ok, boolean reached) throws IOException {
        Files.writeString(spool.resolve("result.json"), "{\"requestId\":\"auto-1\",\"finishedAt\":\"" + at + "\",\"ok\":" + ok
            + ",\"removed\":2,\"freedBytes\":1,\"auto\":true,\"reachedTarget\":" + reached + ",\"output\":\"x\"}");
    }

    @Test
    void statusIsAbsentWithoutAPlan() {
        AutoTrimStatus s = AutoTrimStatus.read(spool, Instant.now());
        assertThat(s.installed()).isFalse();
        assertThat(s.healthy()).isFalse();
    }

    @Test
    void statusReadsThePlansThresholdsAndHealth() throws Exception {
        Instant now = Instant.now();
        plan(now, true);
        AutoTrimStatus s = AutoTrimStatus.read(spool, now);
        assertThat(s.healthy()).isTrue();
        assertThat(s.aboveFull()).isEqualTo(70);
        assertThat(s.downTo()).isEqualTo(55);
        assertThat(s.floorKeep()).isEqualTo(3);

        plan(now.minusSeconds(13 * 3600), true);
        assertThat(AutoTrimStatus.read(spool, now).planStale()).isTrue();
        assertThat(AutoTrimStatus.read(spool, now).healthy()).isFalse();

        plan(now, false);
        assertThat(AutoTrimStatus.read(spool, now).healthy()).isFalse();
    }

    @Test
    void stuckMeansARecentAutomaticRunThatFailedOrHitItsFloor() throws Exception {
        Instant now = Instant.now();
        plan(now, true);
        autoResult(now.minusSeconds(600), true, true);
        assertThat(AutoTrimStatus.read(spool, now).stuck()).isFalse();
        autoResult(now.minusSeconds(600), true, false);
        assertThat(AutoTrimStatus.read(spool, now).stuck()).isTrue();
        autoResult(now.minusSeconds(600), false, true);
        assertThat(AutoTrimStatus.read(spool, now).stuck()).isTrue();
        autoResult(now.minusSeconds(25 * 3600), true, false);   // too old to say anything about the disk now
        assertThat(AutoTrimStatus.read(spool, now).stuck()).isFalse();
    }

    private Map<String, Object> view() {
        StorageMonitorService monitor = mock(StorageMonitorService.class);
        when(monitor.snapshot()).thenReturn(List.of(
            Map.of("mount", "storage-hdd", "available", true, "usedPercent", 80, "freeGb", 225.0, "totalGb", 916.0)));
        return new StorageTrimService(monitor, mock(EventPublisher.class), spool.toString()).view();
    }

    @Test
    void notInstalledViewSaysAutoTrimIsNotRunningGivesTheInstallCommandsAndKeepsTheDisk() {
        Map<String, Object> v = view();
        assertThat(v).containsEntry("installed", false).containsEntry("autoTrim", "not-installed");
        assertThat((String) v.get("message")).contains("Automatic backup trim is not running").contains("not installed");
        String cmds = (String) v.get("installCommands");
        assertThat(cmds).contains("git -C ~/repos/FaceoftheCabin pull --ff-only origin main")
            .contains("sudo install -m 0755 scripts/host/cabin-storage-prune /usr/local/bin/cabin-storage-prune")
            .contains("sudo install -m 0755 scripts/host/cabin-storage-trim-agent /usr/local/bin/cabin-storage-trim-agent")
            .contains("cabin-storage-trim-agent.service")
            .contains("sudo systemctl daemon-reload && sudo systemctl enable --now cabin-storage-trim-agent");
        assertThat(((Map<?, ?>) v.get("disk")).get("usedPercent")).isEqualTo(80);
    }

    @Test
    void installedViewReportsWhetherAutoTrimIsOnOffStaleOrStuck() throws Exception {
        Instant now = Instant.now();
        plan(now, true);
        assertThat(view()).containsEntry("installed", true).containsEntry("autoTrim", "on");
        autoResult(now, true, false);
        assertThat(view()).containsEntry("autoTrim", "stuck");
        Files.delete(spool.resolve("result.json"));
        plan(now, false);
        assertThat(view()).containsEntry("autoTrim", "disabled");
        plan(now.minusSeconds(13 * 3600), true);
        assertThat(view()).containsEntry("autoTrim", "stale");
        assertThat(view()).doesNotContainKey("installCommands");
    }
}
