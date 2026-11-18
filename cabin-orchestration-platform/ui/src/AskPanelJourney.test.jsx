// C3 prerequisite 5, Tier 1: scripted multi-turn Ask-panel journeys (design #521,
// docs/governance/proposals/2026-11-18-c3-ask-panel-journey-harness.md; ruled by Cowork on #244, 6994148775).
//
// Every answer here is written by the test. These journeys show how HelpdeskPanel displays and handles responses;
// they do not show model reasoning, retrieval quality or redactor correctness. Where the product lacks a capability
// today, the journey pins the actual baseline and records the capability as a C3 GAP, not a pass.
// Network is deny-by-default: see scriptedNetwork in askJourneyHarness.js.
import React from "react";
import { describe, it, expect, afterEach, afterAll, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { AppContext, HelpdeskPanel } from "./App.jsx";
import { ASK_URL, answer, reviewedSource, scriptedNetwork, journeyRecorder } from "./askJourneyHarness.js";

const recorder = journeyRecorder();
const EXPECTED_JOURNEYS = 9;

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

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

afterAll(() => {
  // Only journeys whose assertions all held are recorded, so a missing journey means a failed one.
  expect(recorder.journeys.length).toBe(EXPECTED_JOURNEYS);
  recorder.write();
});

describe("Ask panel journeys (Tier 1, scripted stub)", () => {
  it("Explain: one question, a reviewed-document citation", async () => {
    const q = "What does the external dead-man monitor do?";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, "It alerts you when the hub stops checking in.", {
      sources: [reviewedSource("ai-assistant/user-guide/operations-recovery-reference.md#external-dead-man-monitoring")],
    }) }]);
    renderPanel(signedIn(net));

    ask(q);
    expect(await screen.findByText("It alerts you when the hub stops checking in.")).toBeTruthy();
    expect(screen.getByText(/Reviewed doc · ai-assistant\/user-guide\/operations-recovery-reference\.md#external-dead-man-monitoring/)).toBeTruthy();
    expect(bubbles("user")).toHaveLength(1);
    net.assertNoViolations();
    expect(net.unusedSteps()).toBe(0);
    recorder.record({ journey: "explain", userTurns: 1, citationShown: true, recoveryOffered: false, completed: true, productCapability: "MET" });
  });

  it("Stepwise setup plan: numbered steps render in order with the citation, nothing presented as executed", async () => {
    const q = "How do I recreate the instance on a new machine?";
    const steps = "1. Identify the target host.\n2. Provision independent secrets.\n3. Deploy the selected services.";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, steps, {
      sources: [reviewedSource("ai-assistant/user-guide/independent-installation.md#installation-plan")],
    }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await settled();
    const shown = bubbles("assistant")[0].textContent;
    const order = ["1. Identify", "2. Provision", "3. Deploy"].map(s => shown.indexOf(s));
    expect(order.every(i => i >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    expect(screen.getByText(/Reviewed doc · .*independent-installation\.md#installation-plan/)).toBeTruthy();
    // The panel has no action controls: the plan is text only.
    expect(document.querySelectorAll(".helpdesk-log button")).toHaveLength(0);
    net.assertNoViolations();
    recorder.record({ journey: "stepwise-setup-plan", userTurns: 1, citationShown: true, recoveryOffered: false, completed: true, productCapability: "MET" });
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
    const secondBody = JSON.parse(net.authedFetch.mock.calls[1][1].body);
    expect(secondBody).toEqual({ question: q2 });
    net.assertNoViolations();
    recorder.record({ journey: "follow-up-context", userTurns: 2, citationShown: false, recoveryOffered: false, completed: false,
      productCapability: "GAP", gap: "follow-up requests carry only the new question; no conversation context is sent" });
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

    expect(bubbles("user")).toHaveLength(2);
    expect(bubbles("assistant").map(b => b.textContent)).toEqual([
      "Which device do you mean?", "The kitchen temperature sensor reported 2 minutes ago.",
    ]);
    net.assertNoViolations();
    recorder.record({ journey: "clarification", userTurns: 2, clarifications: 1, citationShown: false, recoveryOffered: false, completed: true,
      productCapability: "MET", evidence: "scripted stub; shows display and interaction, not that the model asks good questions" });
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
    recorder.record({ journey: "source-gap-recovery", userTurns: 1, citationShown: false, recoveryOffered: false, completed: false,
      productCapability: "GAP", gap: "no useful recovery guidance when no source supports the answer" });
  });

  it("Model unavailable: fallback note shown and sources still listed", async () => {
    const q = "What does the kitchen temperature sensor report?";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, "kitchen temperature: 68F", {
      answeredByModel: false, sources: [reviewedSource("ai-assistant/user-guide/devices.md#temperature")],
    }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await screen.findByText(/kitchen temperature: 68F/);
    expect(screen.getByText(/The assistant wasn't reachable/)).toBeTruthy();
    expect(screen.getByText(/Reviewed doc · .*devices\.md#temperature/)).toBeTruthy();
    net.assertNoViolations();
    recorder.record({ journey: "model-unavailable", userTurns: 1, citationShown: true, recoveryOffered: true, completed: true, productCapability: "MET" });
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
    recorder.record({ journey: "request-failure-retry", userTurns: 2, citationShown: false, recoveryOffered: true, completed: true, productCapability: "MET" });
  });

  it("Signed out: sign-in prompt only and no request sent", () => {
    const net = scriptedNetwork(vi, []);
    renderPanel({ signedIn: false, signIn: () => {}, authedFetch: net.authedFetch });

    expect(screen.getByText(/Sign in to ask Tiny Helpdesk a question/)).toBeTruthy();
    expect(screen.queryByPlaceholderText("Ask a question…")).toBeNull();
    expect(net.authedFetch).not.toHaveBeenCalled();
    expect(net.globalFetch).not.toHaveBeenCalled();
    net.assertNoViolations();
    recorder.record({ journey: "signed-out", userTurns: 0, citationShown: false, recoveryOffered: false, completed: true, productCapability: "MET" });
  });

  it("Credential question: the administrator-pointer text displays unchanged", async () => {
    const q = "What is the Grafana admin password?";
    const pointer = "Credentials are not shown here. An administrator can find this in the vault entry grafana_admin_password.";
    const net = scriptedNetwork(vi, [{ question: q, body: answer(q, pointer, {
      sources: [reviewedSource("ai-assistant/user-guide/configuration.md#credentials-and-access")],
    }) }]);
    renderPanel(signedIn(net));

    ask(q);
    await settled();
    expect(bubbles("assistant")[0].firstChild.textContent).toBe(pointer);
    net.assertNoViolations();
    recorder.record({ journey: "credential-question", userTurns: 1, citationShown: true, recoveryOffered: false, completed: true,
      productCapability: "MET", evidence: "scripted stub; display only, redactor correctness is covered by backend tests" });
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
  });

  it("catches the global fetch fallback when no authedFetch is supplied", async () => {
    const net = scriptedNetwork(vi, []);
    renderPanel(undefined);

    ask("Is the CO sensor online?");
    expect(await screen.findByText(/Unscripted request blocked by journey harness: global-fetch POST/)).toBeTruthy();
    expect(net.globalFetch).toHaveBeenCalledTimes(1);
    expect(net.authedFetch).not.toHaveBeenCalled();
    expect(() => net.assertNoViolations()).toThrow(/global-fetch/);
  });
});
