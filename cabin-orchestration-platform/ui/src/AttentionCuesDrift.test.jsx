import { describe, it, expect } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { THEMES } from "./ThemeProvider.jsx";
import { ATTN_STATES, attnVar, attnInkVar } from "./attentionTokens.js";

// W-110: the attention cue CSS (border pattern, corner asterisks, label chip, system-setting handling) is one block embedded in both
// styles.css (Cabin UI) and family-hub.html, the same way surfaceLayers.js is embedded. This fails the build if the two copies differ,
// and enforces the W-109 matrix rules that can be checked from the text: no animation, no glow, no bar, no hard-coded colour, hue never
// the only cue, and every token the block reads exists in every theme.
const here = path.dirname(fileURLToPath(import.meta.url));
const hubPath = ["/family-hub/family-hub.html", path.join(here, "../../../family-hub/family-hub.html")].find(existsSync) || null;

const extract = (text) => {
  const a = text.indexOf("/* <attention-cues> */"), b = text.indexOf("/* </attention-cues> */");
  if (a < 0 || b < 0) throw new Error("attention-cues markers missing");
  return text.slice(a, b + "/* </attention-cues> */".length).replace(/\r/g, "").split("\n").map((l) => l.trim()).filter(Boolean).join("\n");
};
const cabinCss = extract(readFileSync(path.join(here, "styles.css"), "utf8"));
const hubCss = hubPath ? extract(readFileSync(hubPath, "utf8")) : null;
const code = cabinCss.replace(/\/\*[\s\S]*?\*\//g, ""); // rules only, no comments

describe("attention cue CSS", () => {
  it("is identical in the Cabin UI and Family Hub", () => {
    expect(hubCss, "family-hub/family-hub.html must be reachable").not.toBeNull();
    expect(hubCss).toBe(cabinCss);
  });

  it("never animates, glows or draws a bar: pulse stays reserved for the critical alert", () => {
    expect(code).not.toMatch(/@keyframes|animation|transition/);
    expect(code).not.toMatch(/box-shadow|text-shadow|drop-shadow|filter\s*:/);
    expect(code).not.toMatch(/border-left|border-top|border-bottom|border-right/);
  });

  it("uses no hard-coded colour and never reuses the layer or danger channels", () => {
    expect(code).not.toMatch(/#[0-9a-fA-F]{3,8}\b(?![0-9a-fA-F])/); // data-URI colours are named, not hex
    expect(code).not.toMatch(/rgba?\(|hsla?\(/);
    expect(code).not.toMatch(/--layer-|--danger|--state-alarm|--rose/);
  });

  it("gives every state a non-colour cue that differs from every other state", () => {
    expect(code).toMatch(/\[data-attn="watch"\][^{]*\{[^}]*dotted/);
    expect(code).toMatch(/\[data-attn="alert"\][^{]*\{[^}]*solid[^}]*outline/);
    expect(code).toMatch(/\[data-attn="unavailable"\][^{]*\{[^}]*dashed/);
    expect(code).toMatch(/\[data-attn="last-known-alert"\][^{]*\{[^}]*dashed/);
    // asterisks in all four corners, on Alert only (plus the outlined last-known Alert)
    expect(code.match(/left top, right top, left bottom, right bottom/g)).toHaveLength(2); // -webkit- and standard
    expect(code).not.toMatch(/\[data-attn="(watch|normal)"\]::after/);
    // a text label with its own silhouette for every state
    for (const state of ["watch", "alert", "unavailable", "last-known-alert"]) expect(code).toContain(`[data-attn-chip="${state}"]`);
  });

  it("follows the system settings: stronger borders for prefers-contrast, system colours for forced-colors, nothing to remove for reduced motion", () => {
    expect(code).toMatch(/@media \(prefers-contrast: more\)/);
    expect(code).toMatch(/@media \(forced-colors: active\)[\s\S]*CanvasText/);
    expect(code).not.toMatch(/prefers-reduced-motion/); // there is no motion in this block to remove
  });

  it("reads only tokens that every theme defines, in both apps", () => {
    const used = [...new Set([...code.matchAll(/var\((--attn-[a-z-]+)/g)].map((m) => m[1]))];
    const supplied = new Set([...ATTN_STATES.flatMap((s) => [attnVar(s), attnInkVar(s)]), "--attn-cue-scale"]);
    // block-local helpers are defined inside the block itself
    const local = new Set([...code.matchAll(/(--attn-[a-z-]+)\s*:/g)].map((m) => m[1]));
    for (const name of used.filter((u) => !local.has(u) || supplied.has(u))) expect(supplied.has(name), name).toBe(true);
    for (const theme of Object.values(THEMES)) for (const name of supplied) expect(theme.vars[name], `${theme.id} ${name}`).toBeTruthy();
  });
});
