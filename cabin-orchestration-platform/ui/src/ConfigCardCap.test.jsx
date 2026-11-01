import React from "react";
import { describe, it, expect, afterEach, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { FamilyConfigPanel, AppContext } from "./App.jsx";

// jsdom has no layout: a card's "height" is faked as the length of its text, which is enough to make one card far
// taller than its peers. Nate, 2026-11-01: no single-column card in Config over 133% of the average of the cards beside it.
describe("Config cards stay within a height budget", () => {
  const realObserver = globalThis.ResizeObserver;
  const heightProp = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");

  beforeEach(() => {
    globalThis.ResizeObserver = class { observe() {} disconnect() {} };
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", { configurable: true, get() { return this.textContent.length; } });
  });
  afterEach(() => {
    cleanup();
    globalThis.ResizeObserver = realObserver;
    if (heightProp) Object.defineProperty(HTMLElement.prototype, "offsetHeight", heightProp);
    else delete HTMLElement.prototype.offsetHeight;
  });

  // demo auth hides the sign-in-only cards, leaving Notification Preferences (short), Remote Access (long) and Platform (full row)
  const renderPanel = () => render(
    <AppContext.Provider value={{ config: {}, locationCfg: { haUrl: "http://cabin-hub:8123" } }}>
      <FamilyConfigPanel auth={{ demo: true }} />
    </AppContext.Provider>
  );
  const card = (title) => screen.getByText(title).closest(".config-card");

  it("clips only the card that is over budget, and marks how to open it", () => {
    renderPanel();
    expect(within(card("Remote Access")).getByRole("button", { name: /Expand/ }).getAttribute("aria-expanded")).toBe("false");
    expect(card("Remote Access").className).toContain("config-card-capped");
    expect(within(card("Notification Preferences")).queryByRole("button", { name: /Expand|Minimize/ })).toBeNull();
    expect(within(card("Platform")).queryByRole("button", { name: /Expand|Minimize/ })).toBeNull();
  });

  it("Expand opens the card and Minimize puts it back", () => {
    renderPanel();
    const toggle = within(card("Remote Access")).getByRole("button", { name: /Expand/ });
    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(toggle.textContent).toContain("Minimize");
    expect(card("Remote Access").className).toContain("config-card-open");
    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(card("Remote Access").className).not.toContain("config-card-open");
  });

  it("focus moving into a clipped card opens it", () => {
    renderPanel();
    const link = within(card("Remote Access")).getAllByRole("group")[0] ?? card("Remote Access").querySelector("summary");
    fireEvent.focus(link, { bubbles: true });
    expect(card("Remote Access").className).toContain("config-card-open");
  });

  it("adds nothing where heights cannot be measured", () => {
    globalThis.ResizeObserver = undefined;
    renderPanel();
    expect(document.querySelector(".config-card-capped")).toBeNull();
  });

  it("styles pin: clipped while closed, scrolling inside its own box when open", () => {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    expect(css).toMatch(/\.config-card-capped:not\(\.config-card-open\) \.config-card-body\s*\{[^}]*overflow:\s*hidden/);
    expect(css).toMatch(/\.config-card-open \.config-card-body\s*\{[^}]*max-height:\s*75vh[^}]*overflow-y:\s*auto/);
  });
});
