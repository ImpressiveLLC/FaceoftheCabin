/**
 * ThemeProvider — independently-selectable palette + font presets.
 *
 * Presets: Modern (default), LCARS, Monolith, Retro-CRT, Bluefin-mono
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
};

const STORAGE_KEY = "cabin-theme";

// ─── Context ────────────────────────────────────────────────────────────────
const ThemeContext = createContext(null);
export function useTheme() { return useContext(ThemeContext); }

// ─── Provider ───────────────────────────────────────────────────────────────
export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(() => {
    // A cross-app link-out (family-hub.html's "How's the cabin?") carries
    // the source app's active theme as ?theme=<id> so it keeps carrying
    // over here instead of resetting -- localStorage alone can't do this,
    // since the two apps live on different subdomains and don't share it.
    // Query param wins over this app's own last-saved choice on first load
    // only; every subsequent in-app change persists to localStorage as before.
    const fromUrl = new URLSearchParams(window.location.search).get("theme");
    if (fromUrl && THEMES[fromUrl]) return fromUrl;
    return localStorage.getItem(STORAGE_KEY) || "modern";
  });

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
