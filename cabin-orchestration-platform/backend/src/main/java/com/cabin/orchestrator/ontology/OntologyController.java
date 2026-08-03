package com.cabin.orchestrator.ontology;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Read-only, unauthenticated -- same tier as /api/events and /api/devices; entity labels aren't sensitive. */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyController {

    private final OntologyLookupService lookupService;

    public OntologyController(OntologyLookupService lookupService) {
        this.lookupService = lookupService;
    }

    /** GET /api/ontology/entities?ids=nvr_frigate,camera_front_door_reolink */
    @GetMapping("/entities")
    public List<OntologyEntitySummary> entities(@RequestParam(name = "ids") String ids) {
        List<String> idList = List.of(ids.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return lookupService.lookup(idList);
    }
}
