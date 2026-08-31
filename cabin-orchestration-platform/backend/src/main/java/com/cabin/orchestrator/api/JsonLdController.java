package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.JsonLdService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** D1 (docs/ontology/DECISIONS.md): makes "cabin:{entity_id}" a real, dereferenceable JSON-LD identity. */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class JsonLdController {

    private static final MediaType JSON_LD = MediaType.parseMediaType("application/ld+json");
    private final JsonLdService jsonLdService;

    public JsonLdController(JsonLdService jsonLdService) {
        this.jsonLdService = jsonLdService;
    }

    /** GET /api/context/cabin-context.jsonld -- the @context every device JSON-LD document references. */
    @GetMapping(value = "/context/cabin-context.jsonld")
    public ResponseEntity<Resource> context() {
        return ResponseEntity.ok().contentType(JSON_LD)
            .body(new ClassPathResource("context/cabin-context.jsonld"));
    }

    /** GET /api/devices/{deviceId}/jsonld -- one device's own JSON-LD representation. */
    @GetMapping(value = "/devices/{deviceId}/jsonld")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String deviceId) {
        return jsonLdService.deviceAsJsonLd(deviceId)
            .map(node -> ResponseEntity.ok().contentType(JSON_LD).body(node))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
