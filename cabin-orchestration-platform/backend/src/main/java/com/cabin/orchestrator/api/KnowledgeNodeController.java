package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.KbGeneratorService;
import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
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

    /** GET /api/kb/nodes -- every KnowledgeNode, for the Tiny Helpdesk (or anything else) to consume. */
    @GetMapping("/nodes")
    public List<KnowledgeNode> nodes() {
        return repository.loadAll();
    }

    /** GET /api/kb/nodes/{entityRef} -- one device's KnowledgeNodes. */
    @GetMapping("/nodes/{entityRef}")
    public List<KnowledgeNode> nodesFor(@PathVariable String entityRef) {
        return repository.findByEntityRef(entityRef);
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
