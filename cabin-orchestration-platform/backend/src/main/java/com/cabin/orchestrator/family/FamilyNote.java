package com.cabin.orchestrator.family;

/**
 * Mirrors the note shape family-hub.html already used for its localStorage
 * NOTES_KEY entries ({id, authorId, text, ts}) so the frontend swap from
 * localStorage to this API is a data-source change, not a shape change.
 */
public record FamilyNote(
    String id,
    String authorId,
    String text,
    long ts
) {}
