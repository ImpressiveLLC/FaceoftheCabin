import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

// W-139 (Nate, 2026-11-01): a guest link is a convenience view of data that is open without signing in (D14), made for sharing a look.
// The product language is pinned in each place that describes it, so a later edit cannot drift back to promising a lock around the data.
const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.join(here, "..", "..", "..");
const read = (...parts) => readFileSync(path.join(repo, ...parts), "utf8").replace(/\r\n/g, "\n");

describe("guest link product language", () => {
  it("the Config card says what a guest link is", () => {
    expect(read("cabin-orchestration-platform", "ui", "src", "App.jsx")).toContain("A convenience view of what is open without signing in (D14)");
  });
  it("the user guide says the same in the table row and the Guest Access section", () => {
    const guide = read("docs", "ai-assistant", "user-guide", "access-and-storage.md");
    expect(guide).toContain("A convenience view for sharing a look: read-only");
    expect(guide).toContain("A guest link is a convenience view of data that is open without signing in (D14)");
    expect(guide).not.toMatch(/cannot open anything you did not tick|reaches nothing else/);
  });
  it("the ontology and both instruction files describe it the same way, with no claim about fixed scope prefixes", () => {
    expect(read("docs", "ontology.yaml")).not.toContain("four fixed\n      read-only scope prefixes");
    for (const file of ["CLAUDE.md", "AGENTS.md"]) {
      expect(read(file), file).toContain("a guest link is a convenience view of data that is open without signing in, D14");
    }
  });
  it("the guide and the card say nothing about what Revoke does beyond marking the link, name no limiting ticks, and show no usage on guest links", () => {
    const guide = read("docs", "ai-assistant", "user-guide", "access-and-storage.md");
    expect(guide).not.toMatch(/stops it at once|choose the parts to include|last opened|View activity/);
    expect(guide).toContain("**Revoke** marks a link Revoked");
    const app = read("cabin-orchestration-platform", "ui", "src", "App.jsx");
    expect(app).toContain('{t.scope?.includes("demo") && <span className="config-hint guest-access-usage">');
    expect(app).toContain('{t.scope?.includes("demo") && <AccessActivity');
    expect(app).toContain("Read-only view of cabin conditions.");
    expect(app).not.toContain("only the sections this link is scoped to grant");
  });
});
