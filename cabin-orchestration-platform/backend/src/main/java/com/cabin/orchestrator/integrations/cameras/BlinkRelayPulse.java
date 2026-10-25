package com.cabin.orchestrator.integrations.cameras;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dead-man switch for the Blink motion trigger path (2026-10-24).
 *
 * Blink's own motion flag is unusable on this account, so the only motion
 * signal is the Blink app's phone notification, relayed to cabin/blink/motion
 * (by the Termux relay at Home, scripts/termux/blink-notification-relay.sh,
 * or the HA Companion automation). When that relay goes quiet (phone reboot,
 * Tailscale off, notification access revoked) nothing else notices: the
 * cameras just stop recording. The relay publishes a retained heartbeat on
 * cabin/blink/relay/heartbeat; this class turns "heartbeat too old" into an
 * active alert.
 *
 * Silent until a heartbeat has been seen at least once, so an instance that
 * never installed the relay is not nagged.
 */
@Component
public class BlinkRelayPulse {

    public static final String TOPIC = "cabin/blink/relay/heartbeat";

    @Value("${cabin.blink.relay.staleAfterMinutes:10}")
    private long staleAfterMinutes = 10;

    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>();

    /** Record a heartbeat; payload is an ISO-8601 instant or JSON containing "ts". Unparseable input is ignored. */
    public void record(String payload, Instant arrivedAt) {
        if (payload == null || payload.isBlank()) return;  // a cleared retained heartbeat is not a sign of life
        Instant ts = parse(payload);
        if (ts == null) ts = arrivedAt;
        lastHeartbeat.accumulateAndGet(ts, (a, b) -> a == null || b.isAfter(a) ? b : a);
    }

    /** Heartbeat age, or empty if the relay has never reported. */
    public Optional<Duration> age(Instant now) {
        Instant last = lastHeartbeat.get();
        return last == null ? Optional.empty() : Optional.of(Duration.between(last, now));
    }

    public Optional<Instant> lastSeen() {
        return Optional.ofNullable(lastHeartbeat.get());
    }

    public boolean stale(Instant now) {
        return age(now).map(a -> a.toMinutes() >= staleAfterMinutes).orElse(false);
    }

    public long staleAfterMinutes() {
        return staleAfterMinutes;
    }

    void setStaleAfterMinutes(long minutes) {
        this.staleAfterMinutes = minutes;
    }

    private static Instant parse(String payload) {
        if (payload == null) return null;
        String p = payload.trim();
        int i = p.indexOf("\"ts\"");
        if (i >= 0) {
            int q1 = p.indexOf('"', p.indexOf(':', i) + 1);
            int q2 = q1 < 0 ? -1 : p.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) p = p.substring(q1 + 1, q2);
        }
        try {
            return Instant.parse(p);
        } catch (Exception e) {
            return null;
        }
    }
}
