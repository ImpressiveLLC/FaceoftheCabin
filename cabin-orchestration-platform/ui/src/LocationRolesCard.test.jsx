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

describe("LocationRolesCard safety and repairs (#342 HOLD)", () => {
  const ONE = { assignments: [{ personId: "nate", location: "cabin", roles: ["primary"] }], unregisteredCounted: [] };

  function recorder(listBody) {
    const puts = [];
    const auth = authWith((url, init) => {
      if (init && init.method === "PUT") { puts.push(JSON.parse(init.body)); return respond(200, {}); }
      return respond(200, listBody);
    });
    return { auth, puts };
  }

  it("unticking a person's last role sends nothing: the box is disabled", async () => {
    const { auth, puts } = recorder(ONE);
    render(<LocationRolesCard auth={auth} />);
    const box = await screen.findByLabelText("nate primary at cabin");
    expect(box.disabled).toBe(true);
    fireEvent.click(box);
    await new Promise(r => setTimeout(r, 20));
    expect(puts).toEqual([]);
  });

  it("Remove asks first, says what happens, and only the confirmed action sends an empty role list", async () => {
    const { auth, puts } = recorder(ONE);
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByLabelText("Remove nate at cabin"));
    expect(screen.getByText(/nate will stop counting as present at cabin/)).toBeTruthy();
    expect(puts).toEqual([]);
    fireEvent.click(screen.getByText("Cancel"));
    expect(puts).toEqual([]);
    fireEvent.click(screen.getByLabelText("Remove nate at cabin"));
    fireEvent.click(screen.getByText("Confirm remove"));
    await waitFor(() => expect(puts).toEqual([{ personId: "nate", location: "cabin", roles: [] }]));
  });

  it("warns that a removed maintenance person counts as presence again", async () => {
    const { auth } = recorder({ assignments: [{ personId: "contractor", location: "cabin", roles: ["maintenance"] }], unregisteredCounted: [] });
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByLabelText("Remove contractor at cabin"));
    expect(screen.getByText(/signals at cabin will count as presence again/)).toBeTruthy();
  });

  it("Assign adds the ticked roles to the ones the person already holds", async () => {
    const { auth, puts } = recorder(ONE);
    render(<LocationRolesCard auth={auth} />);
    fireEvent.change(await screen.findByLabelText("Person id"), { target: { value: "nate" } });
    fireEvent.click(screen.getByLabelText("primary")); // untick the form's default; only maintenance is added
    fireEvent.click(screen.getAllByLabelText("maintenance").find(el => !el.getAttribute("aria-label")?.includes(" at ")));
    expect(screen.getByText(/nate already holds primary at cabin; the ticked roles are added/)).toBeTruthy();
    fireEvent.click(screen.getByText("Assign"));
    await waitFor(() => expect(puts).toEqual([{ personId: "nate", location: "cabin", roles: ["primary", "maintenance"] }]));
  });

  it("keeps the typed person id when the save is refused, and clears it when it is saved", async () => {
    let refuse = true;
    const auth = authWith((url, init) => {
      if (init && init.method === "PUT") return refuse ? respond(400, { error: "unknown location 'mars'" }) : respond(200, {});
      return respond(200, { assignments: [], unregisteredCounted: [] });
    });
    render(<LocationRolesCard auth={auth} />);
    const input = await screen.findByLabelText("Person id");
    fireEvent.change(input, { target: { value: "ghost" } });
    fireEvent.click(screen.getByText("Assign"));
    await screen.findByRole("alert");
    expect(screen.getByLabelText("Person id").value).toBe("ghost");
    refuse = false;
    fireEvent.click(screen.getByText("Assign"));
    await waitFor(() => expect(screen.getByLabelText("Person id").value).toBe(""));
  });

  it("announces a successful save in a polite status region", async () => {
    const { auth } = recorder({ assignments: [], unregisteredCounted: [{ personId: "contractor", location: "cabin" }] });
    render(<LocationRolesCard auth={auth} />);
    fireEvent.click(await screen.findByLabelText("Make contractor maintenance at cabin"));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("contractor at cabin is now maintenance."));
    expect(screen.getByRole("status").getAttribute("aria-live")).toBe("polite");
  });

  it("gives each one-click button the person and location in its name", async () => {
    const { auth } = recorder({ assignments: [], unregisteredCounted: [{ personId: "a", location: "cabin" }, { personId: "b", location: "home" }] });
    render(<LocationRolesCard auth={auth} />);
    expect(await screen.findByLabelText("Make a primary at cabin")).toBeTruthy();
    expect(screen.getByLabelText("Make b maintenance at home")).toBeTruthy();
  });

  it("states that unassigned signals are still counted, and uses plain ASCII dots while loading", async () => {
    const auth = authWith(() => new Promise(() => {})); // never resolves
    const { unmount } = render(<LocationRolesCard auth={auth} />);
    expect(screen.getByText("Loading...")).toBeTruthy();
    unmount();
    render(<LocationRolesCard auth={authWith(() => respond(200, { assignments: [], unregisteredCounted: [] }))} />);
    expect(await screen.findByText(/People publishing presence are still counted/)).toBeTruthy();
  });
});
