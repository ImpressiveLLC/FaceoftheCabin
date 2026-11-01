import React from "react";
import { describe, it, expect, afterEach, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, act, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import {
  ConfigCard, ConfigHeights, useConfigHeights,
  CONFIG_CARD_HEIGHT_RATIO, CONFIG_CARD_MIN_BUDGET, CONFIG_CARD_TOGGLE_ALLOWANCE,
} from "./App.jsx";
import { THEMES } from "./ThemeProvider.jsx";
import { contrastRatio } from "./surfaceLayers.js";

// Nate, 2026-11-01: no single-column card in Config over 133% of the average of the cards beside it.
// jsdom has no layout, so a box's "height" is faked as the length of its text. A card is built from a one-letter title and
// n characters of body, so its natural height is exactly 1 + n and every number below can be worked out by hand.
describe("Config cards stay within a height budget", () => {
  const realObserver = globalThis.ResizeObserver;
  const heightProp = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");
  let observers;

  beforeEach(() => {
    observers = [];
    globalThis.ResizeObserver = class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe() {}
      disconnect() { observers = observers.filter(o => o !== this); }
    };
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", { configurable: true, get() { return this.textContent.length; } });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    globalThis.ResizeObserver = realObserver;
    if (heightProp) Object.defineProperty(HTMLElement.prototype, "offsetHeight", heightProp);
    else delete HTMLElement.prototype.offsetHeight;
  });

  const Icon = () => null;
  function Grid({ cards }) {
    const heights = useConfigHeights();
    return (
      <ConfigHeights.Provider value={heights}>
        {cards.map(({ title, n, ...rest }) => (
          <ConfigCard key={title} title={title} icon={Icon} {...rest}>
            <p>{"x".repeat(n)}</p>
            <input aria-label={`field ${title}`} />
          </ConfigCard>
        ))}
      </ConfigHeights.Provider>
    );
  }
  // the input has no text, so a card's natural height is 1 (title) + n
  const card = (title) => screen.getByText(title, { selector: "strong" }).closest(".config-card");
  const shortened = (title) => card(title).className.includes("config-card-capped");
  const peers = (n) => [{ title: "A", n }, { title: "B", n }];

  it("pins the numbers: ratio 1.33, floor 200, the card itself left out of the average", () => {
    expect(CONFIG_CARD_HEIGHT_RATIO).toBe(1.33);
    expect(CONFIG_CARD_MIN_BUDGET).toBe(200);
    expect(CONFIG_CARD_TOGGLE_ALLOWANCE).toBe(64);
  });

  it("ratio: peers of 301 give a budget of 400; 400 fits, 401 is shortened", () => {
    render(<Grid cards={[...peers(300), { title: "X", n: 399 }]} />);
    expect(shortened("X")).toBe(false);
    cleanup();
    render(<Grid cards={[...peers(300), { title: "X", n: 400 }]} />);
    expect(shortened("X")).toBe(true);
    expect(shortened("A")).toBe(false);
  });

  it("floor: peers of 101 would give 134, but the budget is never below 200", () => {
    render(<Grid cards={[...peers(100), { title: "X", n: 199 }]} />);
    expect(shortened("X")).toBe(false);
    cleanup();
    render(<Grid cards={[...peers(100), { title: "X", n: 200 }]} />);
    expect(shortened("X")).toBe(true);
  });

  it("the card itself is not in the average it is measured against", () => {
    // peers 301, 301; the card is 450. Left out: mean 301, budget 400, shortened. Included: mean 350.7, budget 466, not shortened.
    render(<Grid cards={[...peers(300), { title: "X", n: 449 }]} />);
    expect(shortened("X")).toBe(true);
  });

  it("shortens the body so the card plus its button stays inside the budget", () => {
    render(<Grid cards={[...peers(300), { title: "X", n: 450 }]} />);
    // budget 400, header and padding 1, so the body gets 400 - 1 - 64
    expect(card("X").querySelector(".config-card-body").style.maxHeight).toBe(`${400 - 1 - CONFIG_CARD_TOGGLE_ALLOWANCE}px`);
  });

  it("never shortens a full-row card or one that holds a control to cut off access", () => {
    render(<Grid cards={[...peers(300), { title: "W", n: 900, wide: true }, { title: "G", n: 900, neverShorten: true }]} />);
    expect(shortened("W")).toBe(false);
    expect(shortened("G")).toBe(false);
    expect(within(card("G")).queryByRole("button")).toBeNull();
  });

  it("names its button after the card, points it at the body, and Expand then Minimize toggles", () => {
    render(<Grid cards={[...peers(300), { title: "X", n: 450 }]} />);
    const toggle = screen.getByRole("button", { name: "Expand X" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(toggle.getAttribute("aria-controls")).toBe(card("X").querySelector(".config-card-body").id);
    fireEvent.click(toggle);
    expect(screen.getByRole("button", { name: "Minimize X" }).getAttribute("aria-expanded")).toBe("true");
    expect(card("X").className).toContain("config-card-open");
    fireEvent.click(screen.getByRole("button", { name: "Minimize X" }));
    expect(shortened("X")).toBe(true);
  });

  it("a click on a visible field does not open the card; keyboard focus into it does", () => {
    const original = Element.prototype.matches;
    let keyboard = false;
    vi.spyOn(Element.prototype, "matches").mockImplementation(function (selector) {
      return selector === ":focus-visible" ? keyboard : original.call(this, selector);
    });
    render(<Grid cards={[...peers(300), { title: "X", n: 450 }]} />);
    fireEvent.focus(screen.getByLabelText("field X"));
    expect(shortened("X")).toBe(true);
    keyboard = true;
    fireEvent.focus(screen.getByLabelText("field X"));
    expect(card("X").className).toContain("config-card-open");
  });

  it("an open card keeps its Minimize button when its content later shrinks under the budget", () => {
    const { rerender } = render(<Grid cards={[...peers(300), { title: "X", n: 450 }]} />);
    fireEvent.click(screen.getByRole("button", { name: "Expand X" }));
    rerender(<Grid cards={[...peers(300), { title: "X", n: 100 }]} />);
    act(() => { observers.forEach(o => o.callback()); });
    expect(screen.getByRole("button", { name: "Minimize X" })).toBeTruthy();
  });

  it("adds nothing where heights cannot be measured (checked with the same cards that are shortened when they can)", () => {
    const cards = [...peers(300), { title: "X", n: 450 }];
    render(<Grid cards={cards} />);
    expect(shortened("X")).toBe(true); // precondition: these cards are shortened when ResizeObserver exists
    cleanup();
    globalThis.ResizeObserver = undefined;
    render(<Grid cards={cards} />);
    expect(document.querySelector(".config-card-capped")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("the Expand / Minimize button is readable in every theme (text on the panel colour, 4.5:1)", () => {
    for (const [name, theme] of Object.entries(THEMES)) {
      expect(contrastRatio(theme.vars["--text"], theme.vars["--bg-secondary"]), name).toBeGreaterThanOrEqual(4.5);
    }
    const here = path.dirname(fileURLToPath(import.meta.url));
    const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    expect(css).toMatch(/\.expandable-list-toggle\s*\{[^}]*color:\s*var\(--text\)[^}]*background:\s*var\(--bg-secondary\)/);
    expect(css).toMatch(/\.config-card-capped:not\(\.config-card-open\) \.config-card-body\s*\{[^}]*overflow:\s*hidden/);
    expect(css).toMatch(/\.config-card-open \.config-card-body\s*\{[^}]*max-height:\s*75vh[^}]*overflow-y:\s*auto/);
  });
});
