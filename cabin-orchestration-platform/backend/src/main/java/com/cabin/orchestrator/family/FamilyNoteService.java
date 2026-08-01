package com.cabin.orchestrator.family;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS pattern as
 * DeviceDisplayConfigService — no migration framework in this project yet,
 * so self-migrating on startup is the established approach here.
 */
@Service
public class FamilyNoteService {

    // Matches NOTES_MAX in family-hub.html — oldest notes beyond this are
    // dropped so the table (and the payload every device fetches) doesn't
    // grow unbounded.
    private static final int NOTES_MAX = 50;

    private final JdbcTemplate jdbc;

    public FamilyNoteService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS family_notes (
              id        VARCHAR(64) PRIMARY KEY,
              author_id VARCHAR(128) NOT NULL,
              text      TEXT NOT NULL,
              ts        BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_family_notes_ts ON family_notes (ts DESC)");
    }

    /** Newest first, capped at NOTES_MAX — same order/limit the old localStorage list used. */
    public List<FamilyNote> recent() {
        return jdbc.queryForList("SELECT * FROM family_notes ORDER BY ts DESC LIMIT ?", NOTES_MAX)
            .stream().map(this::fromRow).toList();
    }

    public FamilyNote add(String authorId, String text) {
        FamilyNote note = new FamilyNote(
            "n_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6),
            authorId, text, System.currentTimeMillis());
        jdbc.update("INSERT INTO family_notes (id, author_id, text, ts) VALUES (?,?,?,?)",
            note.id(), note.authorId(), note.text(), note.ts());
        prune();
        return note;
    }

    /** Keeps the table capped at NOTES_MAX so it never grows unbounded. */
    private void prune() {
        jdbc.update("""
            DELETE FROM family_notes WHERE id NOT IN (
              SELECT id FROM family_notes ORDER BY ts DESC LIMIT ?
            )""", NOTES_MAX);
    }

    private FamilyNote fromRow(Map<String, Object> row) {
        return new FamilyNote(
            (String) row.get("id"),
            (String) row.get("author_id"),
            (String) row.get("text"),
            ((Number) row.get("ts")).longValue());
    }
}
