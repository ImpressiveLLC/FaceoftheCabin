// Forced-colours measurement of the Signal theme on the real app CSS (W-126 items 2 and 3). Not part of the vitest run and not a
// repo dependency: it needs Playwright with Chromium installed (for example `npm i --no-save playwright`). It starts vite itself,
// emulates forcedColors active in light and dark, builds one Normal, Watch and Alert tile, and prints contrast ratios.
// Usage: node test/measure-forced-colours.cjs <path to this ui folder> [port]
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const [uiDir, port] = [process.argv[2], process.argv[3] || '5201'];

function lum(c) {
  const m = c.match(/[\d.]+/g).map(Number);
  const [r, g, b] = m.slice(0, 3).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const ratio = (a, b) => { const x = lum(a), y = lum(b); return +((Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)).toFixed(2); };

(async () => {
  const vite = spawn('npx', ['vite', '--port', port, '--strictPort', '--host', '127.0.0.1'], { cwd: uiDir, shell: true });
  let ready = false;
  vite.stdout.on('data', d => { if (String(d).includes('Local')) ready = true; });
  for (let i = 0; i < 60 && !ready; i++) await new Promise(r => setTimeout(r, 500));
  const browser = await chromium.launch();
  const out = {};
  try {
    for (const scheme of ['light', 'dark']) {
      const ctx = await browser.newContext({ colorScheme: scheme, forcedColors: 'active', viewport: { width: 1000, height: 700 } });
      const page = await ctx.newPage();
      await page.goto(`http://127.0.0.1:${port}/?theme=signal`);
      await page.waitForTimeout(1500);
      out[scheme] = await page.evaluate(() => {
        const cv = document.createElement('canvas'); cv.width = cv.height = 1; const cx = cv.getContext('2d', { willReadFrequently: true });
        // Normalise any CSS colour (rgb, color(srgb ...), with alpha) to opaque rgb as it appears over `over`.
        const flat = (c, over) => { cx.clearRect(0, 0, 1, 1); cx.fillStyle = over; cx.fillRect(0, 0, 1, 1); cx.fillStyle = c; cx.fillRect(0, 0, 1, 1); const d = cx.getImageData(0, 0, 1, 1).data; return `rgb(${d[0]}, ${d[1]}, ${d[2]})`; };
        const lumF = (c) => { const m = c.match(/[\d.]+/g).map(Number); const [r, g, b] = m.slice(0, 3).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
        const ratioF = (a, b) => { const x = lumF(a), y = lumF(b); return +((Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)).toFixed(2); };
        document.getElementById('probe')?.remove();
        const h = document.createElement('div');
        h.id = 'probe';
        h.style.cssText = 'position:fixed;left:20px;top:20px;width:900px;z-index:99999;padding:10px;background:Canvas';
        h.innerHTML = ['ok', 'warn', 'alarm'].map(s => `<div class="kpi-grid" style="display:inline-block;width:260px;margin:6px"><div class="kpi-tile kpi-state-${s}"><svg class="lucide lucide-thermometer" viewBox="0 0 24 24" width="16" height="16"><path d="M4 4h16v16H4z" stroke="currentColor" fill="none"/></svg><div class="kpi-label">Kitchen</div><div class="kpi-value">70.1F</div><span class="state-badge state-${s === 'ok' ? 'ok' : s === 'warn' ? 'warn' : 'alarm'}">${s}</span></div></div>`).join('');
        document.body.appendChild(h);
        const canvas = getComputedStyle(h).backgroundColor;
        const res = { canvas };
        for (const s of ['ok', 'warn', 'alarm']) {
          const tile = h.querySelector('.kpi-state-' + s);
          const chip = tile.querySelector('.state-badge');
          const tcs = getComputedStyle(tile), ccs = getComputedStyle(chip);
          res[s] = {
            tileBorder: `${tcs.borderTopWidth} ${tcs.borderTopStyle} ${tcs.borderTopColor}`,
            tileBorderRatio: ratioF(flat(tcs.borderTopColor, canvas), canvas),
            tileBg: tcs.backgroundColor,
            chipBg: ccs.backgroundColor, chipBorder: `${ccs.borderTopWidth} ${ccs.borderTopStyle} ${ccs.borderTopColor}`, chipColor: ccs.color,
            chipFlatBg: flat(ccs.backgroundColor, canvas), chipTextRatio: ratioF(flat(ccs.color, flat(ccs.backgroundColor, canvas)), flat(ccs.backgroundColor, canvas)), chipBorderRatio: ratioF(flat(ccs.borderTopColor, canvas), canvas),
            valueRatio: ratioF(getComputedStyle(tile.querySelector('.kpi-value')).color, canvas),
          };
        }
        return res;
      });
      await ctx.close();
    }
  } finally {
    await browser.close();
    spawn('taskkill', ['/pid', String(vite.pid), '/T', '/F'], { shell: true });
  }
  console.log(JSON.stringify(out, null, 1));
})();
