# Discrepancy log

A discrepancy is any case where a document, spec or handover says one thing and the code or live system does another. Each entry gets one ruling. Entries are not deleted; a resolved entry keeps its ruling.

Historical entries recorded before 2026-09-23 remain in the Open Pins section of [`decisions/ontology-decisions.md`](decisions/ontology-decisions.md#open-pins) until DOC-2 moves them here. New entries go here only.

## Rulings

| Ruling | Meaning |
|---|---|
| `ratified deviation` | The implementation is correct; the spec is amended to match |
| `defect` | The spec is correct; the implementation must change |
| `conditional` | Ratified only if a named condition passes; otherwise a defect |
| `open` | Not yet ruled |

## Entries

| ID | Found | Source says | Code / live does | Ruling | Follow-up |
|---|---|---|---|---|---|
| DL-2026-09-23-01 | 2026-09-22, Code (PR #93 checklist) | r8 handover: `registerCandidate()` writes a DB row; `DeviceMetadata.imported_from_platform` column; idempotent migration | `registerCandidate()` in-memory for passive discovery; `registerPersistentCandidate()` writes durable row for platform imports; provenance in `DeviceLifecycleRecord.extraAttributes.importedFrom/originalId/registeredAt`; no migration, second confirm returns 409 | `ratified deviation` | Amend D10 to the stored provenance value once posted (W-10) |
| DL-2026-09-23-02 | 2026-09-22, Code (guest-access check) | D12: `/view/{token}/**` served by a separate read-only controller | Client-side page calls the shared `/api/*` routes with `?t={token}`; GET-only rule plus scope-to-path map are the only barrier | `conditional` on W-2 | If W-2 passes, amend D12 to describe the built shape; if any denial test fails, D12 as written is required |
| DL-2026-09-23-03 | 2026-09-23, Cowork | claude.ai project doc `ontology/cabin-ontology-decisions.md`: D14 = Energy, D15 = Reporting Topics | Living Ontology and this repo: D14 = Kiosk Auth, D15 = Energy, D16 = Reporting Topics | `defect` (in the project copy) | Retire the project copy (DOC-5) |
| DL-2026-09-23-04 | 2026-09-23, Cowork | Code handover r8 architecture reminders cite "D16 Topic-picker" and "D15 area write path" | Consistent with the Living Ontology numbering, inconsistent with the project copy of the ontology | `ratified deviation` (handover is correct) | None; resolved by DOC-5 |
