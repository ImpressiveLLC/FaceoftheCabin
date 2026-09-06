package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.KbGeneratorService;
import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.CredentialPointerRedactor;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** KB Generator v1 (issue #32) -- read and (re)generate the Tiny Helpdesk's KnowledgeNode content. */
@RestController
@RequestMapping("/api/kb")
@CrossOrigin
public class KnowledgeNodeController {

    private final KbGeneratorService generator;
    private final KnowledgeNodeRepository repository;

    public KnowledgeNodeController(KbGeneratorService generator, KnowledgeNodeRepository repository) {
        this.generator = generator;
        this.repository = repository;
    }

    /** POST /api/kb/regenerate -- regenerates every in-scope device's KnowledgeNodes. */
    @PostMapping("/regenerate")
    public Map<String, Integer> regenerate() {
        return Map.of("chunksWritten", generator.regenerateAll());
    }

    /** POST /api/kb/regenerate/{deviceId} -- regenerates just one device, e.g. right after it's confirmed. */
    @PostMapping("/regenerate/{deviceId}")
    public Map<String, Integer> regenerateOne(@PathVariable String deviceId) {
        return Map.of("chunksWritten", generator.regenerateFor(deviceId));
    }

    // Sprint 5 WSJF #2 (r7 handover): points external tooling/LLM
    // tool-call schemas at the ontology's own machine-readable vocabulary.
    // Reuses D1's existing /api/context/cabin-context.jsonld (JsonLdController)
    // rather than standing up a second, competing context document at a
    // different IRI base -- see that file's own comment for why a second
    // "cabin.*" namespace would fragment, not extend, the one D1 already
    // made real and dereferenceable. Link header only, not embedded inline
    // per the handover's own instruction (avoids inflating every response).
    private static final String JSON_LD_CONTEXT_LINK =
        "</api/context/cabin-context.jsonld>; rel=\"http://www.w3.org/ns/json-ld#context\"; type=\"application/ld+json\"";

    /**
     * GET /api/kb/nodes -- every KnowledgeNode, for the Tiny Helpdesk (or
     * anything else) to consume. This route stays open per WebConfig's
     * GET carve-out (unauthenticated callers included) -- WSJF #8's
     * CREDENTIAL_POINTER redaction is applied here too, not just in the
     * Tiny Helpdesk answer path, since this is the exact same data and
     * leaving this listing unfiltered would make that gate a one-line
     * bypass. An unauthenticated caller has no HouseholdRole attribute at
     * all (null), which redacts exactly like any non-administrator.
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<KnowledgeNode>> nodes(HttpServletRequest request) {
        HouseholdRole role = roleOf(request);
        List<KnowledgeNode> nodes = repository.loadAll().stream()
            .map(node -> CredentialPointerRedactor.redact(node, role)).toList();
        return ResponseEntity.ok().header("Link", JSON_LD_CONTEXT_LINK).body(nodes);
    }

    /** GET /api/kb/nodes/{entityRef} -- one device's KnowledgeNodes. Same redaction as nodes() above. */
    @GetMapping("/nodes/{entityRef}")
    public List<KnowledgeNode> nodesFor(@PathVariable String entityRef, HttpServletRequest request) {
        HouseholdRole role = roleOf(request);
        return repository.findByEntityRef(entityRef).stream().map(node -> CredentialPointerRedactor.redact(node, role)).toList();
    }

    private static HouseholdRole roleOf(HttpServletRequest request) {
        return (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
    }

    /**
     * POST /api/kb/curate -- the one path that writes MANUALLY_CURATED
     * content (D5, docs/ontology/DECISIONS.md). Source is always forced to
     * MANUALLY_CURATED regardless of what's in the request body -- this
     * endpoint is a person's deliberate action, so there's no legitimate
     * reason to accept AUTO_GENERATED through it; KbGeneratorService never
     * overwrites a node this endpoint has written for the same
     * entityRef+chunkType (see its own comment).
     */
    @PostMapping("/curate")
    public KnowledgeNode curate(@RequestBody Map<String, String> body) {
        String entityRef = body.get("entityRef");
        KnowledgeChunkType chunkType = KnowledgeChunkType.valueOf(body.get("chunkType").toUpperCase());
        String content = body.get("content");
        KnowledgeNode node = new KnowledgeNode(entityRef, chunkType, content, KnowledgeSource.MANUALLY_CURATED, Instant.now());
        repository.upsert(node);
        return node;
    }
}
