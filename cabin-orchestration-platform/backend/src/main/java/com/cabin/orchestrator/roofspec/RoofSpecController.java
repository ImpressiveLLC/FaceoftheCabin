package com.cabin.orchestrator.roofspec;

import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Comments and requested inputs for the Roof Monitoring page. The "roof_spec"
 * share link may GET this list and POST exactly /entries (see
 * GoogleAuthInterceptor.handleGuestToken); nothing else, and DELETE is
 * signed-in only. Author is derived server-side, never read from the body.
 */
@RestController
@RequestMapping("/api/roof-spec")
@CrossOrigin
public class RoofSpecController {

    public record EntryView(String id, String kind, String itemKey, String body, String author, String authorKind, Instant createdAt) {}

    private final RoofSpecService service;
    private final CabinAccessTokenService tokens;

    public RoofSpecController(RoofSpecService service, CabinAccessTokenService tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @GetMapping("/entries")
    public List<EntryView> list() {
        return service.list().stream().map(RoofSpecController::view).toList();
    }

    @PostMapping("/entries")
    public EntryView add(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String guestId = (String) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_GUEST_TOKEN_ID);
        String author, authorKind;
        if (guestId != null) {
            author = tokens.list().stream().filter(t -> t.id().equals(guestId)).map(CabinAccessToken::label).findFirst().orElse(null);
            authorKind = "guest";
        } else {
            author = (String) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
            authorKind = "owner";
        }
        try {
            return view(service.add(str(body.get("kind")), str(body.get("itemKey")), str(body.get("body")), author, authorKind, guestId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
    }

    @DeleteMapping("/entries/{id}")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        if (request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_GUEST_TOKEN_ID) != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A share link cannot delete entries");
        }
        service.delete(id);
    }

    private static String str(Object o) { return o instanceof String s ? s : null; }

    private static EntryView view(RoofSpecEntry e) {
        return new EntryView(e.id(), e.kind(), e.itemKey(), e.body(), e.author(), e.authorKind(), e.createdAt());
    }
}
