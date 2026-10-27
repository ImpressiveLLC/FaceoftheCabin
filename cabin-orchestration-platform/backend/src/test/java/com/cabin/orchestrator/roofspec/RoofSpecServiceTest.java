package com.cabin.orchestrator.roofspec;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoofSpecServiceTest {

    private static final class Mem implements RoofSpecStore {
        final List<RoofSpecEntry> rows = new ArrayList<>();
        public List<RoofSpecEntry> loadLive() { return rows.stream().filter(e -> e.deletedAt() == null).toList(); }
        public long countLiveByGuestToken(String id) { return loadLive().stream().filter(e -> id.equals(e.guestTokenId())).count(); }
        public void save(RoofSpecEntry e) { rows.add(e); }
        public void softDelete(String id, Instant at) {
            rows.replaceAll(e -> e.id().equals(id)
                ? new RoofSpecEntry(e.id(), e.kind(), e.itemKey(), e.body(), e.author(), e.authorKind(), e.guestTokenId(), e.createdAt(), at) : e);
        }
    }

    private final RoofSpecService svc = new RoofSpecService(new Mem());

    @Test
    void storesAnInputWithServerDerivedAuthor() {
        RoofSpecEntry e = svc.add("input", "furring", "  1.5 in furring, 3/4 in gap ", "Aaron, Collins", "guest", "t1");
        assertThat(e.author()).isEqualTo("Aaron, Collins");
        assertThat(e.body()).isEqualTo("1.5 in furring, 3/4 in gap");
        assertThat(svc.list()).hasSize(1);
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> svc.add("note", null, "x", "a", "guest", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.add("comment", "nope", "x", "a", "guest", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.add("input", null, "x", "a", "guest", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.add("comment", null, " ", "a", "guest", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.add("comment", null, "x".repeat(4001), "a", "guest", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.add("comment", null, "x", null, "guest", "t")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capsEntriesPerGuestLinkButNotTheOwner() {
        for (int i = 0; i < RoofSpecService.MAX_PER_GUEST_LINK; i++) svc.add("comment", null, "c" + i, "A", "guest", "t1");
        assertThatThrownBy(() -> svc.add("comment", null, "more", "A", "guest", "t1")).isInstanceOf(IllegalStateException.class);
        svc.add("comment", null, "owner reply", "nate@example.com", "owner", null);
        svc.add("comment", null, "other link", "B", "guest", "t2");
    }

    @Test
    void deletedEntriesDisappearFromTheList() {
        RoofSpecEntry e = svc.add("comment", null, "oops", "A", "guest", "t1");
        svc.delete(e.id());
        assertThat(svc.list()).isEmpty();
    }
}
