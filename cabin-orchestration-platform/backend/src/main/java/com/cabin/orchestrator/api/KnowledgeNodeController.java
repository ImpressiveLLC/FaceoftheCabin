package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.KbGeneratorService;
import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import org.springframework.web.bind.annotation.*;

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
}
