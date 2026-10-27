package com.cabin.orchestrator.roofspec;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Backs the Roof Monitoring page. The only thing a "roof_spec" share link can
 * ever write is a comment or a requested input through {@link #add}; every
 * limit that keeps that safe lives here, not in the UI.
 */
@Service
public class RoofSpecService {

    /** The five things the page asks the roofer to confirm, plus plane orientation and a free-form slot. */
    public static final Set<String> ITEM_KEYS = Set.of("furring", "foam", "blocking", "cables", "walk", "orientation", "general");
    public static final Set<String> KINDS = Set.of("comment", "input");
    public static final int MAX_BODY = 4000;
    public static final int MAX_PER_GUEST_LINK = 300;

    private final RoofSpecStore store;

    public RoofSpecService(RoofSpecStore store) { this.store = store; }

    public List<RoofSpecEntry> list() { return store.loadLive(); }

    public RoofSpecEntry add(String kind, String itemKey, String body, String author, String authorKind, String guestTokenId) {
        if (kind == null || !KINDS.contains(kind)) throw new IllegalArgumentException("kind must be comment or input");
        if (itemKey != null && !ITEM_KEYS.contains(itemKey)) throw new IllegalArgumentException("unknown itemKey");
        if ("input".equals(kind) && itemKey == null) throw new IllegalArgumentException("an input needs an itemKey");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("body is required");
        if (body.length() > MAX_BODY) throw new IllegalArgumentException("body is limited to " + MAX_BODY + " characters");
        if (author == null || author.isBlank()) throw new IllegalArgumentException("author could not be determined");
        if (guestTokenId != null && store.countLiveByGuestToken(guestTokenId) >= MAX_PER_GUEST_LINK) {
            throw new IllegalStateException("This link has reached its entry limit. Ask the cabin owner to raise it.");
        }
        RoofSpecEntry e = new RoofSpecEntry(UUID.randomUUID().toString(), kind, itemKey, body.strip(),
            author, authorKind, guestTokenId, Instant.now(), null);
        store.save(e);
        return e;
    }

    public void delete(String id) { store.softDelete(id, Instant.now()); }
}
