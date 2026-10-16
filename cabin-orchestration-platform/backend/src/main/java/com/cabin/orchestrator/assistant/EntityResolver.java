package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.assistant.AssistantInventory.Entry;
import com.cabin.orchestrator.assistant.ResolverModel.ActionKind;
import com.cabin.orchestrator.assistant.ResolverModel.ActionRequest;
import com.cabin.orchestrator.assistant.ResolverModel.Outcome;
import com.cabin.orchestrator.assistant.ResolverModel.Resolution;
import com.cabin.orchestrator.assistant.ResolverModel.ResolvedAction;
import com.cabin.orchestrator.assistant.ResolverModel.SemanticTarget;
import com.cabin.orchestrator.security.HouseholdRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HO-2026-10-16 step 2 (W-75, D27 R-OC-5): turns the semantic target the
 * intent/slot model produced into exactly one inventory entry, or a typed
 * refusal. It never guesses: the model supplies words, this class decides
 * whether those words name one device the caller may act on right now.
 *
 * <p>Checks, in order, each recorded in {@link Resolution#reasons()}:
 * <ol>
 *   <li>the caller's role may use the assistant at all (UNAUTHORIZED);</li>
 *   <li>the inventory snapshot is fresh (STALE);</li>
 *   <li>the words name exactly one device: NOT_FOUND, WRONG_SITE or AMBIGUOUS. Names match only on whole
 *       words from the stored name and aliases, never on an entity id (an id-shaped name is refused), and a
 *       name that fits several devices is never narrowed by preference except for an exact full-name match;</li>
 *   <li>the direction is not guarded for Ask (GUARDED, D27 Q-OC-2: valve open, lock unlock);</li>
 *   <li>the device declares the capability the action needs (UNSUPPORTED_CAPABILITY);</li>
 *   <li>the value and unit are present, understood and inside the safe range (INVALID_VALUE);</li>
 *   <li>the device is enabled and online (OFFLINE) and has been observed recently (STALE).</li>
 * </ol>
 * Valves and locks are recognised the way HaServiceController does, by their id or name, because no device
 * type marks them. No executor is reachable from here.
 */
@Component
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    static final int MAX_CANDIDATES = 5;
    /** Setpoint bounds in degrees Celsius (41 to 86 degrees Fahrenheit). */
    static final BigDecimal MIN_SETPOINT_C = new BigDecimal("5");
    static final BigDecimal MAX_SETPOINT_C = new BigDecimal("30");

    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "my", "our");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d{1,3}(\\.\\d+)?$");
    private static final Pattern VALVE = Pattern.compile("(?i)valve");
    private static final Pattern LOCK = Pattern.compile("(?i)lock");
    private static final Pattern ID_SHAPED = Pattern.compile("[_.]|^[a-z0-9]+(-[a-z0-9]+)+$");

    private final Clock clock;
    private final Duration maxInventoryAge;
    private final Duration maxObservationAge;

    @Autowired
    public EntityResolver(@Value("${cabin.ask.resolver.max-inventory-age-seconds:120}") long maxInventoryAgeSeconds,
                          @Value("${cabin.ask.resolver.max-observation-age-hours:6}") long maxObservationAgeHours) {
        this(Clock.systemUTC(), Duration.ofSeconds(maxInventoryAgeSeconds), Duration.ofHours(maxObservationAgeHours));
    }

    EntityResolver(Clock clock, Duration maxInventoryAge, Duration maxObservationAge) {
        this.clock = clock;
        this.maxInventoryAge = maxInventoryAge;
        this.maxObservationAge = maxObservationAge;
    }

    public Resolution resolve(SemanticTarget target, ActionRequest action, AssistantInventory inventory, HouseholdRole role) {
        Resolution resolution = decide(target, action, inventory, role);
        log.info("EntityResolver outcome={} target={} action={} inventoryVersion={} reasons={}",
            resolution.outcome(), describe(target), action == null ? null : action.kind(),
            resolution.inventoryVersion(), resolution.reasons());
        return resolution;
    }

    private Resolution decide(SemanticTarget target, ActionRequest action, AssistantInventory inventory, HouseholdRole role) {
        String version = inventory == null ? null : inventory.version();
        if (!AssistantInventoryService.mayRead(role)) {
            return refuse(Outcome.UNAUTHORIZED, version, List.of(), "role " + role + " may not use the assistant");
        }
        if (inventory == null || inventory.generatedAt() == null) {
            return refuse(Outcome.STALE, version, List.of(), "there is no inventory snapshot to resolve against");
        }
        Duration age = Duration.between(inventory.generatedAt(), clock.instant());
        if (age.compareTo(maxInventoryAge) > 0) {
            return refuse(Outcome.STALE, version, List.of(),
                "the inventory snapshot is " + age.toSeconds() + "s old, the limit is " + maxInventoryAge.toSeconds() + "s");
        }
        if (action == null || target == null) {
            return refuse(Outcome.INVALID_VALUE, version, List.of(), "the request has no target or no action");
        }
        if (action.kind() == ActionKind.UNRECOGNIZED) {
            String why = action.negated()
                ? "the state wording is a negation, which is not turned into an action"
                : "the state wording is not one of on, off, open, close, lock, unlock";
            return refuse(Outcome.INVALID_VALUE, version, List.of(), why);
        }

        // 3. The words must name exactly one device.
        List<String> reasons = new ArrayList<>();
        List<Tokens> nameTokens = nameQueries(target.deviceName());
        if (blank(target.deviceName()) || nameTokens.isEmpty()) {
            return refuse(Outcome.NOT_FOUND, version, List.of(), "the request names no device");
        }
        if (idShaped(target.deviceName(), inventory)) {
            return refuse(Outcome.NOT_FOUND, version, List.of(),
                "entity ids are not accepted as a device name; say the device's name");
        }
        List<Entry> byName = inventory.entries().stream()
            .filter(e -> matchesName(e, nameTokens.get(0)))
            .filter(e -> matchesLocation(e, target.location()))
            .toList();
        if (byName.isEmpty()) {
            return refuse(Outcome.NOT_FOUND, version, List.of(), "no device in the inventory fits \""
                + sanitize(target.deviceName()) + "\"" + (blank(target.location()) ? "" : " in \"" + sanitize(target.location()) + "\""));
        }
        List<Entry> matches = byName;
        if (!blank(target.site())) {
            matches = byName.stream().filter(e -> target.site().trim().equalsIgnoreCase(e.site())).toList();
            if (matches.isEmpty()) {
                return refuse(Outcome.WRONG_SITE, version, cap(byName),
                    "\"" + sanitize(target.deviceName()) + "\" exists, but not at site \"" + sanitize(target.site()) + "\"");
            }
        }
        if (matches.size() > 1) {
            List<Entry> exact = matches.stream().filter(e -> exactName(e, nameTokens.get(0))).toList();
            if (exact.size() == 1) {
                matches = exact;
                reasons.add("several devices fit the words, one has exactly that name");
            } else {
                return refuse(Outcome.AMBIGUOUS, version, cap(matches), matches.size() + " devices fit \""
                    + sanitize(target.deviceName()) + "\"; name the room or the site");
            }
        }
        Entry device = matches.get(0);
        reasons.add("matched " + device.entityId() + " (" + device.site() + ")");

        // 4. Guards (D27 Q-OC-2): decided by direction, before anything about the device's state.
        String guard = guardedReason(device, action.kind());
        if (guard != null) {
            return refuse(Outcome.GUARDED, version, List.of(device), reasons, guard);
        }

        // 5. Capability.
        String capabilityGap = capabilityGap(device, action);
        if (capabilityGap != null) {
            return refuse(Outcome.UNSUPPORTED_CAPABILITY, version, List.of(device), reasons, capabilityGap);
        }

        // 6. Value and unit.
        ResolvedAction resolvedAction;
        if (action.kind() == ActionKind.SET_ATTRIBUTE) {
            ValueCheck check = checkTemperature(action);
            if (check.problem != null) {
                return refuse(Outcome.INVALID_VALUE, version, List.of(device), reasons, check.problem);
            }
            resolvedAction = check.action;
            reasons.add("setpoint " + action.value() + " " + action.unit() + " is " + check.action.canonicalValue() + " C, inside the safe range");
        } else {
            resolvedAction = new ResolvedAction(action.kind(), null, null, null, null, null);
        }

        // 7. Availability and freshness of the device itself.
        if (!device.enabled()) {
            return refuse(Outcome.OFFLINE, version, List.of(device), reasons, device.entityId() + " is disabled");
        }
        if ("OFFLINE".equalsIgnoreCase(device.availability())) {
            return refuse(Outcome.OFFLINE, version, List.of(device), reasons, device.entityId() + " is offline");
        }
        if ("UNKNOWN".equalsIgnoreCase(device.availability()) || device.lastObserved() == null) {
            return refuse(Outcome.STALE, version, List.of(device), reasons, device.entityId() + " has not reported a state yet");
        }
        Duration observedAge = Duration.between(device.lastObserved(), clock.instant());
        if (observedAge.compareTo(maxObservationAge) > 0) {
            return refuse(Outcome.STALE, version, List.of(device), reasons,
                device.entityId() + " was last heard from " + observedAge.toMinutes() + " minutes ago, the limit is "
                    + maxObservationAge.toMinutes());
        }
        reasons.add("online, last observed " + observedAge.toSeconds() + "s ago");
        return new Resolution(Outcome.RESOLVED, device, List.of(device), resolvedAction, version, List.copyOf(reasons));
    }

    // ── guards ───────────────────────────────────────────────────────────

    private static String guardedReason(Entry device, ActionKind kind) {
        boolean valve = isValve(device);
        boolean lock = isLock(device);
        if (kind == ActionKind.UNLOCK) {
            return "unlocking is not available from Ask";
        }
        if (valve && (kind == ActionKind.OPEN || kind == ActionKind.TURN_ON)) {
            return "opening a valve is not available from Ask";
        }
        if (valve && kind == ActionKind.TURN_OFF) {
            return "on and off do not say which way a valve moves; say close";
        }
        if (lock && (kind == ActionKind.TURN_ON || kind == ActionKind.TURN_OFF)) {
            return "on and off do not say which way a lock moves; say lock";
        }
        return null;
    }

    static boolean isValve(Entry e) {
        return VALVE.matcher(e.entityId()).find() || VALVE.matcher(String.valueOf(e.name())).find()
            || e.aliases().stream().anyMatch(a -> VALVE.matcher(a).find());
    }

    static boolean isLock(Entry e) {
        return "LOCK".equals(e.deviceClass()) || LOCK.matcher(e.entityId()).find() || LOCK.matcher(String.valueOf(e.name())).find()
            || e.aliases().stream().anyMatch(a -> LOCK.matcher(a).find());
    }

    // ── capability ───────────────────────────────────────────────────────

    private static String capabilityGap(Entry device, ActionRequest action) {
        Set<String> caps = new LinkedHashSet<>(device.capabilities());
        switch (action.kind()) {
            case READ:
                return null;
            case TURN_ON: case TURN_OFF: case OPEN: case CLOSE:
                return caps.contains("COMMAND") ? null : device.entityId() + " accepts no commands";
            case LOCK:
                return caps.contains("COMMAND") && caps.contains("ACCESS_CONTROL") ? null
                    : device.entityId() + " is not a lock that accepts commands";
            case SET_ATTRIBUTE:
                if (!"temperature".equals(normalizeAttribute(action.attribute()))) {
                    return "the platform declares no capability to set \"" + sanitize(action.attribute()) + "\"";
                }
                return caps.contains("COMMAND") && caps.contains("CLIMATE") && "THERMOSTAT".equals(device.deviceClass()) ? null
                    : device.entityId() + " is not a thermostat that accepts a setpoint";
            default:
                return "no capability maps to this action";
        }
    }

    private static String normalizeAttribute(String attribute) {
        if (attribute == null) return "";
        String a = attribute.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "temperature", "temp", "setpoint", "target temperature", "set point" -> "temperature";
            default -> a;
        };
    }

    // ── value and unit ───────────────────────────────────────────────────

    private record ValueCheck(String problem, ResolvedAction action) {}

    private static ValueCheck checkTemperature(ActionRequest action) {
        String unit = normalizeTemperatureUnit(action.unit());
        if (blank(action.unit())) {
            return new ValueCheck("a temperature needs a unit, Celsius or Fahrenheit", null);
        }
        if (unit == null) {
            return new ValueCheck("temperature unit \"" + sanitize(action.unit()) + "\" is not Celsius or Fahrenheit", null);
        }
        String raw = action.value() == null ? "" : action.value().trim();
        if (!NUMBER.matcher(raw).matches()) {
            return new ValueCheck("temperature value \"" + sanitize(raw) + "\" is not a plain number", null);
        }
        BigDecimal number = new BigDecimal(raw);
        BigDecimal celsius = "F".equals(unit)
            ? number.subtract(new BigDecimal("32")).multiply(new BigDecimal("5")).divide(new BigDecimal("9"), 4, RoundingMode.HALF_UP)
            : number;
        celsius = celsius.setScale(1, RoundingMode.HALF_UP);
        if (celsius.compareTo(MIN_SETPOINT_C) < 0 || celsius.compareTo(MAX_SETPOINT_C) > 0) {
            return new ValueCheck(raw + " " + unit + " is " + celsius.toPlainString() + " C, outside the safe setpoint range of "
                + MIN_SETPOINT_C + " to " + MAX_SETPOINT_C + " C", null);
        }
        return new ValueCheck(null, new ResolvedAction(ActionKind.SET_ATTRIBUTE, "temperature", celsius.toPlainString(), "C", raw, unit));
    }

    private static String normalizeTemperatureUnit(String unit) {
        if (unit == null) return null;
        String u = unit.trim().toLowerCase(Locale.ROOT).replace("°", "").replace("degrees", "").replace("degree", "")
            .replace("deg", "").trim();
        return switch (u) {
            case "f", "fahrenheit" -> "F";
            case "c", "celsius", "centigrade" -> "C";
            default -> null;
        };
    }

    // ── name and location matching ───────────────────────────────────────

    /** A name or phrase broken into lower-case whole words, with stop words dropped and a trailing plural s folded. */
    record Tokens(List<String> words) {
        boolean isEmpty() { return words.isEmpty(); }
        boolean within(Tokens other) { return !words.isEmpty() && other.words.containsAll(words); }
    }

    static Tokens tokens(String text) {
        if (text == null) return new Tokens(List.of());
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (word.isEmpty() || STOP_WORDS.contains(word)) continue;
            words.add(word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1) : word);
        }
        return new Tokens(words);
    }

    private static List<Tokens> nameQueries(String deviceName) {
        Tokens t = tokens(deviceName);
        return t.isEmpty() ? List.of() : List.of(t);
    }

    private static List<Tokens> namesOf(Entry e) {
        List<Tokens> names = new ArrayList<>();
        names.add(tokens(e.name()));
        e.aliases().forEach(a -> names.add(tokens(a)));
        return names;
    }

    private static boolean matchesName(Entry e, Tokens query) {
        return namesOf(e).stream().anyMatch(query::within);
    }

    private static boolean exactName(Entry e, Tokens query) {
        return namesOf(e).stream().anyMatch(n -> !n.isEmpty() && n.words().equals(query.words()));
    }

    private static boolean matchesLocation(Entry e, String location) {
        if (blank(location)) return true;
        Tokens wanted = tokens(location);
        if (wanted.isEmpty()) return true;
        if (wanted.within(tokens(e.room())) || wanted.within(tokens(e.area()))) return true;
        return namesOf(e).stream().anyMatch(wanted::within);
    }

    /** True when the "name" is really an entity id or is shaped like one (snake_case, dotted, kebab-case). */
    private static boolean idShaped(String deviceName, AssistantInventory inventory) {
        String name = deviceName.trim();
        if (inventory.entries().stream().anyMatch(e -> e.entityId().equalsIgnoreCase(name))) return true;
        return ID_SHAPED.matcher(name).find();
    }

    // ── result helpers ───────────────────────────────────────────────────

    private static List<Entry> cap(List<Entry> entries) {
        return entries.stream().sorted(Comparator.comparing(Entry::entityId)).limit(MAX_CANDIDATES).toList();
    }

    private static Resolution refuse(Outcome outcome, String version, List<Entry> candidates, String reason) {
        return new Resolution(outcome, null, candidates, null, version, List.of(reason));
    }

    private static Resolution refuse(Outcome outcome, String version, List<Entry> candidates, List<String> earlier, String reason) {
        List<String> reasons = new ArrayList<>(earlier);
        reasons.add(reason);
        return new Resolution(outcome, null, candidates, null, version, List.copyOf(reasons));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** User words go into reasons and logs: strip control characters and cap the length. */
    static String sanitize(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return flat.length() > 60 ? flat.substring(0, 60) + "..." : flat;
    }

    private static String describe(SemanticTarget t) {
        return t == null ? "null" : "{device=" + sanitize(t.deviceName()) + ", location=" + sanitize(t.location())
            + ", site=" + sanitize(t.site()) + "}";
    }
}
