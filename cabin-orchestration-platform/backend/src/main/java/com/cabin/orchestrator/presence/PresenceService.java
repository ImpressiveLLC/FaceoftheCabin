package com.cabin.orchestrator.presence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Found 2026-08-08 (user report): the active PresenceProfile was
 * ONLY ever set by a manual PUT /api/presence from the toolbar dropdown
 * -- despite the UI showing a map-pin icon that reads as "your detected
 * location", nothing derived it from anything real, and
 * AutomationRuleService uses this value to decide real security-event
 * severity. A stale or simply-wrong manual toggle therefore had a real
 * safety consequence, not just a cosmetic one.
 *
 * A real signal already existed and was already live
 * (cabin/presence/nate, an HA automation doing a WiFi ARP check --
 * see docs/ontology.yaml's automation_cabin_security_publish_nate_
 * presence_from_phone) but nothing subscribed to it. This service now
 * derives the profile from PresenceSignalRegistry's live signals
 * whenever MqttBridgeService reports one arriving, and that derivation
 * ALWAYS wins over a manual override the moment any real signal exists
 * -- see recomputeFromSignals(). The manual set() path is kept, not
 * removed: an instance/location with no presence automation configured
 * yet (or a guest with no tracked phone) still needs a way to state
 * "away" by hand, and that manual value is exactly what's used until
 * the first real signal ever arrives for this instance.
 *
 * Deliberately N-people x M-locations, not "Nate at the cabin" hardcoded
 * -- see PresenceSignalRegistry's own comment. BOTH_OCCUPIED means
 * someone (anyone) has a live "present" signal at both cabin and home
 * simultaneously; AWAY means no live "present" signal anywhere, once at
 * least one signal has ever been seen.
 */
@Service
public class PresenceService {

    private final JdbcTemplate jdbc;
    private final PresenceSignalRegistry signalRegistry;
    private final AtomicReference<PresenceProfile> current = new AtomicReference<>(PresenceProfile.AT_HOME);
    private final AtomicBoolean autoDerived = new AtomicBoolean(false);

    public PresenceService(JdbcTemplate jdbc, PresenceSignalRegistry signalRegistry) {
        this.jdbc = jdbc;
        this.signalRegistry = signalRegistry;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS active_presence (
              id INT PRIMARY KEY,
              profile VARCHAR(32) NOT NULL DEFAULT 'AT_HOME',
              updated_at TIMESTAMPTZ DEFAULT now()
            )""");
        jdbc.execute("INSERT INTO active_presence (id, profile) VALUES (1, 'AT_HOME') ON CONFLICT DO NOTHING");
        String saved = jdbc.queryForObject("SELECT profile FROM active_presence WHERE id = 1", String.class);
        if (saved != null) {
            try { current.set(PresenceProfile.valueOf(saved)); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    public PresenceProfile get() { return current.get(); }

    /** True once the current value came from a real presence signal rather than the manual fallback. */
    public boolean isAutoDerived() { return autoDerived.get(); }

    /** Manual override -- PUT /api/presence. Authoritative only until the next real signal arrives. */
    public PresenceProfile set(PresenceProfile profile) {
        jdbc.update("UPDATE active_presence SET profile = ?, updated_at = now() WHERE id = 1", profile.name());
        current.set(profile);
        autoDerived.set(false);
        return profile;
    }

    /**
     * Called by MqttBridgeService every time a presence signal arrives.
     * No-ops (returns the current value unchanged) if no signal has ever
     * been seen for this instance -- see PresenceSignalRegistry.
     * hasAnySignal()'s comment for why that matters.
     */
    public PresenceProfile recomputeFromSignals() {
        if (!signalRegistry.hasAnySignal()) return current.get();

        boolean atCabin = signalRegistry.anyPresentAt("cabin");
        boolean atHome  = signalRegistry.anyPresentAt("home");
        PresenceProfile derived =
            atCabin && atHome ? PresenceProfile.BOTH_OCCUPIED :
            atCabin           ? PresenceProfile.AT_CABIN :
            atHome            ? PresenceProfile.AT_HOME :
                                 PresenceProfile.AWAY;

        jdbc.update("UPDATE active_presence SET profile = ?, updated_at = now() WHERE id = 1", derived.name());
        current.set(derived);
        autoDerived.set(true);
        return derived;
    }
}
