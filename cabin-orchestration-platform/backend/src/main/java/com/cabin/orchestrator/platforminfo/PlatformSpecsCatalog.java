package com.cabin.orchestrator.platforminfo;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The checked-in list of everything versioned that this platform is built from or
 * runs on (platform-specs.yaml), shown under Config > Platform. PlatformSpecsGuardTest
 * keeps it in step with the pins in the repo, so it cannot quietly go stale.
 */
@Component
public class PlatformSpecsCatalog {

    public static final String RESOURCE = "platform-specs.yaml";

    /** Running-version probes PlatformInfoService knows how to run; a catalog entry may only name one of these. */
    public static final Set<String> LIVE_KEYS =
        Set.of("java", "spring-boot", "cabin-backend", "postgres", "home-assistant", "zigbee2mqtt", "ollama");

    public static final Set<String> KINDS = Set.of("image", "maven-property", "maven", "npm", "pip", "project", "build-step", "host");
    public static final Set<String> TRACKS = Set.of("pinned", "series", "floating", "unmanaged");

    public record Item(String id, String name, String kind, List<String> keys, String declared, String track,
                       List<String> sources, String locked, String live, String note) {}

    public record Group(String id, String label, List<Item> items) {}

    private final List<Group> groups;

    public PlatformSpecsCatalog() {
        try (InputStream in = PlatformSpecsCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            this.groups = parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }

    public List<Group> groups() {
        return groups;
    }

    public List<Item> items() {
        return groups.stream().flatMap(g -> g.items().stream()).toList();
    }

    @SuppressWarnings("unchecked")
    public static List<Group> parse(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);
        List<Group> out = new ArrayList<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) root.get("groups")) {
            List<Item> items = new ArrayList<>();
            for (Map<String, Object> i : (List<Map<String, Object>>) g.get("items")) {
                items.add(new Item(str(i.get("id")), str(i.get("name")), str(i.get("kind")),
                    strings(i.get("keys")), str(i.get("declared")), str(i.get("track")),
                    strings(i.get("sources")), str(i.get("locked")), str(i.get("live")), str(i.get("note"))));
            }
            out.add(new Group(str(g.get("id")), str(g.get("label")), List.copyOf(items)));
        }
        return List.copyOf(out);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        return ((List<Object>) o).stream().map(String::valueOf).toList();
    }
}
