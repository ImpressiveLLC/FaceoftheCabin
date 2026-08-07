import { describe, it, expect } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { THEMES } from "./ThemeProvider.jsx";

// Regression guard for the exact drift found 2026-08-07: family-hub.html
// had two theme presets (neon80s, pacman) this app's ThemeProvider.jsx
// didn't -- see docs/ontology.yaml's theme_preference entry and
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §2c/2d. There's
// no shared theme-catalog source yet (that's the bigger, still-open §2d
// work), so this test is the cheap interim guard: fail the build if the
// two files' theme id sets ever diverge again.
//
// family-hub.html isn't a JS module (no ESM export to import directly),
// so its THEMES object is extracted by locating the block and pulling out
// every `id: '...'` occurrence -- each theme entry in that file always
// repeats its own key as an explicit `id` field, confirmed by reading the
// file, so this is a reliable (if not fully general-purpose) extraction.
//
// Path resolution: this file lives in a different location depending on
// where it's run from -- a full local repo checkout (relative path from
// src/) vs. this app's own isolated CI Docker image (built with the repo
// root as context specifically so family-hub.html is copied in --
// see ui/test/Dockerfile). Tries both; skips with a clear message (not a
// silent pass) if genuinely unreachable in a given environment.
function findFamilyHubHtml() {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    "/family-hub/family-hub.html",                          // this app's CI Docker image
    path.join(here, "../../../family-hub/family-hub.html"),  // local full-repo checkout
  ];
  return candidates.find(existsSync) || null;
}

function extractFamilyHubThemeIds(html) {
  const start = html.indexOf("const THEMES = {");
  if (start === -1) throw new Error("Could not find `const THEMES = {` in family-hub.html -- has it been renamed/restructured?");
  // \r?\n rather than a literal "\n};\n" -- this file may have CRLF line
  // endings (git's core.autocrlf on Windows checkouts).
  const closeMatch = /\r?\n\};\r?\n/.exec(html.slice(start));
  if (!closeMatch) throw new Error("Could not find the closing `};` for family-hub.html's THEMES object.");
  const block = html.slice(start, start + closeMatch.index);
  return [...block.matchAll(/\bid:\s*'(\w+)'/g)].map(m => m[1]).sort();
}

describe("theme catalog drift (cabin-ui vs family-hub.html)", () => {
  const familyHubPath = findFamilyHubHtml();

  it.skipIf(!familyHubPath)(
    "both apps define exactly the same set of theme ids",
    () => {
      const html = readFileSync(familyHubPath, "utf8");
      const familyHubIds = extractFamilyHubThemeIds(html);
      const cabinUiIds = Object.keys(THEMES).sort();
      expect(cabinUiIds).toEqual(familyHubIds);
    }
  );

  if (!familyHubPath) {
    // eslint-disable-next-line no-console
    console.warn(
      "SKIPPED theme catalog drift check: family-hub.html not reachable from this environment " +
      "(checked /family-hub/family-hub.html and the local repo-relative path). " +
      "This is expected only if running outside a full repo checkout or the cabin-ui CI image."
    );
  }
});
