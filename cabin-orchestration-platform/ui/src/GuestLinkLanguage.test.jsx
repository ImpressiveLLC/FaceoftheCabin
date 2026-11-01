import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

// W-139 (Nate, 2026-11-01): a guest link is a convenience view of data that is open without signing in (D14), made for sharing a look.
// This file pins the wording in App.jsx, which is in the UI test image. The pins for the user guide, the ontology, CLAUDE.md and
// AGENTS.md live in the backend test GuestLinkLanguageTest, because the UI test image (ui/test/Dockerfile) has none of those files.
const here = path.dirname(fileURLToPath(import.meta.url));
const app = readFileSync(path.join(here, "App.jsx"), "utf8").replace(/\r\n/g, "\n");

describe("guest link product language in the UI", () => {
  it("the Config card says what a guest link is", () => {
    expect(app).toContain("A convenience view of what is open without signing in (D14)");
  });
  it("usage and activity show on demo links only, and the guest page lines are neutral", () => {
    expect(app).toContain('{t.scope?.includes("demo") && <span className="config-hint guest-access-usage">');
    expect(app).toContain('{t.scope?.includes("demo") && <AccessActivity');
    expect(app).toContain("Read-only view of cabin conditions.");
    expect(app).not.toContain("only the sections this link is scoped to grant");
  });
});
