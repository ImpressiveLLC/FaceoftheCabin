# Retro theme validation — 2026-09-18

Scope: the optional HA Operations Console proposal and retro presentation changes, based on main `e708946e863e6336bdf112d36179479cfd02e860`. No merge, deployment or live-device action.

| Check | Result |
|---|---|
| Cabin UI existing suite (`npm test`) | 345 tests passed; existing jsdom unsupported-navigation diagnostics do not fail the suite |
| New `ThemeTypography.test.jsx` | 1 test passed: reading/display separation, persistence and reset to Modern after both retro themes |
| Cabin UI production build (`npm run build`) | Passed; public font assets present in output |
| Family Hub (`npm test`) | 81 checks passed, including mobile journeys and cross-app theme handoff |
| Additional Chromium smoke checks | Both apps × both retro themes × 1440/390 CSS pixel widths: no page JavaScript errors or document-level horizontal overflow; theme switching to Modern clears retro reading-font selection |
| Font loading | Browser loaded local-origin Crackman successfully for both apps; independently deployed asset copies have identical SHA-256 hashes |
| Visual review | Desktop and phone screenshots inspected; display tiers and pink/mint/blue/yellow mapping reviewed |
| Patch hygiene | `git diff --check` passed |

## Limits

- SF Atarian binaries are **not distributed** because the supplied package restricts product bundling. The local-only font reference uses Arial when the font is not installed. Browser screenshots validate that fallback, not an installed Atarian render. See [font provenance](retro-theme-fonts.md).
- Chromium previews blocked live service requests and permitted existing Google Fonts for the final visual pass. They establish local rendering behavior, not working production integrations or current cabin health.
- At 390 pixels, Cabin UI's fixed toolbar/action rows visibly clip even though the document itself does not overflow. This PR does not certify a complete mobile layout; the theme work preserves the current navigation/layout structure. Family Hub's mobile journey suite passes.
- Family Hub's Docker font-copy rule was inspected; no container build or deployment was run.
- The proposal is reconstructed from recoverable prior-conversation decisions and current repository contracts; unavailable original downloadable artifacts were not represented as recovered verbatim. HA-UX0–5 remain unscored and unratified.
