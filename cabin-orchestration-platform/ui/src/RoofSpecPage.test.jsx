import React from "react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent, waitFor } from "@testing-library/react";
import { RoofSpecPage } from "./RoofSpecPage.jsx";

const ok = (body) => ({ ok: true, status: 200, json: async () => body });

describe("RoofSpecPage", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("says it is the owner's own project and that replies are optional", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(ok([]))));
    render(<RoofSpecPage token="tok" />);
    expect(await screen.findByText(/my own project, for my own roof/i)).toBeTruthy();
    expect(screen.getAllByText(/optional/i).length).toBeGreaterThan(0);
  });

  it("makes no sales pitch", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(ok([]))));
    const { container } = render(<RoofSpecPage token="tok" />);
    await screen.findByText(/my own project/i);
    expect(container.textContent).not.toMatch(/Brian|pricing|premium|upsell|white-label|sales proof|revenue/i);
  });

  it("sends the token as ?t= and posts an input for an item", async () => {
    const fetchMock = vi.fn((url, opts) => Promise.resolve(opts?.method === "POST" ? ok({ id: "1" }) : ok([])));
    vi.stubGlobal("fetch", fetchMock);
    render(<RoofSpecPage token="tok" />);
    await screen.findByText(/Furring height and air gap/);
    fireEvent.change(screen.getAllByRole("textbox")[0], { target: { value: "1.5 in furring" } });
    fireEvent.click(screen.getAllByText("Save answer")[0]);
    await waitFor(() => {
      const post = fetchMock.mock.calls.find(([, o]) => o?.method === "POST");
      expect(post[0]).toContain("/api/roof-spec/entries?t=tok");
      expect(JSON.parse(post[1].body)).toEqual({ kind: "input", itemKey: "furring", body: "1.5 in furring" });
    });
  });

  it("shows existing answers under their item with the author", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(ok([
      { id: "a", kind: "input", itemKey: "foam", body: "Polyiso with foil facers", author: "Aaron, Collins", authorKind: "guest", createdAt: "2026-10-28T15:00:00Z" },
    ]))));
    render(<RoofSpecPage token="tok" />);
    expect(await screen.findByText("Polyiso with foil facers")).toBeTruthy();
    expect(screen.getByText("Aaron, Collins")).toBeTruthy();
  });

  it("shows an invalid-link message on 401", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: false, status: 401, json: async () => ({}) })));
    render(<RoofSpecPage token="bad" />);
    expect(await screen.findByText(/isn't valid, has expired, or has been revoked/)).toBeTruthy();
  });

  it("offers Remove only to the owner", async () => {
    const entry = { id: "a", kind: "comment", itemKey: null, body: "hi", author: "A", authorKind: "guest", createdAt: "2026-10-28T15:00:00Z" };
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(ok([entry]))));
    const { rerender } = render(<RoofSpecPage token="tok" />);
    await screen.findByText("hi");
    expect(screen.queryByText("Remove")).toBeNull();
    cleanup();
    render(<RoofSpecPage owner authedFetch={vi.fn(() => Promise.resolve(ok([entry])))} />);
    await screen.findByText("hi");
    expect(screen.getByText("Remove")).toBeTruthy();
  });
});
