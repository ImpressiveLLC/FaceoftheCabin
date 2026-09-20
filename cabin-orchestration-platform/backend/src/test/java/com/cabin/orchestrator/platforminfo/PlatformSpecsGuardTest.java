package com.cabin.orchestrator.platforminfo;

import com.cabin.orchestrator.platforminfo.PlatformSpecsCatalog.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps platform-specs.yaml (Config > Platform) in step with the repo, which is the
 * source of truth for code. Every container image, base image, Maven / npm / pip pin
 * and lockfile version under cabin-orchestration-platform/ and family-hub/ must be
 * listed there with the same value, and every entry there must still exist in its
 * source. So adding a dependency, bumping a tag or dropping a service fails the build
 * until the specs say so too (docs/DEFINITION_OF_DONE.md, section 3).
 *
 * Out of scope: docs/ai-assistant/training (the POC1 fine-tuning image), which is not
 * deployed or run yet.
 */
class PlatformSpecsGuardTest {

    private record Pin(String kind, String key, String declared, String file) {
        @Override public String toString() { return kind + " " + key + " = " + (declared.isEmpty() ? "(untagged)" : declared) + "  [" + file + "]"; }
    }

    private static final List<String> SCOPE = List.of("cabin-orchestration-platform", "family-hub");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformSpecsCatalog catalog = new PlatformSpecsCatalog();

    // ---- what the repo pins ----

    @Test
    void everyVersionPinnedInTheRepoIsListedInThePlatformSpecs() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Pin pin : pins()) {
            Item entry = entryFor(pin.kind(), pin.key());
            if (entry == null) {
                problems.add("NOT LISTED: " + pin);
            } else if (!entry.sources().contains(pin.file())) {
                problems.add("SOURCE MISSING from '" + entry.id() + "': " + pin);
            } else if (!pin.declared().equals(entry.declared())) {
                problems.add("VALUE DIFFERS for '" + entry.id() + "': source says '" + pin.declared() + "', platform-specs.yaml says '" + entry.declared() + "'  [" + pin.file() + "]");
            }
        }
        assertTrue(problems.isEmpty(), instructions("Versions in the repo that platform-specs.yaml does not match", problems));
    }

    @Test
    void everyPlatformSpecsEntryStillExistsInItsSource() throws Exception {
        Set<String> present = new HashSet<>();
        for (Pin pin : pins()) present.add(pin.kind() + "|" + pin.key() + "|" + pin.file() + "|" + pin.declared());
        List<String> problems = new ArrayList<>();
        for (Item item : catalog.items()) {
            if ("host".equals(item.kind())) continue;
            for (String key : item.keys()) {
                for (String source : item.sources()) {
                    if (!present.contains(item.kind() + "|" + key + "|" + source + "|" + item.declared())) {
                        problems.add("'" + item.id() + "' (" + item.kind() + " " + key + " = " + item.declared() + ") is not in " + source);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), instructions("Entries in platform-specs.yaml that no longer match the repo", problems));
    }

    @Test
    void theScanFindsTheRepoSoTheChecksAboveCannotPassVacuously() throws Exception {
        List<Pin> pins = pins();
        assertTrue(pins.size() >= 50, "expected at least 50 pins, found " + pins.size() + " (is the repo root being found?)");
        Set<String> seen = new HashSet<>();
        pins.forEach(p -> seen.add(p.kind() + "|" + p.key()));
        for (String expected : List.of("image|grafana/grafana:latest", "image|confluentinc/cp-kafka:7.6.1", "maven-property|spring.boot.version",
                "maven-property|java.version", "maven|kafka-clients", "npm|react", "npm|vitest", "pip|fastapi", "project|orchestrator")) {
            assertTrue(seen.contains(expected), "scan did not find " + expected);
        }
    }

    // ---- what the entries claim ----

    @Test
    void theTrackOfEveryEntryMatchesWhatItsDeclaredVersionSays() {
        List<String> problems = new ArrayList<>();
        for (Item item : catalog.items()) {
            String expected = SpecTracking.classify(item.kind(), item.declared());
            if (!expected.equals(item.track())) {
                problems.add("'" + item.id() + "' declares '" + item.declared() + "', which is " + expected + ", but track says " + item.track());
            }
        }
        assertTrue(problems.isEmpty(), instructions("Entries labelled with the wrong track", problems));
    }

    @Test
    void lockedNpmVersionsMatchThePackageLock() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Item item : catalog.items()) {
            if (!"npm".equals(item.kind())) continue;
            for (String key : item.keys()) {
                for (String source : item.sources()) {
                    String locked = lockedVersion(repoRoot().resolve(source), key);
                    if (locked == null && item.locked() != null) problems.add("'" + item.id() + "' says locked " + item.locked() + " but " + source + " has no lockfile entry for " + key);
                    else if (locked != null && !locked.equals(item.locked())) problems.add("'" + item.id() + "' says locked " + item.locked() + ", package-lock.json has " + locked + " (" + key + ")");
                }
            }
        }
        assertTrue(problems.isEmpty(), instructions("Locked versions that differ from package-lock.json", problems));
    }

    @Test
    void theCatalogIsWellFormed() {
        Set<String> ids = new HashSet<>();
        List<String> problems = new ArrayList<>();
        assertTrue(!catalog.groups().isEmpty());
        catalog.groups().forEach(g -> { if (g.items().isEmpty()) problems.add("group '" + g.id() + "' is empty"); });
        for (Item i : catalog.items()) {
            if (!ids.add(i.id())) problems.add("duplicate id " + i.id());
            if (i.name() == null || i.name().isBlank()) problems.add(i.id() + ": no name");
            if (!PlatformSpecsCatalog.KINDS.contains(i.kind())) problems.add(i.id() + ": unknown kind " + i.kind());
            if (!PlatformSpecsCatalog.TRACKS.contains(i.track())) problems.add(i.id() + ": unknown track " + i.track());
            if (i.declared() == null || i.declared().isBlank()) problems.add(i.id() + ": no declared version");
            if (i.live() != null && !PlatformSpecsCatalog.LIVE_KEYS.contains(i.live())) problems.add(i.id() + ": unknown live probe " + i.live());
            if ("host".equals(i.kind())) {
                if (!"unmanaged".equals(i.track())) problems.add(i.id() + ": host software must be 'unmanaged'");
                if (!i.sources().isEmpty()) problems.add(i.id() + ": host software has no source file");
            } else {
                if (i.keys().isEmpty()) problems.add(i.id() + ": no keys");
                if (i.sources().isEmpty()) problems.add(i.id() + ": no sources");
                if ("unmanaged".equals(i.track())) problems.add(i.id() + ": only host software is 'unmanaged'");
                for (String s : i.sources()) if (!Files.isRegularFile(repoRoot().resolve(s))) problems.add(i.id() + ": source not found " + s);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void trackingRuleClassifiesTheTagShapesInUse() {
        assertEquals("floating", SpecTracking.classify("image", "latest"));
        assertEquals("floating", SpecTracking.classify("image", "stable"));
        assertEquals("floating", SpecTracking.classify("image", "main"));
        assertEquals("floating", SpecTracking.classify("image", "alpine"));
        assertEquals("floating", SpecTracking.classify("image", "latest-pg16"));
        assertEquals("floating", SpecTracking.classify("image", ""));
        assertEquals("series", SpecTracking.classify("image", "2"));
        assertEquals("series", SpecTracking.classify("image", "20-alpine"));
        assertEquals("series", SpecTracking.classify("image", "3.12-slim"));
        assertEquals("series", SpecTracking.classify("image", "3.9-eclipse-temurin-21-alpine"));
        assertEquals("pinned", SpecTracking.classify("image", "7.6.1"));
        assertEquals("pinned", SpecTracking.classify("image", "v1.62.0-noble"));
        assertEquals("floating", SpecTracking.classify("npm", "latest"));
        assertEquals("series", SpecTracking.classify("npm", "^3"));
        assertEquals("series", SpecTracking.classify("npm", "~5.10.0"));
        assertEquals("pinned", SpecTracking.classify("npm", "1.62.0"));
        assertEquals("pinned", SpecTracking.classify("maven", "3.7.0"));
        assertEquals("pinned", SpecTracking.classify("pip", "0.115.6"));
        assertEquals("unmanaged", SpecTracking.classify("host", "not pinned in Git"));
    }

    // ---- reading the repo ----

    private Item entryFor(String kind, String key) {
        return catalog.items().stream().filter(i -> i.kind().equals(kind) && i.keys().contains(key)).findFirst().orElse(null);
    }

    private static String instructions(String what, List<String> problems) {
        return what + " (" + problems.size() + "). Edit cabin-orchestration-platform/backend/src/main/resources/platform-specs.yaml in the same commit:\n  "
            + String.join("\n  ", problems);
    }

    private static Path repoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            final Path here = cursor;
            if (SCOPE.stream().allMatch(d -> Files.isDirectory(here.resolve(d))) && Files.isDirectory(here.resolve("docs"))) return here;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Path.of("").toAbsolutePath());
    }

    private static String rel(Path file) {
        return repoRoot().relativize(file).toString().replace('\\', '/');
    }

    private List<Pin> pins() throws Exception {
        List<Pin> out = new ArrayList<>();
        for (Path file : filesInScope()) {
            String name = file.getFileName().toString();
            if (name.equals("pom.xml")) out.addAll(mavenPins(file));
            else if (name.equals("package.json")) out.addAll(npmPins(file));
            else if (name.startsWith("requirements") && name.endsWith(".txt")) out.addAll(pipPins(file));
            else if (name.startsWith("Dockerfile")) out.addAll(dockerfilePins(file));
            else if (name.endsWith(".yml") || name.endsWith(".yaml")) out.addAll(composePins(file));
        }
        return out;
    }

    private List<Path> filesInScope() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : SCOPE) {
            try (Stream<Path> walk = Files.walk(repoRoot().resolve(dir))) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> !rel(p).contains("/node_modules/") && !rel(p).contains("/target/"))
                    .forEach(files::add);
            }
        }
        return files;
    }

    private static Pin imagePin(String ref, String file) {
        int colon = ref.lastIndexOf(':');
        boolean tagged = colon > ref.lastIndexOf('/');
        return new Pin("image", ref, tagged ? ref.substring(colon + 1) : "", file);
    }

    private static final Pattern COMPOSE_IMAGE = Pattern.compile("^\\s*image:\\s*[\"']?([^\\s\"']+)");
    private static final Pattern UNPINNED_INSTALL = Pattern.compile("apk add|apt-get install|npm install -g|pip install");
    private static final Pattern FROM = Pattern.compile("^FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+(\\S+))?", Pattern.CASE_INSENSITIVE);

    private List<Pin> composePins(Path file) throws IOException {
        List<Pin> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = COMPOSE_IMAGE.matcher(line);
            // Images built from this repo carry ${IMAGE_TAG}: they are the Application entries, not third-party pins.
            if (m.find() && !m.group(1).contains("${")) out.add(imagePin(m.group(1), rel(file)));
        }
        return out;
    }

    private List<Pin> dockerfilePins(Path file) throws IOException {
        List<Pin> out = new ArrayList<>();
        Set<String> stages = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = FROM.matcher(line);
            if (!m.find()) continue;
            String ref = m.group(1);
            if (!ref.equals("scratch") && !stages.contains(ref)) out.add(imagePin(ref, rel(file)));
            if (m.group(2) != null) stages.add(m.group(2));
        }
        // Something installed at build time with no version at all (a package manager pulling
        // "whatever is newest"). Installs from a requirements file are pinned by that file.
        for (String line : Files.readAllLines(file)) {
            String run = line.trim();
            if (run.startsWith("RUN ") && UNPINNED_INSTALL.matcher(run).find() && !run.contains(" -r ")) {
                out.add(new Pin("build-step", run.substring(4).trim(), "latest", rel(file)));
            }
        }
        return out;
    }

    private List<Pin> pipPins(Path file) throws IOException {
        Pattern pin = Pattern.compile("^([A-Za-z0-9_.\\-]+)(?:\\[[^\\]]*\\])?==([^\\s;#]+)");
        List<Pin> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = pin.matcher(line.trim());
            if (m.find()) out.add(new Pin("pip", m.group(1).toLowerCase(), m.group(2), rel(file)));
        }
        return out;
    }

    private List<Pin> npmPins(Path file) throws IOException {
        JsonNode pkg = JSON.readTree(file.toFile());
        List<Pin> out = new ArrayList<>();
        if (pkg.hasNonNull("name") && pkg.hasNonNull("version")) out.add(new Pin("project", pkg.get("name").asText(), pkg.get("version").asText(), rel(file)));
        for (String section : List.of("dependencies", "devDependencies")) {
            JsonNode deps = pkg.get(section);
            if (deps != null) deps.fields().forEachRemaining(e -> out.add(new Pin("npm", e.getKey(), e.getValue().asText(), rel(file))));
        }
        return out;
    }

    private static String lockedVersion(Path packageJson, String name) throws IOException {
        Path lock = packageJson.resolveSibling("package-lock.json");
        if (!Files.isRegularFile(lock)) return null;
        JsonNode node = JSON.readTree(lock.toFile()).path("packages").path("node_modules/" + name).path("version");
        return node.isMissingNode() ? null : node.asText();
    }

    private List<Pin> mavenPins(Path file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
        Element project = doc.getDocumentElement();
        List<Pin> out = new ArrayList<>();
        String artifact = childText(project, "artifactId"), version = childText(project, "version");
        if (artifact != null && version != null) out.add(new Pin("project", artifact, version, rel(file)));
        for (Node props : children(project, "properties")) {
            NodeList kids = props.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element e && e.getTagName().endsWith(".version")) out.add(new Pin("maven-property", e.getTagName(), e.getTextContent().trim(), rel(file)));
            }
        }
        for (String tag : List.of("dependency", "plugin")) {
            NodeList all = doc.getElementsByTagName(tag);
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                String a = childText(e, "artifactId"), v = childText(e, "version");
                if (a != null && v != null && !v.startsWith("${")) out.add(new Pin("maven", a, v, rel(file)));
            }
        }
        return out;
    }

    private static List<Node> children(Element parent, String tag) {
        List<Node> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) if (kids.item(i) instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        return out;
    }

    private static String childText(Element parent, String tag) {
        List<Node> found = children(parent, tag);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }
}
