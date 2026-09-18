# Retro theme fonts and palette

The Pac-Man and 80s Neon presets retain their existing IDs, query-parameter handoff and local preferences in both Cabin UI and Family Hub.

## Font provenance and distribution

**Crackman:** supplied in the user's `crackman.zip`. `Crackman.otf` is copied unmodified into each independently deployed application's font directory, together with the original `license.txt` (Raymond Larabie, CC0). Front/Back variants are not needed. Both apps load the font from their own origin. Pac-Man headings/display text use Crackman; operational body/control text keeps the existing VT323 tier with a monospace fallback.

**SF Atarian System:** the user's `sf-atarian-system.zip` contains the 1999 version 1.0. Its Readme permits installation on unlimited machines but says the package may not be included as part of another product and requires all fonts and original documentation for free website package distribution. This PR therefore does **not** bundle or convert its binaries. The general 80s font uses a local-only `@font-face` alias for SF Atarian System regular/bold, falling back to Arial/sans-serif when absent. A ZIP in Downloads does not install a font: install the original regular/bold files on each viewing device to enable this face. Repository/web embedding requires separate suitable permission before assets are added.

The supplied font maps `@`, `#` and `*` to Atari logos. The local face excludes these code points so email addresses, identifiers and ordinary punctuation use the readable fallback instead. Missing glyphs also fall back. Monoton remains the neon display tier, while Orbitron is removed from general text. This is a requested aesthetic choice, not a claim that Atarian has been proven more accessible.

## Palette reference

The user's “RETRO 80'S” color-guide image supplies pale yellow `#FFFF66`, mint `#7FFFD4` and pale blue `#ADD8E6` in/near the circled region. Existing neon pink `#FF2DD4` remains the primary accent. Mint supports positive/secondary accents, pale blue supports headings/focus/separators, and yellow supplies attention highlights. Cabin UI warning is yellow and danger is pink-red `#FF858A`, retaining distinct semantic roles; Family Hub's existing decorative `--rose` token uses yellow. Dark surfaces and readable light body text remain.

## Packaging and review

Both apps carry the same `fonts/retro-fonts.css` and Crackman bytes because they deploy independently. Vite copies Cabin UI's public assets; Family Hub's explicit Docker allowlist copies its font directory. Keep these copies synchronized when revising assets. No production installation or deployment occurs in this PR.

Validate both applications' existing suites and the Cabin UI production build. Check actual font loading, fallback punctuation, theme switching, headings, menus and mobile widths in a browser. Local previews with mocked services are not live-service validation.
