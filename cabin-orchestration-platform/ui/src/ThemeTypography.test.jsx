import React from "react";
import { afterEach, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ThemeProvider, useTheme, THEMES } from "./ThemeProvider.jsx";

function Switcher() {
  const { setTheme } = useTheme();
  return <>{["pacman", "neon80s", "modern"].map(id =>
    <button key={id} onClick={() => setTheme(id)}>{id}</button>)}</>;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  document.documentElement.removeAttribute("style");
  document.documentElement.removeAttribute("data-theme");
  document.body.removeAttribute("style");
});

it("switches the reading tier separately from headings and restores the default after retro themes", () => {
  render(<ThemeProvider><Switcher /></ThemeProvider>);
  for (const id of ["pacman", "neon80s", "modern"]) {
    fireEvent.click(screen.getByText(id));
    const root = document.documentElement;
    expect(root.style.getPropertyValue("--font-display")).toBe(THEMES[id].vars["--font-display"]);
    expect(root.style.getPropertyValue("--font-ui")).toBe(
      THEMES[id].vars["--font-ui"] || THEMES[id].vars["--font-display"]);
    expect(root.getAttribute("data-theme")).toBe(id);
    expect(localStorage.getItem("cabin-theme")).toBe(id);
  }
  expect(document.body.style.fontFamily).not.toMatch(/Crackman|Baumans|Oxanium|VT323/);
});
