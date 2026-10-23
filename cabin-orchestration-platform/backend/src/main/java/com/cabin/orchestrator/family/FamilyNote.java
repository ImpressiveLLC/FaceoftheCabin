package com.cabin.orchestrator.family;

/** Shared note with optional dated-list metadata and server-captured reply context. */
public record FamilyNote(
    String id,
    String authorId,
    String text,
    long ts,
    String kind,
    String dateType,
    String date,
    String replyToId,
    String replyText,
    String replyAuthorId
) {}
