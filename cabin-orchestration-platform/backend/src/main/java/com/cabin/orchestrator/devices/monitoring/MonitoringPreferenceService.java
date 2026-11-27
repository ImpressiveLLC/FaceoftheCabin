package com.cabin.orchestrator.devices.monitoring;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** No production backfill caller: compatibility input integration belongs to C1 PR 2. */
@Service
public class MonitoringPreferenceService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DeviceRegistry registry;
    private final ObjectMapper mapper;

    public MonitoringPreferenceService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                       DeviceRegistry registry, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.registry = registry;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS monitoring_preference_revision (
                location VARCHAR(32) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0)
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS monitoring_card_preference (
                location VARCHAR(32) NOT NULL, device_id VARCHAR(128) NOT NULL,
                included BOOLEAN NOT NULL, position INTEGER NOT NULL,
                explicit_choice BOOLEAN NOT NULL, PRIMARY KEY(location, device_id))
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS monitoring_preference_audit (
                location VARCHAR(32) NOT NULL, revision BIGINT NOT NULL,
                actor TEXT NOT NULL, changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                operation VARCHAR(16) NOT NULL, preferences TEXT NOT NULL,
                PRIMARY KEY(location, revision))
            """);
    }

    /** One SQL statement gives a consistent revision/card snapshot; reads never initialize rows. */
    public MonitoringPreferences read(String location) {
        validateLocation(location);
        var rows = jdbc.queryForList("""
            SELECT r.revision, c.device_id, c.included FROM monitoring_preference_revision r
            LEFT JOIN monitoring_card_preference c ON c.location = r.location
            WHERE r.location = ? ORDER BY c.position, c.device_id
            """, location);
        long revision = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("revision")).longValue();
        var cards = rows.stream().filter(r -> r.get("device_id") != null)
            .map(r -> new MonitoringPreferences.Card((String) r.get("device_id"), (Boolean) r.get("included"))).toList();
        return new MonitoringPreferences(location, revision, cards);
    }

    /** Canonical identity/location only: acceptance is not renderer eligibility or enrollment. */
    public MonitoringPreferences replace(String location, long expectedRevision,
                                         List<MonitoringPreferences.Card> cards, String actor) {
        validate(location, cards, actor);
        return transaction.execute(status -> {
            long revision = lock(location);
            if (expectedRevision != revision) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Monitoring preferences changed; refetch and retry with the current revision");
            var previous = read(location).cards();
            var submitted = new HashSet<String>();
            int position = 0;
            for (var card : cards) {
                submitted.add(card.deviceId());
                jdbc.update("""
                    INSERT INTO monitoring_card_preference(location, device_id, included, position, explicit_choice)
                    VALUES (?, ?, ?, ?, TRUE) ON CONFLICT (location, device_id) DO UPDATE SET
                    included = EXCLUDED.included, position = EXCLUDED.position, explicit_choice = TRUE
                    """, location, card.deviceId(), card.included(), position++);
            }
            for (var card : previous) if (!submitted.contains(card.deviceId()))
                jdbc.update("UPDATE monitoring_card_preference SET position = ? WHERE location = ? AND device_id = ?",
                    position++, location, card.deviceId());
            record(location, revision + 1, actor, "replace");
            return read(location);
        });
    }

    /** Trusted compatibility fixture/service input only. Not exposed as an endpoint or scheduled job. */
    public MonitoringPreferences backfill(String location, List<String> compatibleIds, String actor) {
        var cards = compatibleIds.stream().map(id -> new MonitoringPreferences.Card(id, true)).toList();
        validate(location, cards, actor);
        return transaction.execute(status -> {
            long revision = lock(location);
            int position = jdbc.queryForObject("SELECT COALESCE(MAX(position), -1) + 1 FROM monitoring_card_preference WHERE location = ?",
                Integer.class, location);
            int changed = 0;
            for (var card : cards) changed += insert(location, card, position++, false);
            if (changed > 0) record(location, revision + 1, actor, "backfill");
            return read(location);
        });
    }

    private long lock(String location) {
        jdbc.update("INSERT INTO monitoring_preference_revision(location) VALUES (?) ON CONFLICT DO NOTHING", location);
        return jdbc.queryForObject("SELECT revision FROM monitoring_preference_revision WHERE location = ? FOR UPDATE",
            Long.class, location);
    }

    private int insert(String location, MonitoringPreferences.Card card, int position, boolean explicit) {
        return jdbc.update("""
            INSERT INTO monitoring_card_preference(location, device_id, included, position, explicit_choice)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (location, device_id) DO NOTHING
            """, location, card.deviceId(), card.included(), position, explicit);
    }

    private void record(String location, long revision, String actor, String operation) {
        jdbc.update("UPDATE monitoring_preference_revision SET revision = ? WHERE location = ?", revision, location);
        try {
            jdbc.update("""
                INSERT INTO monitoring_preference_audit(location, revision, actor, operation, preferences)
                VALUES (?, ?, ?, ?, ?)
                """, location, revision, actor, operation, mapper.writeValueAsString(read(location).cards()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Monitoring audit", e);
        }
    }

    static void validateLocation(String location) {
        if (!"cabin".equals(location) && !"home".equals(location))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explicit cabin or home location required");
    }

    private void validate(String location, List<MonitoringPreferences.Card> cards, String actor) {
        validateLocation(location);
        if (actor == null || actor.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign-in required");
        if (cards == null || cards.size() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 4096 card preferences required");
        var seen = new HashSet<String>();
        for (var card : cards) {
            if (card == null || card.deviceId() == null || card.deviceId().isBlank()
                || card.deviceId().length() > 128 || !seen.add(card.deviceId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate canonical card identity");
            var descriptor = registry.descriptor(card.deviceId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown canonical identity; preference acceptance does not establish tile eligibility"));
            if (!location.equals(descriptor.location())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Canonical identity has a different or unresolved location; tile eligibility is not inferred");
        }
    }
}
