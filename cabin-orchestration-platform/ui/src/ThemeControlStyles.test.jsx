import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const cabinCss = readFileSync(path.join(here, "styles.css"), "utf8");
const familyHubHtml = readFileSync(path.join(here, "../../../family-hub/family-hub.html"), "utf8");

describe("theme-aware controls", () => {
  it("routes cabin buttons, dropdowns, header controls, and banners through theme variables", () => {
    expect(cabinCss).toContain("--control-border:");
    expect(cabinCss).toMatch(/\.btn-primary\s*\{[\s\S]*?background:\s*var\(--accent\)/);
    expect(cabinCss).toMatch(/select:not\(\.presence-select\)[\s\S]*?border:\s*2px solid var\(--control-border\)/);
    expect(cabinCss).toContain(".theme-dropdown {");
    expect(cabinCss).toContain(".discovery-suggested-banner,");
    expect(cabinCss).toContain(".alert-ctrl-critical {");
  });

  it("routes Family Hub dropdowns, banners, settings, and Close controls through theme variables", () => {
    expect(familyHubHtml).toContain("label: 'Asteroid City', id: 'asteroidcity'");
    expect(familyHubHtml).toMatch(/#dash-close,[\s\S]*?border:2px solid var\(--control-outline\)/);
    expect(familyHubHtml).toMatch(/#settings-panel\s*\{[\s\S]*?border-left:2px solid var\(--ui-outline\)/);
    expect(familyHubHtml).toMatch(/\.s-field select,[\s\S]*?border:2px solid var\(--control-outline\)/);
    expect(familyHubHtml).toMatch(/\.chore-reward-banner\s*\{[\s\S]*?border:2px solid var\(--control-outline\)/);
  });
});
