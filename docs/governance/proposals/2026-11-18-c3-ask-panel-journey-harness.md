# C3 prerequisite 5: Ask-panel journey harness (design, 2026-11-18)

Status: **design only; nothing implemented.** Requested by Cowork on #244 ([6993142299](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6993142299)), as prerequisite 5 in the [C3 prerequisites proposal](2026-11-18-guest-link-tidy-and-c3-prerequisites.md). Pinned to `main` 4491ab715efa75bf7e86e8d2d331a7ad26a4765b. The model is always stubbed; there is no model call, live Ask run, training or new infrastructure. A1 NOT_MEASURED, `release_eligible` false.

## What exists today (verified at the pin)

- **The panel:**
  - `HelpdeskPanel({ auth })` in `cabin-orchestration-platform/ui/src/App.jsx` is exported for tests.
  - It posts to `POST {apiBase}/api/helpdesk/ask` through `auth?.authedFetch || fetch`, so it falls back to the global `fetch` when no `authedFetch` is supplied. It shows a sign-in prompt when signed out.
  - It renders `data.answer` unchanged. Empty `sources` produce no extra guidance: the sources block is simply not shown.
  - It keeps a `messages` list of `{role, text, sources, answeredByModel}`.
  - It labels each source as Verified (manually curated), Auto-generated, or reviewed document.
  - It shows a fallback note when `answeredByModel === false`, and an error message on failure.
- **Existing tests:** `App.test.jsx` already has 9 single-step panel tests (empty state, auth routing, sign-in prompt, answer and source labels, fallback note, error, empty-question guard). They use **vitest 3 + jsdom 25 + Testing Library**.
- **CI:** `.github/workflows/kiosk-cabin-ui.yml` runs `npm test` and `npm run build` on every UI pull request. That is the "existing UI test suite".
- **No Playwright in the UI package.** Playwright exists in the repository only for Family Hub: `family-hub/test`, the pinned `mcr.microsoft.com/playwright:v1.62.0-noble` image, and `kiosk-family-hub.yml`. Backlog row W-126 records that adding a browser runner to CI is its own decision.

## Two tiers

### Tier 1: journey tests in the existing suite (no new infrastructure, recommended now)

A new `ui/src/AskPanelJourney.test.jsx` runs inside the existing `npm test` and CI job. Each test drives `HelpdeskPanel` through a multi-turn journey with a **scripted stub** of `authedFetch`. The stub is a fixed list of request matchers and responses shaped like `TinyHelpdeskAnswer`; it never contacts a model or backend. The tests use Testing Library's user events and assert on what a person would see. Authentication is synthetic only: a test `auth` object, never a production session.

**What Tier 1 shows, and what it does not.** The stubbed answers are written by the test. A journey therefore shows how the panel displays and handles a response: rendering, ordering, labels, error and retry, and the signed-out path. It does not show model reasoning, retrieval quality or redactor correctness. Where the product lacks a capability today, the journey pins the actual baseline behavior and records the capability as a **C3 gap**, not a pass.

| Journey (C3 exit-evidence wording) | Script | Assertions |
|---|---|---|
| **Explain** | One question; the answer has a `REVIEWED_DOCUMENT` source | Answer text shown; the source carries the reviewed-document label and its `path#section`; one user turn |
| **Stepwise setup plan** | "How do I recreate the instance on a new machine?"; a numbered-step answer citing the installation plan | Steps render in order; the citation is visible; no action is presented as executed |
| **Follow-up context** | Two turns; the second asks "and after that?" | Both turns stay visible in order. Baseline pinned: the second request carries only the new question. **C3 gap:** follow-up context |
| **Clarification** | The scripted answer asks one clarifying question; the user answers; then the final scripted answer | Both exchanges display in order; clarifications counted (1). Shows display and interaction only, not that the model asks good questions |
| **Source gap and recovery** | The answer has no sources | Baseline pinned: the answer text is shown unchanged and no sources block or recovery guidance appears. **C3 gap:** useful recovery when no source supports the answer |
| **Model unavailable** | `answeredByModel: false` | Fallback note shown; sources still listed |
| **Request failure and retry** | The first call errors; the retry succeeds | Error shown without crashing; the retry clears it; the answer is then shown |
| **Signed out** | `auth` present but not signed in | Sign-in prompt only; **no** request sent |
| **Credential question** | The stubbed answer is the redactor's administrator-pointer text | The pointer text displays unchanged. Shows display only; redactor correctness stays covered by the backend tests |

**Per-journey measures**, as the C3 exit evidence asks: user turns, clarifications, whether a citation was shown, whether recovery was offered, and whether the journey completed. A small helper records these and writes `ui/target/ask-journeys.json`, mirroring the backend harness report, so they can be compared later. The report keeps two fields apart for each journey:

- `harnessAsserted`: the test's assertions held, including pinned baselines.
- `productCapability`: `MET` or `GAP`. Follow-up context and source-gap recovery are `GAP` today. A passing test with a `GAP` is not a completed product journey.

**Network isolation (deny by default).**

- The scripted `authedFetch` refuses any request it was not scripted for.
- The global `fetch` is replaced for the whole file with a function that fails the test on any call. This matters because the panel falls back to the global `fetch` when `authedFetch` is missing, so a regression could bypass the stub.
- Guard tests prove both paths: one journey sends an unscripted URL through the stub, and one renders the panel without `authedFetch`. Each must fail with a clear message.

**Gates:** every scripted journey's assertions hold, and both guard tests catch their bypass. These gates measure the harness, not product completion.

### Tier 2: a real-browser journey (needs a separate decision)

Tier 2 runs the same journeys in Chromium against the **built** UI (`npm run build`, then `vite preview`), with Playwright `page.route()` returning the same scripted responses for `/api/helpdesk/ask`. It adds what jsdom cannot show: layout, focus and keyboard, scrolling of long answers, and the real fetch path.

- **Deny by default.** A catch-all route aborts every request and fails the run. Only these are allowed:
  - the local build's own assets from the preview server;
  - explicitly scripted responses for the auth and bootstrap calls the app makes on load, using synthetic auth only;
  - the scripted Ask responses.
- A guard journey proves an unapproved API or external request is aborted and reported.
- **Tier 1 does not satisfy the real-browser part of prerequisite 5.** That needs Tier 2.

- Reuse the Family Hub pattern exactly: the same pinned `mcr.microsoft.com/playwright:v1.62.0-noble` image, a `ui/test/journey/Dockerfile`, and one CI step in `kiosk-cabin-ui.yml`.
- This adds a browser runner to the cabin-ui CI. That is the decision W-126 leaves to Nate, so Tier 2 waits for an explicit go and is **not** part of this prerequisite's first slice.

### Real model: only in authorized live runs

Neither tier ever calls the real model or uses a production session. No deployed browser journey is authorized by this design or by #520. #520 proposes only one 72-trial native-endpoint batch plus a denial check, and even that still needs Nate's go. Browser journeys against the deployed Ask on the M920q would need their own reviewed protocol and Nate's explicit authorization. #520 is a reference for the safeguards such a protocol should keep: frozen identities, no retries, and the production model is never stopped for an eval.

## Not in scope

- No change to `HelpdeskPanel`, the `/api/helpdesk/ask` contract or the backend. Gaps the journeys expose (for example multi-turn context, or source-gap recovery wording) are **recorded** as C3 work items, not fixed here.
- No new dependencies in Tier 1: vitest, jsdom and Testing Library are already installed.

## Implementation estimate and acceptance (if approved)

- **Tier 1:** one test file plus a small recorder helper, about 45 to 60 minutes.
- **Acceptance:**
  - all nine journeys run in `npm test` locally and in `kiosk-cabin-ui.yml`;
  - both network guards are proven: an unscripted URL through the stub fails, and a render without `authedFetch` that reaches the global `fetch` fails;
  - `ask-journeys.json` reports `harnessAsserted` and `productCapability` separately, with follow-up context and source-gap recovery as `GAP`;
  - `ask-journeys.json` is written;
  - the existing 9 panel tests are unchanged and green.
- **Tier 2:** about 60 to 90 minutes after Nate's go, using the Family Hub image and pattern.

## Requested rulings

1. Approve Tier 1 as the first slice of prerequisite 5. It is a UI harness; prerequisite 5's real-browser requirement stays open until Tier 2.
2. Put Tier 2 (a browser runner in the cabin-ui CI) to Nate as a separate decision, or defer it.
