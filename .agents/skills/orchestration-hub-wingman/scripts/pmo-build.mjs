#!/usr/bin/env node
// Wingman PMO dashboard builder.
// Parses the WSJF table in docs/governance/backlog.md and injects the rows, plus optional
// panels, into assets/pmo-template.html. Standard library only; read-only on the repo.
//
//   node pmo-build.mjs --check
//       parse only: counts per state, exits 1 when a row is malformed or none parse
//   node pmo-build.mjs --out <file.html> [--backlog <md>] [--template <html>]
//                      [--panels <json>] [--prs] [--repo <owner/name>] [--asof <text>] [--source <text>]
//       --panels  JSON: {"needs":[{"b":"bold lead","t":"text"}],
//                        "measure":{"title":"..","rows":[{"label":"..","value":"..","pct":83.7,"mark":90}],"foot":".."}}
//       --prs     list open PRs through `gh api` (REST)
//
// State comes from the first word of the Status cell; words outside the vocabulary are
// mapped by best fit on the first 70 characters, so treat the state column as approximate.
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

/** Split a markdown table row on pipes that are not escaped; drop the outer empties. */
export function splitRow(line) {
  const cells = line.split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, '|'));
  return cells.slice(1, cells.length - 1);
}

const BLOCKED = /^(held|gated|blocked|hold|parked|withdrawn)$/;
const BACKLOG = /^(proposed|backlog|planned|queued|candidate|draft|deferred)$/;
const ACTIVE = /^(ready|in|active|started|ratified|review|open|implementing)$/;
const DONE = /^(merged|released|done|built|decided|closed|implemented|complete|completed|shipped|green-lit|verified)$/;

/** Map a Status cell to Active, Blocked, Backlog or Done. */
export function categorize(status) {
  const s = String(status).replace(/[*_`[\]]/g, '').trim().toLowerCase();
  const w = s.split(/[\s,:;(.]+/)[0] || '';
  if (BLOCKED.test(w)) return 'Blocked';
  if (BACKLOG.test(w)) return 'Backlog';
  if (ACTIVE.test(w)) return 'Active';
  if (DONE.test(w)) return 'Done';
  const head = s.slice(0, 70);
  if (/\b(merged|released|complete|completed|done)\b/.test(head)) return 'Done';
  if (/\b(held|gated|blocked)\b/.test(head)) return 'Blocked';
  if (/\b(ready|in progress|in review)\b/.test(head)) return 'Active';
  return 'Backlog';
}

/** Short title: the bold lead of the Work cell, with a snippet appended when it is very short. */
export function titleOf(work) {
  const m = /^\*\*(.+?)\*\*/.exec(work);
  let t = (m ? m[1] : work).replace(/[*_`]/g, '').trim().replace(/[.:]+$/, '');
  if (m && t.length < 30) {
    const rest = work.slice(m[0].length).replace(/[*_`]/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').trim();
    t = `${t}: ${rest.slice(0, 70).replace(/\s+\S*$/, '')}`.replace(/[\s:]+$/, '');
  }
  return t.slice(0, 120);
}

/** Parse every WSJF row (`| W-n | ...`, 11 cells) into dashboard rows. Returns {rows, bad}. */
export function parseBacklog(md) {
  const rows = [];
  const bad = [];
  for (const line of md.split(/\r?\n/)) {
    if (!/^\|\s*W-\d+[a-z]?\s*\|/.test(line)) continue;
    const c = splitRow(line);
    if (c.length !== 11) { bad.push(c[0] || line.slice(0, 20)); continue; }
    const [id, work, , , , owner, , size, wsjf, status, blocked] = c;
    const score = Number.parseFloat(wsjf);
    rows.push({
      id, title: titleOf(work), wsjf: Number.isFinite(score) ? score : null,
      size: size.replace('\u2014', ''), owner, status, blocked, cat: categorize(status),
    });
  }
  return { rows, bad };
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function openPrs(repo) {
  const out = execFileSync('gh', ['api', `repos/${repo}/pulls?state=open&per_page=50`], { encoding: 'utf8' });
  return JSON.parse(out).map((p) => ({ n: p.number, title: p.title, draft: p.draft, head: p.head.sha.slice(0, 7) }));
}

function measurePanel(m) {
  if (!m) return '';
  const rows = (m.rows || []).map((r) => {
    const bar = r.pct == null ? '' : `<div class="bar"><i${r.pct >= (r.mark ?? 101) ? ' class="ok"' : ''} style="width:${Number(r.pct)}%"></i>${r.mark == null ? '' : `<u class="mark" style="left:${Number(r.mark)}%"></u>`}</div>`;
    return `<div class="g"><div class="row"><span>${esc(r.label)}</span><span>${esc(r.value)}</span></div>${bar}</div>`;
  }).join('\n');
  return `<div class="panel"><h2>${esc(m.title || 'Measurement')}</h2><div class="gate">\n${rows}\n</div>${m.foot ? `<p class="foot" style="margin:10px 0 0">${esc(m.foot)}</p>` : ''}</div>`;
}

export function build({ backlog, template, panels = {}, prs = [], repo, asof, source }) {
  const { rows, bad } = parseBacklog(backlog);
  if (bad.length) throw new Error(`malformed rows: ${bad.join(', ')}`);
  if (!rows.length) throw new Error('no backlog rows parsed');
  const needs = (panels.needs || []).map((n) => `<li><b>${esc(n.b)}</b> ${esc(n.t || '')}</li>`).join('\n') || '<li>Nothing is waiting on Nate.</li>';
  const prHtml = prs.map((p) => `<div><span><a href="https://github.com/${repo}/pull/${p.n}">#${p.n}</a> ${esc(p.title)}</span><span class="m">${p.draft ? 'draft' : 'ready'} ${p.head}</span></div>`).join('\n') || '<div><span>No open pull requests.</span></div>';
  const data = JSON.stringify(rows).replace(/</g, '\\u003c');
  const sub = `FaceoftheCabin platform backlog as of ${esc(asof)}. Parsed from <code>docs/governance/backlog.md</code> (${esc(source)}). Scores marked proposed are proxy-PO, Cowork to confirm. Live M920q state is not visible from here.`;
  return {
    rows,
    html: template.replace('__SUB__', () => sub).replace('__NEEDS__', () => needs).replace('__PRS__', () => prHtml)
      .replace('__PRFOOT__', () => `<p class="foot" style="margin:10px 0 0">Board and handoffs: <a href="https://github.com/${repo}/issues/244">#244</a>.</p>`)
      .replace('__MEASURE__', () => measurePanel(panels.measure)).replace('__DATA__', () => data),
  };
}

function main(argv) {
  const arg = (k) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : undefined; };
  const repo = arg('--repo') || 'ImpressiveLLC/FaceoftheCabin';
  const backlogPath = arg('--backlog') || 'docs/governance/backlog.md';
  const backlog = readFileSync(backlogPath, 'utf8');
  if (argv.includes('--check')) {
    const { rows, bad } = parseBacklog(backlog);
    const n = (c) => rows.filter((r) => r.cat === c).length;
    console.log(`rows ${rows.length} | Active ${n('Active')} Blocked ${n('Blocked')} Backlog ${n('Backlog')} Done ${n('Done')} | malformed ${bad.length}${bad.length ? ` (${bad.join(', ')})` : ''}`);
    process.exit(bad.length || !rows.length ? 1 : 0);
  }
  const out = arg('--out');
  if (!out) { console.error('usage: pmo-build.mjs --check | --out <file.html> [options]'); process.exit(2); }
  const template = readFileSync(arg('--template') || join(here, '..', 'assets', 'pmo-template.html'), 'utf8');
  const panelsPath = arg('--panels');
  const { html, rows } = build({
    backlog, template, repo,
    panels: panelsPath ? JSON.parse(readFileSync(panelsPath, 'utf8')) : {},
    prs: argv.includes('--prs') ? openPrs(repo) : [],
    asof: arg('--asof') || new Date().toISOString(),
    source: arg('--source') || backlogPath,
  });
  writeFileSync(out, html);
  console.log(`wrote ${out} (${rows.length} rows, ${html.length} bytes)`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main(process.argv.slice(2));
