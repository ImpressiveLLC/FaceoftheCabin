// Run: node --test .claude/skills/cabin-peer-agent-collaboration/scripts/
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolveText, mergeRegion, validate, naturalCompare, rowId } from "./resolve-append-tables.mjs";

const HDR = "| ID | Title | Status |\n|---|---|---|\n";
const conflict = (branch, main) => `<<<<<<< HEAD\n${branch}=======\n${main}>>>>>>> origin/main\n`;
const ids = (text) => text.split("\n").filter((l) => /^\| [A-Z]+-/.test(l)).map(rowId);

test("both sides append: keeps both, in id order", () => {
  const raw = HDR + "| W-1 | a | done |\n" + conflict("| W-26 | branch | new |\n", "| W-22 | m | new |\n| W-25 | m | new |\n");
  const out = resolveText(raw);
  assert.deepEqual(ids(out.text), ["W-1", "W-22", "W-25", "W-26"]);
  assert.deepEqual(out.problems, []);
});

test("a row on both sides takes main's version", () => {
  const out = mergeRegion("| W-9 | old | open |\n", "| W-9 | NEWER | merged |\n");
  assert.equal(out, "| W-9 | NEWER | merged |\n");
});

test("--prefer-branch keeps the branch's version of that id only", () => {
  const out = mergeRegion("| W-1 | mine | open |\n| W-2 | mine | open |\n", "| W-1 | main | open |\n| W-2 | main | open |\n", new Set(["W-1"]));
  assert.equal(out, "| W-1 | mine | open |\n| W-2 | main | open |\n");
});

test("main's own order is not disturbed (W-28 stays before W-27)", () => {
  const out = mergeRegion("| W-26 | b | n |\n", "| W-25 | m | n |\n| W-28 | m | n |\n| W-27 | m | n |\n");
  assert.deepEqual(out.trim().split("\n").map(rowId), ["W-25", "W-26", "W-28", "W-27"]);
});

test("a branch row that sorts before everything goes first (dated log rows)", () => {
  const out = mergeRegion("| DL-2026-10-03-01 | b |\n| DL-2026-10-03-02 | b |\n", "| DL-2026-10-04-01 | m |\n");
  assert.deepEqual(out.trim().split("\n").map(rowId), ["DL-2026-10-03-01", "DL-2026-10-03-02", "DL-2026-10-04-01"]);
});

test("refuses a region that contains anything but table rows", () => {
  assert.throws(() => mergeRegion("| W-1 | a |\nsome prose\n", "| W-2 | b |\n"), /non-table line/);
});

test("preserves CRLF line endings", () => {
  const raw = (HDR + conflict("| W-2 | b | n |\n", "| W-1 | m | n |\n")).replace(/\n/g, "\r\n");
  const out = resolveText(raw);
  assert.ok(out.text.includes("\r\n") && !/[^\r]\n/.test(out.text));
  assert.deepEqual(ids(out.text.replace(/\r\n/g, "\n")), ["W-1", "W-2"]);
});

test("validate catches a short row, a duplicate id and leftover markers", () => {
  assert.match(validate(HDR + "| W-1 | only two |\n").join(), /pipes/);
  assert.match(validate(HDR + "| W-1 | a | b |\n| W-1 | c | d |\n").join(), /duplicate id W-1/);
  assert.match(validate("<<<<<<< HEAD\nx\n").join(), /markers/);
  assert.deepEqual(validate(HDR + "| W-1 | a | b |\n"), []);
});

test("an escaped pipe inside a cell does not count as a column", () => {
  assert.deepEqual(validate(HDR + "| W-1 | a \\| b | c |\n"), []);
});

test("naturalCompare orders numbers numerically, not as text", () => {
  assert.ok(naturalCompare("W-9", "W-10") < 0);
  assert.ok(naturalCompare("DL-2026-10-04-01", "DL-2026-10-12-01") < 0);
  assert.equal(naturalCompare("W-5", "W-5"), 0);
});

test("CLI: resolves a file in place, exit 0; refuses prose, exit 2, file untouched", () => {
  const dir = mkdtempSync(join(tmpdir(), "rat-"));
  const script = fileURLToPath(new URL("./resolve-append-tables.mjs", import.meta.url));
  const good = join(dir, "good.md"), bad = join(dir, "bad.md");
  writeFileSync(good, HDR + conflict("| W-2 | b | n |\n", "| W-1 | m | n |\n"));
  const badRaw = conflict("prose from branch\n", "| W-1 | m | n |\n");
  writeFileSync(bad, badRaw);
  assert.equal(spawnSync("node", [script, good]).status, 0);
  assert.deepEqual(ids(readFileSync(good, "utf8")), ["W-1", "W-2"]);
  assert.equal(spawnSync("node", [script, bad]).status, 2);
  assert.equal(readFileSync(bad, "utf8"), badRaw);
});
