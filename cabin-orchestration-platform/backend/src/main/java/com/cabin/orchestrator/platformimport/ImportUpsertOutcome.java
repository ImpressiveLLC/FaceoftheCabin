package com.cabin.orchestrator.platformimport;

/**
 * What happened when a RawImportRecord was reconciled against the
 * (platform, originalId) UNIQUE constraint -- WSJF #9's dedup gate, the
 * Open Pin this item exists to close before any OAuth adapter work.
 *
 * NEW -- never seen before; a fresh row was inserted.
 * ALREADY_PENDING -- seen before, not yet confirmed into a real device.
 *   This is the "conflict" case the Open Pin calls for: a re-import must
 *   never create a second row for the same external device, and the
 *   proposals endpoint must be able to tell a still-undecided repeat import
 *   apart from a genuinely new one, rather than silently treating both the
 *   same and re-surfacing it as if nothing had happened before.
 * ALREADY_CONFIRMED -- already onboarded as a real device (confirmedEntityId
 *   set). Raw fields were refreshed (a background sync updating cached
 *   metadata) -- not a conflict, just a metadata update for a device that
 *   already exists.
 */
public enum ImportUpsertOutcome {
    NEW, ALREADY_PENDING, ALREADY_CONFIRMED
}
