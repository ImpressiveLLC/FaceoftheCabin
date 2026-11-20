# `answer_question` training rows: inventory of candidate sources

Code, Thu 19 Nov 2026. Status: **docs note, no data, no private access.** Ordered on #244 (Cowork, 7029753443, answering Codex 7029470433). The ruling stands: no approved unsealed real-Ask source exists in the record, the acceptance and RAG eval sets stay excluded, nothing is invented, and `answer_question` rows stay out of S2 until a source is approved. This note lists what could be a source, where it lives, what it is, and what approval it needs. It approves nothing and reads nothing private.

Read in git on main at `adbc9c300c8a79643781188b0293403e76fee3b9` (this note's branch base); the host and its logs were not read.

## 1. What "real Ask phrasing" has to mean here

S2 needs `answer_question` rows (the data path asks for about 60, "from real Ask phrasing", section 5) that are text a person actually typed to this system, are **not** acceptance or RAG-eval material, are not the spent dev gold, and are not sealed. A source that is documentation, a model's output or a question authored from the same documents the RAG eval expects answers from does not qualify as "real", and the last of these risks leaking eval answers into training.

## 2. Findings from the repository

- **The Ask question is not stored.** `POST /api/helpdesk/ask` is served by `TinyHelpdeskController` and `TinyHelpdeskService`; the only logging I found is counts and budgets (`AskContextBuilder` categories and sizes; `TinyHelpdeskService` line 82, "Ask prompt: N facts ... M chars"). I found no table (`infra/init-db/001_schema.sql` has `device`, `device_discovery_result`, `telemetry`, `cabin_event`, `automation_rule`) or logger that keeps the asked question. So there is **no Ask question log** to draw on in the committed code.
- **The one place real household text is recorded is the intent-proposal shadow audit.** `Slf4jProposalAuditLog` writes one JSON line per `POST /api/helpdesk/propose` call on the `assistant.proposal.audit` logger (the container log), with the request `input` after `ProposalAuditLog.redact` (which drops secret-looking values and long tokens only; it does not remove names or places). That route is off by default (`cabin.ask.intent.enabled` false, the compose default `CABIN_ASK_INTENT_ENABLED` false, a disabled route answers 404), so unless the host turned it on there may be no lines; I did not check the host.
- **The Ask panel does not use Open WebUI** (`user-guide/ask-and-helpdesk.md`), so chats in Open WebUI are a different product's history, not Ask.

## 3. Candidate sources

| # | Source | Where | What it actually is | Real Ask phrasing | Usable for S2 now | Approval needed |
|---|---|---|---|---|---|---|
| 1 | RAG eval question registry | `docs/ai-assistant/rag/questions_manifest_r1.json` (25 questions), `eval-questions.md`, `cli-questions-r1.json` and `cli-questions-r2.json` (24 answer-only cases, 3 repeats), `context-fixtures-r1.json` | acceptance and evaluation questions with source-reviewed expected answers | no (authored for evaluation) | **No: excluded by ruling** | none; excluded, not a candidate |
| 2 | Taxonomy evaluation questions | `docs/ai-assistant/taxonomy-eval/questions.json` (29 "problems", status "draft for G1") | an evaluation instrument for where data elements live in the topic taxonomy; first-person problem statements with a start context | no (Cowork seed hypotheses and questions drawn from alert conditions, per its own status) | **No: evaluation material** | Cowork or Nate would have to rule it is not evaluation material |
| 3 | Dev gold `answer_question` rows | `docs/ai-assistant/training/intent-slot/gold.jsonl`: gold-09, gold-10, gold-11, gold-21 | project-authored synthetic dev fixtures, already spent on prompt rounds | no (provenance "project-authored synthetic gold") | **No: spent dev, and `prepare_sft.py` excludes gold** | none |
| 4 | Documentation headings and the user guide | `docs/ai-assistant/user-guide/*.md`, `README.md`, `contributing.md`, `knowledge/` | the corpus the RAG answers from | no (documents, not questions) | Not as "real"; questions authored from them are synthetic and share sources with the RAG eval, so they must be screened against the eval sets and labeled synthetic | Cowork (a ruling that synthetic-from-docs is acceptable for `answer_question`, with the screen) |
| 5 | Intent shadow audit log | the backend container log, logger `assistant.proposal.audit`, field `input` | real text sent to `/api/helpdesk/propose`, redacted of secret-looking values only | yes, if the route was ever on; **it is propose traffic, not the Ask Q&A panel**, and may hold names or places | **Unknown and not read**; may be empty | **Nate** (household free text, a private host log); read-only counts first |
| 6 | Open WebUI chat history | the Open WebUI container's own database on the host | a separate chat product, own accounts | not Ask | **Not Ask** | Nate (private chats, a different product) |
| 7 | Public assistant corpora | CLINC150 (CC BY 3.0, downloaded for the E5 stress set, attribution in `intent-slot/stress/`); its in-scope intents were not used | crowd-written utterances for other assistants | not Ask; not home-specific | The `oos_test` rows are already used as a stress set and must not also be training; in-scope intents would be a **new use** of the download | Nate or Cowork (the earlier approval was for the download and the oos stress set) |
| 8 | Questions supplied by Nate or the household | chat, a file Nate provides | a person's own questions | yes, if Nate writes or supplies them | Yes, once supplied and recorded | **Nate** supplies; provenance recorded as supplied by Nate, with consent for use |

Counts for rows 1 to 3 are what is in git; I did not count rows 5, 6 or 8, which I did not read or do not exist yet.

## 4. What I recommend, and what each step would need

1. **Keep `answer_question` out of S2.** No committed source qualifies, as the ruling says.
2. **Cheapest real source: row 8.** Ask Nate for a short list of real questions he or the family would ask Ask (a few dozen is enough to start, about 60 is the plan's figure). Nothing is read from a host; provenance is "supplied by Nate on <date>". Needs Nate's yes and his questions.
3. **Optional: row 5, counts first.** A read-only count of `assistant.proposal.audit` lines on the host (how many exist, no text) tells whether any real propose traffic exists. Reading any text is a separate approval and a redaction and consent step. This is a host action and needs Nate's go; I have not done it.
4. **Optional: row 4, labeled synthetic.** If Cowork rules that doc-derived questions are acceptable, authored questions would be labeled synthetic-from-documents, screened for exact and near overlap against rows 1 to 3 and the six exposure groups, and kept out of any RAG or Ask acceptance use. This does not meet "real Ask phrasing" and should be a Cowork ruling, not an assumption.
5. Row 7 only with a ruling, and not the `oos_test` rows.

## 5. Not claimed

I read no host, log, database or private file, and no sealed or acceptance material beyond listing which committed files are eval sets. I did not verify that the intent route is off on the host (the code and the compose default say off). Nothing here approves a source, authors a row, or authorizes training, export, routing, purchase or access.
