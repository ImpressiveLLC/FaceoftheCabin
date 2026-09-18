# Retro theme fonts and palette

The Pac-Man and 80s Neon presets retain their existing IDs, query-parameter handoff and local preferences in both Cabin UI and Family Hub.

## Font provenance and distribution

**Crackman:** supplied in the user's `crackman.zip`. `Crackman.otf` is copied unmodified into each independently deployed application's font directory, together with the original `license.txt` (Raymond Larabie, CC0). Front/Back variants are not needed. Both apps load the font from their own origin. Pac-Man headings/display text use Crackman; operational body/control text keeps the existing VT323 tier with a monospace fallback.

**Baumans:** the neon general/body/control font, bundled unmodified from Google Fonts as `Baumans-Regular.ttf` (regular 400). Existing bold styling may use browser-synthesized bold because this family supplies only a regular face.

**Oxanium:** the bundled fallback, provided as the original variable TTF with weights 200–800. The general font stack is `Baumans, Oxanium, sans-serif`. Both fonts are served from the app's own origin; neither requires installation on the viewing device or a Google Fonts network request. Monoton remains the existing neon display tier.

Both fonts are licensed under SIL OFL 1.1; their original copyright/license files accompany each deployed copy. Sources are pinned to Google Fonts commit `b346dc3e18bed8b4ca602e00537eb35d76ed5025`: [Baumans](https://github.com/google/fonts/tree/b346dc3e18bed8b4ca602e00537eb35d76ed5025/ofl/baumans), [Oxanium](https://github.com/google/fonts/tree/b346dc3e18bed8b4ca602e00537eb35d76ed5025/ofl/oxanium). No font conversion, renaming or glyph modification was performed.

## Palette reference

The user's “RETRO 80'S” color-guide image supplies pale yellow `#FFFF66`, mint `#7FFFD4` and pale blue `#ADD8E6` in/near the circled region. Existing neon pink `#FF2DD4` remains the primary accent. Mint supports positive/secondary accents, pale blue supports headings/focus/separators, and yellow supplies attention highlights. Cabin UI warning is yellow and danger is pink-red `#FF858A`, retaining distinct semantic roles; Family Hub's existing decorative `--rose` token uses yellow. Dark surfaces and readable light body text remain.

## Packaging and review

Both apps carry the same `fonts/retro-fonts.css` and Crackman/Baumans/Oxanium bytes because they deploy independently. Vite copies Cabin UI's public assets; Family Hub's explicit Docker allowlist copies its font directory. Keep these copies synchronized when revising assets. No production installation or deployment occurs in this PR.

Validate both applications' existing suites and the Cabin UI production build. Check actual font loading, fallback punctuation, theme switching, headings, menus and mobile widths in a browser. Local previews with mocked services are not live-service validation.
