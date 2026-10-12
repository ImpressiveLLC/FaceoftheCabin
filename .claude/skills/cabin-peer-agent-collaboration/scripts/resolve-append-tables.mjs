#!/usr/bin/env node
// Resolve merge-conflict regions that are made only of markdown table rows
// which two sessions each appended (docs/governance/backlog.md,
// discrepancy-log.md, decision indexes, use-case tables).
//
// Run it inside the PR branch's worktree, after `git merge origin/main` stopped
// on a conflict. In that state the FIRST side of each region is the branch you
// are on and the SECOND side is main.
//
//   node resolve-append-tables.mjs <file> [--prefer-branch W-9,W-10] [--check]
//
// Policy (the one the project settled on, 2026-10-12):
//   - a row on both sides            -> main's version (main is newer), unless the
//                                       id is listed in --prefer-branch (the id a
//                                       PR exists to change)
//   - a row only on main             -> kept
//   - a row only on the branch       -> kept, placed after the last row whose id
//                                       sorts lower (main's own order is not
//                                       disturbed)
// It refuses (exit 2, file untouched) when a region holds anything but table
// rows: prose, code and import lists need a human or agent read.
// After resolving it validates the whole file: no conflict markers left, every
// table row has the same pipe count as its table header, ids unique per table.
// Exit 0 resolved and valid, 1 resolved but validation failed, 2 refused.
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const REGION = /<<<<<<< [^\n]*\n([\s\S]*?)=======\n([\s\S]*?)>>>>>>> [^\n]*\n/g;

const isRow = (l) => l.startsWith("|");
const isSeparator = (l) => /^\|[\s:|-]+\|?\s*$/.test(l) && l.includes("-");
const cells = (l) => l.split("|");
export const rowId = (l) => (cells(l)[1] ?? "").trim();
const pipeCount = (l) => (l.replace(/\\\|/g, "").match(/\|/g) ?? []).length;

export function naturalCompare(a, b) {
  const pa = a.match(/\d+|\D+/g) ?? [];
  const pb = b.match(/\d+|\D+/g) ?? [];
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i], y = pb[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x), ny = /^\d+$/.test(y);
    if (nx && ny) {
      if (Number(x) !== Number(y)) return Number(x) - Number(y);
    } else if (x !== y) {
      return x < y ? -1 : 1;
    }
  }
  return 0;
}

const sideRows = (text) => text.split("\n").filter((l) => l !== "");

/** Merge two sides of one region. Throws if either side holds a non-row line. */
export function mergeRegion(branchText, mainText, preferBranch = new Set()) {
  const branch = sideRows(branchText), main = sideRows(mainText);
  for (const l of [...branch, ...main]) {
    if (!isRow(l)) throw new Error(`non-table line in conflict region: ${l.slice(0, 70)}`);
  }
  const branchById = new Map(branch.map((l) => [rowId(l), l]));
  const mainIds = new Set(main.map(rowId));
  const result = main.map((l) => (preferBranch.has(rowId(l)) && branchById.has(rowId(l)) ? branchById.get(rowId(l)) : l));
  for (const l of branch) {
    const id = rowId(l);
    if (mainIds.has(id)) continue;
    let at = 0;
    for (let i = 0; i < result.length; i++) if (naturalCompare(rowId(result[i]), id) < 0) at = i + 1;
    result.splice(at, 0, l);
  }
  return result.join("\n") + "\n";
}

/** Whole-file checks. Returns a list of problems (empty = valid). */
export function validate(text) {
  const problems = [];
  if (/^(<<<<<<<|>>>>>>>) /m.test(text) || /^=======$/m.test(text)) problems.push("conflict markers remain");
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; ) {
    if (!isRow(lines[i])) { i++; continue; }
    let j = i;
    while (j < lines.length && isRow(lines[j])) j++;
    const block = lines.slice(i, j);
    const want = pipeCount(block[0]);
    const seen = new Set();
    block.forEach((l, k) => {
      if (pipeCount(l) !== want) problems.push(`line ${i + k + 1}: ${pipeCount(l)} pipes, table header has ${want} (${rowId(l).slice(0, 20)})`);
      if (k === 0 || isSeparator(l)) return;
      const id = rowId(l);
      if (id && seen.has(id)) problems.push(`line ${i + k + 1}: duplicate id ${id}`);
      seen.add(id);
    });
    i = j;
  }
  return problems;
}

export function resolveText(raw, preferBranch = new Set()) {
  const crlf = raw.includes("\r\n");
  let text = raw.replace(/\r\n/g, "\n");
  let regions = 0;
  text = text.replace(REGION, (_m, b, m) => { regions++; return mergeRegion(b, m, preferBranch); });
  return { text: crlf ? text.replace(/\n/g, "\r\n") : text, regions, problems: validate(text) };
}

function main(argv) {
  const file = argv.find((a) => !a.startsWith("--") && argv[argv.indexOf(a) - 1] !== "--prefer-branch");
  if (!file) { console.error("usage: resolve-append-tables.mjs <file> [--prefer-branch ID,ID] [--check]"); return 2; }
  const pi = argv.indexOf("--prefer-branch");
  const prefer = new Set(pi >= 0 ? argv[pi + 1].split(",").map((s) => s.trim()).filter(Boolean) : []);
  const raw = readFileSync(file, "utf8");
  let out;
  try { out = resolveText(raw, prefer); } catch (e) { console.error(`refused, ${file} untouched: ${e.message}`); return 2; }
  console.log(`${file}: ${out.regions} conflict region(s) resolved`);
  if (!argv.includes("--check")) writeFileSync(file, out.text, "utf8");
  if (out.problems.length) { console.error("validation failed:\n  " + out.problems.join("\n  ")); return 1; }
  console.log("validation ok: no markers, pipe counts match, ids unique");
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exit(main(process.argv.slice(2)));
