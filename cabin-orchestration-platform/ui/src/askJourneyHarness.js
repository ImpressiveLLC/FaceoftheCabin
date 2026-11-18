// C3 prerequisite 5, Tier 1 (design: docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md).
// Test-only helpers for scripted Ask-panel journeys. Nothing here contacts a model or backend.
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, resolve } from "node:path";

export const ASK_URL = "http://cabin-hub:8090/api/helpdesk/ask";
export const REPORT_PATH = resolve(process.cwd(), "target/ask-journeys.json");

/** A response shaped like the backend's TinyHelpdeskAnswer record. */
export function answer(question, text, { sources = [], answeredByModel = true } = {}) {
  return { question, answer: text, sources, answeredByModel };
}

/**
 * A synthetic source in the backend's reviewed-document shape (AskContextBuilder): entityRef `doc:<path>#<anchor>`,
 * chunkType DESCRIPTION, source REVIEWED_DOCUMENT. Callers pass real guide paths and heading anchors. The content
 * is placeholder text: these journeys check display, not what the section says.
 */
export function reviewedSource(path, anchor) {
  return {
    entityRef: `doc:${path}#${anchor}`,
    chunkType: "DESCRIPTION",
    content: "synthetic placeholder for the reviewed section",
    source: "REVIEWED_DOCUMENT",
    generatedAt: "2026-11-18T00:00:00Z",
  };
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
 * Measures observed in the rendered panel, not declared by the test. `recoveredFromError` is the one input the
 * DOM cannot show after the fact: the journey passes true only after it saw an error and then saw it cleared.
 */
export function observeJourney(root, { recoveredFromError = false } = {}) {
  const userBubbles = root.querySelectorAll(".helpdesk-user .helpdesk-bubble");
  const assistantBubbles = Array.from(root.querySelectorAll(".helpdesk-assistant .helpdesk-bubble:not(.helpdesk-thinking)"));
  const answerText = (b) => (b.firstChild && b.firstChild.nodeType === 3 ? b.firstChild.textContent : "");
  const fallbackShown = root.querySelectorAll(".helpdesk-fallback-note").length > 0;
  const signInShown = /Sign in to ask Tiny Helpdesk a question/.test(root.textContent);
  return {
    userTurns: userBubbles.length,
    // An assistant reply that ends in a question mark and is followed by another user turn is a clarification.
    clarifications: assistantBubbles.slice(0, -1).filter(b => answerText(b).trim().endsWith("?")).length,
    citationShown: root.querySelectorAll(".helpdesk-source-badge").length > 0,
    recoveryOffered: fallbackShown || recoveredFromError,
    finalAnswerShown: signInShown || (assistantBubbles.length > 0 && answerText(assistantBubbles.at(-1)).length > 0),
    errorVisible: root.querySelectorAll(".add-place-error").length > 0,
  };
}

function git(args) {
  try {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return null;
  }
}

/** The commit the evidence belongs to, and whether the working tree had uncommitted changes when it ran. */
function gitIdentity() {
  const status = git(["status", "--porcelain", "--", "."]);
  return {
    gitHead: process.env.GITHUB_SHA || git(["rev-parse", "HEAD"]) || "unknown",
    workingTreeDirty: status === null ? "unknown" : status.length > 0,
  };
}

/**
 * Collects per-journey measures for ui/target/ask-journeys.json. Creating the recorder deletes any earlier report,
 * so a failed run cannot leave older clean evidence behind. The report always lists every expected journey and
 * guard: one whose test did not reach its record call is reported as not asserted, and the run status is FAIL.
 * `productCapability` is MET or GAP: a passing journey with a GAP is not a completed product journey.
 */
export function journeyRecorder({ journeys: expectedJourneys, guards: expectedGuards, path = REPORT_PATH }) {
  if (existsSync(path)) rmSync(path);
  const startedAt = new Date().toISOString();
  const journeys = new Map();
  const guards = new Map();
  return {
    record(journey, observed, { productCapability, gap = null, evidence } = {}) {
      if (!expectedJourneys.includes(journey)) throw new Error(`Unknown journey ${journey}`);
      const { finalAnswerShown, errorVisible, ...measures } = observed;
      journeys.set(journey, {
        journey,
        ...measures,
        completed: finalAnswerShown && !errorVisible && productCapability === "MET",
        harnessAsserted: true,
        productCapability,
        gap,
        evidence: evidence ?? "scripted stub; display and interaction only, not model reasoning",
      });
    },
    guardPassed(name) {
      if (!expectedGuards.includes(name)) throw new Error(`Unknown guard ${name}`);
      guards.set(name, "PASS");
    },
    complete() {
      return expectedJourneys.every(j => journeys.has(j)) && expectedGuards.every(g => guards.get(g) === "PASS");
    },
    write() {
      const rows = expectedJourneys.map(j => journeys.get(j) ?? { journey: j, harnessAsserted: false, productCapability: "NOT_ASSERTED" });
      const report = {
        harness: "ask-panel-journeys-tier1",
        design: "docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md",
        status: this.complete() ? "PASS" : "FAIL",
        run: { startedAt, finishedAt: new Date().toISOString(), ...gitIdentity() },
        model: "stubbed",
        network: "deny-by-default",
        guards: Object.fromEntries(expectedGuards.map(g => [g, guards.get(g) ?? "NOT_PASSED"])),
        journeys: rows,
        summary: {
          expectedJourneys: expectedJourneys.length,
          harnessAsserted: rows.filter(r => r.harnessAsserted).length,
          productCapabilityMet: rows.filter(r => r.productCapability === "MET").length,
          productCapabilityGap: rows.filter(r => r.productCapability === "GAP").map(r => r.journey),
        },
      };
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, JSON.stringify(report, null, 2) + "\n");
      return report;
    },
  };
}
