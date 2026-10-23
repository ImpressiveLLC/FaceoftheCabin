package com.cabin.orchestrator.family;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'NOTE'");
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS date_type VARCHAR(16)");
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS note_date VARCHAR(10)");
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS reply_to_id VARCHAR(64)");
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS reply_text TEXT");
        jdbc.execute("ALTER TABLE family_notes ADD COLUMN IF NOT EXISTS reply_author_id VARCHAR(128)");
    }

    /** Newest first, capped at NOTES_MAX — same order/limit the old localStorage list used. */
    public List<FamilyNote> recent() {
        return jdbc.queryForList("SELECT * FROM family_notes ORDER BY ts DESC LIMIT ?", NOTES_MAX)
            .stream().map(this::fromRow).toList();
    }

    public FamilyNote add(String authorId, String text) {
        return add(authorId, text, "NOTE", null, null, null);
    }

    public FamilyNote add(String authorId, String text, String kind, String dateType, String date, String replyToId) {
        kind = kind == null ? "NOTE" : kind;
        validate(authorId, text, kind, dateType, date);
        FamilyNote parent = replyToId == null || replyToId.isBlank() ? null : findReply(replyToId);
        FamilyNote note = new FamilyNote(
            "n_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6),
            authorId, text.trim(), System.currentTimeMillis(), kind, dateType, date,
            parent == null ? null : parent.id(), parent == null ? null : parent.text(),
            parent == null ? null : parent.authorId());
        jdbc.update("""
            INSERT INTO family_notes (id, author_id, text, ts, kind, date_type, note_date,
              reply_to_id, reply_text, reply_author_id) VALUES (?,?,?,?,?,?,?,?,?,?)
            """, note.id(), note.authorId(), note.text(), note.ts(), note.kind(), note.dateType(),
            note.date(), note.replyToId(), note.replyText(), note.replyAuthorId());
        prune();
        return note;
    }

    private FamilyNote findReply(String id) {
        var rows = jdbc.queryForList("SELECT * FROM family_notes WHERE id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Original note is no longer available; cancel reply to post a new note.");
        return fromRow(rows.getFirst());
    }

    private void validate(String author, String text, String kind, String dateType, String date) {
        boolean sticky = "STICKY".equals(kind);
        if (author == null || author.isBlank() || author.length() > 128 || text == null || text.isBlank() || text.length() > 2000
            || (!sticky && !"NOTE".equals(kind))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Author, note text (up to 2000 characters), and valid kind are required.");
        }
        if (!sticky && (dateType != null || date != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates belong to sticky lists.");
        if (sticky) {
            if (!("DUE_BY".equals(dateType) || "LIST_DATE".equals(dateType)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Due by or List date.");
            try {
                if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                LocalDate.parse(date);
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid date.");
            }
        }
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
            ((Number) row.get("ts")).longValue(), (String) row.get("kind"),
            (String) row.get("date_type"), (String) row.get("note_date"),
            (String) row.get("reply_to_id"), (String) row.get("reply_text"),
            (String) row.get("reply_author_id"));
    }
}
