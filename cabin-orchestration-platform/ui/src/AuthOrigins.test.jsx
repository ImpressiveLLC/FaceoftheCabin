import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderHook, cleanup } from "@testing-library/react";

// Exercise an actual production API origin and a separate configured Home origin.
vi.stubEnv("VITE_CABIN_API_BASE", "https://api.unicornpingpong.com");
vi.stubEnv("VITE_HOME_API_BASE", "https://home.example.test");
const { useGoogleAuth, useDemoAuth, isTrustedApiUrl, mergeHubLocations } = await import("./App.jsx");

function storeSession(scheme) {
  const expiry = String(Date.now() + 3600000);
  if (scheme === "Bearer") {
    sessionStorage.setItem("cabinAccessToken", "secret");
    sessionStorage.setItem("cabinTokenExpiresAt", expiry);
  } else {
    const prefix = scheme === "CabinSession" ? "cabinSession" : "managedSession";
    localStorage.setItem(`${prefix}Token`, "secret");
    localStorage.setItem(`${prefix}ExpiresAt`, expiry);
  }
}

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); });

describe("immutable API credential origins", () => {
  it.each(["Bearer", "CabinSession", "ManagedSession"])("blocks untrusted destinations before fetch for %s", async scheme => {
    storeSession(scheme);
    const fetcher = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal("fetch", fetcher);
    const { result } = renderHook(() => useGoogleAuth());
    const mutatedLocations = mergeHubLocations({}, [{ id: "home", apiBase: "https://attacker.example" }]);
    await expect(result.current.authedFetch(`${mutatedLocations.home.apiBase}/api/alerts/acknowledgments`))
      .rejects.toThrow("Untrusted API origin");
    expect(fetcher).not.toHaveBeenCalled();
    for (const base of ["https://api.unicornpingpong.com", "https://home.example.test", window.location.origin]) {
      await result.current.authedFetch(`${base}/api/events/status-checks`);
      expect(fetcher).toHaveBeenLastCalledWith(`${base}/api/events/status-checks`, expect.objectContaining({
        headers: { Authorization: `${scheme} secret` }, redirect: "error",
      }));
    }
  });

  it("applies the same origin guard to demo credentials and relative same-origin URLs", async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal("fetch", fetcher);
    const { result } = renderHook(() => useDemoAuth("demo-secret"));
    await expect(result.current.authedFetch("https://attacker.example/api/events")).rejects.toThrow("Untrusted API origin");
    expect(fetcher).not.toHaveBeenCalled();
    await result.current.authedFetch("/api/events/status-checks");
    expect(fetcher).toHaveBeenCalledWith("/api/events/status-checks", expect.objectContaining({
      headers: { Authorization: "CabinToken demo-secret" }, redirect: "error",
    }));
  });

  it.each([null, undefined, "", "https://api.unicornpingpong.com.attacker.example/api/events",
    "https://api.unicornpingpong.com@attacker.example/api/events", "https://user:pass@api.unicornpingpong.com/api/events",
    "http://api.unicornpingpong.com/api/events", "https://api.unicornpingpong.com:444/api/events", "javascript:alert(1)"])
    ("rejects malformed, credentialed, or lookalike URL %s", url => { expect(isTrustedApiUrl(url)).toBe(false); });

  it("does not add an origin when runtime environment or location records change", () => {
    vi.stubEnv("VITE_HOME_API_BASE", "https://runtime-attacker.example");
    const merged = mergeHubLocations({}, [{ id: "home", apiBase: "https://runtime-attacker.example" }]);
    expect(isTrustedApiUrl(merged.home.apiBase)).toBe(false);
    expect(isTrustedApiUrl("https://home.example.test/api/events")).toBe(true);
    vi.stubEnv("VITE_HOME_API_BASE", "https://home.example.test");
  });
});
