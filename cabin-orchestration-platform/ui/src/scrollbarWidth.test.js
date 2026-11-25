import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// Wide scrollbars are a kiosk (touch screen, no mouse) feature. A scrollbar that changed width on click or scroll
// moved row controls out from under the pointer. jsdom does no layout, so this guards the rules at their source.
const sheets = ['src/touch-display.css', '../../family-hub/touch-display.css'].map(p => resolve(process.cwd(), p));

describe('scrollbar width', () => {
  it.each(sheets)('is restyled only inside kiosk mode in %s', file => {
    const css = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .filter(([, , body]) => /(^|[;\s])scrollbar-(width|color)\s*:/.test(body) || /var\(--scroll-/.test(body));
    const selectors = [...css.matchAll(/([^{}]*::-webkit-scrollbar[\w-]*[^{}]*)\{/g)]
      .map(m => m[1].trim()).filter(s => !s.startsWith('@supports'))
      .concat(rules.map(m => m[1].trim()));
    expect(selectors.length).toBeGreaterThan(0);
    for (const selector of selectors.flatMap(s => s.split(','))) expect(selector.trim()).toMatch(/^\.kiosk-mode\b/);
    expect(css.match(/--scroll-size\s*:[^;]+/g)).toEqual(['--scroll-size:32px']);
  });
  it('has no script that restyles the page on interaction', () => {
    expect(existsSync(resolve(process.cwd(), 'src/touch-scroll.js'))).toBe(false);
    expect(existsSync(resolve(process.cwd(), '../../family-hub/touch-scroll.js'))).toBe(false);
  });
});
