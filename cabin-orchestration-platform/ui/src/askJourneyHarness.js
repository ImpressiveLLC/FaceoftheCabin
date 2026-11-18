// C3 prerequisite 5, Tier 1 (design: docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md).
// Test-only helpers for scripted Ask-panel journeys. Nothing here contacts a model or backend.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

export const ASK_URL = "http://cabin-hub:8090/api/helpdesk/ask";

/** A response shaped like the backend's TinyHelpdeskAnswer record. */
export function answer(question, text, { sources = [], answeredByModel = true } = {}) {
  return { question, answer: text, sources, answeredByModel };
}

export function reviewedSource(entityRef) {
  return { entityRef, chunkType: "REVIEWED_SECTION", content: "reviewed section text", source: "REVIEWED_DOCUMENT", generatedAt: "2026-11-18T00:00:00Z" };
}

/**
 * Deny-by-default network for one journey.
 *
 * `steps` is the ordered script: each step names the question it expects and the response to return, either
 * `{ status, body }` for an HTTP answer or `{ reject: "message" }` for a transport failure. Any request that does
 * not match the next step (wrong URL, wrong method, wrong question, or no steps left) is recorded as a violation
 * and rejected. The panel catches fetch errors and shows them, so a rejection alone would not fail a test: the
 * journey's `assertNoViolations()` is what fails it.
 *
 * The global `fetch` is replaced with a function that records a violation on every call, because the panel falls
 * back to the global `fetch` when no `authedFetch` is supplied.
 */
export function scriptedNetwork(vi, steps) {
  const remaining = [...steps];
  const requests = [];
  const violations = [];

  const violation = (kind, url, init) => {
    const v = { kind, url: String(url), method: init?.method || "GET", body: init?.body ?? null };
    violations.push(v);
    return Promise.reject(new Error(`Unscripted request blocked by journey harness: ${v.kind} ${v.method} ${v.url}`));
  };

  const authedFetch = vi.fn((url, init) => {
    const next = remaining[0];
    let question = null;
    try { question = JSON.parse(init?.body ?? "null")?.question ?? null; } catch { question = null; }
    if (!next || String(url) !== ASK_URL || init?.method !== "POST" || question !== next.question) {
      return violation("unscripted", url, init);
    }
    remaining.shift();
    requests.push({ question });
    if (next.reject) return Promise.reject(new Error(next.reject));
    const status = next.status ?? 200;
    return Promise.resolve({
      ok: status >= 200 && status < 300,
      status,
      json: async () => next.body,
      text: async () => (typeof next.body === "string" ? next.body : JSON.stringify(next.body)),
    });
  });

  const globalFetch = vi.fn((url, init) => violation("global-fetch", url, init));
  vi.stubGlobal("fetch", globalFetch);

  return {
    authedFetch,
    globalFetch,
    requests,
    violations,
    unusedSteps: () => remaining.length,
    assertNoViolations() {
      if (violations.length > 0) {
        throw new Error(`Journey made ${violations.length} blocked request(s): ${JSON.stringify(violations)}`);
      }
    },
  };
}

/**
 * Collects per-journey measures for ui/target/ask-journeys.json. `harnessAsserted` says the test's assertions held,
 * including pinned baselines. `productCapability` is MET or GAP: a passing journey with a GAP is not a completed
 * product journey.
 */
export function journeyRecorder() {
  const journeys = [];
  return {
    record(entry) {
      journeys.push({
        journey: entry.journey,
        userTurns: entry.userTurns,
        clarifications: entry.clarifications ?? 0,
        citationShown: entry.citationShown,
        recoveryOffered: entry.recoveryOffered,
        completed: entry.completed,
        harnessAsserted: true,
        productCapability: entry.productCapability,
        gap: entry.gap ?? null,
        evidence: entry.evidence ?? "scripted stub; display and interaction only, not model reasoning",
      });
    },
    journeys,
    write(path = resolve(process.cwd(), "target/ask-journeys.json")) {
      const report = {
        harness: "ask-panel-journeys-tier1",
        design: "docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md",
        model: "stubbed",
        network: "deny-by-default",
        journeys,
        summary: {
          journeys: journeys.length,
          harnessAsserted: journeys.filter(j => j.harnessAsserted).length,
          productCapabilityMet: journeys.filter(j => j.productCapability === "MET").length,
          productCapabilityGap: journeys.filter(j => j.productCapability === "GAP").map(j => j.journey),
        },
      };
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, JSON.stringify(report, null, 2) + "\n");
      return report;
    },
  };
}
