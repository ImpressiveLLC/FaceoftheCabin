package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * KB Generator v1 (issue #32) -- the first real KnowledgeNode source
 * feeding the Tiny Helpdesk (Ollama + Open WebUI, deployed 2026-08-30).
 * Draws entirely from data this backend already assembles in-process
 * (DeviceRegistry aggregates every protocol adapter -- Zigbee, HA REST,
 * etc. -- uniformly; DeviceRepository and DeviceReportingRelationshipRepository
 * are this sprint's own D1/D6/D7 persistence), so v1 needs no new external
 * API client of its own.
 *
 * D5's safety-critical exclusion (docs/ontology/DECISIONS.md) is enforced
 * by scope, not by a per-device runtime check: this generator only ever
 * writes DESCRIPTION and RELATIONSHIP chunks -- short, factual device
 * metadata. It never writes TROUBLESHOOTING, SETUP, or CREDENTIAL_POINTER
 * content for anything, so a freeze-risk/mold-risk/water-shutoff procedure
 * can never accidentally end up auto_generated through this path. Every
 * node this writes is tagged AUTO_GENERATED; nothing here ever writes
 * MANUALLY_CURATED.
 */
@Service
public class KbGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(KbGeneratorService.class);

    private final DeviceRegistry registry;
    private final DeviceRepository deviceRepository;
    private final DeviceReportingRelationshipRepository reportingRelationshipRepository;
    private final KnowledgeNodeRepository knowledgeNodeRepository;

    public KbGeneratorService(DeviceRegistry registry, DeviceRepository deviceRepository,
                               DeviceReportingRelationshipRepository reportingRelationshipRepository,
                               KnowledgeNodeRepository knowledgeNodeRepository) {
        this.registry = registry;
        this.deviceRepository = deviceRepository;
        this.reportingRelationshipRepository = reportingRelationshipRepository;
        this.knowledgeNodeRepository = knowledgeNodeRepository;
    }

    /**
     * Sprint 2 (docs/ontology/SPRINT-STATUS.md): keeps auto-generated
     * content from going stale as devices are renamed, re-paired, or newly
     * confirmed reporting relationships land -- 3am, matching
     * TelemetryArchivalService's off-peak-hour convention.
     */
    @Scheduled(cron = "${cabin.kbGenerator.cron:0 0 3 * * *}")
    public void scheduledRegenerateAll() {
        int written = regenerateAll();
        log.info("KB Generator scheduled refresh: {} chunks written", written);
    }

    /** Regenerates every in-scope device's KnowledgeNodes. Returns how many chunks were written. */
    public int regenerateAll() {
        int written = 0;
        for (DeviceStatus status : registry.inScope()) {
            written += regenerateFor(status.deviceId());
        }
        return written;
    }

    /** Regenerates one device's KnowledgeNodes. A device with no metadata/reporting data yet still gets a description chunk. */
    public int regenerateFor(String deviceId) {
        DeviceStatus status = registry.get(deviceId);
        if (status == null) return 0;

        Instant now = Instant.now();
        int written = 0;

        knowledgeNodeRepository.upsert(new KnowledgeNode(
            deviceId, KnowledgeChunkType.DESCRIPTION, buildDescription(status),
            KnowledgeSource.AUTO_GENERATED, now));
        written++;

        String relationship = buildRelationship(deviceId, status);
        if (relationship != null) {
            knowledgeNodeRepository.upsert(new KnowledgeNode(
                deviceId, KnowledgeChunkType.RELATIONSHIP, relationship,
                KnowledgeSource.AUTO_GENERATED, now));
            written++;
        }

        return written;
    }

    private String buildDescription(DeviceStatus status) {
        DeviceMetadata metadata = deviceRepository.find(status.deviceId()).orElse(null);
        StringBuilder sb = new StringBuilder(status.name()).append(" is a ");
        if (metadata != null && metadata.manufacturer() != null) sb.append(metadata.manufacturer()).append(' ');
        if (metadata != null && metadata.model() != null) sb.append(metadata.model()).append(' ');
        sb.append('(').append(humanize(status.type())).append(')');
        String where = metadata != null && metadata.area() != null ? metadata.area() : status.location();
        if (where != null) sb.append(" located in ").append(where);
        sb.append('.');
        return sb.toString();
    }

    /** Null when nothing's ever been confirmed to report anything -- an empty chunk would be noise, not a fact. */
    private String buildRelationship(String deviceId, DeviceStatus status) {
        List<DeviceReportingRelationship> relationships = reportingRelationshipRepository.findByDevice(deviceId);
        if (relationships.isEmpty()) return null;
        String fields = relationships.stream()
            .map(DeviceReportingRelationship::semanticField)
            .sorted()
            .collect(Collectors.joining(", "));
        return status.name() + " reports: " + fields + ".";
    }

    private static String humanize(DeviceType type) {
        return type.name().toLowerCase().replace('_', ' ');
    }
}
