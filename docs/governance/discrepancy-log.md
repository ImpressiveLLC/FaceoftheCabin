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
| DL-2026-09-23-05 | 2026-09-23, Code | [SO-2026-09-23-r1](signoffs/SO-2026-09-23-r1.md) §2 lists `camera-events-bulk-clip-download` as "no PR yet" and `frigate-front-door-motion-sensitivity` as "no PR seen" | Both existed before the sign-off was issued: [PR #96](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/96) (bulk clip download, open, `MERGEABLE`) and [PR #97](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/97) (Frigate tuning, open, `MERGEABLE`), both opened 2026-09-22/23 before this sign-off's own "evidence reviewed" cutoff | `defect` (in the sign-off's evidence row, not in the code) | The §2.3 and §2.5 rulings (APPROVED WITH CONDITIONS; GATED on W-5) apply to these existing PRs directly — no new PR is opened for either item. Sign-offs are append-only ([governance/README.md](README.md#ownership)), so this is logged here rather than editing SO-2026-09-23-r1.md in place. ITER-2026-09-23.md row 7 ("Bulk clip") corrected the same way in Code's iteration acknowledgement |
