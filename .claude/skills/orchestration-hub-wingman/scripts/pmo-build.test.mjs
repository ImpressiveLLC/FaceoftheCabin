// Run: node --test .claude/skills/orchestration-hub-wingman/scripts/pmo-build.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { splitRow, categorize, titleOf, parseBacklog, build } from './pmo-build.mjs';

const HEAD = '| ID | Work | UC | Traces to | Origin | Owner | CoD | Size | WSJF | Status | Blocked by |\n|---|---|---|---|---|---|---|---|---|---|---|\n';
const row = (id, work, status, wsjf = '17.5', blocked = 'none') =>
  `| ${id} | ${work} | UC-1 | D1 | Nate | Code | 7 | 2 | ${wsjf} | ${status} | ${blocked} |`;

test('splitRow keeps escaped pipes inside a cell', () => {
  assert.deepEqual(splitRow('| a | b \\| c | d |'), ['a', 'b | c', 'd']);
});

test('categorize maps the status vocabulary and best fits the rest', () => {
  assert.equal(categorize('**merged** 2026-10-12 (PR #128)'), 'Done');
  assert.equal(categorize('held: waits on Nate'), 'Blocked');
  assert.equal(categorize('proposed'), 'Backlog');
  assert.equal(categorize('ready for review'), 'Active');
  assert.equal(categorize('in progress, slice 2'), 'Active');
  assert.equal(categorize('ratified 2026-10-13'), 'Active');
  assert.equal(categorize('slice 1 merged 2026-10-16'), 'Done');
  assert.equal(categorize('something unrecognised'), 'Backlog');
});

test('titleOf uses the bold lead and pads very short leads', () => {
  assert.equal(titleOf('**Measurement policy rollout, replay and rollback.** AC: more'), 'Measurement policy rollout, replay and rollback');
  assert.match(titleOf('**P0 write gate:** Gate every state-changing route behind a session'), /^P0 write gate: Gate every/);
  assert.equal(titleOf('plain text work'), 'plain text work');
});

test('parseBacklog reads WSJF rows, ignores other tables, reports malformed rows', () => {
  const md = `# x\n${HEAD}${row('W-1', '**One.** AC', 'merged')}\n${row('W-2b', '**Two.** AC', 'held', '')}\n| S6-1 | other table | UC-3 | src | merged |\n| W-3 | short | row |\n`;
  const { rows, bad } = parseBacklog(md);
  assert.deepEqual(rows.map((r) => [r.id, r.cat, r.wsjf]), [['W-1', 'Done', 17.5], ['W-2b', 'Blocked', null]]);
  assert.deepEqual(bad, ['W-3']);
});

test('build injects rows safely and fills every placeholder', () => {
  const tpl = '<div>__SUB__</div>__NEEDS__|__PRS__|__PRFOOT__|__MEASURE__|const ROWS=__DATA__;';
  const md = HEAD + row('W-1', '**A </script> title.** AC', 'proposed');
  const { html } = build({
    backlog: md, template: tpl, repo: 'o/r', asof: 'now', source: 'src',
    panels: { needs: [{ b: 'Go.', t: 'Say <go>.' }], measure: { title: 'M', rows: [{ label: 'x', value: '1', pct: 50, mark: 90 }] } },
    prs: [{ n: 5, title: 'T & U', draft: false, head: 'abc1234' }],
  });
  assert.ok(!/__[A-Z]+__/.test(html), 'no placeholder left');
  assert.ok(!html.includes('</script>'), 'row text cannot close the script tag');
  assert.ok(html.includes('Say &lt;go&gt;.') && html.includes('T &amp; U') && html.includes('width:50%'));
});

test('build refuses an empty or malformed backlog', () => {
  assert.throws(() => build({ backlog: '# nothing', template: '__DATA__', repo: 'o/r', asof: 'x', source: 'y' }), /no backlog rows/);
  assert.throws(() => build({ backlog: HEAD + '| W-9 | a | b |', template: '__DATA__', repo: 'o/r', asof: 'x', source: 'y' }), /malformed/);
});
