import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

// Nate, 2026-11-02: boxes of cards span the full width of the banner bars above them. A fixed column count or an
// auto-fill grid leaves empty tracks on the right when there are few cards, so these grids must be auto-fit.
const css = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "styles.css"), "utf8");
const rule = selector => {
  const start = css.indexOf(`\n${selector} {`);
  expect(start, `${selector} rule`).toBeGreaterThan(-1);
  return css.slice(start, css.indexOf("}", start));
};

describe("card grids fill the row", () => {
  it("Config cards use auto-fit", () => {
    expect(rule(".config-grid")).toMatch(/repeat\(auto-fit,/);
  });
  it("My Places cards use auto-fit, not a fixed three columns", () => {
    const r = rule(".family-hub-grid");
    expect(r).toMatch(/repeat\(auto-fit,/);
    expect(r).not.toMatch(/repeat\(3,/);
  });
  it("no breakpoint forces My Places back to a fixed column count", () => {
    expect(css).not.toMatch(/\.family-hub-grid \{ grid-template-columns: repeat\(2, 1fr\)/);
  });
});
