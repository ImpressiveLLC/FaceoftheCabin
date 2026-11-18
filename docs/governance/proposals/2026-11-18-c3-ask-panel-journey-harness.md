# C3 prerequisite 5: Ask-panel journey harness (design, 2026-11-18)

Status: **design only; nothing implemented.** Requested by Cowork on #244 ([6993142299](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6993142299)), as prerequisite 5 in the [C3 prerequisites proposal](2026-11-18-guest-link-tidy-and-c3-prerequisites.md). Pinned to `main` 4491ab715efa75bf7e86e8d2d331a7ad26a4765b. The model is always stubbed; there is no model call, live Ask run, training or new infrastructure. A1 NOT_MEASURED, `release_eligible` false.

## What exists today (verified at the pin)

- **The panel:**
  - `HelpdeskPanel({ auth })` in `cabin-orchestration-platform/ui/src/App.jsx` is exported for tests.
  - It posts to `POST {apiBase}/api/helpdesk/ask` through `auth.authedFetch` when signed in, and shows a sign-in prompt otherwise.
  - It keeps a `messages` list of `{role, text, sources, answeredByModel}`.
  - It labels each source as Verified (manually curated), Auto-generated, or reviewed document.
  - It shows a fallback note when `answeredByModel === false`, and an error message on failure.
- **Existing tests:** `App.test.jsx` already has 9 single-step panel tests (empty state, auth routing, sign-in prompt, answer and source labels, fallback note, error, empty-question guard). They use **vitest 3 + jsdom 25 + Testing Library**.
- **CI:** `.github/workflows/kiosk-cabin-ui.yml` runs `npm test` and `npm run build` on every UI pull request. That is the "existing UI test suite".
- **No Playwright in the UI package.** Playwright exists in the repository only for Family Hub: `family-hub/test`, the pinned `mcr.microsoft.com/playwright:v1.62.0-noble` image, and `kiosk-family-hub.yml`. Backlog row W-126 records that adding a browser runner to CI is its own decision.

## Two tiers

### Tier 1: journey tests in the existing suite (no new infrastructure, recommended now)

A new `ui/src/AskPanelJourney.test.jsx` runs inside the existing `npm test` and CI job. Each test drives `HelpdeskPanel` through a multi-turn journey with a **scripted stub** of `authedFetch`. The stub is a fixed list of request matchers and responses shaped like `TinyHelpdeskAnswer`; it never contacts a model or backend. The tests use Testing Library's user events and assert on what a person would see.

| Journey (C3 exit-evidence wording) | Script | Assertions |
|---|---|---|
| **Explain** | One question; the answer has a `REVIEWED_DOCUMENT` source | Answer text shown; the source carries the reviewed-document label and its `path#section`; one user turn |
| **Stepwise setup plan** | "How do I recreate the instance on a new machine?"; a numbered-step answer citing the installation plan | Steps render in order; the citation is visible; no action is presented as executed |
| **Follow-up context** | Two turns; the second asks "and after that?" | Both turns stay visible in order; the second request carries only the new question (today's contract), recorded as a **C3 gap to close**, not a pass |
| **Clarification** | The answer asks one clarifying question; the user answers; then the final answer | Clarifications counted (1); a final answer reached within the turn budget |
| **Source gap and recovery** | The answer has no sources | A useful-recovery message is shown, not an empty answer. The current behavior is recorded as the baseline |
| **Model unavailable** | `answeredByModel: false` | Fallback note shown; sources still listed |
| **Request failure and retry** | The first call errors; the retry succeeds | Error shown without crashing; the retry clears it; the answer is then shown |
| **Signed out** | `auth` present but not signed in | Sign-in prompt only; **no** request sent |
| **Credential question** | The stubbed answer is the redactor's administrator-pointer text | Only the vault entry name appears in the response, no value-shaped text |

**Per-journey measures**, as the C3 exit evidence asks: user turns, clarifications, whether a citation was shown, whether recovery was offered, and whether the journey completed. A small helper records these and writes `ui/target/ask-journeys.json`, mirroring the backend harness report, so they can be compared later.

**Gates:** every scripted journey completes as asserted. The stub refuses any unscripted request, so a journey cannot pass by silently calling the network.

### Tier 2: a real-browser journey (needs a separate decision)

Tier 2 runs the same journeys in Chromium against the **built** UI (`npm run build`, then `vite preview`), with Playwright `page.route()` intercepting `/api/helpdesk/ask` and returning the same scripted responses. It adds what jsdom cannot show: layout, focus and keyboard, scrolling of long answers, and the real fetch path.

- Reuse the Family Hub pattern exactly: the same pinned `mcr.microsoft.com/playwright:v1.62.0-noble` image, a `ui/test/journey/Dockerfile`, and one CI step in `kiosk-cabin-ui.yml`.
- This adds a browser runner to the cabin-ui CI. That is the decision W-126 leaves to Nate, so Tier 2 waits for an explicit go and is **not** part of this prerequisite's first slice.

### Real model: only in authorized live runs

Neither tier ever calls the real model. Journeys against the deployed Ask on the M920q belong to the separately authorized measurement runs (Codex's protocol, #520) and follow its rules: frozen identities, no retries, and the production model is never stopped for an eval.

## Not in scope

- No change to `HelpdeskPanel`, the `/api/helpdesk/ask` contract or the backend. Gaps the journeys expose (for example multi-turn context, or source-gap recovery wording) are **recorded** as C3 work items, not fixed here.
- No new dependencies in Tier 1: vitest, jsdom and Testing Library are already installed.

## Implementation estimate and acceptance (if approved)

- **Tier 1:** one test file plus a small recorder helper, about 45 to 60 minutes.
- **Acceptance:**
  - all nine journeys run in `npm test` locally and in `kiosk-cabin-ui.yml`;
  - the unscripted-request guard is proven by a test that fails when a journey calls an unscripted URL;
  - `ask-journeys.json` is written;
  - the existing 9 panel tests are unchanged and green.
- **Tier 2:** about 60 to 90 minutes after Nate's go, using the Family Hub image and pattern.

## Requested rulings

1. Approve Tier 1 as the first slice of prerequisite 5.
2. Put Tier 2 (a browser runner in the cabin-ui CI) to Nate as a separate decision, or defer it.
