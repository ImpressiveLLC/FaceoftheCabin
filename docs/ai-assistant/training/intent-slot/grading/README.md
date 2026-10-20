# Local human grading (POC2 / UC-17 / D27)

This screen reviews expected labels and, separately, actual model responses.
No model is trained or called by the screen. No devices are controlled.
Serve it with Python; opening index.html directly cannot load or save data.

## Every session, in order (the screen shows these too)

1. Open **PowerShell** in your clone (the folder that holds `docs`) and run `.\docs\ai-assistant\training\intent-slot\grading\start.ps1`
   (if scripts are disabled: `powershell -ExecutionPolicy Bypass -File .\docs\ai-assistant\training\intent-slot\grading\start.ps1`).
   It checks you are on main, pulls the latest, stops any old copy of the tool (even one you cannot find), and starts the tool in
   that window; leave the window open, Ctrl+C stops it. Windows PowerShell 5.1 has no `&&`: use `;` between commands.
   On macOS or Linux: `git checkout main && git pull`, then stop the old server and run
   `python docs/ai-assistant/training/intent-slot/grading/server.py`.
   If the tool cannot find the packages it needs, run once: `python -m pip install -r docs/ai-assistant/training/intent-slot/requirements.txt`.
   No other options are needed: the journal is kept in `~/.fotc-grading/`, one file per dataset and contract, so a
   changed vocabulary or contract starts a fresh journal by itself (the old one is left there, untouched).
2. Open http://127.0.0.1:8766. The checklist at the top must be all green: on main, up to date, gold clean, code unchanged since start.
3. Grade.
4. Press **Done, move to gold**, then confirm. The tool merges your approved examples into `gold.jsonl`, runs the package
   tests (and puts the file back if they fail), creates a `grading/gold-YYYYMMDD-HHMM` branch, commits, pushes it, and shows
   the pull request link. It never touches main and never merges. Tell Code or Cowork "gold PR ready".
   In a candidate session (`--purpose training-candidate`) the button writes `<journal>.approved.jsonl` instead and never edits gold.

**Approved work is kept.** A new journal starts with every example gold already records as approved (it must still pass the contract), so the screen asks only about new or changed examples. Done writes back only the examples you approved or corrected this session; every other line of `gold.jsonl` keeps its exact bytes. Editing a concept's definition, examples or proposed asks does not reset anything; changing a concept id or alias, a room, the contract or a schema does start a fresh journal and an example that no longer passes is asked again.

## Details

1. Start `server.py --package PATH_TO_MATCHING_PACKAGE --state PRIVATE_JOURNAL.json --port 8766`.
   The package needs gold.jsonl, contract.py, schemas and system.txt, plus its Python dependencies.
   Current main supplies 47 fixtures (41 already reviewed, 6 pending).
   **Vocabulary (2026-10-16).** `concepts.json` (11 human needs) and `areas.json` (rooms and sites) are part of the
   package and of the journal's fingerprint: changing either, the schema or `contract.py` makes an older journal
   refuse to load, on purpose, so the default journal name (which includes the hashes) changes by itself; with an explicit `--state` start a new file.
2. Enter a reviewer name. Search/filter examples, correct intent/details or the advanced JSON,
   explain corrections/rejections, then approve, reject or save for later. Decisions persist on disk.
   While you edit, the screen asks the server to check the answer (`/api/validate`, the same rules approval uses):
   a room or site outside the vocabulary is flagged under its field and **Approve stays disabled** until it is fixed
   (reject and save-for-later still work); `Mechanical Room`, `mech-room` and `the mech room` are saved as `mech room`
   and the screen says so; a human need can be added only to a need request, each need shows its definition,
   aliases and neighbours, and a direct command such as "turn on the hall lights" shows, read-only, which needs
   it would satisfy. To add a room, edit `areas.json` in a PR; to add a need, edit `concepts.json` and re-run the tests.
3. Fetch the selected pinned public sources with `collect_sources.py --package PATH_TO_MATCHING_PACKAGE --output PRIVATE_SOURCE_FOLDER`.
   This makes candidates.jsonl, quarantine.jsonl, manifest.json and upstream copies/license notices.
   Start a second server with `--dataset PRIVATE_SOURCE_FOLDER/candidates.jsonl --purpose training-candidate --port 8768`
   and a different `--state` journal. Candidate approval is not promotion into held-out gold.
4. Curate template/paraphrase families and a separate holdout before making any training split.
   Exact duplicate filtering is implemented; semantic deduplication and human label approval remain required.
5. Run the selected package's evaluate.py against the exact gold file on a machine with local Ollama.
   Load its JSON report in the Model response tab. Grade actual output separately.
   A mismatched gold fingerprint is rejected. Changed expected answers invalidate associated human model grades.
6. Download approved examples and the full review history. The original fixtures are never overwritten.
   No export is a release approval; existing evaluate.py gates are unchanged.

The server binds only 127.0.0.1 and uses same-origin/token checks. It is a local tool,
not a publicly authenticated website. Keep journals outside Git. Stop/restart the process
after code updates. Dataset/contract/purpose changes require a new journal, never recycled approvals.
Navigation links use local ports 8766 and 8768; adjust them if using other ports.

## Verified preparation, 2026-10-16

Recommended sources recovered from the original PR #159 sources.json:
Enfuse smart-home JSONL, OHF-Voice concrete English on/off tests, Snips lights sample.
CASAS remains deferred behavioral research, not an intent corpus.

- Enfuse revision 39056ca057ae92d71fb7a272f7960567e55dca70: 1,042 candidates.
- OHF-Voice revision a64026f9359dc705138314d7c363fef1f6d7bf5d: 86 candidates from four selected files.
- Snips revision 74b2893c91fc0bafc919a7e088ecb0b2bd611acf: 12 candidates.
- Total 1,140 unapproved candidates; 988 quarantine entries retained, including duplicate sentences,
  duplicate slots, temperature labels needing explicit-unit review and unsupported/guarded mappings.
  Counts are mixed source-record/sentence units; do not subtract them from input-record counts.
- Source revisions, hashes, URLs, record positions and license notices are retained locally.
  HA's pinned test format derives the intent from the file path; only light/fan/climate domains map.
  Lock/valve/cover and unknown-domain cases stay quarantined for governed manual mapping.
- No human approvals, training, tuning, model results or quality claims were produced by collection.
- Local Ollama on this workstation was unavailable; model-response grading awaits a matching run.
- Requested Karpathy skill was not found in accessible skill catalogs/checkouts; no claim it was applied.

Code review and current main integration of the separate POC2 contract remain pending.
