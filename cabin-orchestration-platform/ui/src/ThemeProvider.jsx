/**
 * ThemeProvider — independently-selectable palette + font presets.
 *
 * Presets: Modern (default), LCARS, Monolith, Retro-CRT, Bluefin-mono,
 * Mad Science, Deep Space, 80s Neon, Pac-Man, Asteroid City -- same
 * 10-preset catalog as
 * family-hub.html's own THEMES object (kept in sync manually as of
 * 2026-08-07; see docs/ontology.yaml's theme_preference entry for the
 * cross-app drift this file and family-hub.html are both prone to).
 * Persisted to localStorage under key "cabin-theme".
 *
 * Usage:
 *   Wrap <App/> with <ThemeProvider>
 *   Call useTheme() anywhere to get { theme, setTheme, themes }
 *   CSS custom properties are stamped on <html> — use var(--bg) etc. in styles.
 */

import React, { createContext, useContext, useEffect, useState } from "react";
import { Palette } from "lucide-react";

// ─── Theme definitions ──────────────────────────────────────────────────────
export const THEMES = {
  modern: {
    id: "modern",
    label: "Modern",
    vars: {
      "--bg":           "#0d1117",
      "--bg-secondary": "#161b22",
      "--bg-tertiary":  "#21262d",
      "--surface":      "#161b22",
      "--border":       "#21262d",
      "--border-focus": "#1f6feb",
      "--text":         "#e6edf3",
      "--text-muted":   "#8b949e",
      "--text-dim":     "#6e7681",
      "--accent":       "#1f6feb",
      "--accent-hover": "#388bfd",
      "--success":      "#3fb950",
      "--warning":      "#d29922",
      "--danger":       "#f85149",
      "--font-display": "'Inter', system-ui, sans-serif",
      "--font-mono":    "'JetBrains Mono', 'Fira Code', monospace",
      "--radius":       "10px",
      "--radius-sm":    "6px",
    },
  },

  lcars: {
    id: "lcars",
    label: "LCARS",
    vars: {
      "--bg":           "#000000",
      "--bg-secondary": "#0a0a1a",
      "--bg-tertiary":  "#111130",
      "--surface":      "#0d0d28",
      "--border":       "#cc6600",
      "--border-focus": "#ff9900",
      "--text":         "#ff9900",
      "--text-muted":   "#cc7700",
      "--text-dim":     "#885500",
      "--accent":       "#cc6600",
      "--accent-hover": "#ff9900",
      "--success":      "#99cc00",
      "--warning":      "#ffcc00",
      "--danger":       "#cc0000",
      "--font-display": "'Antonio', 'Orbitron', 'Arial Narrow', sans-serif",
      "--font-mono":    "'Share Tech Mono', monospace",
      "--radius":       "18px",
      "--radius-sm":    "4px",
    },
  },

  monolith: {
    id: "monolith",
    label: "Monolith",
    vars: {
      "--bg":           "#0a0a0a",
      "--bg-secondary": "#111111",
      "--bg-tertiary":  "#1a1a1a",
      "--surface":      "#111111",
      "--border":       "#2a2a2a",
      "--border-focus": "#555555",
      "--text":         "#cccccc",
      "--text-muted":   "#666666",
      "--text-dim":     "#444444",
      "--accent":       "#444444",
      "--accent-hover": "#666666",
      "--success":      "#448844",
      "--warning":      "#886644",
      "--danger":       "#884444",
      "--font-display": "'IBM Plex Mono', 'Courier New', monospace",
      "--font-mono":    "'IBM Plex Mono', monospace",
      "--radius":       "2px",
      "--radius-sm":    "1px",
    },
  },

  retrocrt: {
    id: "retrocrt",
    label: "Retro-CRT",
    vars: {
      "--bg":           "#050a05",
      "--bg-secondary": "#071007",
      "--bg-tertiary":  "#0a1a0a",
      "--surface":      "#071007",
      "--border":       "#1a4a1a",
      "--border-focus": "#00ff41",
      "--text":         "#00ff41",
      "--text-muted":   "#00aa2a",
      "--text-dim":     "#006618",
      "--accent":       "#00cc33",
      "--accent-hover": "#00ff41",
      "--success":      "#00ff41",
      "--warning":      "#ffcc00",
      "--danger":       "#ff3300",
      "--font-display": "'VT323', 'Share Tech Mono', monospace",
      "--font-mono":    "'VT323', monospace",
      "--radius":       "0px",
      "--radius-sm":    "0px",
    },
  },

  bluefin: {
    id: "bluefin",
    label: "Bluefin-mono",
    vars: {
      "--bg":           "#0a0f1a",
      "--bg-secondary": "#0f1929",
      "--bg-tertiary":  "#162035",
      "--surface":      "#0f1929",
      "--border":       "#1e3050",
      "--border-focus": "#4080c0",
      "--text":         "#a0c8f0",
      "--text-muted":   "#608ab0",
      "--text-dim":     "#3a5570",
      "--accent":       "#2060a0",
      "--accent-hover": "#4080c0",
      "--success":      "#40a080",
      "--warning":      "#a09040",
      "--danger":       "#a04040",
      "--font-display": "'Roboto Mono', 'JetBrains Mono', monospace",
      "--font-mono":    "'Roboto Mono', monospace",
      "--radius":       "6px",
      "--radius-sm":    "3px",
    },
  },

  madscience: {
    id: "madscience",
    label: "Mad Science",
    vars: {
      "--bg":           "#050508",
      "--bg-secondary": "#0d0a1a",
      "--bg-tertiary":  "#130f25",
      "--surface":      "#0d0a1a",
      "--border":       "#1a2e12",
      "--border-focus": "#39ff14",
      "--text":         "#c8ffb0",
      "--text-muted":   "#5a8050",
      "--text-dim":     "#3a5530",
      "--accent":       "#39ff14",
      "--accent-hover": "#bf00ff",
      "--accent-2":     "#bf00ff",
      "--success":      "#39ff14",
      "--warning":      "#f5f500",
      "--danger":       "#ff006e",
      "--font-display": "'Share Tech Mono', 'Courier New', monospace",
      "--font-mono":    "'Courier New', monospace",
      "--radius":       "2px",
      "--radius-sm":    "1px",
      "--glow-green":   "0 0 8px #39ff14, 0 0 20px #39ff1440",
      "--glow-purple":  "0 0 8px #bf00ff, 0 0 20px #bf00ff40",
    },
  },

  deepspace: {
    id: "deepspace",
    label: "Deep Space",
    vars: {
      "--bg":           "#080a0f",   // void hull — matte deep black
      "--bg-secondary": "#111520",   // console surface
      "--bg-tertiary":  "#161c2e",   // elevated panel
      "--surface":      "#111520",
      "--border":       "#21283b",   // zero-tolerance bezel seam
      "--border-focus": "#00a3ff",   // data-cyan status readout
      "--text":         "#ffffff",   // starlight-pure
      "--text-muted":   "#8e8e93",   // starlight-dim
      "--text-dim":     "#4a4f5e",
      "--accent":       "#00a3ff",   // primary computer status blue
      "--accent-hover": "#33b8ff",
      "--success":      "#00a3ff",   // cyan = nominal / operational
      "--warning":      "#ff9500",   // data-amber secondary grid
      "--danger":       "#ff2d55",   // HAL 9000 eye — luminous red
      "--font-display": "'Chakra Petch', 'Helvetica Neue', sans-serif",
      "--font-mono":    "'Share Tech Mono', monospace",
      "--radius":       "0px",       // rectilinear — no curves in space
      "--radius-sm":    "0px",
      "--glow-hal":     "0 0 8px #ff2d55, 0 0 24px #ff2d5540",
      "--glow-cyan":    "0 0 8px #00a3ff, 0 0 20px #00a3ff40",
    },
  },

  // neon80s and pacman added 2026-08-07 -- FOUND that session (see
  // docs/ontology.yaml's theme_preference entry and
  // docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §2c/2d):
  // family-hub.html had these two presets and this file didn't, real
  // cross-app theme-catalog drift, not by design. Palette translated from
  // family-hub's THEMES entries of the same id into this file's own CSS
  // var names using the mapping already consistent across every other
  // paired theme here: family --night->--bg, --gold->--accent/--border-focus,
  // --teal->--success, --rose->--danger, --cream->--text. Not a byte-for-byte
  // port (family-hub's vars are a different, glass-morphism-oriented set),
  // but the same palette and vibe.
  neon80s: {
    id: "neon80s",
    label: "80s Neon",
    vars: {
      "--bg":           "#0d0221",
      "--bg-secondary": "#170a35",
      "--bg-tertiary":  "#20114a",
      "--surface":      "#170a35",
      "--border":       "#4a2270",
      "--border-focus": "#ff2dd4",
      "--text":         "#f6f0ff",
      "--text-muted":   "#b9a0d9",
      "--text-dim":     "#7a5a9e",
      "--accent":       "#ff2dd4",
      "--accent-hover": "#ff5fe0",
      "--accent-2":     "#00fff0",
      "--success":      "#00fff0",
      "--warning":      "#ff6b35",
      "--danger":       "#ffe600",
      "--font-display": "'Monoton', 'Chakra Petch', sans-serif",
      "--font-mono":    "'Orbitron', 'Chakra Petch', sans-serif",
      "--radius":       "2px",
      "--radius-sm":    "1px",
      "--glow-neon":    "0 0 8px #ff2dd4, 0 0 24px #ff2dd440",
    },
  },

  pacman: {
    id: "pacman",
    label: "Pac-Man",
    vars: {
      "--bg":           "#000000",
      "--bg-secondary": "#0a0a14",
      "--bg-tertiary":  "#12122a",
      "--surface":      "#0a0a14",
      "--border":       "#2121de",
      "--border-focus": "#ffff00",
      "--text":         "#ffffff",
      "--text-muted":   "#cccccc",
      "--text-dim":     "#888888",
      "--accent":       "#ffff00",
      "--accent-hover": "#ffff66",
      "--success":      "#00ffde",
      "--warning":      "#ffaa00",
      "--danger":       "#ff0000",
      "--font-display": "'Bungee', 'Chakra Petch', sans-serif",
      "--font-mono":    "'VT323', 'Share Tech Mono', monospace",
      "--radius":       "16px",
      "--radius-sm":    "8px",
    },
  },

  // Reworked 2026-08-19 (user report, with screenshot + the film's own
  // reference color swatch): the first pass's card surface (#f9f5eb,
  // near-white) didn't actually blend with the turquoise background --
  // family-hub.html's hardcoded rgba(75,200,200,...) card colors were the
  // real culprit there (see that file's own comment) -- but this app's
  // highway-sign yellow (--bg-tertiary) and broad terracotta accent usage
  // weren't drawn from the film's own palette at all, just an approximation.
  // Rebuilt from the user-supplied swatch (an actual Asteroid City still):
  // deep sky turquoise stays the page background as requested; "larger UI
  // element boxes... across the site" get desert sand (unmistakably
  // different from teal in both hue and lightness, not just a low-opacity
  // tint of it) as the primary surface and dusty sage as the secondary/
  // hover tier -- the sky/ground/foliage layering the reference image
  // itself uses. Blazing orange is reserved for --accent only (primary
  // actions, focus, "today"-class highlights) per "a minimal amount of
  // navajo red as accent" -- every rounded control border (--border-focus)
  // uses the more muted sun-baked rust instead, so orange doesn't spread
  // across every input/button on the page.
  asteroidcity: {
    id: "asteroidcity",
    label: "Asteroid City",
    vars: {
      "--bg":           "#009ca6", // Deep Sky Turquoise
      "--bg-secondary": "#dcb881", // Desert Sand -- primary card/box surface
      "--bg-tertiary":  "#a1c19b", // Dusty Sage -- secondary/hover surface
      "--surface":      "#ffffff",
      "--border":       "#1f3438", // deep matte charcoal signage outline
      "--border-focus": "#ad7154", // Sun-Baked Rust -- everyday control borders (muted, not the accent)
      "--text":         "#1f3438",
      "--text-muted":   "#3f7166", // Sage Shadow
      "--text-dim":     "#5c7d74",
      "--accent":       "#e34e24", // Blazing Orange -- minimal, high-impact accent only
      "--accent-hover": "#c98f5c", // Muted Terracotta
      "--success":      "#608c4a", // Cactus Green
      "--warning":      "#ad7154", // Sun-Baked Rust
      "--danger":       "#b33a2b",
      "--font-display": "'Georgia', 'Times New Roman', serif",
      "--font-mono":    "'Courier New', monospace",
      "--radius":       "2px",
      "--radius-sm":    "2px",
    },
  },
};

const STORAGE_KEY = "cabin-theme";

// A cross-app link-out (family-hub.html's "How's the cabin?") carries the
// source app's active theme as ?theme=<id> so it keeps carrying over here
// instead of resetting -- localStorage alone can't do this, since the two
// apps live on different subdomains and don't share it. Query param wins
// over this app's own last-saved choice on first load only; every
// subsequent in-app change persists to localStorage as before. Pure
// function (no window/localStorage reads inside it) so it's directly
// unit-testable -- see src/ThemeProvider.test.jsx.
export function resolveInitialThemeId(searchParams, storedThemeId, themes = THEMES) {
  const fromUrl = searchParams.get("theme");
  if (fromUrl && themes[fromUrl]) return fromUrl;
  return (storedThemeId && themes[storedThemeId]) ? storedThemeId : "modern";
}

// ─── Context ────────────────────────────────────────────────────────────────
const ThemeContext = createContext(null);
export function useTheme() { return useContext(ThemeContext); }

// ─── Provider ───────────────────────────────────────────────────────────────
export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(() =>
    resolveInitialThemeId(new URLSearchParams(window.location.search), localStorage.getItem(STORAGE_KEY))
  );

  const theme = THEMES[themeId] || THEMES.modern;

  useEffect(() => {
    const root = document.documentElement;
    Object.entries(theme.vars).forEach(([k, v]) => root.style.setProperty(k, v));
    document.body.style.fontFamily = theme.vars["--font-display"];
    // Stamp data-theme so CSS selectors can apply theme-specific effects
    root.setAttribute("data-theme", themeId);
    localStorage.setItem(STORAGE_KEY, themeId);

    // Load web fonts only for themes that need them
    const THEME_FONTS = {
      madscience: {
        id: "cabin-font-madscience",
        href: "https://fonts.googleapis.com/css2?family=Share+Tech+Mono&display=swap",
      },
      deepspace: {
        id: "cabin-font-deepspace",
        href: "https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@400;700&family=Share+Tech+Mono&display=swap",
      },
      neon80s: {
        id: "cabin-font-neon80s",
        href: "https://fonts.googleapis.com/css2?family=Monoton&family=Orbitron:wght@400;600;700&display=swap",
      },
      pacman: {
        id: "cabin-font-pacman",
        href: "https://fonts.googleapis.com/css2?family=Bungee&family=VT323&display=swap",
      },
    };
    Object.entries(THEME_FONTS).forEach(([tid, font]) => {
      let el = document.getElementById(font.id);
      if (themeId === tid) {
        if (!el) {
          el = document.createElement("link");
          el.id = font.id;
          el.rel = "stylesheet";
          el.href = font.href;
          document.head.appendChild(el);
        }
      } else {
        el?.remove();
      }
    });
  }, [themeId, theme]);

  const setTheme = (id) => {
    if (THEMES[id]) setThemeId(id);
  };

  return (
    <ThemeContext.Provider value={{ theme, themeId, setTheme, themes: THEMES }}>
      {children}
    </ThemeContext.Provider>
  );
}

// ─── ThemeSwitcher widget (drop into any toolbar) ───────────────────────────
export function ThemeSwitcher() {
  const { themeId, setTheme, themes } = useTheme();
  const [open, setOpen] = useState(false);

  return (
    <div className="theme-switcher" style={{ position: "relative" }}>
      <button
        className="btn-ghost theme-btn"
        onClick={() => setOpen(o => !o)}
        title="Change theme"
      >
        <Palette size={14}/>
        <span>{themes[themeId]?.label || "Theme"}</span>
      </button>
      {open && (
        <div className="theme-dropdown">
          {Object.values(themes).map(t => (
            <button
              key={t.id}
              className={`theme-option ${themeId === t.id ? "theme-option-active" : ""}`}
              onClick={() => { setTheme(t.id); setOpen(false); }}
            >
              <ThemeSwatch vars={t.vars}/>
              {t.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ThemeSwatch({ vars }) {
  return (
    <span className="theme-swatch" style={{
      background: `linear-gradient(135deg, ${vars["--bg-secondary"]} 50%, ${vars["--accent"]} 50%)`,
      border: `1px solid ${vars["--border"]}`,
    }}/>
  );
}
