// C3 prerequisite 5, Tier 1: scripted multi-turn Ask-panel journeys (design #521,
// docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md; ruled by Cowork on #244, 6994148775).
//
// Every answer here is written by the test. These journeys show how HelpdeskPanel displays and handles responses;
// they do not show model reasoning, retrieval quality or redactor correctness. Where the product lacks a capability
// today, the journey pins the actual baseline and records the capability as a C3 GAP, not a pass.
// Network is deny-by-default: see scriptedNetwork in askJourneyHarness.js. Reported measures are observed from the
// rendered panel (observeJourney) and asserted before they are recorded.
import React from "react";
import { describe, it, expect, afterEach, afterAll, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { AppContext, HelpdeskPanel } from "./App.jsx";
import { ASK_URL, answer, reviewedSource, scriptedNetwork, observeJourney, journeyRecorder } from "./askJourneyHarness.js";

const JOURNEYS = ["explain", "stepwise-setup-plan", "follow-up-context", "clarification", "source-gap-recovery",
  "model-unavailable", "request-failure-retry", "signed-out", "credential-question"];
const GUARDS = ["unscripted-request", "global-fetch-fallback"];
// Creating the recorder deletes any earlier report, so only this run's evidence can be on disk.
const recorder = journeyRecorder({ journeys: JOURNEYS, guards: GUARDS });

// Real guide sections, in the backend's reviewed-document source shape.
const GUIDE = "ai-assistant/user-guide";
const DEADMAN = reviewedSource(`${GUIDE}/operations-recovery-reference.md`, "external-dead-man-monitoring");
const PLAN = reviewedSource(`${GUIDE}/independent-installation.md`, "establish-the-actual-installation-plan");
const NOT_RESPONDING = reviewedSource(`${GUIDE}/devices.md`, "why-a-device-says-not-responding-late-or-has-a-blank-card");
const CREDENTIALS = reviewedSource(`${GUIDE}/configuration.md`, "credentials-and-access");

function renderPanel(auth) {
  return render(
    <AppContext.Provider value={{ locationCfg: { apiBase: "http://cabin-hub:8090" } }}>
      <HelpdeskPanel auth={auth} />
    </AppContext.Provider>
  );
}

// Synthetic auth only: never a production session.
const signedIn = (net) => ({ signedIn: true, authedFetch: net.authedFetch });

function ask(text) {
  fireEvent.change(screen.getByPlaceholderText("Ask a question…"), { target: { value: text } });
  fireEvent.click(screen.getByRole("button", { name: /Ask/ }));
}

async function settled() {
  await waitFor(() => expect(screen.queryByText("Thinking…")).toBeNull());
}

const bubbles = (role) => Array.from(document.querySelectorAll(`.helpdesk-${role} .helpdesk-bubble`));
const badge = (src) => `Reviewed doc · ${src.entityRef}`;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

afterAll(() => {
  // The report is written either way, with status FAIL and the missing journeys or guards named if any test
  // did not reach its record call.
  const report = recorder.write();
  expect(report.status).toBe("PASS");
});

describe("Ask panel journeys (Tier 1, scripted stub)", () => {
  it("Explain: one question, a reviewed-document citation", async () => {
    const q = "What does the external dead-man monitor do?";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, "It alerts you when the hub stops checking in.", { sources: [DEADMAN] }) }]);
    renderPanel(signedIn(net));

    ask(q);
    expect(await screen.findByText("It alerts you when the hub stops checking in.")).toBeTruthy();
    expect(screen.getByText(badge(DEADMAN))).toBeTruthy();
    net.assertNoViolations();
    expect(net.unusedSteps()).toBe(0);
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 1, clarifications: 0, citationShown: true, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("explain", m, { productCapability: "MET" });
  });

  it("Stepwise setup plan: numbered steps render in order with the citation, nothing presented as executed", async () => {
    const q = "How do I recreate the instance on a new machine?";
    const steps = "1. Identify the target host.\n2. Provision independent secrets.\n3. Deploy the selected services.";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, steps, { sources: [PLAN] }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await settled();
    const shown = bubbles("assistant")[0].textContent;
    const order = ["1. Identify", "2. Provision", "3. Deploy"].map(s => shown.indexOf(s));
    expect(order.every(i => i >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    expect(screen.getByText(badge(PLAN))).toBeTruthy();
    // The panel has no action controls: the plan is text only.
    expect(document.querySelectorAll(".helpdesk-log button")).toHaveLength(0);
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 1, clarifications: 0, citationShown: true, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("stepwise-setup-plan", m, { productCapability: "MET" });
  });

  it("Follow-up context: both turns stay visible; baseline sends only the new question (C3 GAP)", async () => {
    const q1 = "How do I recreate the instance on a new machine?";
    const q2 = "and after that?";
    const net = scriptedNetwork(vi, [
      { question: q1, body: answer(q1, "First, identify the target host.") },
      { question: q2, body: answer(q2, "I need more detail about what you mean.") },
    ]);
    renderPanel(signedIn(net));

    ask(q1);
    await screen.findByText("First, identify the target host.");
    ask(q2);
    await screen.findByText("I need more detail about what you mean.");

    expect(bubbles("user").map(b => b.textContent)).toEqual([q1, q2]);
    // Baseline pinned: the second request body carries only the new question, no prior turns.
    expect(JSON.parse(net.authedFetch.mock.calls[1][1].body)).toEqual({ question: q2 });
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 2, clarifications: 0, citationShown: false, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("follow-up-context", m, { productCapability: "GAP", gap: "follow-up requests carry only the new question; no conversation context is sent" });
  });

  it("Clarification: a scripted clarifying question, the user's answer, then a final answer", async () => {
    const q1 = "Is it working?";
    const q2 = "The kitchen temperature sensor.";
    const net = scriptedNetwork(vi, [
      { question: q1, body: answer(q1, "Which device do you mean?") },
      { question: q2, body: answer(q2, "The kitchen temperature sensor reported 2 minutes ago.") },
    ]);
    renderPanel(signedIn(net));

    ask(q1);
    await screen.findByText("Which device do you mean?");
    ask(q2);
    await screen.findByText("The kitchen temperature sensor reported 2 minutes ago.");

    expect(bubbles("assistant").map(b => b.textContent)).toEqual([
      "Which device do you mean?", "The kitchen temperature sensor reported 2 minutes ago.",
    ]);
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 2, clarifications: 1, citationShown: false, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("clarification", m, { productCapability: "MET",
      evidence: "scripted stub; shows display and interaction, not that the model asks good questions" });
  });

  it("Source gap: no sources gives the answer unchanged and no recovery guidance (C3 GAP)", async () => {
    const q = "What is the warranty on the dehumidifier?";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, "I don't know.", { sources: [] }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await settled();
    const [bubble] = bubbles("assistant");
    // Baseline pinned: the text renders unchanged, with no sources block and nothing added.
    expect(bubble.textContent).toBe("I don't know.");
    expect(bubble.querySelector(".helpdesk-sources")).toBeNull();
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 1, clarifications: 0, citationShown: false, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("source-gap-recovery", m, { productCapability: "GAP", gap: "no useful recovery guidance when no source supports the answer" });
  });

  it("Model unavailable: fallback note shown and sources still listed", async () => {
    const q = "Why does the kitchen sensor say not responding?";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, "kitchen sensor: last seen 3 hours ago", {
      answeredByModel: false, sources: [NOT_RESPONDING],
    }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await screen.findByText(/kitchen sensor: last seen 3 hours ago/);
    expect(screen.getByText(/The assistant wasn't reachable/)).toBeTruthy();
    expect(screen.getByText(badge(NOT_RESPONDING))).toBeTruthy();
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 1, clarifications: 0, citationShown: true, recoveryOffered: true, finalAnswerShown: true, errorVisible: false });
    recorder.record("model-unavailable", m, { productCapability: "MET" });
  });

  it("Request failure and retry: error shown, retry clears it, the answer is shown", async () => {
    const q = "Is the CO sensor online?";
    const net = scriptedNetwork(vi, [
      { question: q, status: 503, body: "Service Unavailable" },
      { question: q, body: answer(q, "Yes, it reported a minute ago.") },
    ]);
    renderPanel(signedIn(net));

    ask(q);
    expect(await screen.findByText("Service Unavailable")).toBeTruthy();
    ask(q);
    expect(await screen.findByText("Yes, it reported a minute ago.")).toBeTruthy();
    expect(screen.queryByText("Service Unavailable")).toBeNull();
    net.assertNoViolations();
    expect(net.unusedSteps()).toBe(0);
    // The error was seen above and is now gone, so the recovery is observed, not declared.
    const m = observeJourney(document.body, { recoveredFromError: true });
    expect(m).toEqual({ userTurns: 2, clarifications: 0, citationShown: false, recoveryOffered: true, finalAnswerShown: true, errorVisible: false });
    recorder.record("request-failure-retry", m, { productCapability: "MET" });
  });

  it("Signed out: sign-in prompt only and no request sent", () => {
    const net = scriptedNetwork(vi, []);
    renderPanel({ signedIn: false, signIn: () => {}, authedFetch: net.authedFetch });

    expect(screen.getByText(/Sign in to ask Tiny Helpdesk a question/)).toBeTruthy();
    expect(screen.queryByPlaceholderText("Ask a question…")).toBeNull();
    expect(net.authedFetch).not.toHaveBeenCalled();
    expect(net.globalFetch).not.toHaveBeenCalled();
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 0, clarifications: 0, citationShown: false, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("signed-out", m, { productCapability: "MET", evidence: "sign-in prompt shown; no request sent" });
  });

  it("Credential question: the administrator-pointer text displays unchanged", async () => {
    const q = "What is the Grafana admin password?";
    const pointer = "Credentials are not shown here. An administrator can find this in the vault entry grafana_admin_password.";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, pointer, { sources: [CREDENTIALS] }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await settled();
    expect(bubbles("assistant")[0].firstChild.textContent).toBe(pointer);
    expect(screen.getByText(badge(CREDENTIALS))).toBeTruthy();
    net.assertNoViolations();
    const m = observeJourney(document.body);
    expect(m).toEqual({ userTurns: 1, clarifications: 0, citationShown: true, recoveryOffered: false, finalAnswerShown: true, errorVisible: false });
    recorder.record("credential-question", m, { productCapability: "MET",
      evidence: "scripted stub; display only, redactor correctness is covered by backend tests" });
  });
});

describe("Ask journey network guards", () => {
  it("catches an unscripted request through authedFetch even though the panel swallows the error", async () => {
    const net = scriptedNetwork(vi, [{ question: "scripted question", body: answer("scripted question", "never sent") }]);
    renderPanel(signedIn(net));

    ask("a different question");
    expect(await screen.findByText(/Unscripted request blocked by journey harness: unscripted POST/)).toBeTruthy();
    expect(net.violations).toEqual([expect.objectContaining({ kind: "unscripted", url: ASK_URL })]);
    expect(() => net.assertNoViolations()).toThrow(/blocked request/);
    recorder.guardPassed("unscripted-request");
  });

  it("catches the global fetch fallback when no authedFetch is supplied", async () => {
    const net = scriptedNetwork(vi, []);
    renderPanel(undefined);

    ask("Is the CO sensor online?");
    expect(await screen.findByText(/Unscripted request blocked by journey harness: global-fetch POST/)).toBeTruthy();
    expect(net.globalFetch).toHaveBeenCalledTimes(1);
    expect(net.authedFetch).not.toHaveBeenCalled();
    expect(() => net.assertNoViolations()).toThrow(/global-fetch/);
    recorder.guardPassed("global-fetch-fallback");
  });
});
