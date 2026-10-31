// W-38 slice 2 (D24): the admin card that assigns primary / maintenance roles per location.
import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LocationRolesCard } from "./App.jsx";

afterEach(() => cleanup());

function respond(status, body) {
  return Promise.resolve({ status, ok: status >= 200 && status < 300, json: () => Promise.resolve(body) });
}

function authWith(handler) {
  return { authedFetch: vi.fn((url, init) => handler(url, init)) };
}

const EMPTY = { assignments: [], unregisteredCounted: [] };

describe("LocationRolesCard", () => {
  it("tells a non-administrator plainly instead of showing an error", async () => {
    render(<LocationRolesCard auth={authWith(() => respond(403, { error: "no" }))} />);
    expect(await screen.findByText(/Admin access required to assign location roles/)).toBeTruthy();
  });

  it("lists assignments and the people who are counted but not assigned", async () => {
    const auth = authWith(() => respond(200, {
      assignments: [{ personId: "nate", location: "cabin", roles: ["primary"] }],
      unregisteredCounted: [{ personId: "binary_sensor.newphone", location: "cabin" }],
    }));
    render(<LocationRolesCard auth={auth} />);
    expect(await screen.findByText("Counted but not assigned")).toBeTruthy();
    expect(screen.getByText("binary_sensor.newphone")).toBeTruthy();
    expect(screen.getByLabelText("nate primary at cabin").checked).toBe(true);
    expect(screen.getByLabelText("nate maintenance at cabin").checked).toBe(false);
  });

  it("says that nothing is assigned yet and that every signal is counted", async () => {
    render(<LocationRolesCard auth={authWith(() => respond(200, EMPTY))} />);
    expect(await screen.findByText(/No one is assigned yet/)).toBeTruthy();
  });

  it("PUTs the new role list when a box is ticked, then reloads", async () => {
    const calls = [];
    const auth = authWith((url, init) => {
      calls.push([url, init && init.method, init && init.body]);
      if (init && init.method === "PUT") return respond(200, {});
      return respond(200, { assignments: [{ personId: "nate", location: "cabin", roles: ["primary"] }], unregisteredCounted: [] });
    });
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByLabelText("nate maintenance at cabin"));
    await waitFor(() => expect(calls.some(c => c[1] === "PUT")).toBe(true));
    const put = calls.find(c => c[1] === "PUT");
    expect(put[0]).toMatch(/\/api\/presence\/roles$/);
    expect(JSON.parse(put[2])).toEqual({ personId: "nate", location: "cabin", roles: ["primary", "maintenance"] });
    await waitFor(() => expect(calls.filter(c => !c[1]).length).toBeGreaterThan(1)); // reloaded after saving
  });

  it("gives an unassigned person a role with one click", async () => {
    const calls = [];
    const auth = authWith((url, init) => {
      calls.push([init && init.method, init && init.body]);
      if (init && init.method === "PUT") return respond(200, {});
      return respond(200, { assignments: [], unregisteredCounted: [{ personId: "contractor", location: "cabin" }] });
    });
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByText("Make maintenance"));
    await waitFor(() => expect(calls.some(c => c[0] === "PUT")).toBe(true));
    expect(JSON.parse(calls.find(c => c[0] === "PUT")[1])).toEqual({ personId: "contractor", location: "cabin", roles: ["maintenance"] });
  });

  it("shows the server's message when a save is refused", async () => {
    const auth = authWith((url, init) => {
      if (init && init.method === "PUT") return respond(400, { error: "unknown location 'mars'" });
      return respond(200, { assignments: [{ personId: "nate", location: "cabin", roles: ["primary"] }], unregisteredCounted: [] });
    });
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByLabelText("nate maintenance at cabin"));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toContain("unknown location 'mars'");
  });

  it("Assign needs a person id and at least one role", async () => {
    render(<LocationRolesCard auth={authWith(() => respond(200, EMPTY))} />);
    const assign = await screen.findByText("Assign");
    expect(assign.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Person id"), { target: { value: "nate" } });
    expect(assign.disabled).toBe(false);
    fireEvent.click(screen.getByLabelText("primary"));
    expect(assign.disabled).toBe(true);
  });
});
