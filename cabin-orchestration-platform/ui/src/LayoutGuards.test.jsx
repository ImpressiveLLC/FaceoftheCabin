import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].map(([, selector, body]) => ({
  selector: selector.trim().replace(/\s+/g, " "),
  body,
}));
const bodyOf = (selector) => rules.filter(r => r.selector === selector || r.selector.split(",").map(s => s.trim()).includes(selector)).map(r => r.body).join(";");

// jsdom has no layout, so these pin the CSS that fixed two reported problems
// (found by measuring in a 425px browser, 2026-09-19).
describe("links styled as buttons are real boxes", () => {
  // An inline <a> paints its padding and border over the lines around it when its
  // label wraps: the frame cut through the paragraph above and its own text.
  it.each(["a.btn-primary", "a.btn-secondary", "a.btn-ghost", "a.btn-danger"])("%s is inline-flex and stays inside its container", (sel) => {
    const body = bodyOf(sel);
    expect(body).toMatch(/display:\s*inline-flex/);
    expect(body).toMatch(/max-width:\s*100%/);
    expect(body).toMatch(/box-sizing:\s*border-box/);
  });

  it("has space above it inside a config card so it never touches the hint text", () => {
    expect(bodyOf(".config-card a.btn-secondary")).toMatch(/margin-top:\s*\d+px/);
  });
});

describe("config cards contain long values", () => {
  // A grid item won't shrink below its content, so one long unbroken value
  // (a commit hash, a firmware string) pushed Platform Info past its card.
  it("lets the card shrink and wrap anywhere", () => {
    const body = bodyOf(".config-card");
    expect(body).toMatch(/min-width:\s*0/);
    expect(body).toMatch(/overflow-wrap:\s*anywhere/);
  });

  it("wraps the platform info table's cells and doesn't force its labels onto one line", () => {
    expect(bodyOf(".platform-info-table td")).toMatch(/overflow-wrap:\s*anywhere/);
    expect(bodyOf(".platform-info-table td:first-child")).not.toMatch(/white-space:\s*nowrap/);
  });
});

// Reported 2026-09-20: in Devices > Change the bottom of a long list, and the
// groups below the first, couldn't be reached. Measured in a browser with 120
// devices in 6 groups: the list's scrollHeight equalled its clientHeight (580px)
// because every group had been squashed to fit; after the fix it is 7242px and
// scrolls to the last row.
describe("the device list scrolls instead of squashing its groups", () => {
  it("keeps each child of the capped, scrolling list at its natural height", () => {
    expect(bodyOf(".dm-list")).toMatch(/display:\s*flex/);
    expect(bodyOf(".dm-list > *")).toMatch(/flex-shrink:\s*0/);
  });

  it("a group is overflow:hidden, which is exactly why it needs that", () => {
    expect(bodyOf(".dm-device-group")).toMatch(/overflow:\s*hidden/);
  });
});
