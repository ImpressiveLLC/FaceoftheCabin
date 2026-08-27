/**
 * Cabin Orchestration Platform — Shell UI
 *
 * Docked/expandable panels:
 *   FAMILY_HUB     — "My Places" location-card grid (Cabin/Home at a glance)
 *   FAMILY_CONFIG  — Instance config: Google account, notification preferences, platform/remote access
 *   DEVICE_MANAGER — Add / edit / remove / activate devices (drag-reorder)
 *   MONITORING     — Live WebSocket telemetry tiles + Grafana embed
 *   RULES_ENGINE   — Node-RED embed + rule CRUD + Kafka topic browser
 *   CAMERA_EVENTS  — Authenticated camera snapshots/clips/live view
 *   OPPORTUNITY_MAP — Tech ID Service findings as actionable, ontology-
 *                     linked "Opportunities" (See/Think/Act)
 *
 * Location switcher: Cabin | Home | Both
 * Both hubs are reachable via Tailscale (cabin-hub / home-hub).
 * Env vars VITE_CABIN_* and VITE_HOME_* override defaults for local dev.
 */

import React, { useEffect, useState, useRef, useCallback, useMemo, createContext, useContext, forwardRef } from "react";
import { createRoot } from "react-dom/client";
import { ThemeProvider, ThemeSwitcher, useTheme } from "./ThemeProvider.jsx";
import {
  Home, Settings, Cpu, Activity, Zap,
  ChevronDown, ChevronUp, Wifi, WifiOff,
  Droplets, Thermometer, Camera, ShieldAlert, Lock, Unlock,
  RefreshCw, Plus, Trash2, ToggleLeft, ToggleRight,
  AlertTriangle, CheckCircle, Circle, ArrowLeft,
  Eye, Edit2, UserPlus, Minus, ExternalLink,
  Radio, Clock, Battery, MapPin, GripVertical, BarChart2,
  Lightbulb, ThumbsUp, ThumbsDown, ShoppingCart, Wrench, Send, Search, Bell,
  Wind
} from "lucide-react";
import "./styles.css";

// ─── Temperature unit ──────────────────────────────────────────────────────
function useTempUnit() {
  const [unit, setUnit] = useState(() => localStorage.getItem("tempUnit") || "F");
  const toggle = () => setUnit(u => { const n = u === "F" ? "C" : "F"; localStorage.setItem("tempUnit", n); return n; });
  return [unit, toggle];
}
function fmtTemp(celsius, unit) {
  if (celsius == null) return "—";
  const val = unit === "F" ? (celsius * 9/5 + 32).toFixed(1) : celsius;
  return `${val}°${unit}`;
}

// ─── Location config ───────────────────────────────────────────────────────
// Both hubs exposed via Tailscale MagicDNS. Override per-hub with env vars
// (e.g. VITE_CABIN_API_BASE=http://localhost:8080 for local dev on cabin-hub).
const LOCATIONS = {
  cabin: {
    id: "cabin",
    label: "Cabin",
    apiBase:       import.meta.env.VITE_CABIN_API_BASE       || "http://cabin-hub:8090",
    wsBase:        import.meta.env.VITE_CABIN_WS_BASE        || "ws://cabin-hub:9001",
    grafanaUrl:    import.meta.env.VITE_CABIN_GRAFANA_URL    || "http://cabin-hub:3002",
    noderedUrl:    import.meta.env.VITE_CABIN_NODERED_URL    || "http://cabin-hub:1880",
    haUrl:         import.meta.env.VITE_CABIN_HA_URL         || "http://cabin-hub:8123",
    frigateUrl:    import.meta.env.VITE_CABIN_FRIGATE_URL    || "http://cabin-hub:5000",
    z2mUrl:        import.meta.env.VITE_CABIN_Z2M_URL        || "http://cabin-hub:8080",
    familyHubUrl:  import.meta.env.VITE_CABIN_FAMILY_HUB_URL || null,
  },
  home: {
    id: "home",
    label: "Home",
    apiBase:       import.meta.env.VITE_HOME_API_BASE       || "http://home-hub:8080",
    wsBase:        import.meta.env.VITE_HOME_WS_BASE        || "ws://home-hub:9001",
    grafanaUrl:    import.meta.env.VITE_HOME_GRAFANA_URL    || "http://home-hub:3000",
    noderedUrl:    import.meta.env.VITE_HOME_NODERED_URL    || "http://home-hub:1880",
    haUrl:         import.meta.env.VITE_HOME_HA_URL         || "http://home-hub:8123",
    frigateUrl:    import.meta.env.VITE_HOME_FRIGATE_URL    || "http://home-hub:5000",
    familyHubUrl:  import.meta.env.VITE_HOME_FAMILY_HUB_URL || null,
  },
};

// A location whose apiBase is still the undeployed-placeholder value
// (`{id}-hub:<port>`, a Docker-internal hostname no real browser can
// resolve) has never had its VITE_*_API_BASE build arg set -- i.e. it
// isn't live yet, same signal docker-compose.m920q.yml's build args
// encode. Used so an undeployed location's always-failing fetch doesn't
// drag down the "API offline" badge for a location that IS actually up
// (found 2026-08-07: viewing "Home" or "Both" before home-hub existed
// permanently flipped the badge red regardless of cabin's real health).
export function isLocationDeployed(loc) {
  return !!loc?.apiBase && !new RegExp(`^https?://${loc.id}-hub:`).test(loc.apiBase);
}

// Real Grafana dashboard UIDs that actually exist, by location -- see the
// MonitoringPanel Grafana embed's own comment for why this replaced a
// hardcoded `${location}-overview` UID that was never real. Only "cabin"
// has one today (the Frigate monitoring dashboard, Phase 7 §1a); a
// per-location, ontology-driven dashboard is still open work.
const GRAFANA_DASHBOARD_UID = {
  cabin: "aezbolgn22qdce",
};
// ─── Panel definitions ─────────────────────────────────────────────────────
// ─── Google Sign-In — cabin-ui's OWN standalone flow ───────────────────────
// Deliberately separate from Family Hub's sign-in (a different app, a
// different session) even though it reuses the same Web-application OAuth
// client (VITE_CABIN_GOOGLE_CLIENT_ID, same underlying Google Cloud client
// as family-hub's GOOGLE_CLIENT_ID — needs cabin.unicornpingpong.com added
// as an authorized JS origin on that client). Gates UI visibility only —
// /api/events itself stays unauthenticated server-side, same precedent as
// /api/devices; this is a client-side "who's looking" gate, not a second
// auth layer on the API.
//
// Found 2026-08-03 (external review): a stored token was reused across
// page loads with no expiry tracking, so a genuinely expired token still
// rendered as "signed in" (email shown, "Sign out" button present) while
// every authenticated request silently 401'd and callers converted that
// into an empty array/list — camera controls just vanished with no
// explanation. Fixed by tracking `expires_in`, refusing to resurrect an
// already-expired stored token on load, and centralizing every
// authenticated request through `authedFetch`, which clears the session
// and flips `sessionExpired` the moment any call comes back 401.
function loadStoredGoogleSession() {
  const token = sessionStorage.getItem("cabinAccessToken");
  const email = sessionStorage.getItem("cabinUserEmail");
  const expiresAtRaw = sessionStorage.getItem("cabinTokenExpiresAt");
  const expiresAt = expiresAtRaw ? Number(expiresAtRaw) : null;
  // No tracked expiry (a session stored before this fix shipped) is
  // treated the same as an expired one -- fail closed, not open.
  if (token && (!expiresAt || Date.now() >= expiresAt)) {
    sessionStorage.removeItem("cabinAccessToken");
    sessionStorage.removeItem("cabinUserEmail");
    sessionStorage.removeItem("cabinTokenExpiresAt");
    return { token: null, email: null };
  }
  return { token, email };
}

function useGoogleAuth() {
  const clientId = import.meta.env.VITE_CABIN_GOOGLE_CLIENT_ID || "";
  const [accessToken, setAccessToken] = useState(() => loadStoredGoogleSession().token);
  const [userEmail, setUserEmail] = useState(() => loadStoredGoogleSession().email);
  const [sessionExpired, setSessionExpired] = useState(false);
  const tokenClientRef = useRef(null);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUserEmail(null);
    sessionStorage.removeItem("cabinAccessToken");
    sessionStorage.removeItem("cabinUserEmail");
    sessionStorage.removeItem("cabinTokenExpiresAt");
  }, []);

  const ensureTokenClient = useCallback(() => {
    if (tokenClientRef.current || !window.google?.accounts?.oauth2 || !clientId) return tokenClientRef.current;
    tokenClientRef.current = window.google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: "openid email",
      callback: async (resp) => {
        if (resp.error) return;
        setSessionExpired(false);
        setAccessToken(resp.access_token);
        sessionStorage.setItem("cabinAccessToken", resp.access_token);
        // expires_in is seconds-from-now per Google's token response; a
        // small safety margin (30s) means we treat it as expired slightly
        // before Google actually would, so a request never races the
        // exact expiry instant.
        const expiresAt = Date.now() + (Math.max(Number(resp.expires_in) || 3600, 60) - 30) * 1000;
        sessionStorage.setItem("cabinTokenExpiresAt", String(expiresAt));
        try {
          const res = await fetch("https://www.googleapis.com/oauth2/v3/userinfo", {
            headers: { Authorization: `Bearer ${resp.access_token}` },
          });
          const info = await res.json();
          if (info.email) {
            setUserEmail(info.email);
            sessionStorage.setItem("cabinUserEmail", info.email);
          }
        } catch { /* email display is cosmetic — sign-in already succeeded */ }
      },
    });
    return tokenClientRef.current;
  }, [clientId]);

  const signIn = useCallback(() => {
    const client = ensureTokenClient();
    client?.requestAccessToken({ prompt: "select_account" });
  }, [ensureTokenClient]);

  const signOut = useCallback(() => {
    setSessionExpired(false);
    clearSession();
  }, [clearSession]);

  // Called by authedFetch on a 401 -- the token looked valid client-side
  // (present, not past its tracked expiry) but the server rejected it
  // anyway (revoked, clock skew, etc.). Clearing accessToken here also
  // stops any in-flight media: CameraLiveView's <img src> and
  // useAuthedMediaUrl's effect both key off accessToken being present.
  const handleUnauthorized = useCallback(() => {
    clearSession();
    setSessionExpired(true);
  }, [clearSession]);

  // Every authenticated call in this app should go through this instead
  // of a raw fetch() + manual Authorization header, so a 401 is handled
  // once, consistently, instead of each caller independently swallowing
  // it into an empty array with no visible explanation.
  const authedFetch = useCallback((url, options = {}) => {
    if (!accessToken) return Promise.reject(new Error("Not signed in"));
    return fetch(url, {
      ...options,
      headers: { ...(options.headers || {}), Authorization: `Bearer ${accessToken}` },
    }).then(res => {
      if (res.status === 401) handleUnauthorized();
      return res;
    });
  }, [accessToken, handleUnauthorized]);

  // Found 2026-08-03: this hook's return value was a fresh object literal
  // on every render, which is invisible for consumers that only read
  // primitive fields off it -- but the new liveview useEffect below
  // depends on the whole `auth` object, and React's effect-dependency
  // comparison is reference-based. Without this memo, `auth` "changed"
  // (a new object, same values) on every App() re-render -- and App()
  // re-renders constantly from its own 15s device-refresh interval and
  // friends -- so the liveview effect's cleanup+rerun (stop, then start)
  // fired every few seconds instead of only on a real sign-in/out. Real
  // symptom in production: /liveview/start and /stop calls looping every
  // 5-15s, so the live relay never got more than a few seconds to
  // stabilize before being torn down and restarted -- the camera view
  // never had a chance to show anything but the last frame from before
  // the loop started. `refreshCameraList` in CameraEventsPanel had the
  // same latent bug (excessive re-fetching), just less visible since
  // repeating a GET is cheaper than repeatedly restarting a live session.
  return useMemo(() => ({
    accessToken, userEmail, signedIn: !!accessToken, sessionExpired,
    signIn, signOut, authedFetch, configured: !!clientId,
  }), [accessToken, userEmail, sessionExpired, signIn, signOut, authedFetch, clientId]);
}

// ─── Camera media: authenticated snapshot/clip fetch ──────────────────────
// /api/camera/** requires a Google bearer token (see WebConfig.java) — a
// plain <img src="..."> or <video src="..."> can't set that header, so we
// fetch as a blob with fetch() (which can) and hand the component an
// object URL instead. Revokes the previous URL on cleanup/change so this
// doesn't leak memory as someone scrolls through a long event list.
// 2026-08-15: a media 404 (Frigate genuinely has no clip/snapshot for this
// event -- expired past retention, or the event never got footage in the
// first place) is an expected, everyday outcome now that
// FrigateEventReconciliationService backfills up to 10 days of event
// history while Frigate itself only retains clips for a much shorter
// window -- not a sign anything is broken. Distinguishing it from a real
// fetch failure (network/auth/5xx) is what lets the two render different,
// honest messages instead of one generic "something went wrong" that
// trains a user to distrust every clip button. Exported so
// src/App.test.jsx can test the classification without mocking fetch.
export function classifyMediaFetchStatus(status) {
  return status === 404 ? "missing" : "error";
}

function useAuthedMediaUrl(url, authedFetch) {
  const [objectUrl, setObjectUrl] = useState(null);
  const [status, setStatus] = useState(null); // null | "missing" | "error"

  useEffect(() => {
    if (!url) { setObjectUrl(null); setStatus(null); return; }
    let cancelled = false;
    let currentUrl = null;
    setStatus(null);
    authedFetch(url)
      .then(res => {
        if (!res.ok) return Promise.reject(Object.assign(new Error(String(res.status)), { status: res.status }));
        return res.blob();
      })
      .then(blob => {
        if (cancelled) return;
        currentUrl = URL.createObjectURL(blob);
        setObjectUrl(currentUrl);
      })
      .catch(err => { if (!cancelled) setStatus(classifyMediaFetchStatus(err?.status)); });
    return () => {
      cancelled = true;
      if (currentUrl) URL.revokeObjectURL(currentUrl);
    };
  }, [url, authedFetch]);

  return { objectUrl, status };
}

// DTM (date/time) stamp overlay, rendered directly on the thumbnail image
// itself -- not just as adjacent list text -- so the image still carries
// its own timestamp if viewed or shared out of that list context. Uses
// CabinEvent.timestamp, already returned by /api/events; no new data or
// backend change needed. If Frigate's own snapshot already burns in a
// timestamp (unverified against the live M920q as of this session -- see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4b), this
// overlay would be redundant with that and worth dropping once confirmed.
function CameraEventThumbnail({ apiBase, authedFetch, frigateEventId, timestamp }) {
  const { objectUrl, status } = useAuthedMediaUrl(
    frigateEventId ? `${apiBase}/api/camera/events/${frigateEventId}/snapshot` : null,
    authedFetch
  );
  if (!frigateEventId || status) {
    return <div className="camera-event-thumb camera-event-thumb-empty"><Camera size={18} /></div>;
  }
  if (!objectUrl) {
    return <div className="camera-event-thumb camera-event-thumb-loading" />;
  }
  return (
    <div className="camera-event-thumb-wrap">
      <img className="camera-event-thumb" src={objectUrl} alt="" />
      {timestamp && <span className="camera-event-thumb-dtm">{new Date(timestamp).toLocaleString()}</span>}
    </div>
  );
}

// Temporary, hardcoded by camera key -- confirmed empirically 2026-08-24
// (live M920q diagnostics): front_door and driveway both feed Frigate a
// persistent stream (native RTSP for front_door when reachable; driveway's
// blinkbridge relay loops a continuously-refreshed local clip via a
// long-running ffmpeg process, confirmed via `docker exec blinkbridge ps
// aux`), so a missing clip from either is genuinely unusual. AldrichFront's
// blinkbridge relay only opens a transient liveview session per motion
// event (see frigate.yml's own comment on home_aldrich_front), so a
// missing clip from it is the expected common case, not a surprise.
// Explicitly NOT ontology-driven yet and NOT health-aware (front_door
// being physically offline right now doesn't flip this to false) -- see
// this session's plan file, Item 1's amendment, for the tracked follow-up
// to migrate this into real device metadata once Item 4's device graph
// exists. Keyed by the same cameraId CameraEventClip already receives as
// cameraName (== CabinEvent.sourceDeviceId == the Frigate camera key).
const CAMERA_FEED_CONTINUOUS = { front_door: true, driveway: true, home_aldrich_front: false };

// Takes a ready-made clipUrl rather than building one internally -- reused
// by both the detection flow (keyed off Frigate's own frigateEventId) and
// the motion-only flow below (keyed off cabin-backend's eventId, via
// clipByTime -- see CameraMediaController.clipByTime's javadoc). frigateUrl
// is only used to build the "Open in Frigate" fallback link on a genuine
// miss, never fetched from directly.
export function CameraEventClip({ authedFetch, clipUrl, frigateUrl, cameraName }) { // exported for src/App.test.jsx's clip-confidence wording test
  const { objectUrl, status } = useAuthedMediaUrl(clipUrl, authedFetch);
  // "missing" (404) is Frigate simply no longer having this clip. For a
  // camera with a genuinely intermittent feed (see CAMERA_FEED_CONTINUOUS
  // above) that's an everyday, expected outcome -- not something to word
  // like a bug. For a continuously-fed camera it's unusual enough to say
  // so plainly, since Frigate's own recording is unconditional and a real
  // miss there is more likely to be worth a person's attention. Any other
  // failure is worded differently on purpose, since that one IS worth a
  // user reporting regardless of feed type.
  if (status === "missing") {
    const continuous = CAMERA_FEED_CONTINUOUS[cameraName] === true;
    return (
      <p className="config-desc">
        {continuous
          ? "Clip not found — this camera usually has continuous footage, so this may be worth checking Frigate directly."
          : "Clip expired or unavailable — Frigate only keeps recordings for a limited time."}
        {frigateUrl && (
          <> <a href={frigateUrl} target="_blank" rel="noreferrer">Open {cameraName ? `${cameraName} in` : ""} Frigate ↗</a> to check its own history directly.</>
        )}
      </p>
    );
  }
  if (status === "error") {
    return <p className="config-desc camera-live-error">Couldn't load this clip — try again shortly.</p>;
  }
  if (!objectUrl) return <p className="config-desc">Loading clip…</p>;
  return <video className="camera-clip-player" src={objectUrl} controls autoPlay muted />;
}

// Live view uses a plain <img> against Frigate's MJPEG multipart stream —
// that's the standard way browsers render multipart/x-mixed-replace, but
// it means the token has to travel as a query param (see
// GoogleAuthInterceptor's extractToken()) since <img> can't set headers
// and this stream is unbounded, unlike snapshot/clip above which can be
// blob-fetched in full.
//
// Found 2026-08-03 (external review): a camera whose Frigate stream is
// down still produces a "completed" <img> load event with
// naturalWidth/naturalHeight of 0 — no error event fires, so the old
// version rendered an empty box with no explanation and the "Stop
// {camera}" button stayed up as if it were working. Fixed with an
// explicit status state: onLoad checks natural dimensions (zero-size
// "success" is treated as a failure, not a success), onError catches a
// hard failure, and a bounded timeout catches a request that never
// resolves either way.
function CameraLiveView({ apiBase, accessToken, cameraName }) {
  const [status, setStatus] = useState("loading"); // loading | ok | error
  const timeoutRef = useRef(null);
  const src = `${apiBase}/api/camera/${cameraName}/live?access_token=${encodeURIComponent(accessToken)}`;

  useEffect(() => {
    setStatus("loading");
    timeoutRef.current = setTimeout(() => {
      setStatus(prev => (prev === "loading" ? "error" : prev));
    }, 8000);
    return () => clearTimeout(timeoutRef.current);
  }, [src]);

  const handleLoad = (e) => {
    clearTimeout(timeoutRef.current);
    const ok = e.target.naturalWidth > 0 && e.target.naturalHeight > 0;
    setStatus(ok ? "ok" : "error");
  };
  const handleError = () => {
    clearTimeout(timeoutRef.current);
    setStatus("error");
  };

  return (
    <div className="camera-live-view">
      <img
        key={src}
        src={src}
        alt={`Live: ${cameraName}`}
        onLoad={handleLoad}
        onError={handleError}
        style={status === "error" ? { display: "none" } : undefined}
      />
      {status === "loading" && <p className="config-desc camera-live-status">Connecting to {cameraName}…</p>}
      {status === "error" && (
        <p className="config-desc camera-live-status camera-live-error">
          Camera unavailable — {cameraName}'s stream didn't load. It may be offline or misconfigured.
        </p>
      )}
    </div>
  );
}

// ─── Panel: Camera Events ───────────────────────────────────────────────────
// /api/events returns every device's events, not just cameras' -- Found
// 2026-08-07: CameraEventsPanel fetched that unfiltered stream directly, so
// leak/temp/motion-sensor state changes showed up mixed in with real
// camera activity. EventController's `camera` query param only scopes to
// one named camera at a time, not "any camera" -- filtering by the
// eventType values cabin_camera_event actually produces
// (DETECTION_*/MOTION_*, per docs/ontology.yaml) is the correct scope
// here client-side. A server-side eventType filter (avoiding fetching
// non-camera events at all) is tracked as the fuller fix in
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4a/§4c.
// Module-level (not redefined per render) and exported so it's directly
// unit-testable -- see src/App.test.jsx.
export const isCameraEvent = (e) => /^(DETECTION_|MOTION_)/.test(e?.eventType || "");

// Page size for both the initial load and each "Load older" click.
const CAMERA_EVENTS_PAGE_SIZE = 30;

// Found 2026-08-12 (user report): window=24h was hardcoded here, so "Load
// older" could only page further into the SAME 24-hour window (increasing
// offset) -- it could never actually reach anything older than 24h, no
// matter how many times clicked, even though Postgres (cabin_event) keeps
// every event indefinitely and EventController's own `window` query param
// already accepted anything. 240h (10 days) is the practical ceiling, not
// an arbitrary round number: Frigate's own config retains motion/alert
// clips for exactly 10 days (infra/frigate.yml's record.detections/
// alerts.retain.days) -- events older than that still exist as bare
// Postgres rows, but the clip a card would try to play is already gone.
export const CAMERA_EVENTS_WINDOWS = [
  { value: "24h", label: "Last 24 hours" },
  { value: "72h", label: "Last 3 days" },
  { value: "168h", label: "Last 7 days" },
  { value: "240h", label: "Last 10 days" },
];

export function cameraEventsWindowLabel(window) {
  return CAMERA_EVENTS_WINDOWS.find(w => w.value === window)?.label || "the selected range";
}

// MOTION_ON/OFF is real operational telemetry (it's how touchCamera()
// keeps a camera's liveness state fresh -- see MqttBridgeService) but it
// fires far more often than actual replayable detections and has no
// clip/snapshot of its own to show. Rendering it inline at the same
// weight as DETECTION_* rows buried real activity under a wall of "motion
// on/off" -- this splits the two so detections stay the primary,
// always-visible list and motion becomes a separate, collapsed-by-default
// summary instead of disappearing outright. Exported so
// src/App.test.jsx can test the split without rendering the panel.
export function groupCameraEvents(events) {
  const detections = [];
  const motionEvents = [];
  for (const e of events) {
    if ((e?.eventType || "").startsWith("MOTION_")) motionEvents.push(e);
    else detections.push(e);
  }
  return { detections, motionEvents };
}

// Pure URL-builder, no fetch inside -- extracted specifically so the
// offset/eventTypePrefix query-param wiring is directly unit-testable
// without mocking fetch. See src/App.test.jsx.
export function buildCameraEventsUrl(apiBase, offset, window = "24h", location = null) {
  const base = `${apiBase}/api/events?limit=${CAMERA_EVENTS_PAGE_SIZE}&offset=${offset}&window=${window}&eventTypePrefix=DETECTION_,MOTION_`;
  return location ? `${base}&location=${location}` : base;
}

// "Assign a workflow directly from Camera Events" -- creates/removes a
// real WorkflowRule (trigger_camera_detection, scoped to this one camera
// via triggerDeviceId, existing notify_critical action -- see
// WorkflowRuleService/docs/ontology.yaml, nothing new needed action-side)
// via the real /api/rules/workflows API rather than the hardcoded
// WORKFLOW_BY_TYPE groupBy bucket Device Manager already has. Toggle
// semantics, matching DmRowEnableToggle's own pattern elsewhere in this
// file: off->on creates (workflows are always created disabled, see
// RulesController's own doc comment) then activates in one user action;
// on->off deletes outright rather than leaving a disabled row behind,
// since a simple per-camera notify toggle has no reason to keep history.
export function CameraNotifyToggle({ cameraName, apiBase, authedFetch, workflows, onChanged }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const existing = (workflows || []).find(w =>
    w.triggerDefinitionId === "trigger_camera_detection" && w.triggerDeviceId === cameraName);

  const toggle = async () => {
    setSaving(true);
    setError(null);
    try {
      if (existing) {
        const response = await authedFetch(`${apiBase}/api/rules/workflows/${existing.workflowId}`, { method: "DELETE" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
      } else {
        const createResponse = await authedFetch(`${apiBase}/api/rules/workflows`, {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            workflowId: `notify-${cameraName}-${Date.now()}`, name: `Notify: ${cameraName}`,
            location: "cabin", triggerKind: "DEVICE_EVENT", triggerDefinitionId: "trigger_camera_detection",
            triggerDeviceId: cameraName, enabled: false, resetMode: "MANUAL_ONLY", parentWorkflowId: null,
            actions: [{ actionId: `notify-${cameraName}-${Date.now()}-a1`, stepOrder: 0, actionDefinitionId: "notify_critical" }],
          }),
        });
        const created = await createResponse.json().catch(() => ({}));
        if (!createResponse.ok || created.error) throw new Error(created.error || `HTTP ${createResponse.status}`);
        const activated = await authedFetch(`${apiBase}/api/rules/workflows/${created.workflowId}/activate`, { method: "POST" });
        if (!activated.ok) throw new Error(`HTTP ${activated.status}`);
      }
      onChanged();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <button
      className={`btn-ghost camera-notify-toggle${existing ? " active" : ""}`}
      onClick={toggle}
      disabled={saving}
      title={error ? `Not saved: ${error}` : (existing
        ? `Notifying on ${cameraName} activity -- click to stop`
        : `Notify me when ${cameraName} detects something`)}
    >
      <Bell size={14} /> {existing ? "Notifying" : "Notify me"}
    </button>
  );
}

export function CameraEventsPanel({ auth }) { // exported for src/App.test.jsx's time-range window test
  const { locationCfg, workflows, refreshWorkflows } = useApp();
  // 2026-08-15: a location can have real devices (e.g. Home's AldrichFront,
  // a Blink camera relayed through the cabin M920q's own blinkbridge/
  // Frigate) before that location has its own deployed backend -- same
  // "borrow cabin's until this location is real" fallback RulesPanel's
  // hasOwnNodeRed already uses for Node-RED embeds. When the active
  // location isn't actually deployed, query cabin's backend instead and
  // filter to just that location's devices server-side; a genuinely
  // deployed location's own backend never needs the filter, since it only
  // ever has its own data to begin with.
  const locationDeployed = isLocationDeployed(locationCfg);
  const apiBase = locationDeployed ? (locationCfg?.apiBase || LOCATIONS.cabin.apiBase) : LOCATIONS.cabin.apiBase;
  // 2026-08-16 (user report): the comment above only accounted for an
  // undeployed location borrowing cabin's backend -- it missed that
  // cabin's OWN backend is the same shared instance, so it can contain
  // another location's events too (Home's AldrichFront, reconciled from
  // the same Frigate). Filtering only kicked in for a non-cabin location,
  // so viewing "Cabin" specifically returned every location's events
  // unfiltered. The real distinguishing question isn't "is this cabin?"
  // but "are we hitting the shared backend at all?" -- apply the filter
  // any time apiBase resolves to cabin's, regardless of which location
  // that is. Skipped only once a location has a genuinely separate,
  // independently deployed backend of its own (apiBase !== cabin's),
  // since that backend can only ever hold its own location's data.
  const eventsLocationFilter = (apiBase === LOCATIONS.cabin.apiBase && locationCfg?.id)
    ? locationCfg.id : null;
  // Same fallback reasoning as apiBase just above -- used only to build the
  // "Open in Frigate" escape hatch below, never for a real API call.
  const frigateUrl = locationDeployed ? (locationCfg?.frigateUrl || LOCATIONS.cabin.frigateUrl) : LOCATIONS.cabin.frigateUrl;
  const [window_, setWindow] = useState(() => localStorage.getItem("cameraEvents.window") || "24h");
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [expandedEventId, setExpandedEventId] = useState(null);
  const [expandedMotionId, setExpandedMotionId] = useState(null);
  const [liveCamera, setLiveCamera] = useState(null);
  const [cameras, setCameras] = useState([]);
  const [cameraListError, setCameraListError] = useState(null);
  const [showMotion, setShowMotion] = useState(false);
  const { detections, motionEvents } = useMemo(() => groupCameraEvents(events), [events]);

  // Triggers/ends a real on-demand liveview session for Blink-backed
  // cameras (a no-op server-side for the Reolink, which is already
  // continuously live) whenever liveCamera changes -- see
  // CameraMediaController's startLiveview/stopLiveview and blinkbridge's
  // own liveview control API (added 2026-08-03). The effect's own
  // cleanup naturally covers every way this needs to stop: switching to
  // a different camera, clicking "Stop", or leaving this panel entirely.
  useEffect(() => {
    if (!liveCamera || !auth.accessToken) return;
    auth.authedFetch(`${apiBase}/api/camera/${liveCamera}/liveview/start`, { method: "POST" }).catch(() => {});
    return () => {
      auth.authedFetch(`${apiBase}/api/camera/${liveCamera}/liveview/stop`, { method: "POST" }).catch(() => {});
    };
  }, [liveCamera, apiBase, auth]);

  // Real server-side filtering (eventTypePrefix) as of 2026-08-07 -- see
  // EventController's own comment. This replaced the original client-side
  // isCameraEvent filter (still exported/tested for reuse elsewhere, but
  // no longer needed here since the server now only returns camera
  // events to begin with).
  const refresh = useCallback(() => {
    setLoading(true);
    fetch(buildCameraEventsUrl(apiBase, 0, window_, eventsLocationFilter))
      .then(r => r.json())
      .then(list => {
        setEvents(list);
        setHasMore(list.length === CAMERA_EVENTS_PAGE_SIZE);
      })
      .catch(() => { setEvents([]); setHasMore(false); })
      .finally(() => setLoading(false));
  }, [apiBase, window_, eventsLocationFilter]);

  useEffect(() => localStorage.setItem("cameraEvents.window", window_), [window_]);

  // "Load older" -- pages back further than the initial 30 instead of the
  // old hard cap. Appends rather than replacing (refresh() above still
  // owns the "get the current newest state" full-replace behavior, used
  // for the initial load and the periodic poll).
  const loadMore = useCallback(() => {
    setLoadingMore(true);
    fetch(buildCameraEventsUrl(apiBase, events.length, window_, eventsLocationFilter))
      .then(r => r.json())
      .then(list => {
        setEvents(prev => [...prev, ...list]);
        setHasMore(list.length === CAMERA_EVENTS_PAGE_SIZE);
      })
      .catch(() => setHasMore(false))
      .finally(() => setLoadingMore(false));
  }, [apiBase, events.length, window_, eventsLocationFilter]);

  // Real, current camera names from Frigate's own config — NOT derived
  // from event history. That was the original approach and broke the
  // moment cameras got renamed 2026-08-02: historical events keep their
  // old sourceDeviceId forever by design, but Frigate itself only
  // recognizes the new names, so "watch live" against an old name 404'd
  // (a real bug, found via live testing after the rename, not caught by
  // review beforehand).
  //
  // Found 2026-08-03 (external review): a failed/401 fetch here used to
  // just fall back to an empty camera list with zero explanation, which
  // is indistinguishable in the UI from "this host has no cameras
  // configured." Now surfaces a real error message, and goes through
  // auth.authedFetch so an expired token clears the session instead of
  // this request silently 401ing forever.
  const refreshCameraList = useCallback(() => {
    if (!auth.accessToken) return;
    setCameraListError(null);
    const listUrl = eventsLocationFilter
      ? `${apiBase}/api/camera/list?location=${eventsLocationFilter}`
      : `${apiBase}/api/camera/list`;
    auth.authedFetch(listUrl)
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(list => setCameras(list.filter(c => c.enabled).map(c => c.name)))
      .catch(err => {
        setCameras([]);
        // A 401 already triggers the "session expired" banner via
        // auth.sessionExpired — no need to duplicate that message here.
        if (!auth.sessionExpired) setCameraListError(err.message);
      });
  }, [apiBase, auth, eventsLocationFilter]);

  useEffect(() => {
    if (!auth.signedIn) return;
    refresh();
    refreshCameraList();
    const t = setInterval(refresh, 20000);
    return () => clearInterval(t);
  }, [auth.signedIn, refresh, refreshCameraList]);

  if (!auth.configured) {
    return (
      <div className="panel-content">
        <div className="panel-header-bar"><h2>Camera Events</h2></div>
        <p className="config-desc">Google Sign-In isn't configured on this host yet (VITE_CABIN_GOOGLE_CLIENT_ID unset at build time).</p>
      </div>
    );
  }

  if (!auth.signedIn) {
    return (
      <div className="panel-content">
        <div className="panel-header-bar"><h2>Camera Events</h2></div>
        {auth.sessionExpired ? (
          <p className="config-desc camera-live-error">Session expired — sign in again.</p>
        ) : (
          <p className="config-desc">Sign in to view camera activity.</p>
        )}
        <button className="btn-primary" onClick={auth.signIn}>Sign in with Google</button>
      </div>
    );
  }

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Camera Events</h2>
        <div className="toolbar-right">
          <label className="dm-toolbar-select">Show
            <select value={window_} onChange={e => setWindow(e.target.value)} aria-label="Camera events time range">
              {CAMERA_EVENTS_WINDOWS.map(w => <option key={w.value} value={w.value}>{w.label}</option>)}
            </select>
          </label>
          {auth.userEmail && <span className="config-desc">{auth.userEmail}</span>}
          <button className="btn-secondary" onClick={auth.signOut}>Sign out</button>
        </div>
      </div>

      {cameraListError && (
        <p className="config-desc camera-live-error">Couldn't load the camera list ({cameraListError}).</p>
      )}
      {cameras.length > 0 && (
        <div className="camera-live-section">
          <div className="camera-live-buttons">
            {cameras.map(cam => (
              <div key={cam} className="camera-live-row">
                <button
                  className={`btn-secondary${liveCamera === cam ? " active" : ""}`}
                  onClick={() => setLiveCamera(liveCamera === cam ? null : cam)}
                >
                  <Radio size={14} /> {liveCamera === cam ? `Stop ${cam}` : `Watch ${cam} live`}
                </button>
                <CameraNotifyToggle
                  cameraName={cam} apiBase={apiBase} authedFetch={auth.authedFetch}
                  workflows={workflows} onChanged={refreshWorkflows}
                />
              </div>
            ))}
          </div>
          {liveCamera && (
            <CameraLiveView apiBase={apiBase} accessToken={auth.accessToken} cameraName={liveCamera} />
          )}
        </div>
      )}

      {loading && events.length === 0 && <p className="config-desc">Loading…</p>}
      {!loading && events.length === 0 && <p className="config-desc">No camera activity in {cameraEventsWindowLabel(window_).toLowerCase()}.</p>}
      <div className="camera-events-list">
        {detections.map(e => {
          const frigateEventId = e.payload?.frigateEventId;
          const isExpanded = expandedEventId === e.eventId;
          const canExpand = !!frigateEventId && e.payload?.hasClip;
          return (
            <div key={e.eventId} className="camera-event-item">
              <div
                className={`camera-event-row${canExpand ? " clickable" : ""}`}
                onClick={() => canExpand && setExpandedEventId(isExpanded ? null : e.eventId)}
              >
                <CameraEventThumbnail apiBase={apiBase} authedFetch={auth.authedFetch} frigateEventId={e.payload?.hasSnapshot ? frigateEventId : null} timestamp={e.timestamp} />
                <div>
                  <div className="camera-event-title">
                    {e.sourceDeviceId} — {e.eventType.replace("DETECTION_", "").toLowerCase()}
                    {e.payload?.label ? ` (${e.payload.label}${e.payload.score ? `, ${Math.round(e.payload.score * 100)}%` : ""})` : ""}
                  </div>
                  <div className="camera-event-time">{new Date(e.timestamp).toLocaleString()}</div>
                </div>
              </div>
              {isExpanded && (
                <div className="camera-clip-expanded">
                  <CameraEventClip
                    authedFetch={auth.authedFetch}
                    clipUrl={`${apiBase}/api/camera/events/${frigateEventId}/clip`}
                    frigateUrl={frigateUrl}
                    cameraName={e.sourceDeviceId}
                  />
                </div>
              )}
            </div>
          );
        })}
        {!loading && detections.length === 0 && motionEvents.length > 0 && (
          <p className="config-desc">No detections in {cameraEventsWindowLabel(window_).toLowerCase()} — {motionEvents.length} motion-only event{motionEvents.length === 1 ? "" : "s"} below.</p>
        )}
      </div>

      {motionEvents.length > 0 && (
        <div className="camera-motion-section">
          <button className="btn-ghost camera-motion-toggle" onClick={() => setShowMotion(s => !s)}>
            {showMotion ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            {motionEvents.length} motion event{motionEvents.length === 1 ? "" : "s"} (no Frigate detection — tap to try the recording)
          </button>
          {showMotion && (
            <div className="camera-motion-list">
              {motionEvents.map(e => {
                const isMotionExpanded = expandedMotionId === e.eventId;
                return (
                  <div key={e.eventId} className="camera-motion-item">
                    <div
                      className="camera-motion-row clickable"
                      onClick={() => setExpandedMotionId(isMotionExpanded ? null : e.eventId)}
                    >
                      <span className="camera-motion-camera">{e.sourceDeviceId}</span>
                      <span className="camera-motion-state">{e.eventType === "MOTION_ON" ? "motion started" : "motion ended"}</span>
                      <span className="camera-event-time">{new Date(e.timestamp).toLocaleString()}</span>
                    </div>
                    {isMotionExpanded && (
                      <div className="camera-clip-expanded">
                        {/* No native Frigate event behind a motion-only row -- this asks
                            Frigate for whatever it continuously recorded around this
                            timestamp instead (record.enabled is true regardless of
                            detection, confirmed live 2026-08-18). Falls back to the
                            same "Open in Frigate" link CameraEventClip already renders
                            on a genuine miss (retention expired, camera was down). */}
                        <CameraEventClip
                          authedFetch={auth.authedFetch}
                          clipUrl={`${apiBase}/api/camera/events/${e.eventId}/clip-by-time`}
                          frigateUrl={frigateUrl}
                          cameraName={e.sourceDeviceId}
                        />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {hasMore && (
        <button className="btn-secondary camera-events-load-more" onClick={loadMore} disabled={loadingMore}>
          {loadingMore ? "Loading…" : "Load older events"}
        </button>
      )}
    </div>
  );
}

// ─── Panel: Opportunity Map ─────────────────────────────────────────────────
// See docs/PRODUCT_NOTES.md's 2026-08-03 "Opportunity Map: UX & Architecture
// Review" for the full design this implements. Findings (backend name:
// TechIdFinding) surface here as "Opportunities" — every user-facing label
// in this panel says Opportunity, never the technical name.
const FINDING_TYPE_LABELS = {
  new_api: "New API",
  deprecation: "Deprecation",
  better_integration: "Better Integration",
  competitive_product: "Competitive Product",
  complementary_device: "Complementary Device",
};
const DISMISS_REASONS = [
  { key: "not_interested", label: "Not interested" },
  { key: "already_have_it", label: "Already have it" },
  { key: "not_applicable", label: "Not applicable" },
];

// Resolves raw ontology entity ids (lineage on a finding) to display-safe
// labels via GET /api/ontology/entities — never render a snake_case id
// directly, per docs/PRODUCT_NOTES.md's Name Management Decision.
function useOntologyLabels(apiBase, ids) {
  const [labels, setLabels] = useState({});
  const key = ids.join(",");
  useEffect(() => {
    if (!key) return;
    fetch(`${apiBase}/api/ontology/entities?ids=${encodeURIComponent(key)}`)
      .then(r => r.ok ? r.json() : [])
      .then(list => {
        setLabels(prev => {
          const next = { ...prev };
          for (const e of list) next[e.id] = e.uiDisplayName || e.id;
          return next;
        });
      })
      .catch(() => {});
  }, [apiBase, key]);
  return labels;
}

function OpportunityCard({ apiBase, auth, opportunity, entityLabels, onChanged }) {
  const [expanded, setExpanded] = useState(false);
  const [choosingReason, setChoosingReason] = useState(false);
  const lineageIds = [opportunity.entityId, ...(opportunity.relatedEntityIds || [])].filter(Boolean);

  // Routed through auth.authedFetch (not a raw fetch + manual header) so
  // an expired token is handled the same way everywhere in the app: the
  // session clears and auth.sessionExpired flips, instead of this POST
  // just silently 401ing with no visible effect.
  const logAction = (actionType, detail) => {
    if (!auth.accessToken) return Promise.resolve();
    return auth.authedFetch(`${apiBase}/api/tech-id/findings/${opportunity.id}/actions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actionType, detail: detail || null }),
    }).catch(() => {});
  };

  const setStatus = (status) => {
    if (!auth.accessToken) return Promise.resolve();
    return auth.authedFetch(`${apiBase}/api/tech-id/findings/${opportunity.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }).catch(() => {});
  };

  const toggleExpand = () => {
    const next = !expanded;
    setExpanded(next);
    if (next) logAction("see_expand", null);
  };

  const include = () => { setStatus("reviewed").then(onChanged); logAction("think_include", null); };
  const dismissWithReason = (reason) => {
    setChoosingReason(false);
    setStatus("dismissed").then(onChanged);
    logAction("think_dismiss", reason);
  };

  const buyElsewhere = () => {
    const url = opportunity.actionable?.url || opportunity.sources?.[0];
    logAction("act_purchase_elsewhere", url || null);
    if (url) window.open(url, "_blank", "noopener,noreferrer");
  };
  const requestCore = () => logAction("act_request_core", opportunity.summary);
  const doItNow = () => {
    logAction("act_do_it_now", opportunity.actionable?.detail || null);
    if (opportunity.actionable?.url) window.open(opportunity.actionable.url, "_blank", "noopener,noreferrer");
  };

  const canSelfServe = opportunity.actionable?.mode === "self_serve";
  const dimmed = opportunity.status === "dismissed";

  return (
    <div className={`opportunity-card${dimmed ? " opportunity-dismissed" : ""}`}>
      <div className="opportunity-header">
        <span className="opportunity-type-badge">{FINDING_TYPE_LABELS[opportunity.findingType] || opportunity.findingType}</span>
        <span className={`opportunity-confidence opportunity-confidence-${opportunity.confidence}`}>{opportunity.confidence} confidence</span>
        {opportunity.status !== "new" && <span className="opportunity-status">{opportunity.status}</span>}
      </div>
      <p className="opportunity-summary">{opportunity.summary}</p>
      {lineageIds.length > 0 && (
        <div className="opportunity-lineage">
          {lineageIds.map(id => (
            <span key={id} className="opportunity-lineage-chip">because you have: {entityLabels[id] || id}</span>
          ))}
        </div>
      )}

      <button className="opportunity-see-more" onClick={toggleExpand}>
        {expanded ? "See less" : "See more"}
      </button>
      {expanded && (
        <div className="opportunity-detail">
          {opportunity.sources?.length > 0 && (
            <ul className="opportunity-sources">
              {opportunity.sources.map((s, i) => (
                <li key={i}><a href={s} target="_blank" rel="noopener noreferrer">{s}</a></li>
              ))}
            </ul>
          )}
          <div className="config-desc">Checked {new Date(opportunity.checkedAt).toLocaleDateString()} · via {opportunity.provider}</div>
        </div>
      )}

      {!auth.signedIn ? (
        <p className="config-desc">
          {auth.sessionExpired ? "Session expired — sign in again to " : "Sign in to "}
          think through or act on this opportunity.
        </p>
      ) : (
        <>
          <div className="opportunity-think-row">
            {!choosingReason ? (
              <>
                <button className="btn-secondary" onClick={include}><ThumbsUp size={14} /> Worth exploring</button>
                <button className="btn-secondary" onClick={() => setChoosingReason(true)}><ThumbsDown size={14} /> Not for us</button>
              </>
            ) : (
              DISMISS_REASONS.map(r => (
                <button key={r.key} className="btn-secondary" onClick={() => dismissWithReason(r.key)}>{r.label}</button>
              ))
            )}
          </div>
          <div className="opportunity-act-row">
            <button className="btn-secondary" onClick={buyElsewhere}><ShoppingCart size={14} /> Buy it elsewhere ↗</button>
            <button className="btn-secondary" onClick={requestCore}><Send size={14} /> Request this for the platform</button>
            {canSelfServe && (
              <button className="btn-primary" onClick={doItNow}><Wrench size={14} /> Do it now</button>
            )}
          </div>
          {canSelfServe && <p className="config-desc opportunity-self-serve-detail">{opportunity.actionable.detail}</p>}
        </>
      )}
    </div>
  );
}

function OpportunityMapPanel({ auth }) {
  const { locationCfg } = useApp();
  const apiBase = locationCfg?.apiBase || LOCATIONS.cabin.apiBase;
  const [opportunities, setOpportunities] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showDismissed, setShowDismissed] = useState(false);

  const refresh = useCallback(() => {
    setLoading(true);
    fetch(`${apiBase}/api/tech-id/findings?limit=50`)
      .then(r => r.json()).then(setOpportunities).catch(() => setOpportunities([]))
      .finally(() => setLoading(false));
  }, [apiBase]);

  useEffect(() => { refresh(); }, [refresh]);

  const allEntityIds = useMemo(() => {
    const ids = new Set();
    for (const o of opportunities) {
      if (o.entityId) ids.add(o.entityId);
      for (const id of (o.relatedEntityIds || [])) ids.add(id);
    }
    return [...ids];
  }, [opportunities]);
  const entityLabels = useOntologyLabels(apiBase, allEntityIds);

  const visible = showDismissed ? opportunities : opportunities.filter(o => o.status !== "dismissed");

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Opportunities</h2>
        <div className="toolbar-right">
          {!auth.signedIn && auth.configured && (
            <button className="btn-secondary" onClick={auth.signIn}>Sign in to think/act</button>
          )}
          <label className="config-desc opportunity-show-dismissed">
            <input type="checkbox" checked={showDismissed} onChange={e => setShowDismissed(e.target.checked)} /> Show dismissed
          </label>
        </div>
      </div>
      <p className="config-desc">
        Findings from the platform's monthly Tech ID Service scan — new capabilities,
        deprecations, or better ways to use devices and services you already have.
      </p>
      {loading && visible.length === 0 && <p className="config-desc">Loading…</p>}
      {!loading && visible.length === 0 && <p className="config-desc">No opportunities yet — the next monthly scan will surface anything it finds here.</p>}
      <div className="opportunity-list">
        {visible.map(o => (
          <OpportunityCard key={o.id} apiBase={apiBase} auth={auth} opportunity={o} entityLabels={entityLabels} onChanged={refresh} />
        ))}
      </div>
    </div>
  );
}

const PANELS = [
  { id: "FAMILY_HUB",     label: "My Places",       icon: Home },
  { id: "FAMILY_CONFIG",  label: "Config",   icon: Settings },
  { id: "DEVICE_MANAGER", label: "Devices",         icon: Cpu },
  { id: "MONITORING",     label: "Monitoring",      icon: Activity },
  { id: "RULES_ENGINE",   label: "Rules & Alerts",  icon: Zap },
  { id: "CAMERA_EVENTS",  label: "Camera Events",   icon: Camera },
  { id: "OPPORTUNITY_MAP", label: "Opportunities",  icon: Lightbulb },
];

// ─── Context ───────────────────────────────────────────────────────────────
export const AppContext = createContext(null); // exported for src/App.test.jsx's FamilyHubPanel render test
function useApp() { return useContext(AppContext); }

// ─── Location switcher component ───────────────────────────────────────────
// Exported for src/App.test.jsx. "Both" only reads correctly for exactly
// two locations -- found 2026-08-08 (user, ahead of adding a third
// location): the internal value driving the "show every location"
// toggle stays "both" everywhere (existing localStorage order keys,
// refreshDevices' branching, etc. all key off that literal string, and
// changing it would orphan them for no benefit) but the LABEL shown to
// the user must read correctly regardless of count.
export function allLocationsLabel(locationCount) {
  return locationCount <= 2 ? "Both" : "All";
}

function LocationSwitcher({ active, onChange }) {
  // Object.keys(LOCATIONS) instead of a hardcoded ["cabin","home"] so a
  // location added via POST /api/locations (see useHubLocations below)
  // shows up here without a source change.
  const locationIds = Object.keys(LOCATIONS);
  const options = [...locationIds, "both"];
  return (
    <div className="location-switcher">
      {options.map(loc => (
        <button
          key={loc}
          className={`loc-btn ${active === loc ? "loc-active" : ""}`}
          onClick={() => onChange(loc)}
        >
          {loc === "both" ? allLocationsLabel(locationIds.length) : (LOCATIONS[loc]?.label || loc)}
        </button>
      ))}
    </div>
  );
}

// ─── Helpers ───────────────────────────────────────────────────────────────
function stateColor(state) {
  if (!state) return "state-unknown";
  const s = state.toUpperCase();
  if (s === "ONLINE" || s === "LOCKED" || s === "ON") return "state-ok";
  if (s === "ALARM" || s === "CRITICAL") return "state-alarm";
  if (s === "OFFLINE" || s === "DOWN") return "state-offline";
  return "state-unknown";
}

// "OFFLINE" the instant a device misses one poll interval was misleading
// users (2026-08-08 report) — it doesn't distinguish "hasn't checked in
// yet" from "confirmed unreachable." checkinStatus (from
// GET /api/devices/checkin-status, backed by DeviceHealthMonitor's
// ON_SCHEDULE/LATE/MISSED/NOT_CONFIGURED tiering) is the more honest
// label; this only overrides the badge for the ambiguous cases and never
// touches an ALARM/CRITICAL device, which must always read as itself.
export function checkinStatusLabel(state, checkinStatus) {
  const s = (state || "").toUpperCase();
  if (s === "ALARM" || s === "CRITICAL") return null;
  switch (checkinStatus) {
    case "NOT_CONFIGURED": return { text: "Not configured", cls: "state-not-configured" };
    case "LATE":            return { text: "Late checking in", cls: "state-late" };
    case "MISSED":           return { text: "Not responding", cls: "state-offline" };
    default: return null; // ON_SCHEDULE, or no data yet — show the raw state
  }
}

function useCheckinStatuses(apiBase) {
  const [statuses, setStatuses] = useState({});
  useEffect(() => {
    let cancelled = false;
    fetch(`${apiBase}/api/devices/checkin-status`)
      .then(r => r.json())
      .then(data => { if (!cancelled) setStatuses(data || {}); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [apiBase]);
  return statuses;
}

function useCheckinDetails(apiBase) {
  const [details, setDetails] = useState({});
  useEffect(() => {
    let cancelled = false;
    fetch(`${apiBase}/api/devices/checkin-details`)
      .then(r => r.json())
      .then(data => { if (!cancelled) setDetails(data || {}); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [apiBase]);
  return details;
}

function deviceIcon(type) {
  const map = {
    CAMERA: Camera, WATER_PRESSURE_SENSOR: Droplets, TEMPERATURE_SENSOR: Thermometer,
    THERMOSTAT: Thermometer, SMOKE_ALARM: ShieldAlert, CO_ALARM: ShieldAlert,
    LOCK: Lock, MOTION_SENSOR: Activity, HOME_ASSISTANT_ENTITY: Wifi,
    DISHWASHER: Cpu, WASHING_MACHINE: Cpu, DRYER: Cpu, POWER_METER: Zap,
    DASHBOARD: Home, CO2_SENSOR: Wind, AIR_QUALITY_SENSOR: Wind, CO_SENSOR: Wind,
  };
  return map[type] || Circle;
}

// ─── Live WebSocket telemetry hook ─────────────────────────────────────────
// wsBase: the Mosquitto WebSocket URL for the target hub, or null to disconnect.
function useMqttTelemetry(active, wsBase) {
  const [messages, setMessages] = useState([]);
  const ws = useRef(null);

  useEffect(() => {
    if (!active || !wsBase) { ws.current?.close(); return; }
    try {
      ws.current = new WebSocket(wsBase);
      ws.current.onopen = () => console.log("MQTT WS connected →", wsBase);
      ws.current.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          setMessages(prev => [{ ts: Date.now(), ...data }, ...prev].slice(0, 50));
        } catch {}
      };
      ws.current.onerror = () => {};
    } catch {}
    return () => ws.current?.close();
  }, [active, wsBase]);

  return messages;
}

// ─── Panel: Family Hub ─────────────────────────────────────────────────────
function FamilyHubLocationCard({ locId, locCfg, devices }) {
  const locDevices = devices.filter(d => !d.location || d.location === locId);
  const online  = locDevices.filter(d => d.state === "ONLINE").length;
  const alarm   = locDevices.filter(d => d.state === "ALARM").length;
  const offline = locDevices.filter(d => d.state === "OFFLINE").length;
  const total   = locDevices.length;
  const [tempUnit, toggleTempUnit] = useTempUnit();
  const tempSensors = locDevices.filter(d => d.type === "TEMPERATURE_SENSOR");

  const deployed = !!locCfg.apiBase;
  const stateColor = alarm > 0 ? "var(--state-alarm)" : online === total && total > 0 ? "var(--state-online)" : "var(--state-warning)";

  const quickLinks = [
    { label: "Home Assistant", url: locCfg.haUrl,      icon: Home },
    { label: "Zigbee2MQTT",   url: locCfg.z2mUrl,     icon: Radio },
    { label: "Grafana",       url: locCfg.grafanaUrl,  icon: BarChart2 },
    { label: "Node-RED",      url: locCfg.noderedUrl,  icon: Cpu },
  ];
  // Carry this app's active theme into Family Hub on link-out (same
  // ?theme= handoff ThemeProvider reads on load) -- the two apps live on
  // different subdomains so localStorage can't do this by itself.
  const { themeId } = useTheme();
  const familyHubUrl = locCfg.familyHubUrl
    ? `${locCfg.familyHubUrl}${locCfg.familyHubUrl.includes("?") ? "&" : "?"}theme=${themeId}`
    : locCfg.familyHubUrl;

  return (
    <div className="family-hub-card">
      <div className="family-hub-card-header">
        <span className="family-hub-location-label">{locCfg.label}</span>
        {deployed && (
          <span className="family-hub-state-dot" style={{ background: stateColor }} />
        )}
      </div>

      {!deployed ? (
        <div className="family-hub-placeholder">
          <Home size={28} opacity={0.25} />
          <p>Hub not yet deployed</p>
        </div>
      ) : (
        <>
          <div className="family-hub-device-summary">
            <span className="fh-stat fh-online">{online} online</span>
            {alarm   > 0 && <span className="fh-stat fh-alarm">{alarm} alarm</span>}
            {offline > 0 && <span className="fh-stat fh-offline">{offline} offline</span>}
            <span className="fh-stat fh-total">{total} total</span>
          </div>

          {tempSensors.length > 0 && (
            <div className="family-hub-temps">
              {tempSensors.map(s => (
                <div key={s.deviceId} className="fh-temp-row">
                  <Thermometer size={13} />
                  <span className="fh-temp-name">{s.name}</span>
                  <span className="fh-temp-val">
                    {fmtTemp(s.attributes?.temperature, tempUnit)}
                    {s.attributes?.humidity != null && ` · ${s.attributes.humidity}%`}
                  </span>
                </div>
              ))}
              <button className="btn-ghost btn-ghost-xs" onClick={toggleTempUnit}>°{tempUnit}</button>
            </div>
          )}

          {familyHubUrl && (
            <a href={familyHubUrl} target="_blank" rel="noreferrer" className="fh-launch-btn">
              Open Family Hub ↗
            </a>
          )}

          <div className="family-hub-links">
            {quickLinks.map(({ label, url, icon: Icon }) => (
              <a key={label} href={url} target="_blank" rel="noreferrer" className="fh-quick-link">
                <Icon size={12} /> {label} ↗
              </a>
            ))}
          </div>
          <div className="tailscale-hint">
            <Lock size={11} /> These are cabin-network admin tools — link only opens if you're on Tailscale.
          </div>
        </>
      )}
    </div>
  );
}

// Exported for src/App.test.jsx. Only id/label are required -- the
// connection URLs (apiBase/wsBase/grafanaUrl/noderedUrl/haUrl/
// frigateUrl/z2mUrl/familyHubUrl) are genuinely optional at creation
// time, since a brand-new physical location's stack usually doesn't
// exist yet when the place is first added (see PATCH /api/locations/
// {id} for filling them in later, same endpoint the live hub_locations
// URL fix used 2026-08-08).
const ADD_PLACE_FIELDS = [
  { key: "id",           label: "ID (slug)",       placeholder: "lakehouse", required: true },
  { key: "label",        label: "Display Name",    placeholder: "Lake House", required: true },
  { key: "apiBase",      label: "API Base URL",      placeholder: "https://api.example.com (fill in later if unknown)" },
  { key: "wsBase",       label: "WebSocket Base",    placeholder: "ws://... (fill in later if unknown)" },
  { key: "grafanaUrl",   label: "Grafana URL",       placeholder: "(fill in later if unknown)" },
  { key: "noderedUrl",   label: "Node-RED URL",      placeholder: "(fill in later if unknown)" },
  { key: "haUrl",        label: "Home Assistant URL", placeholder: "(fill in later if unknown)" },
  { key: "frigateUrl",   label: "Frigate URL",       placeholder: "(fill in later if unknown)" },
  { key: "z2mUrl",       label: "Zigbee2MQTT URL",   placeholder: "(fill in later if unknown)" },
  { key: "familyHubUrl", label: "Family Hub URL",    placeholder: "(fill in later if unknown)" },
];

function AddPlaceForm({ onCreated, onCancel }) {
  const [form, setForm] = useState(() => Object.fromEntries(ADD_PLACE_FIELDS.map(f => [f.key, ""])));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const update = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    if (!form.id.trim() || !form.label.trim()) {
      setError("ID and Display Name are required.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const body = {};
      for (const [k, v] of Object.entries(form)) {
        if (v.trim()) body[k] = v.trim();
      }
      const res = await fetch(`${LOCATIONS.cabin.apiBase}/api/locations`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(text || `HTTP ${res.status}`);
      }
      onCreated();
    } catch (err) {
      setError(err.message || "Failed to create location.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="add-place-form" onSubmit={submit}>
      <div className="add-place-grid">
        {ADD_PLACE_FIELDS.map(f => (
          <label key={f.key} className="add-place-field">
            {f.label}{f.required && " *"}
            <input value={form[f.key]} onChange={update(f.key)} placeholder={f.placeholder} />
          </label>
        ))}
      </div>
      {error && <p className="add-place-error">{error}</p>}
      <div className="add-place-actions">
        <button type="submit" className="btn-primary" disabled={saving}>
          {saving ? "Creating…" : "Create Place"}
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

// Place-card order (Phase 7 §3): same client-side, per-browser
// localStorage pattern as Device Manager/Monitoring's own drag-reorder
// (useDraggableOrder, reused as-is) -- matches the user's own request
// ("the same way we allow re-ordering of devices"), not the
// server-persisted /api/locations/reorder endpoint hub_location's backend
// also exposes (built in §1b, still unused by any UI -- a server-synced
// order is a deliberate, separate future option, not what this
// implements).
export function FamilyHubPanel() { // exported for src/App.test.jsx's reorder test
  const { devices } = useApp();
  const [reorderMode, setReorderMode] = useState(false);
  const [adding, setAdding] = useState(false);
  const [dragIdx, setDragIdx] = useState(null);
  const [overIdx, setOverIdx] = useState(null);
  const { ordered, reorder } = useDraggableOrder("order.places", Object.values(LOCATIONS));

  const onDragStart = (idx) => (e) => { setDragIdx(idx); e.dataTransfer.effectAllowed = "move"; };
  const onDragOver  = (idx) => (e) => { e.preventDefault(); setOverIdx(idx); };
  const onDrop      = (idx) => (e) => {
    e.preventDefault();
    if (dragIdx !== null) reorder(dragIdx, idx);
    setDragIdx(null); setOverIdx(null);
  };
  const onDragEnd   = () => { setDragIdx(null); setOverIdx(null); };

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <div className="panel-header-title">
          <h2>My Places</h2>
          <span className="panel-subtitle">Both locations at a glance</span>
        </div>
        <div className="header-actions">
          <button
            className={`btn-ghost ${adding ? "btn-ghost-active" : ""}`}
            onClick={() => { setAdding(a => !a); setReorderMode(false); }}>
            <UserPlus size={14}/> {adding ? "Cancel" : "Add Place"}
          </button>
          <button
            className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
            onClick={() => { setReorderMode(r => !r); setAdding(false); }}>
            <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
          </button>
        </div>
      </div>
      {/* Found 2026-08-08 (user report, ahead of adding a real second
          location): the backend's full create/update/reorder/archive
          CRUD for hub_location (Phase 7 §1b) has existed since that
          work landed, but nothing in the frontend ever called
          POST /api/locations -- there was no way to add a place through
          the UI at all. AddPlaceForm below is that missing piece. */}
      {adding && (
        <AddPlaceForm
          onCreated={() => { setAdding(false); window.location.reload(); }}
          onCancel={() => setAdding(false)}
        />
      )}
      <div className={`family-hub-grid ${reorderMode ? "reorder-mode" : ""}`}>
        {ordered.map((cfg, idx) => {
          const isOver = reorderMode && overIdx === idx && dragIdx !== idx;
          return (
            <div key={cfg.id}
              className={`family-hub-card-wrap reorder-card ${isOver ? "drag-over-card" : ""}`}
              draggable={reorderMode}
              onDragStart={reorderMode ? onDragStart(idx) : undefined}
              onDragOver={reorderMode ? onDragOver(idx) : undefined}
              onDrop={reorderMode ? onDrop(idx) : undefined}
              onDragEnd={reorderMode ? onDragEnd : undefined}
            >
              {reorderMode && <GripVertical size={14} className="drag-handle family-hub-drag-handle" />}
              <FamilyHubLocationCard locId={cfg.id} locCfg={cfg} devices={devices} />
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── Panel: Config ──────────────────────────────────────────────────────
// Exported for src/App.test.jsx's rename/dynamic-config-fields test.
export function FamilyConfigPanel({ auth }) {
  const { config, locationCfg } = useApp();
  const haUrl = locationCfg?.haUrl || LOCATIONS.cabin.haUrl;
  const remoteAccessMethods = (config?.remoteAccess || "Tailscale")
    .split(",").map(s => s.trim()).filter(Boolean);
  return (
    <div className="panel-content">
      <div className="panel-header-bar"><h2>Configuration</h2></div>
      <div className="config-grid">
        <ConfigCard title="Google Account" icon={Home}>
          {auth?.userEmail ? (
            <>
              <p className="config-desc">Signed in as {auth.userEmail}.</p>
              <button className="btn-secondary" onClick={auth.signIn}>Switch Google Account</button>
            </>
          ) : (
            <>
              <p className="config-desc">Not signed in — Camera Events and Opportunities require Google sign-in.</p>
              <button className="btn-secondary" onClick={auth?.signIn}>Sign in with Google</button>
            </>
          )}
          <p className="config-hint">
            Switching only grants entry if the account is already in this instance's OAuth
            allowlist (ADMIN_EMAILS) — see ROADMAP.md's "Template Configuration Fields" for how
            to add accounts when cloning this app. Signing in as an account that isn't currently
            signed into Google in this browser is tracked as a roadmap item, not yet supported here.
          </p>
          <a href={`${haUrl}/config/integrations`} target="_blank" rel="noreferrer" className="btn-secondary">
            Manage Home Assistant's Google integration ↗
          </a>
          <div className="tailscale-hint">
            <Lock size={11} /> Won't load off Tailscale — Home Assistant admin is cabin-network-only.
          </div>
        </ConfigCard>
        <ConfigCard title="Notification Preferences" icon={AlertTriangle}>
          <p className="config-desc">Backend CRITICAL events use the configured notification channel.</p>
          <p className="config-hint">
            The backend channel is deploy-time configuration. Home Assistant and Node-RED own their
            own automation delivery paths; review each source in Rules &amp; Alerts rather than assuming
            one switch configures all three.
          </p>
        </ConfigCard>
        <ConfigCard title="Remote Access" icon={Wifi}>
          <p className="config-desc">{remoteAccessMethods.join(", ")}</p>
          <p className="config-hint">
            New template clones default to Tailscale — set CABIN_INSTANCE_REMOTE_ACCESS
            (comma-separated) once a method is configured, and it appears here.
          </p>
          <code className="code-block">tailscale up --advertise-routes=192.168.1.0/24 --hostname=home-hub</code>
        </ConfigCard>
        <ConfigCard title="Platform" icon={Cpu}>
          <p className="config-desc">{config?.platformName || "Orchestration Platform"}</p>
          <p className="config-hint">{config?.platform || "Not configured — set CABIN_INSTANCE_PLATFORM"}</p>
        </ConfigCard>
      </div>
    </div>
  );
}

function ConfigCard({ title, icon: Icon, children }) {
  return (
    <div className="config-card">
      <div className="config-card-header"><Icon size={18} /><strong>{title}</strong></div>
      {children}
    </div>
  );
}

// ─── Alert controls (rendered at top of each alertable panel) ─────────────
function AlertControls({ panelId }) {
  const {
    activeAlerts = [], activeAlertLocations = [], activeAlertUnavailableLocations = [],
    activeLocation = "cabin",
  } = useApp();
  const locationAvailable = activeLocation === "both"
    ? activeAlertLocations.length > 0
    : activeAlertLocations.includes(activeLocation);

  if (!locationAvailable) {
    return (
      <div className="alert-ctrl alert-ctrl-unconfigured">
        <Circle size={12} className="alert-ctrl-dot"/>
        <span>Current alert status unavailable — no badge is inferred for this location.</span>
      </div>
    );
  }

  const visibleAlerts = activeLocation === "both"
    ? activeAlerts
    : activeAlerts.filter(alert => alert.location === activeLocation);
  const level = alertLevelFor(visibleAlerts);
  const isCritical = level === "critical";
  const isWarn = level === "warn";
  const label = visibleAlerts.length === 1 ? "condition" : "conditions";
  const partialLocations = activeLocation === "both" ? activeAlertUnavailableLocations : [];
  const partialSuffix = partialLocations.length > 0
    ? ` Status unavailable for ${partialLocations.map(id => LOCATIONS[id]?.label || id).join(", ")}.`
    : "";

  return (
    <div className={`alert-ctrl ${isCritical ? "alert-ctrl-critical" : isWarn ? "alert-ctrl-warn" : "alert-ctrl-ok"}`}>
      {isCritical && <AlertTriangle size={12} className="alert-ctrl-dot"/>}
      {isWarn     && <AlertTriangle size={12} className="alert-ctrl-dot"/>}
      {!isCritical && !isWarn && <CheckCircle size={12} className="alert-ctrl-dot"/>}
      <span>
        {isCritical && `Critical — ${visibleAlerts.length} current ${label}.${partialSuffix}`}
        {isWarn && `Attention — ${visibleAlerts.length} current ${label}.${partialSuffix}`}
        {!isCritical && !isWarn && `Watching assigned, enabled devices — no current alert conditions from reporting locations.${partialSuffix}`}
      </span>
    </div>
  );
}

// ─── Panel: Device Manager ─────────────────────────────────────────────────
// L1 nav: See | Change | Add | Remove
// L2: device list filtered by action
// L3: device detail / edit / pairing flow / confirm remove

const DM_VIEWS = [
  { id: "see",    label: "See",    icon: Eye },
  { id: "change", label: "Change", icon: Edit2 },
  { id: "add",    label: "Add",    icon: UserPlus },
  { id: "remove", label: "Remove", icon: Minus },
];

export function DeviceManagerPanel() {
  const { devices, refreshDevices, activeLocation, workflows, setActivePanel } = useApp();
  const [view, setView]             = useState("see");
  const [selected, setSelected]     = useState(null);
  const [reorderMode, setReorderMode] = useState(false);
  const [groupBy, setGroupBy] = useState(() => localStorage.getItem("devices.groupBy") || "type");
  const [groupFlow, setGroupFlow] = useState(() => localStorage.getItem("devices.groupFlow") || "horizontal");
  const [deviceFilter, setDeviceFilter] = useState(() => {
    const saved = localStorage.getItem("devices.filter") || "in_scope";
    // "configured" was renamed to "in_scope"; "all" was folded into
    // "in_scope"'s own default meaning (2026-08-25) and is no longer a
    // separate selectable option -- both migrate the same way.
    return (saved === "configured" || saved === "all") ? "in_scope" : saved;
  });
  const [candidateDevices, setCandidateDevices] = useState([]);
  const [previouslyExposed, setPreviouslyExposed] = useState([]);
  const [reviewingPrevious, setReviewingPrevious] = useState(false);
  // { device, mode: "new"|"replace" } while the self-discovery overlay is
  // open, null otherwise. Held here (not in DmSeeView/DmChangeView) so the
  // overlay renders once, above whichever L2 view is active.
  const [discoveryTarget, setDiscoveryTarget] = useState(null);

  useEffect(() => localStorage.setItem("devices.groupBy", groupBy), [groupBy]);
  useEffect(() => localStorage.setItem("devices.groupFlow", groupFlow), [groupFlow]);
  useEffect(() => localStorage.setItem("devices.filter", deviceFilter), [deviceFilter]);

  const reviewLocations = useMemo(() => activeLocation === "both"
    ? [LOCATIONS.cabin, LOCATIONS.home]
    : [LOCATIONS[activeLocation] || LOCATIONS.cabin], [activeLocation]);

  const fetchDeviceReviewList = useCallback(async (path) => {
    const results = await Promise.allSettled(reviewLocations.map(location =>
      fetch(`${location.apiBase}/api/devices/${path}`)
        .then(response => { if (!response.ok) throw new Error(`HTTP ${response.status}`); return response.json(); })
    ));
    return results.filter(result => result.status === "fulfilled").flatMap(result => result.value);
  }, [reviewLocations]);

  const refreshReviewDevices = useCallback(async () => {
    setCandidateDevices(await fetchDeviceReviewList("candidates"));
    if (reviewingPrevious) {
      setPreviouslyExposed(await fetchDeviceReviewList("previously-exposed"));
    }
  }, [fetchDeviceReviewList, reviewingPrevious]);

  useEffect(() => {
    refreshReviewDevices();
    const timer = setInterval(refreshReviewDevices, 15000);
    return () => clearInterval(timer);
  }, [refreshReviewDevices]);

  useEffect(() => {
    if (deviceFilter !== "previous" || reviewingPrevious) return;
    setReviewingPrevious(true);
  }, [deviceFilter, reviewingPrevious]);

  const managerDevices = useMemo(() => {
    const byId = new Map();
    [...devices, ...candidateDevices, ...(reviewingPrevious ? previouslyExposed : [])]
      .forEach(device => byId.set(device.deviceId, device));
    return [...byId.values()];
  }, [devices, candidateDevices, previouslyExposed, reviewingPrevious]);

  // Found 2026-08-08 (user report): every DmXView below was rendering the
  // FULL, unfiltered devices array regardless of which location tab was
  // active -- LocationSwitcher changed activeLocation, but nothing here
  // ever consumed it to actually filter the list. Selecting "Home" still
  // showed Cabin's devices. Filtered once, here, so all three sub-views
  // (See/Change/Remove) share one correct source instead of each needing
  // its own filter (Add doesn't need one -- it creates, not lists).
  const locDevices = activeLocation === "both"
    ? managerDevices
    : managerDevices.filter(d => !d.location || d.location === activeLocation);
  const effectiveDeviceFilter = resolveDeviceManagerFilter(groupBy, deviceFilter);

  // Hoisted up from DmSeeView (was local there) so See and Change render
  // the exact same saved grouping/order -- Change is a read-only consumer
  // (no reorderGroup/reorderDevice passed to it), See keeps the only
  // drag UI. One computed source instead of two independently-ordered
  // lists that could drift apart.
  const isAlarm = useCallback((d) => d.state === "ALARM" || d.state === "CRITICAL", []);
  const { groups, reorderGroup, reorderDevice } = useGroupedDraggableOrder(
    `order.deviceGroups.${activeLocation}.${groupBy}`,
    `order.devices.${activeLocation}.${groupBy}`,
    `order.devices.${activeLocation}`,
    locDevices, groupBy, isAlarm
  );

  const refreshManagerDevices = useCallback(() => {
    refreshDevices();
    refreshReviewDevices();
  }, [refreshDevices, refreshReviewDevices]);

  const applyLifecycleAction = useCallback(async (device, action) => {
    const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
    const response = await fetch(`${apiBase}/api/devices/${device.deviceId}/lifecycle`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.error) throw new Error(body.message || body.error || `HTTP ${response.status}`);
    refreshManagerDevices();
    return body;
  }, [refreshManagerDevices]);

  // 2026-08-25 (user report): switching L1 tabs used to always drop the
  // selected device -- a person looking at a device in See had to
  // remember it, find it again in Change, and only then could edit it.
  // Only "Add" genuinely has no device context to carry; See/Change/
  // Remove now keep whatever was selected (each view's own `devices.find`
  // already degrades gracefully to "nothing selected" if that device
  // isn't in the target view's filtered list).
  const handleViewChange = (v) => {
    setView(v);
    if (v === "add") setSelected(null);
    setReorderMode(false);
  };

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Device Manager</h2>
        <div className="header-actions">
          {(view === "see" || view === "change") && (
            <>
              <label className="dm-toolbar-select">Group
                <select value={groupBy} onChange={e => setGroupBy(e.target.value)}>
                  <option value="none">None</option><option value="type">Type</option>
                  <option value="source">Source</option><option value="room">Room</option>
                  <option value="state">Status</option><option value="candidate">Lifecycle</option>
                  <option value="workflow">Workflow</option>
                </select>
              </label>
              <label className="dm-toolbar-select">Show
                <select value={effectiveDeviceFilter} onChange={e => setDeviceFilter(e.target.value)}
                  disabled={groupBy === "candidate"}
                  title={groupBy === "candidate" ? "Candidate grouping always shows both setup states" : undefined}>
                  <option value="in_scope">All In-Scope + Candidates</option>
                  <option value="candidates">Candidates</option>
                  <option value="previous">Review previously exposed</option>
                </select>
              </label>
              <button className="btn-ghost" onClick={() => { setGroupBy("type"); setDeviceFilter("in_scope"); }}
                title="Return Group and Show to their defaults">
                Reset Filters
              </button>
              {view === "see" && (
                <>
                  <button className="btn-ghost" onClick={() => setGroupFlow(f => f === "horizontal" ? "vertical" : "horizontal")}
                    title="Choose whether groups flow across the screen or stack downward">
                    {groupFlow === "horizontal" ? "Groups ↔" : "Groups ↕"}
                  </button>
                  <button
                    className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
                    onClick={() => setReorderMode(r => !r)}>
                    <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
                  </button>
                </>
              )}
            </>
          )}
          <button className="btn-ghost" onClick={refreshManagerDevices}><RefreshCw size={14}/> Refresh</button>
        </div>
      </div>

      <AlertControls panelId="DEVICE_MANAGER" />

      <div className="dm-l1-nav">
        {DM_VIEWS.map(v => {
          const Icon = v.icon;
          return (
            <button key={v.id}
              className={`dm-l1-btn ${view === v.id ? "dm-l1-active" : ""}`}
              onClick={() => handleViewChange(v.id)}>
              <Icon size={15}/> {v.label}
            </button>
          );
        })}
        <a href={LOCATIONS.cabin.z2mUrl}
           className="dm-advanced-link" target="_blank" rel="noreferrer"
           title="Requires Tailscale — Zigbee2MQTT admin is cabin-network-only">
          Advanced (Z2M) <ExternalLink size={11}/>
        </a>
      </div>

      {view === "see"    && <DmSeeView key={`${activeLocation}:${groupBy}`} groups={groups} reorderGroup={reorderGroup} reorderDevice={reorderDevice}
        selected={selected} onSelect={setSelected}
        reorderMode={reorderMode} groupFlow={groupFlow}
        deviceFilter={effectiveDeviceFilter}
        onLifecycleAction={applyLifecycleAction}
        onOpenDiscovery={(device, mode) => setDiscoveryTarget({ device, mode })}
        onConfigure={(id) => { setSelected(id); setView("change"); setReorderMode(false); }}
        onManageWorkflows={() => setActivePanel("RULES_ENGINE")}
        onRefresh={refreshManagerDevices} workflows={workflows} />}
      {view === "change" && <DmChangeView groups={groups} deviceFilter={effectiveDeviceFilter} selected={selected} onSelect={setSelected} onRefresh={refreshManagerDevices}
        onOpenDiscovery={(device, mode) => setDiscoveryTarget({ device, mode })}
        onManageWorkflows={() => setActivePanel("RULES_ENGINE")}
        workflows={workflows} />}
      {view === "add"    && <DmAddView    onDone={() => { refreshDevices(); setView("see"); }} />}
      {view === "remove" && <DmRemoveView devices={locDevices} selected={selected} onSelect={setSelected} onRefresh={refreshManagerDevices} />}
      {discoveryTarget && (
        <DeviceDiscoveryOverlay
          device={discoveryTarget.device}
          mode={discoveryTarget.mode}
          onClose={() => setDiscoveryTarget(null)}
          onApplied={() => { refreshManagerDevices(); setDiscoveryTarget(null); }}
        />
      )}
    </div>
  );
}

// Workflow affiliation -- coarser than device type, groups by "what this
// device is FOR" rather than what it physically is, matching the safety/
// security/climate categories DeviceType.java's own comments already use
// backend-side. Not sourced from DeviceCapability (ALARM/CLIMATE/etc.) --
// that set lives on DeviceDescriptor, never serialized into DeviceStatus,
// so (like every other groupBy dimension here) this stays a pure,
// client-side derivation from the type string alone. A first pass, not a
// final taxonomy -- deliberately only the three workflows named when this
// was requested (alerting, automations, hvac) plus a catch-all, rather
// than inventing categories nobody asked for yet.
export const WORKFLOW_BY_TYPE = {
  SMOKE_ALARM: "Alerting", CO_ALARM: "Alerting", WATER_LEAK_SENSOR: "Alerting",
  MOTION_SENSOR: "Alerting", CONTACT_SENSOR: "Alerting", CAMERA: "Alerting",
  THERMOSTAT: "HVAC", TEMPERATURE_SENSOR: "HVAC", HUMIDITY_SENSOR: "HVAC",
  CO2_SENSOR: "HVAC", AIR_QUALITY_SENSOR: "HVAC", CO_SENSOR: "HVAC",
  LOCK: "Automations", HOME_ASSISTANT_ENTITY: "Automations", GOOGLE_HOME_DEVICE: "Automations",
};

export function deviceLifecycleState(device) {
  const explicit = device?.attributes?.deviceLifecycle;
  if (explicit) return String(explicit).toUpperCase();
  return device?.attributes?.candidate === true ? "CANDIDATE" : "ASSIGNED";
}

const LIFECYCLE_LABELS = {
  CANDIDATE: "Candidates",
  AVAILABLE: "Available",
  ASSIGNED: "Assigned",
  DEFERRED: "Saved for later",
  IGNORED: "Ignored",
};

// ── L2/L3: See ──
export function groupDevices(devices, groupBy) {
  if (groupBy === "none") return [["All devices", devices]];
  const keyFor = (d) => {
    if (groupBy === "source") return d.attributes?.discoveredFrom || d.attributes?.source || (d.deviceId.startsWith("z2m-") ? "Zigbee2MQTT" : "Other");
    if (groupBy === "room") return d.attributes?.room || d.attributes?.area_name || "Room not assigned";
    if (groupBy === "state") return d.state || "UNKNOWN";
    if (groupBy === "candidate") return LIFECYCLE_LABELS[deviceLifecycleState(d)] || "Assigned";
    if (groupBy === "workflow") return WORKFLOW_BY_TYPE[d.type] || "Other";
    return d.type || "Other";
  };
  const groups = new Map();
  devices.forEach(d => { const key = keyFor(d); groups.set(key, [...(groups.get(key) || []), d]); });
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
}

// Real workflow membership -- deliberately separate from WORKFLOW_BY_TYPE
// above, which is a cosmetic, single-valued, client-only groupBy bucket
// derived from device TYPE and never persisted anywhere. This instead asks
// "which real, persisted WorkflowRule objects (GET /api/rules/workflows)
// actually reference this device" -- as either the trigger device or the
// target of any of its ordered actions -- which is the thing a device can
// genuinely belong to more than one of. A device with zero matches here is
// in zero workflows, full stop; there's no fallback "Other" bucket the way
// groupDevices has one, because "not in any workflow" is a real, accurate
// answer rather than a missing categorization.
export function workflowsForDevice(workflows, deviceId) {
  if (!deviceId) return [];
  return (workflows || []).filter(w =>
    w.triggerDeviceId === deviceId || (w.actions || []).some(a => a.targetDeviceId === deviceId));
}

// 2026-08-25: "in_scope" (the default) used to mean AVAILABLE/ASSIGNED
// only, excluding undecided candidates -- but every OTHER option here
// narrows the view, so a default that silently hides candidates isn't
// actually "what you should see by default." Merged the old separate
// "all" filter (in-scope + candidates, excluding only deferred/ignored)
// into this same default fallthrough -- "all" is no longer a distinct
// code path, any unrecognized/legacy filter value (including a
// localStorage value saved as "all" before this change) now resolves
// here too, so resolveDeviceManagerFilter's own forced "all" override
// for Lifecycle grouping still works unchanged.
export function filterDeviceManagerDevices(devices, filter = "in_scope") {
  if (filter === "candidates") {
    return devices.filter(d => deviceLifecycleState(d) === "CANDIDATE");
  }
  if (filter === "previous") {
    return devices.filter(d => ["DEFERRED", "IGNORED"].includes(deviceLifecycleState(d)));
  }
  return devices.filter(d => !["DEFERRED", "IGNORED"].includes(deviceLifecycleState(d)));
}

// 2026-08-25: the toolbar's device count used raw devices.length -- every
// HA sub-entity/service counted as its own "device" (Kidde's ~9-18
// entities, Liebherr's 9, each a separate row), producing a number like
// 157 with no way to tell how many *physical* things that actually
// represents. "Parent" here means "not itself a child of another device"
// (parentDeviceId unset) -- a standalone device with no children counted
// under it still counts as one. Devices with no parent relationships set
// at all (the common case until someone uses the Parent device picker)
// will show the same count either way -- that's correct, not a bug in
// this function; the count only shrinks once real relationships exist.
export function countParentDevices(devices) {
  return devices.filter(d => !d.attributes?.parentDeviceId).length;
}

export function resolveDeviceManagerFilter(groupBy, savedFilter = "in_scope") {
  return groupBy === "candidate" ? "all" : savedFilter;
}

function readStoredJson(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem(key));
    return value == null ? fallback : value;
  } catch {
    return fallback;
  }
}

export function reorderIds(ids, fromId, toId) {
  if (fromId === toId) return ids;
  const fromIdx = ids.indexOf(fromId);
  const toIdx = ids.indexOf(toId);
  if (fromIdx < 0 || toIdx < 0) return ids;
  const next = [...ids];
  const [moved] = next.splice(fromIdx, 1);
  next.splice(toIdx, 0, moved);
  return next;
}

export function buildOrderedDeviceGroups(devices, groupBy, savedGroupOrder = [], savedDeviceOrders = {}, isAlarm) {
  const rawGroups = groupDevices(devices, groupBy);
  const groupMap = new Map(rawGroups);
  const rawNames = rawGroups.map(([name]) => name);
  const orderedNames = [
    ...savedGroupOrder.filter(name => groupMap.has(name)),
    ...rawNames.filter(name => !savedGroupOrder.includes(name)),
  ];

  return orderedNames.map(name => {
    const items = groupMap.get(name) || [];
    const byId = new Map(items.map(item => [item.deviceId, item]));
    const savedIds = Array.isArray(savedDeviceOrders[name]) ? savedDeviceOrders[name] : [];
    let ordered = [
      ...savedIds.filter(id => byId.has(id)).map(id => byId.get(id)),
      ...items.filter(item => !savedIds.includes(item.deviceId)),
    ];
    if (isAlarm) ordered = [...ordered.filter(isAlarm), ...ordered.filter(item => !isAlarm(item))];
    return [name, ordered];
  });
}

export function migrateLegacyDeviceOrder(devices, groupBy, legacyOrder) {
  if (!Array.isArray(legacyOrder) || legacyOrder.length === 0) return {};
  // Device data arrives asynchronously. null means "migration pending" and
  // prevents the persistence effect from overwriting the legacy order with an
  // empty object before the first real /api/devices response arrives.
  if (devices.length === 0) return null;
  return Object.fromEntries(groupDevices(devices, groupBy).map(([name, items]) => {
    const ids = new Set(items.map(item => item.deviceId));
    return [name, legacyOrder.filter(id => ids.has(id))];
  }));
}

function useGroupedDraggableOrder(groupStorageKey, deviceStorageKey, legacyStorageKey, devices, groupBy, isAlarm) {
  const [savedGroupOrder, setSavedGroupOrder] = useState(() => {
    const saved = readStoredJson(groupStorageKey, []);
    return Array.isArray(saved) ? saved : [];
  });
  const [savedDeviceOrders, setSavedDeviceOrders] = useState(() => {
    const saved = readStoredJson(deviceStorageKey, null);
    const legacy = readStoredJson(legacyStorageKey, []);
    if (saved && !Array.isArray(saved) && typeof saved === "object"
        && (Object.keys(saved).length > 0 || !Array.isArray(legacy) || legacy.length === 0)) {
      return saved;
    }
    return migrateLegacyDeviceOrder(devices, groupBy, legacy);
  });

  useEffect(() => {
    localStorage.setItem(groupStorageKey, JSON.stringify(savedGroupOrder));
  }, [groupStorageKey, savedGroupOrder]);
  useEffect(() => {
    if (savedDeviceOrders !== null) {
      localStorage.setItem(deviceStorageKey, JSON.stringify(savedDeviceOrders));
    }
  }, [deviceStorageKey, savedDeviceOrders]);

  useEffect(() => {
    if (savedDeviceOrders !== null || devices.length === 0) return;
    const legacy = readStoredJson(legacyStorageKey, []);
    setSavedDeviceOrders(migrateLegacyDeviceOrder(devices, groupBy, legacy));
  }, [savedDeviceOrders, devices, groupBy, legacyStorageKey]);

  const groups = useMemo(() => buildOrderedDeviceGroups(
    devices, groupBy, savedGroupOrder, savedDeviceOrders || {}, isAlarm
  ), [devices, groupBy, savedGroupOrder, savedDeviceOrders, isAlarm]);

  const reorderGroup = useCallback((fromName, toName) => {
    setSavedGroupOrder(reorderIds(groups.map(([name]) => name), fromName, toName));
  }, [groups]);

  const reorderDevice = useCallback((groupName, fromId, toId) => {
    const items = groups.find(([name]) => name === groupName)?.[1] || [];
    const from = items.find(item => item.deviceId === fromId);
    const to = items.find(item => item.deviceId === toId);
    if (!from || !to || (isAlarm && (isAlarm(from) || isAlarm(to)))) return;
    setSavedDeviceOrders(current => ({
      ...(current || {}),
      [groupName]: reorderIds(items.map(item => item.deviceId), fromId, toId),
    }));
  }, [groups, isAlarm]);

  return { groups, reorderGroup, reorderDevice };
}

// See/Change (2026-08-27, user report): the shared `selected` state already
// carries a selection across a See<->Change tab switch, but each view fully
// unmounts/remounts on switch (conditional rendering, not a hidden pane) --
// so nothing ever scrolled the still-selected row into view, leaving a
// person to manually scroll and hunt for it in any list of real length even
// though the "right" device was, in fact, still selected the whole time.
// Ref goes on the currently-selected row only (see call sites below);
// re-fires on mount AND whenever `selected` itself changes (e.g. picking a
// different device while already on this tab).
function useScrollSelectedIntoView(selected) {
  const ref = useRef(null);
  useEffect(() => {
    // jsdom (this project's test environment) doesn't implement
    // scrollIntoView at all -- optional-call, not just optional-chained on
    // ref.current, so tests exercising this component don't need a mock.
    ref.current?.scrollIntoView?.({ block: "nearest", behavior: "smooth" });
  }, [selected]);
  return ref;
}

function DmSeeView({ groups, reorderGroup, reorderDevice, selected, onSelect, reorderMode, groupFlow, deviceFilter, onConfigure, onLifecycleAction, onOpenDiscovery, onRefresh, workflows, onManageWorkflows }) {
  const [health, setHealth] = useState(null);
  const [dragItem, setDragItem] = useState(null);
  const [overItem, setOverItem] = useState(null);
  const checkinStatuses = useCheckinStatuses(LOCATIONS.cabin.apiBase);
  const checkinDetails = useCheckinDetails(LOCATIONS.cabin.apiBase);

  useEffect(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/system/health`)
      .then(r => r.json()).then(setHealth).catch(() => {});
  }, []);

  // Same predicate DeviceManagerPanel uses to drive the hoisted ordering
  // hook -- only needed here for the drag UI's "alarm devices can't be
  // dragged" guard, cheap enough to duplicate rather than thread as a prop.
  const isAlarm = useCallback((d) => d.state === "ALARM" || d.state === "CRITICAL", []);

  const visibleGroups = groups
    .map(([name, items]) => [name, filterDeviceManagerDevices(items, deviceFilter)])
    .filter(([, items]) => items.length > 0);
  const visibleDevices = visibleGroups.flatMap(([, items]) => items);
  const totalDevices = groups.reduce((n, [, items]) => n + items.length, 0);
  const sel = selected ? visibleDevices.find(d => d.deviceId === selected) : null;
  const selectedRowRef = useScrollSelectedIntoView(selected);

  const clearDrag = () => { setDragItem(null); setOverItem(null); };
  const onGroupDragStart = (groupName) => (e) => {
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", `group:${groupName}`);
    setDragItem({ kind: "group", groupName });
  };
  const onGroupDragOver = (groupName) => (e) => {
    if (dragItem?.kind !== "group") return;
    e.preventDefault();
    setOverItem({ kind: "group", groupName });
  };
  const onGroupDrop = (groupName) => (e) => {
    if (dragItem?.kind !== "group") return;
    e.preventDefault();
    reorderGroup(dragItem.groupName, groupName);
    clearDrag();
  };
  const onDeviceDragStart = (groupName, deviceId) => (e) => {
    e.stopPropagation();
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", `device:${deviceId}`);
    setDragItem({ kind: "device", groupName, deviceId });
  };
  const onDeviceDragOver = (groupName, deviceId) => (e) => {
    if (dragItem?.kind !== "device" || dragItem.groupName !== groupName) return;
    e.preventDefault();
    e.stopPropagation();
    setOverItem({ kind: "device", groupName, deviceId });
  };
  const onDeviceDrop = (groupName, deviceId) => (e) => {
    if (dragItem?.kind !== "device" || dragItem.groupName !== groupName) return;
    e.preventDefault();
    e.stopPropagation();
    reorderDevice(groupName, dragItem.deviceId, deviceId);
    clearDrag();
  };

  return (
    <div className="dm-layout">
      <div className={`dm-list ${reorderMode ? "reorder-mode" : ""}`}>
        {health && (
          <div className="dm-health-bar">
            <span className="health-chip health-ok"><CheckCircle size={11}/> {health.online} online</span>
            <span className="health-chip health-offline"><WifiOff size={11}/> {health.offline} offline</span>
            {health.alarm > 0 && <span className="health-chip health-alarm"><ShieldAlert size={11}/> {health.alarm} alarm</span>}
            <span className="health-chip health-z2m">Z2M: {health.zigbeeBridge || "—"}</span>
          </div>
        )}
        <div className={`dm-groups dm-groups-${groupFlow}`}>
        {visibleGroups.map(([groupName, groupItems]) => (
          <section className={`dm-device-group ${reorderMode && overItem?.kind === "group" && overItem.groupName === groupName && dragItem?.groupName !== groupName ? "drag-over-group" : ""}`}
            key={groupName}
            onDragOver={reorderMode ? onGroupDragOver(groupName) : undefined}
            onDrop={reorderMode ? onGroupDrop(groupName) : undefined}>
            <header className="dm-device-group-header"
              draggable={reorderMode}
              onDragStart={reorderMode ? onGroupDragStart(groupName) : undefined}
              onDragEnd={reorderMode ? clearDrag : undefined}
              title={reorderMode ? "Drag to reorder this group" : undefined}>
              <span>{reorderMode && <GripVertical size={12} className="drag-handle"/>}{groupName}</span>
              <span>{groupItems.length}</span>
            </header>
            {groupItems.map((d) => {
          const isPinned = isAlarm(d);
          const isOver = reorderMode && overItem?.kind === "device"
            && overItem.groupName === groupName && overItem.deviceId === d.deviceId
            && dragItem?.deviceId !== d.deviceId;
          return (
            <div key={d.deviceId}
              className={`reorder-card ${isOver ? "drag-over-card" : ""}`}
              draggable={reorderMode && !isPinned}
              onDragStart={reorderMode && !isPinned ? onDeviceDragStart(groupName, d.deviceId) : undefined}
              onDragOver={reorderMode ? onDeviceDragOver(groupName, d.deviceId) : undefined}
              onDrop={reorderMode ? onDeviceDrop(groupName, d.deviceId) : undefined}
              onDragEnd={reorderMode ? clearDrag : undefined}
            >
              <DmDeviceRow device={d}
                selected={selected === d.deviceId}
                ref={selected === d.deviceId ? selectedRowRef : undefined}
                checkinStatus={checkinDetails[d.deviceId]?.status || checkinStatuses[d.deviceId]}
                onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)}
                onToggled={onRefresh}
                workflows={workflows}
                dragHandle={reorderMode
                  ? (isPinned
                    ? <Lock size={12} className="auto-pin-icon" title="Auto-pinned: alarm active"/>
                    : <GripVertical size={12} className="drag-handle"/>)
                  : null}
              />
            </div>
          );
        })}
          </section>
        ))}
        </div>
        {visibleDevices.length === 0 && <div className="empty-state"><Cpu size={36} opacity={0.3}/>
          <p>{totalDevices === 0 ? "No devices registered." : "No devices match this view."}</p></div>}
      </div>
      {sel && (
        <div className="dm-detail">
          <DmDeviceDetail device={sel} checkinStatus={checkinDetails[sel.deviceId]?.status || checkinStatuses[sel.deviceId]}
            checkinDetail={checkinDetails[sel.deviceId]} onConfigure={() => onConfigure(sel.deviceId)}
            onLifecycleAction={onLifecycleAction} onOpenDiscovery={onOpenDiscovery}
            workflows={workflows} onManageWorkflows={onManageWorkflows} />
        </div>
      )}
    </div>
  );
}

// ── L2/L3: Change ──
// Deliberately reads the same `groups` (computed once in DeviceManagerPanel
// via useGroupedDraggableOrder) that See renders -- no independent ordering,
// no drag props passed here, so there's no way for a Reorder control to
// appear in Change even by accident. A saved See-mode order/grouping just
// shows up identically, with no separate state to keep in sync.
function DmChangeView({ groups, deviceFilter, selected, onSelect, onRefresh, onOpenDiscovery, workflows, onManageWorkflows }) {
  const visibleGroups = groups
    .map(([name, items]) => [name, filterDeviceManagerDevices(items, deviceFilter)])
    .filter(([, items]) => items.length > 0);
  const visibleDevices = visibleGroups.flatMap(([, items]) => items);
  const totalDevices = groups.reduce((n, [, items]) => n + items.length, 0);
  const sel = selected ? visibleDevices.find(d => d.deviceId === selected) : null;
  const selectedRowRef = useScrollSelectedIntoView(selected);
  return (
    <div className="dm-layout">
      <div className="dm-list">
        <p className="dm-hint">Select a device to review its details or save an actual configuration change.</p>
        {visibleGroups.map(([groupName, groupItems]) => (
          <section className="dm-device-group" key={groupName}>
            <header className="dm-device-group-header"><span>{groupName}</span><span>{groupItems.length}</span></header>
            {groupItems.map(d => <DmDeviceRow key={d.deviceId} device={d} selected={selected === d.deviceId}
              ref={selected === d.deviceId ? selectedRowRef : undefined}
              onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)} onToggled={onRefresh} workflows={workflows} />)}
          </section>
        ))}
        {visibleDevices.length === 0 && <div className="empty-state"><Cpu size={36} opacity={0.3}/>
          <p>{totalDevices === 0 ? "No devices registered." : "No devices match this view."}</p></div>}
      </div>
      {sel && (
        <div className="dm-detail">
          <DmEditForm key={sel.deviceId} device={sel} onSaved={onRefresh} onOpenDiscovery={onOpenDiscovery}
            workflows={workflows} onManageWorkflows={onManageWorkflows} />
        </div>
      )}
    </div>
  );
}

// ── L2/L3: Add ──
function DmAddView({ onDone }) {
  const [mode, setMode] = useState(null); // null | "zigbee" | "manual"
  return (
    <div className="dm-add-root">
      {!mode && (
        <div className="dm-add-choice">
          <p className="dm-hint">How do you want to add a device?</p>
          <div className="dm-add-options">
            <button className="dm-add-option" onClick={() => setMode("zigbee")}>
              <Radio size={28}/>
              <strong>Pair a Zigbee device</strong>
              <span>Opens a 4-minute pairing window on the cabin hub's Zigbee coordinator</span>
            </button>
            <button className="dm-add-option" onClick={() => setMode("manual")}>
              <Cpu size={28}/>
              <strong>Register manually</strong>
              <span>Add a Home Assistant entity, RTSP camera, or MQTT device by ID</span>
            </button>
          </div>
        </div>
      )}
      {mode === "zigbee" && <ZigbeePairingFlow onBack={() => setMode(null)} onDone={onDone} />}
      {mode === "manual" && <ManualAddForm onBack={() => setMode(null)} onDone={onDone} />}
    </div>
  );
}

// ── Zigbee pairing flow ──
function ZigbeePairingFlow({ onBack, onDone }) {
  const PAIR_DURATION = 254; // seconds (~4m14s)
  const [phase, setPhase]         = useState("idle"); // idle | pairing | found | done
  const [secondsLeft, setSeconds] = useState(PAIR_DURATION);
  const [newDevices, setNewDevices] = useState([]);
  const [afterChoice, setAfterChoice] = useState(null); // "another" | "configure" | "seeall"
  const timerRef = useRef(null);
  const pollRef  = useRef(null);
  const prevIds  = useRef(null);

  const startPairing = async () => {
    // Snapshot current device IDs before opening window
    const snap = await fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
      .then(r => r.json()).catch(() => []);
    prevIds.current = new Set(snap.map(d => d.deviceId));

    await fetch(`${LOCATIONS.cabin.apiBase}/api/devices/permit-join`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enable: true, duration: PAIR_DURATION })
    });
    setPhase("pairing");
    setSeconds(PAIR_DURATION);

    // Countdown
    timerRef.current = setInterval(() => {
      setSeconds(s => {
        if (s <= 1) { clearInterval(timerRef.current); setPhase("done"); return 0; }
        return s - 1;
      });
    }, 1000);

    // Poll for new devices every 3s
    pollRef.current = setInterval(async () => {
      const all = await fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
        .then(r => r.json()).catch(() => []);
      const found = all.filter(d => !prevIds.current.has(d.deviceId));
      if (found.length > 0) setNewDevices(found);
    }, 3000);
  };

  const stopPairing = async () => {
    clearInterval(timerRef.current);
    clearInterval(pollRef.current);
    await fetch(`${LOCATIONS.cabin.apiBase}/api/devices/permit-join`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enable: false, duration: 0 })
    });
    setPhase("done");
  };

  useEffect(() => () => { clearInterval(timerRef.current); clearInterval(pollRef.current); }, []);

  const mins = String(Math.floor(secondsLeft / 60)).padStart(2, "0");
  const secs = String(secondsLeft % 60).padStart(2, "0");

  if (phase === "idle") return (
    <div className="pairing-container">
      <button className="btn-ghost dm-back" onClick={onBack}><ArrowLeft size={14}/> Back</button>
      <div className="pairing-card">
        <Radio size={36} className="pairing-icon"/>
        <h3>Pair a Zigbee Device</h3>
        <p>Put your device into pairing mode (hold the button until the LED flashes), then open the pairing window.</p>
        <p className="config-hint">The cabin hub's Zigbee coordinator will accept new devices for 4 minutes 14 seconds.</p>
        <button className="btn-primary pairing-start-btn" onClick={startPairing}>
          Open pairing window
        </button>
      </div>
    </div>
  );

  if (phase === "pairing") return (
    <div className="pairing-container">
      <div className="pairing-card pairing-active">
        <div className="pairing-countdown">{mins}:{secs}</div>
        <p className="pairing-status">Pairing window open — put device into pairing mode now</p>
        {newDevices.length > 0 && (
          <div className="pairing-found">
            <strong>New device{newDevices.length > 1 ? "s" : ""} found:</strong>
            {newDevices.map(d => (
              <div key={d.deviceId} className="pairing-found-row">
                <CheckCircle size={14} className="found-check"/> {d.name} ({d.deviceId})
              </div>
            ))}
          </div>
        )}
        <button className="btn-ghost" onClick={stopPairing}>Stop early</button>
      </div>
    </div>
  );

  // phase === "done"
  return (
    <div className="pairing-container">
      <div className="pairing-card">
        {newDevices.length > 0 ? (
          <>
            <CheckCircle size={36} className="pairing-icon pairing-success"/>
            <h3>Paired {newDevices.length} device{newDevices.length > 1 ? "s" : ""}!</h3>
            {newDevices.map(d => (
              <div key={d.deviceId} className="pairing-found-row">
                <CheckCircle size={13} className="found-check"/> {d.name}
              </div>
            ))}
            <div className="pairing-choices">
              <button className="btn-secondary" onClick={() => { setPhase("idle"); setNewDevices([]); }}>
                Add another
              </button>
              <button className="btn-primary" onClick={onDone}>See all devices</button>
            </div>
          </>
        ) : (
          <>
            <Clock size={36} className="pairing-icon"/>
            <h3>Pairing window closed</h3>
            <p className="config-hint">No new devices were found. Make sure the device is in pairing mode before opening the window.</p>
            <div className="pairing-choices">
              <button className="btn-secondary" onClick={() => setPhase("idle")}>Try again</button>
              <button className="btn-ghost" onClick={onBack}>Back</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

// ── Manual add form ──
function ManualAddForm({ onBack, onDone }) {
  const { locationCfg } = useApp();
  const [form, setForm] = useState({
    deviceId: "", name: "", type: "HOME_ASSISTANT_ENTITY",
    protocolAdapter: "ha_rest", connectionString: "", enabled: true,
    location: locationCfg?.id || "cabin"
  });
  const [saving, setSaving] = useState(false);
  const apiBase = form.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;

  const submit = async () => {
    if (!form.deviceId || !form.name) return;
    setSaving(true);
    await fetch(`${apiBase}/api/devices`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...form, capabilities: [] })
    });
    setSaving(false);
    onDone();
  };

  return (
    <div className="pairing-container">
      <button className="btn-ghost dm-back" onClick={onBack}><ArrowLeft size={14}/> Back</button>
      <div className="dm-edit-form">
        <h3>Register Device Manually</h3>
        <label>Location
          <select value={form.location} onChange={e=>setForm({...form,location:e.target.value})}>
            <option value="cabin">Cabin</option>
            <option value="home">Home</option>
          </select>
        </label>
        <label>Device ID <input value={form.deviceId} placeholder="e.g. home-lock-garage" onChange={e=>setForm({...form,deviceId:e.target.value})}/></label>
        <label>Display Name <input value={form.name} placeholder="e.g. Home Garage Lock" onChange={e=>setForm({...form,name:e.target.value})}/></label>
        <label>Type
          <select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>
            {["LOCK","THERMOSTAT","SMOKE_ALARM","CAMERA","WATER_PRESSURE_SENSOR","WATER_LEAK_SENSOR",
              "MOTION_SENSOR","CONTACT_SENSOR","DISHWASHER","WASHING_MACHINE","DRYER","POWER_METER",
              "HOME_ASSISTANT_ENTITY"].map(t=><option key={t}>{t}</option>)}
          </select>
        </label>
        <label>Protocol Adapter
          <select value={form.protocolAdapter} onChange={e=>setForm({...form,protocolAdapter:e.target.value})}>
            {["ha_rest","mqtt","rtsp","http_poll","google_sdm"].map(a=><option key={a}>{a}</option>)}
          </select>
        </label>
        <label>Connection String
          <input value={form.connectionString}
            placeholder="HA entity_id, MQTT topic, or RTSP URL"
            onChange={e=>setForm({...form,connectionString:e.target.value})}/>
        </label>
        <div className="modal-actions">
          <button className="btn-ghost" onClick={onBack}>Cancel</button>
          <button className="btn-primary" onClick={submit} disabled={saving || !form.deviceId || !form.name}>
            {saving ? "Saving…" : "Register Device"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── L2/L3: Remove ──
function DmRemoveView({ devices, selected, onSelect, onRefresh }) {
  const [confirming, setConfirming] = useState(false);
  const sel = selected ? devices.find(d => d.deviceId === selected) : null;

  const doRemove = async () => {
    if (!sel) return;
    const apiBase = LOCATIONS[sel.location]?.apiBase || LOCATIONS.cabin.apiBase;
    await fetch(`${apiBase}/api/devices/${sel.deviceId}`, { method: "DELETE" });
    onSelect(null);
    setConfirming(false);
    onRefresh();
  };

  return (
    <div className="dm-layout">
      <div className="dm-list">
        <p className="dm-hint">Select a device to remove it from the registry.</p>
        {devices.map(d => <DmDeviceRow key={d.deviceId} device={d} selected={selected === d.deviceId} onClick={() => { onSelect(selected === d.deviceId ? null : d.deviceId); setConfirming(false); }} />)}
      </div>
      {sel && (
        <div className="dm-detail">
          {!confirming ? (
            <div className="dm-remove-panel">
              <div className="dm-remove-device-name">{sel.name}</div>
              <div className="dm-remove-device-id">{sel.deviceId} · {sel.location}</div>
              <p className="dm-hint">This removes the device from the registry. It does not affect Home Assistant or Zigbee2MQTT pairing.</p>
              <button className="btn-danger dm-remove-confirm-btn" onClick={() => setConfirming(true)}>
                <Trash2 size={14}/> Remove this device
              </button>
            </div>
          ) : (
            <div className="dm-remove-panel">
              <AlertTriangle size={32} className="remove-warn-icon"/>
              <strong>Remove "{sel.name}"?</strong>
              <p className="dm-hint">This cannot be undone from the UI. The device will disappear from all panels.</p>
              <div className="pairing-choices">
                <button className="btn-ghost" onClick={() => setConfirming(false)}>Cancel</button>
                <button className="btn-danger" onClick={doRemove}><Trash2 size={13}/> Confirm remove</button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Shared sub-components ──

// Found (user report): enabling a device required clicking into its name,
// switching to the "Change" tab, re-selecting it there, THEN toggling --
// two-plus clicks and a tab switch for something that should be one click
// from the row itself. onToggled is only passed by callers that make sense
// for it (DmSeeView/DmChangeView's browsing lists) -- DmRemoveView and
// MnChangeView (display-config overrides, an unrelated "Change" concept)
// don't pass it, so the toggle simply doesn't render there, matching their
// existing behavior unchanged.
function DmRowEnableToggle({ device, onToggled }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const enabled = device.attributes?.enabled ?? (device.enabled !== false);
  const lifecycle = deviceLifecycleState(device);
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;

  const toggle = async (e) => {
    e.stopPropagation(); // don't also trigger the row's own onClick (select)
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(`${apiBase}/api/devices/${device.deviceId}/config`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !enabled })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || body.error) throw new Error(body.message || body.error || `HTTP ${response.status}`);
      onToggled();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <button
      className="btn-ghost dm-row-enable-toggle"
      onClick={toggle}
      disabled={saving}
      title={error ? `Not saved: ${error}` : (lifecycle === "CANDIDATE"
        ? "Enabling accepts and assigns this device"
        : (enabled ? "Disable" : "Enable"))}
    >
      {enabled ? <ToggleRight size={18} className="toggle-on"/> : <ToggleLeft size={18} className="toggle-off"/>}
    </button>
  );
}

// forwardRef (2026-08-27, user report): switching L1 tabs (See<->Change)
// unmounts and remounts whichever view isn't active, so a selection that
// correctly carries over via the shared `selected` state (2026-08-25 fix)
// still requires scrolling to physically find the row again in a list of
// any real length -- "sticky" only in the sense of "the right device is
// still selected," not "you can see that it is." The ref lets the parent
// view scroll the actually-selected row into view on mount/selection
// change instead of leaving that entirely to the user each time.
export const DmDeviceRow = forwardRef(function DmDeviceRow(
    { device, selected, onClick, dragHandle, checkinStatus, onToggled, workflows }, ref) {
  const Icon = deviceIcon(device.type);
  const isZ2m = device.deviceId.startsWith("z2m-");
  const override = checkinStatusLabel(device.state, checkinStatus);
  const lifecycle = deviceLifecycleState(device);
  // workflows is undefined for callers that don't fetch it (DmRemoveView,
  // MnChangeView) -- same opt-in shape as onToggled/DmRowEnableToggle above.
  const deviceWorkflows = workflows ? workflowsForDevice(workflows, device.deviceId) : [];
  return (
    <div ref={ref} className={`dm-device-row ${selected ? "dm-row-selected" : ""}`} onClick={onClick}>
      {dragHandle}
      <Icon size={16} className="dm-row-icon"/>
      <div className="dm-row-info">
        <span className="dm-row-name">{device.name}</span>
        <span className="dm-row-meta">{device.type} · {device.location}{isZ2m ? " · zigbee" : ""}</span>
      </div>
      {lifecycle !== "ASSIGNED" && (
        <span className={`candidate-badge lifecycle-${lifecycle.toLowerCase()}`}>
          {LIFECYCLE_LABELS[lifecycle] || lifecycle}
        </span>
      )}
      {deviceWorkflows.length > 0 && (
        <span className="workflow-badge" title={deviceWorkflows.map(w => w.name).join(", ")}>
          {deviceWorkflows.length} workflow{deviceWorkflows.length === 1 ? "" : "s"}
        </span>
      )}
      <span className={`state-badge ${override ? override.cls : stateColor(device.state)}`}>
        {override ? override.text : device.state}
      </span>
      {onToggled && <DmRowEnableToggle device={device} onToggled={onToggled} />}
    </div>
  );
});

export function DmDeviceDetail({ device, checkinStatus, checkinDetail, onConfigure, onLifecycleAction, onOpenDiscovery, workflows, onManageWorkflows }) {
  const override = checkinStatusLabel(device.state, checkinStatus);
  const lifecycle = deviceLifecycleState(device);
  // Resolved parent name, not just the raw id -- same "show it, don't
  // make a human cross-reference an id by hand" reasoning as Category/
  // Capabilities below. useApp() may return null in a standalone render
  // (e.g. a test rendering this component with no provider) -- called
  // unconditionally either way, per the Rules of Hooks; a missing
  // devices list just means no name resolves, not a crash.
  const parentDeviceId = device.attributes?.parentDeviceId;
  const appDevices = useApp()?.devices || [];
  const parentDevice = parentDeviceId ? appDevices.find(d => d.deviceId === parentDeviceId) : null;
  const [lifecycleResult, setLifecycleResult] = useState(null);
  const [pendingAction, setPendingAction] = useState(null);
  const decide = async (action) => {
    setPendingAction(action);
    setLifecycleResult(null);
    try {
      await onLifecycleAction(device, action);
      setLifecycleResult({ ok: true, text: "Decision saved." });
    } catch (error) {
      setLifecycleResult({ ok: false, text: `Decision was not saved: ${error.message}` });
    } finally {
      setPendingAction(null);
    }
  };
  return (
    <div className="dm-detail-inner">
      <div className="dm-detail-name">{device.name}</div>
      <div className="dm-detail-id">{device.deviceId}</div>
      <div className="dm-detail-rows">
        <div className="dm-detail-row"><span>Type</span><span>{device.type}</span></div>
        {device.attributes?.category && (
          <div className="dm-detail-row"><span>Category</span>
            <span className="category-badge">{device.attributes.category}</span>
          </div>
        )}
        {device.attributes?.capabilities?.length > 0 && (
          <div className="dm-detail-row"><span>Capabilities</span>
            <span className="capability-chips">
              {device.attributes.capabilities.map(c => <span key={c} className="capability-chip">{c}</span>)}
            </span>
          </div>
        )}
        {parentDeviceId && (
          <div className="dm-detail-row"><span>Belongs to</span>
            <span>{parentDevice ? parentDevice.name : parentDeviceId}</span>
          </div>
        )}
        <div className="dm-detail-row"><span>Location</span><span>{device.location}</span></div>
        <div className="dm-detail-row"><span>State</span>
          <span className={`state-badge ${override ? override.cls : stateColor(device.state)}`}>
            {override ? override.text : device.state}
          </span>
        </div>
        <div className="dm-detail-row"><span>Last seen</span>
          <span>{device.lastSeen ? new Date(device.lastSeen).toLocaleString() : "—"}</span>
        </div>
      </div>
      {checkinDetail?.reason && (
        <div className="dm-why-card"><strong>Why is this status shown?</strong><span>{checkinDetail.reason}</span>
          <small>Expected within {checkinDetail.expectedMinutes} min; not responding after {checkinDetail.missedAfterMinutes} min.</small></div>
      )}
      {lifecycle === "CANDIDATE" && (
        <div className="dm-candidate-card"><strong>New device candidate</strong>
          <span>Discovered from {device.attributes.discoveredFrom || device.attributes.source || "an integration"}. Looking at it or closing this view leaves it a candidate.</span>
          {device.attributes.discoverySuggested && (
            <div className="discovery-suggested-banner">
              <Search size={13}/> New device — want to look it up before deciding?
            </div>
          )}
          {onOpenDiscovery && (
            <button
              className={device.attributes.discoverySuggested ? "btn-primary" : "btn-secondary"}
              onClick={() => onOpenDiscovery(device, "new")}>
              <Search size={13}/> Recognize this device
            </button>
          )}
          <div className="device-actions">
            <button className="btn-primary" onClick={() => decide("ACCEPT")} disabled={pendingAction}>Use this device</button>
            <button className="btn-secondary" onClick={() => decide("DEFER")} disabled={pendingAction}>Not now</button>
            <button className="btn-ghost" onClick={() => decide("IGNORE")} disabled={pendingAction}>Ignore</button>
          </div>
          <button className="btn-ghost" onClick={onConfigure}>Review details without deciding</button>
        </div>
      )}
      {lifecycle === "AVAILABLE" && (
        <div className="dm-candidate-card"><strong>Available</strong>
          <span>This device is in scope but not assigned. It stays disabled until an actual configuration change is saved.</span>
          <button className="btn-primary" onClick={onConfigure}>Assign / configure</button>
        </div>
      )}
      {["DEFERRED", "IGNORED"].includes(lifecycle) && (
        <div className="dm-candidate-card"><strong>Previously exposed device</strong>
          <span>Only cached identification is shown here. The app does not actively poll or command this device.</span>
          <div className="device-actions">
            <button className="btn-primary" onClick={() => decide("ACCEPT")} disabled={pendingAction}>Use this device</button>
            <button className="btn-secondary" onClick={() => decide("REVIEW")} disabled={pendingAction}>Return to candidates</button>
          </div>
        </div>
      )}
      {lifecycleResult && <p className={lifecycleResult.ok ? "action-result action-ok" : "action-result action-error"}>{lifecycleResult.text}</p>}
      {Object.keys(device.attributes || {}).length > 0 && (
        <>
          <div className="dm-detail-section">Attributes</div>
          {/* category/capabilities/parentDeviceId have their own structured
              rows above (real ontology data / resolved names, not
              free-form) -- shown there only, not duplicated here as raw
              key/value text. */}
          {Object.entries(device.attributes)
            .filter(([k]) => k !== "category" && k !== "capabilities" && k !== "parentDeviceId")
            .map(([k, v]) => v != null && (
              <div key={k} className="attr-row">
                <span className="attr-key">{k}</span>
                <span className="attr-val">{String(v)}</span>
              </div>
            ))}
        </>
      )}
      <DmDeviceWorkflows device={device} workflows={workflows} onManage={onManageWorkflows} />
      {lifecycle === "ASSIGNED" && device.type === "LOCK" && <DmLockActions device={device}/>}
      {lifecycle === "ASSIGNED" && <DmCapabilityActions device={device} onConfigure={onConfigure}/>}
    </div>
  );
}

// Real per-device workflow membership -- read-only here on purpose.
// Fire/Activate/Deactivate/Delete/History already exist, tested and
// auth-gated, on WorkflowRow in RulesPanel; duplicating mutation controls
// into Device Manager would mean two places that can change a workflow's
// state, one of them untested here. "Manage in Rules & Alerts" is an
// honest jump to where those actions already work, not a scrolled/focused
// deep link -- building real cross-panel focus state wasn't in scope here.
function DmDeviceWorkflows({ device, workflows, onManage }) {
  const deviceWorkflows = workflowsForDevice(workflows, device.deviceId);
  return (
    <div className="dm-workflows-section">
      <div className="dm-detail-section">Workflows ({deviceWorkflows.length})</div>
      {deviceWorkflows.length === 0
        ? <p className="config-hint">Not used by any workflow yet.</p>
        : deviceWorkflows.map(w => (
          <div key={w.workflowId} className="rule-row">
            <span className={`rule-dot ${w.enabled ? "rule-defined" : "rule-inactive"}`}>●</span>
            <div>
              <div className="rule-name">{w.name}</div>
              <div className="rule-detail">
                {w.triggerDeviceId || w.triggerDefinitionId || "any trigger"} {" → "}
                {(w.actions || []).length > 0 ? w.actions.map(a => a.targetDeviceId || a.actionDefinitionId).join(", ") : "no actions"}
              </div>
            </div>
          </div>
        ))}
      {deviceWorkflows.length > 0 && onManage && (
        <button className="btn-ghost" style={{ marginTop: 4 }} onClick={onManage}>Manage in Rules & Alerts</button>
      )}
    </div>
  );
}

function DmCapabilityActions({ device, onConfigure }) {
  const [result, setResult] = useState(null);
  const capabilities = device.attributes?.capabilities || [];
  if (!capabilities.includes("COMMAND") || device.type === "LOCK") return null;
  const entityId = device.attributes?.entityId || "";
  const domain = entityId.split(".")[0];
  const commands = ["switch", "light"].includes(domain)
    ? [[`${domain}.turn_on`, "Turn on"], [`${domain}.turn_off`, "Turn off"]]
    : domain === "cover"
      ? [["cover.open_cover", "Open"], ["cover.close_cover", "Close"]]
      : [];
  if (!commands.length) return (
    <>
      <p className="config-hint">This device can receive commands, but this app only offers safe one-tap buttons for a small preset of actions (turn on/off, open/close) — this device's type isn't in that preset yet.</p>
      {onConfigure && <button className="btn-ghost" onClick={onConfigure}>Review its configuration in Change</button>}
    </>
  );
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
  const send = async (command) => {
    setResult({ pending: true, text: "Sending…" });
    try {
      const response = await fetch(`${apiBase}/api/devices/${device.deviceId}/command`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ command })
      });
      const body = await response.json();
      setResult(body.accepted
        ? { ok: true, text: "Action accepted. The device may take a moment to report its new state." }
        : { ok: false, text: "Action was not accepted. Check that the device is enabled and its Home Assistant integration is available." });
    } catch {
      setResult({ ok: false, text: "Action could not reach the cabin service. Check the connection and try again." });
    }
  };
  return <><div className="device-actions">{commands.map(([command, label]) =>
    <button key={command} className="btn-secondary" onClick={() => send(command)} disabled={result?.pending}>{label}</button>)}</div>
    {result && <p className={result.ok ? "action-result action-ok" : "action-result action-error"}>{result.text}</p>}</>;
}

function DmLockActions({ device }) {
  const { refreshDevices } = useApp();
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
  const cmd = async (command) => {
    await fetch(`${apiBase}/api/devices/${device.deviceId}/command`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command })
    });
    refreshDevices();
  };
  return (
    <div className="device-actions" style={{marginTop: 12}}>
      <button className="btn-secondary" onClick={() => cmd("lock.lock")}><Lock size={12}/> Lock</button>
      <button className="btn-secondary" onClick={() => cmd("lock.unlock")}>Unlock</button>
    </div>
  );
}

export function DmEditForm({ device, onSaved, onOpenDiscovery, workflows, onManageWorkflows }) {
  const [name, setName]       = useState(device.name);
  const [enabled, setEnabled] = useState(device.attributes?.enabled ?? (device.enabled !== false));
  // Room (added 2026-08-18): the grouping dimension wired up on request --
  // was always readable in groupDevices()'s "room" option, but nothing in
  // the backend ever set it, so every device showed "Room not assigned"
  // with no way to change that. Persists durably via PATCH .../config's
  // new 'room' field (DeviceLifecycleRecord.extraAttributes), not just in
  // memory -- see DeviceController's own comment.
  const [room, setRoom]       = useState(device.attributes?.room || "");
  // Parent device (added 2026-08-25, Item 4a): the device-to-services
  // hierarchy's MVP link -- e.g. tying a Kidde unit's separate HA-entity
  // "devices" (CO alarm, air quality, ...) back to the one physical unit
  // they actually belong to. Same extraAttributes mechanism as room, but
  // real referential validation lives server-side (DeviceRegistry.
  // validateParentDeviceId) since a dangling/cyclic reference is a real
  // bug, not free text. useApp() gives the full device list to pick a
  // parent from -- same context DmLockActions already reads from.
  const allDevices = useApp()?.devices || [];
  const [parentDeviceId, setParentDeviceId] = useState(device.attributes?.parentDeviceId || "");
  const [saving, setSaving]   = useState(false);
  const [saved, setSaved]     = useState(false);
  const [saveError, setSaveError] = useState(null);
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
  const lifecycle = deviceLifecycleState(device);
  const originalEnabled = device.attributes?.enabled ?? (device.enabled !== false);
  const originalRoom = device.attributes?.room || "";
  const originalParentDeviceId = device.attributes?.parentDeviceId || "";
  const changed = name.trim() !== device.name || enabled !== originalEnabled || room.trim() !== originalRoom
    || parentDeviceId !== originalParentDeviceId;
  // Same location, not itself -- matches validateParentDeviceId's own
  // rules; filtering candidates here is a UX nicety, the real guard is
  // still server-side.
  const parentCandidates = (allDevices || [])
    .filter(d => d.deviceId !== device.deviceId && d.location === device.location)
    .sort((a, b) => a.name.localeCompare(b.name));

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const response = await fetch(`${apiBase}/api/devices/${device.deviceId}/config`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, enabled, room: room.trim(), parentDeviceId })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || body.error) throw new Error(body.message || body.error || `HTTP ${response.status}`);
      setSaved(true);
      onSaved();
      setTimeout(() => setSaved(false), 2000);
    } catch (error) {
      setSaveError(error.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="dm-edit-form">
      <div className="dm-detail-name">{device.deviceId}</div>
      {lifecycle === "CANDIDATE" && <p className="config-desc">Reviewing or saving a corrected name leaves this device a candidate. Turning Enabled on and saving is an explicit decision to use it, and accepts and assigns it in one step.</p>}
      {lifecycle === "AVAILABLE" && <p className="config-desc">This accepted device is available but unassigned. Saving an actual change assigns it.</p>}
      {/* 2026-08-25: this used to be hidden entirely for CANDIDATE devices
          -- fine as long as a candidate was always reached via DmSeeView's
          DmDeviceDetail (which has its own "Recognize this device" button),
          but DmChangeView renders this form directly for a candidate too,
          with no fallback -- a candidate opened that way had no lookup
          option at all. mode="new" is the backend-correct path for a
          candidate (DeviceRegistry.replaceConfiguration() itself rejects
          CANDIDATE state; applyNew()'s ACCEPT+saveConfiguration is what
          DmDeviceDetail's own button already uses for the same case). */}
      {onOpenDiscovery && (
        <button className="btn-secondary"
          onClick={() => onOpenDiscovery(device, lifecycle === "CANDIDATE" ? "new" : "replace")}>
          <Search size={13}/> {lifecycle === "CANDIDATE" ? "Recognize this device" : "Re-check device info"}
        </button>
      )}
      <label>Display Name
        <input value={name} onChange={e => { setName(e.target.value); setSaved(false); setSaveError(null); }}/>
      </label>
      <label>Room
        <input value={room} placeholder="e.g. Kitchen, Mechanical Room"
          onChange={e => { setRoom(e.target.value); setSaved(false); setSaveError(null); }}/>
      </label>
      <label>Parent device
        <select value={parentDeviceId}
          onChange={e => { setParentDeviceId(e.target.value); setSaved(false); setSaveError(null); }}>
          <option value="">None — this is a standalone device</option>
          {parentCandidates.map(d => <option key={d.deviceId} value={d.deviceId}>{d.name}</option>)}
        </select>
      </label>
      <label className="dm-toggle-row">
        <span>Enabled</span>
        <button className="btn-ghost" onClick={() => setEnabled(e => !e)}
          title={lifecycle === "CANDIDATE" ? "Saving Enabled on accepts and assigns this candidate" : undefined}>
          {enabled ? <ToggleRight size={22} className="toggle-on"/> : <ToggleLeft size={22} className="toggle-off"/>}
        </button>
      </label>
      <DmDeviceWorkflows device={device} workflows={workflows} onManage={onManageWorkflows} />
      <div className="modal-actions">
        {saved && <span className="save-ok"><CheckCircle size={13}/> Saved</span>}
        {saveError && <span className="action-result action-error">Not saved: {saveError}</span>}
        <button className="btn-primary" onClick={save} disabled={saving || !changed || !name.trim()}>
          {saving ? "Saving…" : "Save changes"}
        </button>
      </div>
    </div>
  );
}

// ── Self-discovery / assisted-onboarding overlay ──
// mode="new": a CANDIDATE being reviewed -- "Import" accepts + configures
//   it in one step (backend: applyLifecycleAction(ACCEPT) + saveConfiguration()).
// mode="replace": an already-configured device's re-sync -- "Replace" merges
//   only the checked fields into the live descriptor (backend:
//   replaceConfiguration()), never touches enabled state.

// Marks an error as carrying a real, server-supplied reason (e.g. the
// rate-limit guard's "try again in Ns") worth showing verbatim. Any other
// failure -- a bad HTTP status with no JSON body, a raw network rejection --
// stays generic; a raw fetch/TypeError message isn't fit to show a person.
class DiscoveryRunError extends Error {}
// Never mutates anything itself -- every field application happens through
// POST .../discovery/apply, which a person triggers explicitly by clicking
// Import/Replace after reviewing sources. First real full-screen overlay in
// this file; repurposes the .modal-overlay/.modal CSS that existed but had
// no consumer.
export function DeviceDiscoveryOverlay({ device, mode, onClose, onApplied }) {
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
  const [status, setStatus] = useState("loading"); // loading | ok | error
  const [errorMessage, setErrorMessage] = useState(null);
  const [result, setResult] = useState(null);
  const [matchIdx, setMatchIdx] = useState(0);
  const [selectedFields, setSelectedFields] = useState({});
  const [applying, setApplying] = useState(false);
  const [applyError, setApplyError] = useState(null);
  const [applyOk, setApplyOk] = useState(false);
  const pollRef = useRef(null);
  const timeoutRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    const clearTimers = () => { clearInterval(pollRef.current); clearTimeout(timeoutRef.current); };

    setStatus("loading");
    setErrorMessage(null);
    setResult(null);
    setApplyOk(false);
    setApplyError(null);

    const poll = () => {
      fetch(`${apiBase}/api/devices/${device.deviceId}/discovery/latest`)
        .then(r => r.json())
        .then(body => {
          if (cancelled || body.pending) return;
          setResult(body);
          setStatus("ok");
          const top = body.matches?.[0];
          setSelectedFields({
            name: !!top?.suggestedName,
            type: mode === "new" && !!top?.suggestedType,
            capabilities: mode === "new" && (top?.suggestedCapabilities || []).length > 0,
            enabled: false,
          });
          clearTimers();
        })
        .catch(() => { /* transient error while polling -- keep retrying until the bounded timeout below */ });
    };

    fetch(`${apiBase}/api/devices/${device.deviceId}/discovery/run`, { method: "POST" })
      .then(async response => {
        const body = await response.json().catch(() => ({}));
        if (body.error) throw new DiscoveryRunError(body.error);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
      })
      .then(() => {
        if (cancelled) return;
        poll(); // check right away -- no reason to wait a full interval if it's already done
        pollRef.current = setInterval(poll, 2000);
        // Bounded like CameraLiveView's connection timeout -- a slow or
        // hung external lookup must degrade to a clear error, not spin
        // forever with no explanation.
        timeoutRef.current = setTimeout(() => {
          clearTimers();
          if (!cancelled) setStatus(current => current === "loading" ? "error" : current);
        }, 25000);
      })
      // A rejected run (e.g. the rate-limit guard) must surface its own
      // message, not the generic timeout copy -- otherwise re-opening the
      // overlay within the cooldown window would silently show whatever
      // stale result /discovery/latest still has on file, with the person
      // never told why nothing new ran. Any other failure (bad HTTP status,
      // a raw network rejection) stays generic -- see DiscoveryRunError.
      .catch(error => {
        if (cancelled) return;
        setErrorMessage(error instanceof DiscoveryRunError ? error.message : null);
        setStatus("error");
      });

    return () => { cancelled = true; clearTimers(); };
  }, [device.deviceId, apiBase, mode]);

  const match = result?.matches?.[matchIdx];
  const toggleField = (field) => setSelectedFields(f => ({ ...f, [field]: !f[field] }));
  const nothingSelected = !Object.values(selectedFields).some(Boolean);

  const apply = async () => {
    if (!match) return;
    setApplying(true);
    setApplyError(null);
    try {
      const fields = {};
      if (selectedFields.name && match.suggestedName) fields.name = match.suggestedName;
      if (selectedFields.type && match.suggestedType) fields.type = match.suggestedType;
      if (selectedFields.capabilities && (match.suggestedCapabilities || []).length) fields.capabilities = match.suggestedCapabilities;
      if (mode === "new") fields.enabled = !!selectedFields.enabled;

      const response = await fetch(`${apiBase}/api/devices/${device.deviceId}/discovery/apply`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: result.runId, mode, fields }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || body.error) throw new Error(body.error || `HTTP ${response.status}`);
      setApplyOk(true);
      onApplied();
    } catch (error) {
      setApplyError(error.message);
    } finally {
      setApplying(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal discovery-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h3>{mode === "replace" ? "Re-check device info" : "Recognize this device"}</h3>
          <button className="btn-ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {status === "loading" && (
          <div className="discovery-status-pane">
            <p>Looking up {device.name}…</p>
            <p className="config-hint">
              Checking local discovery data{mode === "replace" ? " and comparing against the current configuration" : ""},
              plus an external lookup if one is configured on this deployment.
            </p>
          </div>
        )}

        {status === "error" && (
          <div className="discovery-status-pane">
            <p>{errorMessage || "The discovery service didn't respond in time."}</p>
            <button className="btn-secondary" onClick={onClose}>Close</button>
          </div>
        )}

        {status === "ok" && match && (
          <div className="discovery-result">
            {result.matches.length > 1 && (
              <div className="discovery-match-tabs">
                {result.matches.map((m, i) => (
                  <button key={i} className={`btn-ghost ${i === matchIdx ? "btn-ghost-active" : ""}`} onClick={() => setMatchIdx(i)}>
                    Match {i + 1} ({m.confidence})
                  </button>
                ))}
              </div>
            )}

            <span className={`candidate-badge lifecycle-${match.confidence}`}>{match.confidence} confidence</span>
            <p className="discovery-summary">{match.summary}</p>

            {mode === "replace" && <p className="config-hint">Check a field to replace its current value with what was found below. Nothing changes until you click Replace.</p>}

            <div className="discovery-fields">
              {match.suggestedName && (
                <label className="discovery-field-row">
                  <input type="checkbox" checked={!!selectedFields.name} onChange={() => toggleField("name")} />
                  <span className="discovery-field-label">Name</span>
                  {mode === "replace" && <span className="discovery-field-current">{device.name} →</span>}
                  <span className="discovery-field-value">{match.suggestedName}</span>
                </label>
              )}
              {match.suggestedType && (
                <label className="discovery-field-row">
                  <input type="checkbox" checked={!!selectedFields.type} onChange={() => toggleField("type")} />
                  <span className="discovery-field-label">Type</span>
                  {mode === "replace" && <span className="discovery-field-current">{device.type} →</span>}
                  <span className="discovery-field-value">{match.suggestedType}</span>
                </label>
              )}
              {(match.suggestedCapabilities || []).length > 0 && (
                <label className="discovery-field-row">
                  <input type="checkbox" checked={!!selectedFields.capabilities} onChange={() => toggleField("capabilities")} />
                  <span className="discovery-field-label">Capabilities</span>
                  <span className="discovery-field-value">{match.suggestedCapabilities.join(", ")}</span>
                </label>
              )}
              {mode === "new" && (
                <label className="discovery-field-row">
                  <input type="checkbox" checked={!!selectedFields.enabled} onChange={() => toggleField("enabled")} />
                  <span className="discovery-field-label">Enable immediately</span>
                </label>
              )}
            </div>

            <div className="dm-why-card">
              <strong>Setup / install info{match.installGuide.mode !== "linkonly" ? ` (${match.installGuide.mode})` : ""}</strong>
              <span>{match.installGuide.content}</span>
            </div>

            {match.sources.length > 0 ? (
              <div className="discovery-sources">
                <strong>Sources</strong>
                {match.sources.map((s, i) => (
                  <a key={i} href={s.url} target="_blank" rel="noreferrer" className="discovery-source-link">
                    {s.title || s.url} <ExternalLink size={11}/>
                  </a>
                ))}
              </div>
            ) : (
              <p className="config-hint">No external sources were found for this match — treat the summary above as unverified.</p>
            )}

            {applyError && <p className="action-result action-error">Not applied: {applyError}</p>}
            {applyOk && <p className="action-result action-ok"><CheckCircle size={13}/> Applied.</p>}

            <div className="modal-actions">
              <button className="btn-ghost" onClick={onClose}>Close</button>
              <button className="btn-primary" onClick={apply} disabled={applying || applyOk || nothingSelected}>
                {applying ? "Applying…" : mode === "replace" ? "Replace device settings with new definitions" : "Import this device"}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Panel: Monitoring ─────────────────────────────────────────────────────

// Replaced the embedded Grafana iframe, 2026-08-08 -- three separate fix
// attempts (URL/subpath, SameSite cookie, a theory that was then
// disproven live) all failed. Root cause landed on either browser-level
// third-party cookie blocking or the iframe request never reaching the
// server at all -- see docs/ontology.yaml's cabin_grafana_public_access
// notes. User's own call: stop fighting the iframe, query Prometheus
// directly (via cabin-backend's new /api/frigate-metrics -- Prometheus
// itself stays Tailscale/internal-only, never exposed publicly) for the
// one metric that actually matters, and link out to the full Grafana
// dashboard for anyone who wants more detail.

// Exported for src/App.test.jsx. Pure function, no fetch/state -- kept
// separate from "no data yet" (fps === undefined/null) so an unreachable
// Prometheus never reads as "camera confirmed down," same reasoning as
// SecurityBadge's Unknown state.
export function cameraHealthLabel(fps) {
  if (fps == null) return { label: "Unknown", className: "camera-health-unknown" };
  return fps > 0
    ? { label: `${fps.toFixed(1)} fps`, className: "camera-health-ok" }
    : { label: "No signal", className: "camera-health-down" };
}

function useFrigateMetrics(apiBase) {
  const [metrics, setMetrics] = useState({});
  useEffect(() => {
    const fetchMetrics = () =>
      fetch(`${apiBase}/api/frigate-metrics`).then(r => r.json()).then(setMetrics).catch(() => {});
    fetchMetrics();
    const t = setInterval(fetchMetrics, 15000);
    return () => clearInterval(t);
  }, [apiBase]);
  return metrics;
}

function CameraHealthPanel({ locCfg }) {
  const metrics = useFrigateMetrics(locCfg.apiBase);
  const cameraIds = Object.keys(metrics);
  const dashboardUid = GRAFANA_DASHBOARD_UID[locCfg.id];

  return (
    <div className="embed-section">
      <div className="embed-label">Camera Health — {locCfg.label}</div>
      <div className="camera-health-grid">
        {cameraIds.length === 0 && (
          <p className="config-desc">No camera metrics available yet.</p>
        )}
        {cameraIds.map(id => {
          const { label, className } = cameraHealthLabel(metrics[id]?.cameraFps);
          return (
            <div key={id} className={`camera-health-tile ${className}`}>
              <Camera size={16} />
              <span className="camera-health-name">{id}</span>
              <span className="camera-health-value">{label}</span>
            </div>
          );
        })}
      </div>
      {dashboardUid ? (
        <a className="btn-secondary" href={`${locCfg.grafanaUrl}/grafana/d/${dashboardUid}`}
          target="_blank" rel="noreferrer">
          Open Full Dashboard in Grafana ↗
        </a>
      ) : (
        <p className="config-hint">No Grafana dashboard configured for {locCfg.label} yet.</p>
      )}
    </div>
  );
}

// Renders KPI tiles + Grafana + event log for a single location.
// Which device types get a KPI tile at all, and how each one's tile is
// built -- pulled out of LocationMonitoringSection as a pure function
// (2026-08-25) so the grid can iterate devices in the user's saved order
// instead of a fixed per-type sequence, while every type's existing
// icon/label/value logic stays byte-for-byte the same. Returns null for
// any device type that never got a tile before (unchanged).
export function kpiTileFor(device, tempUnit) { // exported for src/App.test.jsx's Monitoring reorder tests
  switch (device.type) {
    case "WATER_PRESSURE_SENSOR":
      return { icon: Droplets, label: "Water Pressure", deviceId: device.deviceId,
        value: device.attributes?.psi != null ? `${device.attributes.psi} PSI` : "—", state: device.state };
    case "THERMOSTAT":
      return { icon: Thermometer, label: device.name, deviceId: device.deviceId,
        value: fmtTemp(device.attributes?.current_temperature, tempUnit), state: device.state };
    case "TEMPERATURE_SENSOR": {
      const temp = device.attributes?.temperature;
      const hum  = device.attributes?.humidity;
      const val  = [fmtTemp(temp, tempUnit), hum != null && `${hum}%`].filter(Boolean).join(" · ") || "—";
      return { icon: Thermometer, label: device.name, deviceId: device.deviceId, value: val, state: device.state };
    }
    case "SMOKE_ALARM":
      return { icon: ShieldAlert, label: device.name || "Smoke/CO Alarm", deviceId: device.deviceId,
        value: device.state || "UNKNOWN", state: device.state === "ALARM" ? "ALARM" : device.state };
    case "POWER_METER":
      return { icon: Zap, label: "Energy", deviceId: device.deviceId,
        value: device.attributes?.state_w != null ? `${device.attributes.state_w} W` : "—", state: device.state };
    case "LOCK":
      return { icon: Lock, label: device.name, deviceId: device.deviceId, value: device.state, state: device.state };
    case "CAMERA":
      return { icon: Camera, label: device.name, deviceId: device.deviceId, value: device.state, state: device.state };
    default:
      return null;
  }
}

// 2026-08-25: real in-app historical trend view, replacing the Grafana
// link-out entirely -- Grafana proved unreliable/unfit for this specific
// need (see docs/MAINTENANCE.md Known Issues: a datasource-uid crash-
// loop, then a stale-panel-uid "no data" bug, then a login wall) for
// what turned out to actually be evidence for an active insurance claim
// -- a timestamped humidity/temperature trend a person can read and
// export without logging into a separate tool, not a live dashboard.
// Backed by GET /api/events/telemetry-history (day-bucketed min/avg/max,
// CabinEventService.dailyAggregates()) -- not raw event replay, which
// caps at 200 rows and can't cover weeks at this sensor network's
// ~10-15min sample interval.
// Reusable "3rd-party/non-native sensor onboarding" pattern, frontend half
// (see HomeAssistantDiscoveryService.semanticFieldFor()'s javadoc for the
// backend half, and the cabin-3rd-party-device-onboarding skill for the
// full pattern write-up). Each option's `types` list is which DeviceType(s)
// can actually produce that payload field -- added 2026-08-27 when Kidde's
// CO2/air-quality/CO entities turned out to be excluded by this panel's old
// `d.type === "TEMPERATURE_SENSOR"`-only filter (docs/MAINTENANCE.md's own
// note: "same underlying endpoint would serve them, just needs the picker
// widened"). Onboarding a future non-native sensor type (a new HACS
// integration, a different vendor) should mean adding one entry here plus
// one DeviceType/semanticFieldFor() case backend-side -- nothing else in
// this component should need to change.
// Order matters: it's also the default-selection order for whichever
// device is selected (fieldOptions[0]) -- "humidity" stays before
// "temperature" specifically to preserve this panel's pre-existing default
// (a Zigbee TEMPERATURE_SENSOR device defaulted to showing humidity first).
const SENSOR_FIELD_OPTIONS = [
  { value: "humidity", label: "Humidity", types: ["TEMPERATURE_SENSOR", "HUMIDITY_SENSOR"] },
  { value: "temperature", label: "Temperature", types: ["TEMPERATURE_SENSOR"] },
  { value: "co2", label: "CO₂", types: ["CO2_SENSOR"] },
  { value: "airQualityIndex", label: "Air Quality Index", types: ["AIR_QUALITY_SENSOR"] },
  { value: "co", label: "CO", types: ["CO_SENSOR"] },
];
const SENSOR_HISTORY_TYPES = [...new Set(SENSOR_FIELD_OPTIONS.flatMap(o => o.types))];
const SENSOR_FIELD_UNITS = { temperature: "°", humidity: "%", co2: " ppm", co: " ppm", airQualityIndex: "" };

export function SensorHistoryPanel({ devices, apiBase, tempUnit }) {
  const sensors = devices.filter(d => SENSOR_HISTORY_TYPES.includes(d.type));
  const [deviceId, setDeviceId] = useState(sensors[0]?.deviceId || "");
  const selectedDevice = sensors.find(d => d.deviceId === deviceId);
  const fieldOptions = selectedDevice
    ? SENSOR_FIELD_OPTIONS.filter(o => o.types.includes(selectedDevice.type))
    : SENSOR_FIELD_OPTIONS;
  const [field, setField] = useState(fieldOptions[0]?.value || "temperature");
  const [days, setDays] = useState(30);
  const [points, setPoints] = useState([]);
  const [loading, setLoading] = useState(false);

  // Keep `field` valid whenever the selected device's type changes (e.g.
  // switching from a Temperature/Humidity Zigbee sensor to a Kidde CO2
  // sensor) -- otherwise the picker would silently keep an old field
  // selection this device can never report, instead of one it actually has.
  useEffect(() => {
    if (!fieldOptions.some(o => o.value === field)) {
      setField(fieldOptions[0]?.value || "temperature");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDevice?.type]);

  useEffect(() => {
    if (!deviceId) return;
    setLoading(true);
    fetch(`${apiBase}/api/events/telemetry-history?deviceId=${encodeURIComponent(deviceId)}&field=${field}&days=${days}`)
      .then(r => r.json())
      .then(data => setPoints(Array.isArray(data) ? data : []))
      .catch(() => setPoints([]))
      .finally(() => setLoading(false));
  }, [apiBase, deviceId, field, days]);

  if (sensors.length === 0) return null;

  const unit = field === "temperature" ? `°${tempUnit}` : (SENSOR_FIELD_UNITS[field] ?? "");
  // Backend always stores/returns Celsius -- convert for display only,
  // same on-the-fly conversion fmtTemp() already does for current values.
  const toDisplay = (v) => v == null ? null : (field === "temperature" && tempUnit === "F" ? v * 9 / 5 + 32 : v);
  const plotted = points.filter(p => p.avg != null);
  const displayValues = plotted.map(p => toDisplay(p.avg));
  const chartMin = displayValues.length ? Math.min(...displayValues) : 0;
  const chartMax = displayValues.length ? Math.max(...displayValues) : 1;
  const range = chartMax - chartMin || 1;
  const chartW = 560, chartH = 100, pad = 8;
  const pathD = plotted.map((p, i) => {
    const x = plotted.length > 1 ? pad + (i / (plotted.length - 1)) * (chartW - pad * 2) : chartW / 2;
    const y = chartH - pad - ((toDisplay(p.avg) - chartMin) / range) * (chartH - pad * 2);
    return `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");

  const downloadCsv = () => {
    const header = "date,avg,min,max,samples\n";
    const rows = points.map(p => [
      new Date(p.day).toLocaleDateString(),
      p.avg != null ? toDisplay(p.avg).toFixed(2) : "",
      p.min != null ? toDisplay(p.min).toFixed(2) : "",
      p.max != null ? toDisplay(p.max).toFixed(2) : "",
      p.sampleCount,
    ].join(",")).join("\n");
    const blob = new Blob([header + rows], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${deviceId}-${field}-${days}d.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="sensor-history-panel">
      <div className="sensor-history-header">Sensor History</div>
      <div className="sensor-history-controls">
        <label className="dm-toolbar-select">Sensor
          <select value={deviceId} onChange={e => setDeviceId(e.target.value)}>
            {sensors.map(d => <option key={d.deviceId} value={d.deviceId}>{d.name}</option>)}
          </select>
        </label>
        <label className="dm-toolbar-select">Field
          <select value={field} onChange={e => setField(e.target.value)}>
            {fieldOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        <label className="dm-toolbar-select">Range
          <select value={days} onChange={e => setDays(Number(e.target.value))}>
            <option value={7}>7 days</option>
            <option value={30}>30 days</option>
            <option value={60}>60 days</option>
            <option value={90}>90 days</option>
          </select>
        </label>
        <button className="btn-ghost" onClick={downloadCsv} disabled={points.length === 0}>Download CSV</button>
      </div>
      {loading && <p className="config-hint">Loading…</p>}
      {!loading && points.length === 0 && (
        <p className="config-hint">No {field} history for this sensor in the last {days} days.</p>
      )}
      {!loading && points.length > 0 && (
        <>
          {plotted.length > 1 && (
            <svg viewBox={`0 0 ${chartW} ${chartH}`} className="sensor-history-chart">
              <path d={pathD} fill="none" stroke="var(--accent, #58a6ff)" strokeWidth="2"/>
            </svg>
          )}
          <div className="sensor-history-table-wrap">
            <table className="sensor-history-table">
              <thead><tr><th>Date</th><th>Avg</th><th>Min</th><th>Max</th><th>Samples</th></tr></thead>
              <tbody>
                {[...points].reverse().map(p => (
                  <tr key={p.day}>
                    <td>{new Date(p.day).toLocaleDateString()}</td>
                    <td>{p.avg != null ? `${toDisplay(p.avg).toFixed(1)}${unit}` : "—"}</td>
                    <td>{p.min != null ? `${toDisplay(p.min).toFixed(1)}${unit}` : "—"}</td>
                    <td>{p.max != null ? `${toDisplay(p.max).toFixed(1)}${unit}` : "—"}</td>
                    <td>{p.sampleCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

// 2026-08-25: devices is now the full, user-ordered list (from
// useDraggableOrder in MnSeeView), not raw API order -- filtering it here
// preserves that order within this location's subset, which is what
// makes "reorder" actually affect the real grid instead of only a
// separate list nothing else reads. reorderMode/dragIdx/overIdx/onDrag*
// are undefined outside reorder mode, same as KpiTile's own defaults.
function LocationMonitoringSection({ locCfg, devices, active, reorderMode, dragIdx, overIdx, pinnedCount,
    onDragStart, onDragOver, onDrop, onDragEnd }) {
  const liveMessages = useMqttTelemetry(active, locCfg.wsBase);
  const [tempUnit, toggleTempUnit] = useTempUnit();

  const kpiEntries = devices
    .map((d, globalIdx) => ({ d, globalIdx }))
    .filter(({ d }) => !d.location || d.location === locCfg.id)
    .map(({ d, globalIdx }) => ({ globalIdx, tile: kpiTileFor(d, tempUnit) }))
    .filter(({ tile }) => tile !== null);

  return (
    <div className="location-section">
      <div className="location-section-header">
        {locCfg.label}
        <button className="btn-ghost btn-ghost-sm" onClick={toggleTempUnit} title="Toggle °F / °C">
          °{tempUnit}
        </button>
      </div>

      <div className={`kpi-grid ${reorderMode ? "reorder-mode" : ""}`}>
        {kpiEntries.map(({ globalIdx, tile }) => {
          const isPinned = pinnedCount != null && globalIdx < pinnedCount;
          return (
            <KpiTile key={tile.deviceId} {...tile}
              reorderMode={reorderMode}
              isPinned={isPinned}
              isOver={reorderMode && overIdx === globalIdx && dragIdx !== globalIdx}
              onDragStart={reorderMode && !isPinned ? onDragStart?.(globalIdx) : undefined}
              onDragOver={reorderMode ? onDragOver?.(globalIdx) : undefined}
              onDrop={reorderMode ? onDrop?.(globalIdx) : undefined}
              onDragEnd={reorderMode ? onDragEnd : undefined}
            />
          );
        })}
      </div>

      <SensorHistoryPanel devices={devices.filter(d => !d.location || d.location === locCfg.id)}
        apiBase={locCfg.apiBase} tempUnit={tempUnit} />

      <CameraHealthPanel locCfg={locCfg} />

      <div className="event-log">
        <div className="event-log-header">Live MQTT — {locCfg.label}</div>
        {liveMessages.length === 0 && (
          <div className="event-log-empty">No live messages from {locCfg.wsBase}</div>
        )}
        {liveMessages.map((m, i) => (
          <div key={i} className="event-row">
            <span className="event-ts">{new Date(m.ts).toLocaleTimeString()}</span>
            <span className="event-body">{JSON.stringify(m)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

const MN_VIEWS = [
  { id: "see",    label: "See",    icon: Eye },
  { id: "change", label: "Change", icon: Edit2 },
  { id: "add",    label: "Add",    icon: Plus },
  { id: "remove", label: "Remove", icon: Minus },
];

function MonitoringPanel({ active }) {
  const { devices, activeLocation, activeProfile } = useApp();
  const [view, setView]               = useState("see");
  const [selected, setSelected]       = useState(null);
  const [reorderMode, setReorderMode] = useState(false);

  const handleViewChange = (v) => { setView(v); setSelected(null); setReorderMode(false); };

  return (
    <div className="panel-content">
      <AlertControls panelId="MONITORING" />
      <div className="panel-header-bar">
        <h2>Monitoring</h2>
        <div className="header-actions">
          {view === "see" && (
            <button
              className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
              onClick={() => setReorderMode(r => !r)}>
              <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
            </button>
          )}
          <span className={`ws-indicator ${active ? "ws-live" : "ws-off"}`}>
            {active ? <><Wifi size={12}/> Live</> : <><WifiOff size={12}/> Docked</>}
          </span>
        </div>
      </div>

      <div className="dm-l1-nav">
        {MN_VIEWS.map(v => {
          const Icon = v.icon;
          return (
            <button key={v.id}
              className={`dm-l1-btn ${view === v.id ? "dm-l1-active" : ""}`}
              onClick={() => handleViewChange(v.id)}>
              <Icon size={15}/> {v.label}
            </button>
          );
        })}
        <span className="mn-profile-hint">
          <MapPin size={11}/> {PROFILE_LABELS[activeProfile] || activeProfile}
        </span>
      </div>

      {view === "see"    && <MnSeeView devices={devices} activeLocation={activeLocation} active={active} reorderMode={reorderMode} />}
      {view === "change" && <MnChangeView devices={devices} selected={selected} onSelect={setSelected} />}
      {view === "add"    && <MnChangeView devices={devices} selected={selected} onSelect={setSelected} />}
      {view === "remove" && <MnRemoveView />}
    </div>
  );
}

// 2026-08-25: reordering now happens directly on the real grid tiles
// (LocationMonitoringSection/KpiTile) instead of a separate vertical
// list -- the list rendered a different layout than what you actually
// see day to day, so dragging in it couldn't show you real left-right
// grid position. This view always renders the same location-split grid;
// reorderMode only toggles whether drag handlers are attached, matching
// how KpiTile/LocationMonitoringSection already gate on it.
export function MnSeeView({ devices, activeLocation, active, reorderMode }) { // exported for src/App.test.jsx's Monitoring reorder tests
  const [dragIdx, setDragIdx] = useState(null);
  const [overIdx, setOverIdx] = useState(null);

  const isAlarm = useCallback((d) => d.state === "ALARM" || d.state === "CRITICAL", []);
  const locDevices = activeLocation === "both"
    ? devices
    : devices.filter(d => !d.location || d.location === activeLocation);

  const { ordered, pinnedCount, reorder } = useDraggableOrder(
    `order.monitoring.${activeLocation}`, locDevices, isAlarm
  );

  const onDragStart = (idx) => (e) => { setDragIdx(idx); e.dataTransfer.effectAllowed = "move"; };
  const onDragOver  = (idx) => (e) => { e.preventDefault(); setOverIdx(idx); };
  const onDrop      = (idx) => (e) => {
    e.preventDefault();
    if (dragIdx !== null) reorder(dragIdx, idx);
    setDragIdx(null); setOverIdx(null);
  };
  const onDragEnd = () => { setDragIdx(null); setOverIdx(null); };

  const locs = activeLocation === "both"
    ? [LOCATIONS.cabin, LOCATIONS.home]
    : [LOCATIONS[activeLocation] || LOCATIONS.cabin];
  return (
    <div className={activeLocation === "both" ? "monitoring-split" : ""}>
      {locs.map(loc => (
        <LocationMonitoringSection key={loc.id} locCfg={loc} devices={ordered} active={active}
          reorderMode={reorderMode} dragIdx={dragIdx} overIdx={overIdx} pinnedCount={pinnedCount}
          onDragStart={onDragStart} onDragOver={onDragOver} onDrop={onDrop} onDragEnd={onDragEnd}
        />
      ))}
      {reorderMode && ordered.length === 0 && (
        <div className="empty-state"><Activity size={36} opacity={0.3}/><p>No devices for this location.</p></div>
      )}
    </div>
  );
}

function MnChangeView({ devices, selected, onSelect }) {
  const { activeProfile, refreshDisplayConfigs } = useApp();
  const sel = selected ? devices.find(d => d.deviceId === selected) : null;

  return (
    <div className="dm-layout">
      <div className="dm-list">
        <div className="dm-health-bar" style={{ fontSize: 11, opacity: 0.65 }}>
          Configure display overrides for profile:&nbsp;<strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>
        </div>
        {devices.map(d => (
          <DmDeviceRow key={d.deviceId} device={d}
            selected={selected === d.deviceId}
            onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)} />
        ))}
        {devices.length === 0 && (
          <div className="empty-state"><Cpu size={36} opacity={0.3}/><p>No devices.</p></div>
        )}
      </div>
      {sel && (
        <div className="dm-detail">
          <DisplayConfigForm device={sel} profile={activeProfile} onSaved={refreshDisplayConfigs} />
        </div>
      )}
    </div>
  );
}

function MnRemoveView() {
  const { activeProfile, displayConfigs, refreshDisplayConfigs, devices } = useApp();
  const [removing, setRemoving] = useState(null);
  const configured = Object.values(displayConfigs);

  const doRemove = async (cfg) => {
    setRemoving(cfg.deviceId);
    const apiBase = cfg.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
    await fetch(`${apiBase}/api/devices/${cfg.deviceId}/display-config?profile=${activeProfile}`,
      { method: "DELETE" }).catch(() => {});
    setRemoving(null);
    refreshDisplayConfigs();
  };

  if (configured.length === 0) {
    return (
      <div className="empty-state" style={{ marginTop: 24 }}>
        <Activity size={36} opacity={0.3}/>
        <p>No display overrides configured for<br/><strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>.</p>
      </div>
    );
  }

  return (
    <div className="dm-remove-list">
      <p className="config-hint" style={{ margin: "10px 14px" }}>
        Display configs for profile: <strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>
      </p>
      {configured.map(cfg => {
        const dev = devices.find(d => d.deviceId === cfg.deviceId);
        return (
          <div key={cfg.deviceId} className="dm-remove-row">
            <div className="dm-remove-info">
              <strong>{cfg.displayName || dev?.name || cfg.deviceId}</strong>
              <span className="config-hint">{cfg.deviceId} · {cfg.presenceProfile}</span>
              {cfg.severityOverride && (
                <span className="health-chip">Override: {cfg.severityOverride}</span>
              )}
            </div>
            <button className="btn-danger" disabled={removing === cfg.deviceId}
              onClick={() => doRemove(cfg)}>
              <Trash2 size={14}/> {removing === cfg.deviceId ? "Removing…" : "Remove"}
            </button>
          </div>
        );
      })}
    </div>
  );
}

function DisplayConfigForm({ device, profile, onSaved }) {
  const { displayConfigs } = useApp();
  const existing = displayConfigs?.[device.deviceId];

  const [displayName,      setDisplayName]      = useState(existing?.displayName || "");
  const [severityOverride, setSeverityOverride] = useState(existing?.severityOverride || "");
  const [labelMap,         setLabelMap]         = useState(existing?.stateLabelMap || {});
  const [newKey,  setNewKey]  = useState("");
  const [newVal,  setNewVal]  = useState("");
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);

  // Sync form when device or existing config changes
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    setDisplayName(existing?.displayName || "");
    setSeverityOverride(existing?.severityOverride || "");
    setLabelMap(existing?.stateLabelMap || {});
    setSaved(false);
  }, [device.deviceId, JSON.stringify(existing)]);

  const save = async () => {
    setSaving(true);
    const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
    await fetch(`${apiBase}/api/devices/${device.deviceId}/display-config?profile=${profile}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ displayName, stateLabelMap: labelMap, severityOverride }),
    }).catch(() => {});
    setSaving(false);
    setSaved(true);
    onSaved();
    setTimeout(() => setSaved(false), 2000);
  };

  const addLabel = () => {
    if (!newKey.trim()) return;
    setLabelMap(m => ({ ...m, [newKey.trim()]: newVal }));
    setNewKey(""); setNewVal("");
  };
  const removeLabel = (k) => setLabelMap(m => { const n = { ...m }; delete n[k]; return n; });

  return (
    <div className="dm-edit-form">
      <div className="dm-detail-name">{device.name || device.deviceId}</div>
      <p className="config-hint">Display overrides for: <strong>{PROFILE_LABELS[profile] || profile}</strong></p>

      <label>Custom display name
        <input placeholder={device.name} value={displayName}
          onChange={e => { setDisplayName(e.target.value); setSaved(false); }} />
      </label>

      <label>Severity override
        <select value={severityOverride}
          onChange={e => { setSeverityOverride(e.target.value); setSaved(false); }}>
          <option value="">Default (auto from status)</option>
          <option value="OK">OK — green</option>
          <option value="WARN">Warn — orange</option>
          <option value="ALERT">Alert — red</option>
        </select>
      </label>

      <div className="dm-section-label">State label overrides</div>
      <p className="config-hint" style={{ marginBottom: 6 }}>
        Map raw status → display label (e.g. ONLINE → "Unlocked")
      </p>

      {Object.entries(labelMap).map(([k, v]) => (
        <div key={k} className="dm-label-row">
          <code className="dm-label-key">{k}</code>
          <span>→</span>
          <input value={v} onChange={e => setLabelMap(m => ({ ...m, [k]: e.target.value }))} />
          <button className="btn-ghost" onClick={() => removeLabel(k)}><Trash2 size={13}/></button>
        </div>
      ))}

      <div className="dm-label-row dm-label-add">
        <input placeholder="State (e.g. ONLINE)" value={newKey}
          onChange={e => setNewKey(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addLabel()} />
        <span>→</span>
        <input placeholder="Label (e.g. Unlocked)" value={newVal}
          onChange={e => setNewVal(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addLabel()} />
        <button className="btn-ghost" onClick={addLabel}><Plus size={13}/></button>
      </div>

      <div className="modal-actions">
        {saved && <span className="save-ok"><CheckCircle size={13}/> Saved</span>}
        <button className="btn-primary" onClick={save} disabled={saving}>
          {saving ? "Saving…" : "Save overrides"}
        </button>
      </div>
    </div>
  );
}

function severityClass(override) {
  return { OK: "state-ok", WARN: "state-warn", ALERT: "state-alarm" }[override] || null;
}

// reorderMode/isPinned/isOver/onDrag* (added 2026-08-25) let a tile be
// dragged directly in the real grid -- same drag-and-drop shape
// MnSeeView already built for the old separate list view, just wired
// onto the actual tile instead. All undefined/false outside reorder
// mode, so normal (non-reorder) rendering is untouched.
function KpiTile({ icon: Icon, label, value, state, deviceId, reorderMode, isPinned, isOver,
    onDragStart, onDragOver, onDrop, onDragEnd }) {
  const { displayConfigs } = useApp();
  const cfg = deviceId ? displayConfigs?.[deviceId] : null;

  const effectiveLabel = cfg?.displayName || label;
  const effectiveValue = cfg?.stateLabelMap?.[state] || cfg?.stateLabelMap?.[value] || value;
  const stCls          = severityClass(cfg?.severityOverride) || stateColor(state);
  const badgeLabel     = cfg?.stateLabelMap?.[state] || state || "UNKNOWN";

  return (
    <div
      className={`kpi-tile kpi-${stCls} ${reorderMode ? "reorder-card" : ""} ${isOver ? "drag-over-card" : ""}`}
      draggable={reorderMode && !isPinned}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDrop={onDrop}
      onDragEnd={onDragEnd}
    >
      {reorderMode && (isPinned
        ? <Lock size={13} className="auto-pin-icon" title="Auto-pinned: alarm active"/>
        : <GripVertical size={13} className="drag-handle"/>)}
      <Icon size={22} />
      <div className="kpi-label">{effectiveLabel}</div>
      <div className="kpi-value">{effectiveValue}</div>
      <span className={`state-badge ${stCls}`}>{badgeLabel}</span>
    </div>
  );
}

// ─── Panel: Rules Engine ───────────────────────────────────────────────────
// Per-location Node-RED section — mirrors LocationMonitoringSection's
// split pattern so "Both" behaves the same way here as everywhere else
// in the app (user's explicit ask, 2026-08-08: "same context shift
// behavior for all locations... I only see one node red"). "All can live
// on the 920q for now" (user's words) — this doesn't stand up a second
// Node-RED instance, it just makes the UI honest about which flows it's
// actually showing: a location without its own configured instance falls
// back to Cabin's, labeled as a fallback rather than silently presented
// as if it were Home's own.
function LocationRulesSection({ locCfg }) {
  // Cabin is always the canonical/fallback source, never a fallback target.
  const isCabin = locCfg.id === "cabin";
  const hasOwnNodeRed = isCabin || (isLocationDeployed(locCfg) && !!locCfg.noderedUrl);
  const noderedUrl = hasOwnNodeRed ? locCfg.noderedUrl : LOCATIONS.cabin.noderedUrl;
  // Lazy-mounted, 2026-08-24 -- Node-RED's own editor/admin API run with no
  // auth configured on this instance (adminAuth/httpNodeAuth both unset,
  // confirmed live) and it sends no framing-protection headers either, so
  // an always-present iframe pointed at a private-LAN URL means every
  // browser that opens this panel gets Chrome's Local Network Access
  // prompt whether or not anyone actually wants the embed -- reported
  // directly by a resident on a different network. Mounting src only on
  // an explicit click means a browser that never opens this section never
  // sees the prompt at all. This is containment, not a fix -- the real
  // gap (no auth on Node-RED itself) is a separate, live-infra change
  // that needs its own explicit go-ahead before touching a running
  // instance; see this session's plan file, Item 2.
  const [loadRequested, setLoadRequested] = useState(false);
  return (
    <div className="location-section rules-nodered">
      <div className="location-section-header">
        {locCfg.label} Automation Flows
        <a href={noderedUrl} target="_blank" rel="noreferrer" className="btn-ghost btn-ghost-sm">
          Open ↗
        </a>
      </div>
      {!hasOwnNodeRed && (
        <p className="config-hint">
          {locCfg.label} doesn't have its own Node-RED instance configured yet — showing Cabin's flows.
        </p>
      )}
      <div className="tailscale-hint">
        <Lock size={11} /> Won't load off Tailscale — the flow editor is cabin-network-only.
      </div>
      {loadRequested ? (
        <iframe title={`Node-RED — ${locCfg.label}`} src={noderedUrl} className="embed-frame" />
      ) : (
        <div className="nodered-lazy-placeholder">
          <p className="config-desc">
            Node-RED isn't loaded here yet. Your browser may ask permission to reach devices on this
            network when you load it — that's expected for an embed like this.
          </p>
          <button className="btn-secondary" onClick={() => setLoadRequested(true)}>
            Load Node-RED
          </button>
        </div>
      )}
    </div>
  );
}

export function RulesPanel({ auth }) { // exported for src/App.test.jsx's location-split test
  const { activeLocation, workflows, refreshWorkflows, devices } = useApp();
  const locationIds = Object.keys(LOCATIONS);
  const locs = activeLocation === "both"
    ? locationIds.map(id => LOCATIONS[id])
    : [LOCATIONS[activeLocation] || LOCATIONS.cabin];

  return (
    <div className="panel-content">
      <AlertControls panelId="RULES_ENGINE" />
      <div className="panel-header-bar">
        <h2>Rules &amp; Alerts</h2>
      </div>
      <ActiveConditionsCard />
      <AutomationAlertCard />
      <div className="rules-layout">
        <div className={locs.length > 1 ? "rules-nodered-split" : "rules-nodered-single"}>
          {locs.map(loc => <LocationRulesSection key={loc.id} locCfg={loc} />)}
        </div>
        <div className="rules-sidebar">
          <KafkaStatus location={activeLocation} />
          <WorkflowRulesCard workflows={workflows} auth={auth} devices={devices} activeLocation={activeLocation}
            defaultLocation={activeLocation !== "both" ? activeLocation : "cabin"} onChanged={refreshWorkflows} />
          <BuiltinRules location={activeLocation} />
        </div>
      </div>
    </div>
  );
}

function ActiveConditionsCard() {
  const {
    activeAlerts = [], activeAlertLocations = [], activeLocation = "cabin",
  } = useApp();
  const locationAvailable = activeLocation === "both"
    ? activeAlertLocations.length > 0
    : activeAlertLocations.includes(activeLocation);
  if (!locationAvailable) return null;

  const visibleAlerts = activeLocation === "both"
    ? activeAlerts
    : activeAlerts.filter(alert => alert.location === activeLocation);
  if (visibleAlerts.length === 0) return null;

  return (
    <section className="active-conditions" aria-label="Current active alert conditions">
      <div className="active-conditions-header">
        <strong>Current conditions</strong>
        <span>{visibleAlerts.length}</span>
      </div>
      {visibleAlerts.map(alert => (
        <div className={`active-condition active-condition-${(alert.severity || "warn").toLowerCase()}`} key={alert.alertId}>
          <AlertTriangle size={15} />
          <div>
            <strong>{alert.title}</strong>
            <p>{alert.detail}</p>
            <span>{alert.location} · {alert.condition.replaceAll("_", " ").toLowerCase()}</span>
          </div>
        </div>
      ))}
    </section>
  );
}

// Found 2026-08-11 (user report, comparing the real product against
// impressive.llc's marketing site): the site shows a polished See/Think/Act
// water-pressure alert card, but the real app had no equivalent -- Rules &
// Alerts was just a Node-RED iframe link plus a sidebar list. This card is
// the first real one, backed by AutomationRuleService's now-real
// AUTOMATION_ALERT events (see docs/ontology.yaml's
// automation_alert_see_think_act entity for the full backend-to-UI trace).
//
// Broadened 2026-08-21: WorkflowRuleService.publishNotification() reuses
// this exact same {see,think,act,tags,ruleId} payload shape for its own
// WORKFLOW_ACTION/WORKFLOW_UNCONFIRMED events (docs/ontology.yaml's
// notify_critical entity), but until now this card only ever queried
// eventTypePrefix=AUTOMATION_ALERT -- every workflow-engine-driven alert
// (leak shutoff, camera-detection notify, unconfirmed commands) was
// invisible here even though the backend already narrates it identically.
// Also was hardcoded to LOCATIONS.cabin and limit=1 -- now follows
// activeLocation (same per-location fetch shape as refreshWorkflows) and
// shows a real recent list, not just the single latest.
export function humanizeRuleId(ruleId) {
  if (!ruleId) return "Alert";
  if (ruleId.startsWith("WORKFLOW_UNCONFIRMED_")) return "Workflow Unconfirmed";
  if (ruleId.startsWith("WORKFLOW_")) return "Workflow";
  return ruleId.split("_").map(w => w[0] + w.slice(1).toLowerCase()).join(" ");
}

export function automationAlertSteps(alert) {
  const { ruleId, act, see } = alert.payload || {};
  const critical = alert.severity === "CRITICAL";
  return [
    { label: "SEE", headline: humanizeRuleId(ruleId), detail: "Sensor reports change" },
    { label: "THINK", headline: critical ? "No routine explains it" : "An ordinary explanation exists",
      detail: "Presence checked" },
    { label: "ACT", headline: act || "Logged only",
      detail: critical ? "CRITICAL event published; delivery depends on the configured channel" : "Logged, no push" },
  ];
}

const AUTOMATION_ALERT_EVENT_PREFIXES = "AUTOMATION_ALERT,WORKFLOW_ACTION,WORKFLOW_UNCONFIRMED";

function AutomationAlertCard() {
  const { activeLocation } = useApp();
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    // Same "cabin or both" / "home or both" attempt shape as
    // refreshWorkflows -- an unrecognized/missing activeLocation falls
    // back to cabin-only, matching RulesPanel's own LOCATIONS[activeLocation]
    // || LOCATIONS.cabin default just above where this card is rendered.
    const loc = activeLocation === "both" ? "both" : (LOCATIONS[activeLocation] ? activeLocation : "cabin");
    const attempts = [];
    if (loc === "cabin" || loc === "both") {
      attempts.push(fetch(`${LOCATIONS.cabin.apiBase}/api/events?eventTypePrefix=${AUTOMATION_ALERT_EVENT_PREFIXES}&limit=5&window=24h`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    if (loc === "home" || loc === "both") {
      attempts.push(fetch(`${LOCATIONS.home.apiBase}/api/events?eventTypePrefix=${AUTOMATION_ALERT_EVENT_PREFIXES}&limit=5&window=24h`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    Promise.all(attempts)
      .then(results => {
        if (cancelled) return;
        const merged = results.flat().sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
        setAlerts(merged.slice(0, 5));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [activeLocation]);

  if (loading) return null;
  if (alerts.length === 0) {
    return (
      <div className="automation-alert-card automation-alert-none">
        <p className="config-desc">No automation alerts in the last 24 hours — built-in safety rules are watching.</p>
      </div>
    );
  }

  return (
    <div className="automation-alert-list">
      {alerts.map(alert => <AutomationAlertEntry key={alert.eventId} alert={alert} />)}
    </div>
  );
}

function AutomationAlertEntry({ alert }) {
  const { see, think, tags = [], ruleId } = alert.payload || {};
  const steps = automationAlertSteps(alert);

  return (
    <div className={`automation-alert-card automation-alert-${(alert.severity || "info").toLowerCase()}`}>
      <div className="automation-alert-header">
        <span className="automation-alert-category">{humanizeRuleId(ruleId).toUpperCase()}</span>
        <span className="automation-alert-time">{new Date(alert.timestamp).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</span>
      </div>
      <h3 className="automation-alert-headline">{see}</h3>
      {think && <p className="automation-alert-context">{think}</p>}
      {tags.length > 0 && (
        <div className="automation-alert-tags">
          {tags.map(t => <span key={t} className="automation-alert-tag">{t}</span>)}
        </div>
      )}
      <div className="automation-alert-flow">
        {steps.map((step, i) => (
          <React.Fragment key={step.label}>
            {i > 0 && <span className="automation-alert-flow-arrow">→</span>}
            <div className="automation-alert-flow-step">
              <span className="automation-alert-flow-num">{String(i + 1).padStart(2, "0")} · {step.label}</span>
              <strong>{step.headline}</strong>
              <span className="config-hint">{step.detail}</span>
            </div>
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}

function KafkaStatus({ location }) {
  const loc = location === "both" ? "cabin + home" : (location || "cabin");
  const prefix = location === "home" ? "home" : "cabin";
  return (
    <div className="sidebar-card">
      <strong>Kafka Topics — {loc}</strong>
      <div className="kafka-topics">
        {[`${prefix}.events.raw`, `${prefix}.events.alerts`, `${prefix}.events.motion`].map(t => (
          <div key={t} className="kafka-topic">
            <span className="topic-dot">●</span>{t}
          </div>
        ))}
      </div>
      <p className="config-hint">Node-RED consumes {prefix}.events.raw. Broker: localhost:9092.</p>
    </div>
  );
}

// The real, persisted trigger->action rules engine (WorkflowRuleService,
// GET /api/rules/workflows) had zero frontend surface anywhere before
// 2026-08-18 -- BuiltinRules below shows AutomationRuleService's older,
// separate, hardcoded-in-Java rule catalog, not this one. Was read-only
// ("creating and editing a workflow is a real form-design task of its own,
// tracked separately" per that commit's own comment) until this change
// (2026-08-20) added WorkflowCreateForm below plus per-row activate/
// deactivate/delete, reusing CameraNotifyToggle's already-proven
// create-then-activate / delete API sequence rather than inventing a new
// one. See workflowsForDevice for how a device row's own badge
// (DmDeviceRow) cross-references this same data.
//
// The trigger/action option lists used to be hardcoded here to exactly
// what WorkflowRuleService interprets -- replaced 2026-08-21 with a real
// fetch from GET /api/rules/vocabulary/triggers|actions (RulesController),
// backed by JdbcWorkflowVocabularyStore's seeded rows (the exact same set
// this file used to hardcode) plus candidate entries merged in live from
// docs/ontology.yaml (OntologyLookupService). The user's own ask: this
// should "trace back to the ontology from a DB table owned by the
// system," not a JS constant with no connection to either.
//
// supported:false (candidate) entries are still rendered, deliberately --
// as disabled options with an explanatory suffix, not hidden -- so a
// person can see what's designed but not yet buildable (the hardware
// backlog: entry light, deterrent plug, RF tripwire, spare siren, water
// heater) instead of the option silently not existing. Never selectable,
// so the "narrower-but-genuinely-functional over broader-but-fake"
// guarantee this form always had is unchanged.
function useWorkflowVocabulary(apiBase) {
  const [triggers, setTriggers] = useState([]);
  const [actions, setActions] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      fetch(`${apiBase}/api/rules/vocabulary/triggers`).then(r => r.ok ? r.json() : []).catch(() => []),
      fetch(`${apiBase}/api/rules/vocabulary/actions`).then(r => r.ok ? r.json() : []).catch(() => []),
    ]).then(([t, a]) => {
      if (cancelled) return;
      setTriggers(Array.isArray(t) ? t : []);
      setActions(Array.isArray(a) ? a : []);
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [apiBase]);

  return { triggers, actions, loading };
}

// privileged actions (server-enforced by RulesController.validateReopenGuard(),
// this is a UI convenience only) are excluded from a DEVICE_EVENT workflow's
// picker so the form can't offer a combination guaranteed to be rejected on
// save; unsupported (candidate) actions stay in the list but disabled.
function actionsFor(actions, triggerKind) {
  return triggerKind === "MANUAL" ? actions : actions.filter(a => !a.privileged);
}

function newActionRow(actions, triggerKind) {
  const firstSelectable = actionsFor(actions, triggerKind).find(a => a.supported);
  return {
    key: `a${Date.now()}${Math.random().toString(36).slice(2, 6)}`,
    actionDefinitionId: firstSelectable?.id || "", targetDeviceId: "", cooldownSeconds: "",
  };
}

function WorkflowCreateForm({ auth, devices, defaultLocation, onCreated, onCancel }) {
  const [location, setLocation] = useState(defaultLocation);
  const apiBase = LOCATIONS[location]?.apiBase || LOCATIONS.cabin.apiBase;
  const { triggers, actions, loading: vocabLoading } = useWorkflowVocabulary(apiBase);
  const [name, setName] = useState("");
  const [triggerKind, setTriggerKind] = useState("DEVICE_EVENT");
  const [triggerDefinitionId, setTriggerDefinitionId] = useState("");
  const [triggerDeviceId, setTriggerDeviceId] = useState("");
  const [resetMode, setResetMode] = useState("AUTO_ON_CLEAR");
  const [actionRows, setActionRows] = useState(null); // null until vocabulary loads
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const availableActions = actionsFor(actions, triggerKind);

  // Seed the first-selectable defaults once the real vocabulary arrives --
  // can't do this at useState-init time since triggers/actions start empty.
  useEffect(() => {
    if (vocabLoading) return;
    setTriggerDefinitionId(prev => prev || triggers.find(t => t.supported)?.id || "");
    setActionRows(prev => prev || [newActionRow(actions, "DEVICE_EVENT")]);
  }, [vocabLoading, triggers, actions]); // eslint-disable-line react-hooks/exhaustive-deps

  const changeTriggerKind = (kind) => {
    setTriggerKind(kind);
    // A privileged or now-unsupported action already picked while switching
    // away from MANUAL would silently fail (or never fire) on save.
    setActionRows(rows => (rows || []).map(r =>
      actionsFor(actions, kind).some(a => a.id === r.actionDefinitionId && a.supported)
        ? r : { ...r, actionDefinitionId: actionsFor(actions, kind).find(a => a.supported)?.id || "" }));
  };

  const updateAction = (key, patch) =>
    setActionRows(rows => (rows || []).map(r => (r.key === key ? { ...r, ...patch } : r)));

  const selectedTrigger = triggers.find(t => t.id === triggerDefinitionId);
  // Filters the device-scoping picker to devices of the selected trigger's
  // own type when the vocabulary knows one (e.g. only water-leak sensors
  // for "Water leak detected") -- purely a UI aid using ontology metadata,
  // WorkflowRuleService itself still matches by event payload, not device
  // type. Falls back to every visible device when the vocabulary doesn't
  // say (or a device's own type is missing), same as before this change.
  const triggerScopedDevices = selectedTrigger?.appliesToDeviceType
    ? devices.filter(d => !d.type || d.type === selectedTrigger.appliesToDeviceType)
    : devices;

  const submit = async (e) => {
    e.preventDefault();
    if (!name.trim() || !actionRows || actionRows.length === 0) return;
    setSaving(true);
    setError(null);
    try {
      const workflowId = `wf-${name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "")}-${Date.now()}`;
      const res = await auth.authedFetch(`${apiBase}/api/rules/workflows`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          workflowId, name: name.trim(), location, triggerKind,
          triggerDefinitionId: triggerKind === "MANUAL" ? null : triggerDefinitionId,
          triggerDeviceId: triggerKind === "MANUAL" ? null : (triggerDeviceId || null),
          enabled: false, resetMode: triggerKind === "MANUAL" ? "MANUAL_ONLY" : resetMode, parentWorkflowId: null,
          actions: actionRows.map((r, i) => {
            const def = actions.find(a => a.id === r.actionDefinitionId);
            return {
              actionId: `${workflowId}-a${i}`, stepOrder: i, actionDefinitionId: r.actionDefinitionId,
              // An instance-specific action (a fixed targetDeviceId in the
              // vocabulary, e.g. "the" main valve) always commands that
              // device -- the row's own free-picker value is for a
              // type-generic action only and is ignored otherwise, closing
              // the mistargeting risk a free picker had for this case.
              targetDeviceId: def?.targetDeviceId || (def?.needsTarget ? (r.targetDeviceId || null) : null),
              cooldownSeconds: r.cooldownSeconds === "" ? null : Number(r.cooldownSeconds),
            };
          }),
        }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.error) throw new Error(body.error || `HTTP ${res.status}`);
      onCreated();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  if (vocabLoading || actionRows === null) {
    return <p className="config-hint">Loading available triggers and actions…</p>;
  }

  return (
    <form className="add-place-form workflow-create-form" onSubmit={submit}>
      <div className="add-place-grid">
        <label className="add-place-field">
          Name
          <input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Leak shutoff — mech room" required />
        </label>
        <label className="add-place-field">
          Location
          <select value={location} onChange={e => setLocation(e.target.value)}>
            {Object.values(LOCATIONS).map(loc => <option key={loc.id} value={loc.id}>{loc.label}</option>)}
          </select>
        </label>
        <label className="add-place-field">
          When
          <select value={triggerKind} onChange={e => changeTriggerKind(e.target.value)}>
            <option value="DEVICE_EVENT">A device reports something</option>
            <option value="MANUAL">A person triggers it</option>
          </select>
        </label>
        {triggerKind === "DEVICE_EVENT" && (
          <>
            <label className="add-place-field">
              Specifically
              <select value={triggerDefinitionId} onChange={e => setTriggerDefinitionId(e.target.value)}>
                {triggers.map(t => (
                  <option key={t.id} value={t.id} disabled={!t.supported}>
                    {t.label}{!t.supported ? " — not available yet" : ""}
                  </option>
                ))}
              </select>
            </label>
            <label className="add-place-field">
              On this device (optional)
              <select value={triggerDeviceId} onChange={e => setTriggerDeviceId(e.target.value)}>
                <option value="">Any device</option>
                {triggerScopedDevices.map(d => <option key={d.deviceId} value={d.deviceId}>{d.name || d.deviceId}</option>)}
              </select>
              {triggerScopedDevices.length === 0 && (
                <span className="config-hint">
                  No matching devices registered yet on this instance — device discovery populates this list from live traffic.
                </span>
              )}
            </label>
            <label className="add-place-field">
              After it fires
              <select value={resetMode} onChange={e => setResetMode(e.target.value)}>
                <option value="AUTO_ON_CLEAR">Ready to fire again once the condition clears</option>
                <option value="MANUAL_ONLY">Stays fired until someone clears it manually</option>
              </select>
            </label>
          </>
        )}
      </div>
      {triggerKind === "DEVICE_EVENT" && (
        <p className="config-hint">
          This never resets the device itself — the actions below only run the moment this workflow
          fires, not when it clears. To restore something automatically once a condition resolves,
          build a separate workflow triggered by "Water leak cleared" (or fire a Manual workflow
          yourself, like reopening the water valve — that one's human-only, by design).
        </p>
      )}
      <div className="workflow-actions-list">
        <div className="add-place-field" style={{ marginBottom: 6 }}>Then, do this</div>
        {actionRows.map((row, i) => {
          const def = actions.find(a => a.id === row.actionDefinitionId);
          // An instance-specific action (a fixed targetDeviceId in the
          // vocabulary -- today, "the" one main water valve, not "a" valve
          // of a class, see action_main_water_valve_off/_open's own
          // docs/ontology.yaml notes) locks the device instead of offering
          // a free picker: nothing before this stopped a person from
          // pointing this action at an unrelated device.
          const lockedDeviceLabel = def?.targetDeviceId
            ? (devices.find(d => d.deviceId === def.targetDeviceId)?.name || def.targetDeviceId)
            : null;
          return (
            <div key={row.key} className="workflow-action-row">
              <select value={row.actionDefinitionId} onChange={e => updateAction(row.key, { actionDefinitionId: e.target.value })}>
                {availableActions.map(a => (
                  <option key={a.id} value={a.id} disabled={!a.supported}>
                    {a.label}{!a.supported ? " — not available yet" : ""}
                  </option>
                ))}
              </select>
              {lockedDeviceLabel ? (
                <span className="config-hint workflow-action-locked-device" title="This action always targets this specific device">
                  → {lockedDeviceLabel}
                </span>
              ) : def?.needsTarget && (
                <select value={row.targetDeviceId} onChange={e => updateAction(row.key, { targetDeviceId: e.target.value })}>
                  <option value="">Choose a device…</option>
                  {devices.map(d => <option key={d.deviceId} value={d.deviceId}>{d.name || d.deviceId}</option>)}
                </select>
              )}
              <input
                type="number" min="0" placeholder="No limit" value={row.cooldownSeconds}
                title="Don't repeat this specific action for this many seconds after it last ran — leave blank to always run it"
                onChange={e => updateAction(row.key, { cooldownSeconds: e.target.value })}
                style={{ width: 90 }}
              />
              <span className="config-hint" style={{ margin: 0 }}>sec cooldown</span>
              {actionRows.length > 1 && (
                <button type="button" className="btn-ghost" onClick={() => setActionRows(rows => rows.filter(r => r.key !== row.key))}>Remove</button>
              )}
            </div>
          );
        })}
        <button type="button" className="btn-ghost" onClick={() => setActionRows(rows => [...rows, newActionRow(actions, triggerKind)])}>+ Add another action</button>
      </div>
      {error && <p className="add-place-error">Not saved: {error}</p>}
      <p className="config-hint">Saves as a draft — it won't run until you tap Activate on it below.</p>
      <div className="add-place-actions">
        <button type="submit" className="btn-primary" disabled={saving}>{saving ? "Saving…" : "Save draft"}</button>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

// One firing of a workflow -- actionResults' shape matches
// WorkflowRuleService.executeAction()'s result map exactly (success,
// commandStatus, skipped/reason for a cooldown-suppressed action, error).
// "Reset" here is deliberately just RulesController's POST .../clear --
// bookkeeping that marks the execution row resolved, never a device
// command. It must never reopen/reverse anything: reopening the main
// valve stays human-only via the "Fire now" MANUAL-workflow path above,
// not a side effect of clearing a record (flagged by the session that
// built fireManual()/validateReopenGuard() as an easy trap to reintroduce
// from this exact angle).
function WorkflowExecutionHistory({ workflow, apiBase, auth, onCleared }) {
  const [executions, setExecutions] = useState(null); // null = not yet loaded
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    fetch(`${apiBase}/api/rules/workflows/${workflow.workflowId}/executions?limit=10`)
      .then(r => r.ok ? r.json() : [])
      .then(setExecutions)
      .catch(() => setExecutions([]));
  }, [apiBase, workflow.workflowId]);

  useEffect(() => { load(); }, [load]);

  const clear = async (executionId) => {
    setBusyId(executionId);
    setError(null);
    try {
      const res = await auth.authedFetch(`${apiBase}/api/rules/executions/${executionId}/clear`, { method: "POST" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      load();
      onCleared();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyId(null);
    }
  };

  if (executions === null) return <p className="config-hint">Loading history…</p>;
  if (executions.length === 0) return <p className="config-hint">No executions yet.</p>;

  return (
    <div className="workflow-execution-history">
      {executions.map(exec => (
        <div key={exec.executionId} className="workflow-execution-row">
          <div className="workflow-execution-meta">
            <span>{new Date(exec.firedAt).toLocaleString()}</span>
            <span className={exec.clearedAt ? "workflow-execution-cleared" : "workflow-execution-active"}>
              {exec.clearedAt ? `Cleared · ${exec.clearedBy}` : "Active"}
            </span>
          </div>
          <ul className="workflow-execution-actions">
            {(exec.actionResults || []).map((r, i) => (
              <li key={i}>
                {r.actionDefinitionId}: {r.success
                  ? (r.skipped ? `skipped (${r.reason})` : (r.commandStatus || "ok"))
                  : `failed — ${r.error}`}
              </li>
            ))}
          </ul>
          {!exec.clearedAt && auth?.signedIn && (
            <button type="button" className="btn-ghost" disabled={busyId === exec.executionId}
              onClick={() => clear(exec.executionId)} title="Marks this execution resolved -- does not undo or reverse its actions">
              Reset
            </button>
          )}
        </div>
      ))}
      {error && <p className="config-hint" style={{ color: "var(--danger, #e05555)" }}>{error}</p>}
    </div>
  );
}

function WorkflowRow({ workflow, auth, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [showHistory, setShowHistory] = useState(false);
  const apiBase = LOCATIONS[workflow.location]?.apiBase || LOCATIONS.cabin.apiBase;

  const act = async (path, method) => {
    setBusy(true);
    setError(null);
    try {
      const res = await auth.authedFetch(`${apiBase}/api/rules/workflows/${workflow.workflowId}${path}`, { method });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      onChanged();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rule-row">
      <span className={`rule-dot ${workflow.enabled ? "rule-defined" : "rule-inactive"}`}>●</span>
      <div>
        <div className="rule-name">{workflow.name}</div>
        <div className="rule-detail">
          {workflow.triggerDeviceId || workflow.triggerDefinitionId || "any trigger"}
          {" → "}
          {(workflow.actions || []).length > 0
            ? workflow.actions.map(a => a.targetDeviceId || a.actionDefinitionId).join(", ")
            : "no actions"}
        </div>
        <div className="rule-source">{workflow.location} · {workflow.enabled ? "active" : "draft"}</div>
        {auth?.signedIn && (
          <div className="workflow-row-actions">
            {workflow.triggerKind === "MANUAL" && workflow.enabled && (
              <button type="button" className="btn-ghost" disabled={busy} onClick={() => act("/fire", "POST")}
                title="Runs this workflow's actions right now">
                Fire now
              </button>
            )}
            <button type="button" className="btn-ghost" disabled={busy}
              onClick={() => act(workflow.enabled ? "/deactivate" : "/activate", "POST")}>
              {workflow.enabled ? "Deactivate" : "Activate"}
            </button>
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => act("", "DELETE")}>Delete</button>
          </div>
        )}
        <button type="button" className="btn-ghost" style={{ marginTop: 4 }} onClick={() => setShowHistory(v => !v)}>
          {showHistory ? "Hide history" : "History"}
        </button>
        {showHistory && (
          <WorkflowExecutionHistory workflow={workflow} apiBase={apiBase} auth={auth} onCleared={onChanged} />
        )}
        {error && <p className="config-hint" style={{ color: "var(--danger, #e05555)" }}>{error}</p>}
      </div>
    </div>
  );
}

// The "Recent" half of ROADMAP Phase 5's "Active→Reset, Recent→Undo"
// reductive UI item -- GET /api/rules/executions/recent (unviewed
// executions) already existed server-side with zero frontend caller
// before this. "Undo" is deliberately not offered here: there's no real
// undo primitive in this engine (reopening the valve is its own separate,
// human-only MANUAL workflow, not an automatic reversal of the close
// action) -- this button is honestly named for what POST .../view
// actually does, mark the notification acknowledged.
function RecentExecutionsList({ workflows, activeLocation, auth }) {
  const [recent, setRecent] = useState([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(() => {
    setLoading(true);
    const loc = activeLocation === "both" ? "both" : (LOCATIONS[activeLocation] ? activeLocation : "cabin");
    const attempts = [];
    if (loc === "cabin" || loc === "both") {
      attempts.push(fetch(`${LOCATIONS.cabin.apiBase}/api/rules/executions/recent`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    if (loc === "home" || loc === "both") {
      attempts.push(fetch(`${LOCATIONS.home.apiBase}/api/rules/executions/recent`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    Promise.all(attempts)
      .then(results => setRecent(results.flat().sort((a, b) => new Date(b.firedAt) - new Date(a.firedAt))))
      .finally(() => setLoading(false));
  }, [activeLocation]);

  useEffect(() => { refresh(); }, [refresh]);

  const dismiss = async (exec) => {
    const wf = workflows.find(w => w.workflowId === exec.workflowId);
    const apiBase = LOCATIONS[wf?.location]?.apiBase || LOCATIONS.cabin.apiBase;
    try {
      await auth.authedFetch(`${apiBase}/api/rules/executions/${exec.executionId}/view`, { method: "POST" });
    } catch { /* refresh() below still re-shows it if the write failed -- no silent loss */ }
    refresh();
  };

  if (loading || recent.length === 0) return null;

  return (
    <div className="workflow-recent-executions">
      <strong>Recent</strong>
      <p className="config-hint">Unviewed workflow firings.</p>
      {recent.map(exec => {
        const wf = workflows.find(w => w.workflowId === exec.workflowId);
        return (
          <div key={exec.executionId} className="rule-row">
            <span className={`rule-dot ${exec.clearedAt ? "rule-inactive" : "rule-defined"}`}>●</span>
            <div>
              <div className="rule-name">{wf?.name || exec.workflowId}</div>
              <div className="rule-detail">{new Date(exec.firedAt).toLocaleString()} · {exec.clearedAt ? "cleared" : "active"}</div>
              {auth?.signedIn && (
                <button type="button" className="btn-ghost" onClick={() => dismiss(exec)}>Mark seen</button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function WorkflowRulesCard({ workflows = [], auth, devices = [], defaultLocation = "cabin", activeLocation = "cabin", onChanged = () => {} }) {
  const [creating, setCreating] = useState(false);
  return (
    <div className="sidebar-card">
      <strong>Workflows</strong>
      <p className="config-hint">Real, persisted trigger → action rules (separate from the rules below and from Node-RED).</p>
      <RecentExecutionsList workflows={workflows} activeLocation={activeLocation} auth={auth} />
      {workflows.length === 0 && <p className="config-hint">No workflows configured yet.</p>}
      {workflows.map(w => <WorkflowRow key={w.workflowId} workflow={w} auth={auth} onChanged={onChanged} />)}
      {!creating && (
        auth?.signedIn
          ? <button type="button" className="btn-secondary" onClick={() => setCreating(true)}>+ New Workflow</button>
          : (
            <>
              <p className="config-hint">Sign in with Google (Configuration tab) to create or manage workflows.</p>
              {auth?.signIn && <button type="button" className="btn-secondary" onClick={auth.signIn}>Sign in with Google</button>}
            </>
          )
      )}
      {creating && (
        <WorkflowCreateForm
          auth={auth} devices={devices} defaultLocation={defaultLocation}
          onCreated={() => { setCreating(false); onChanged(); }}
          onCancel={() => setCreating(false)}
        />
      )}
    </div>
  );
}

function BuiltinRules({ location }) {
  const loc = location === "both" ? LOCATIONS.cabin : (LOCATIONS[location] || LOCATIONS.cabin);
  const [rules, setRules] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetch(`${loc.apiBase}/api/alerts/rules`)
      .then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
      })
      .then(list => {
        if (!cancelled) setRules(Array.isArray(list) ? list.filter(rule => rule?.ruleId) : []);
      })
      .catch(() => { if (!cancelled) setRules(null); });
    return () => { cancelled = true; };
  }, [loc.apiBase]);

  return (
    <div className="sidebar-card">
      <strong>{loc.label} Backend Rules</strong>
      <p className="config-hint">Read from the backend evaluator. These are separate from the Node-RED and Home Assistant flows.</p>
      {rules === null && <p className="config-hint">Rule catalog unavailable — no status inferred.</p>}
      {rules?.map(r => (
        <div key={r.ruleId} className="rule-row">
          <span className={`rule-dot ${r.enabled ? "rule-defined" : "rule-inactive"}`}>●</span>
          <div>
            <div className="rule-name">{r.name}</div>
            <div className="rule-detail">{r.trigger} → {r.action}</div>
            <div className="rule-source">{r.owner} · {r.configurationMode.replaceAll("_", " ").toLowerCase()} · read only</div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Nav alert system ──────────────────────────────────────────────────────
// Current conditions come from GET /api/alerts/active. The browser does not
// opt in, reset, remember a timer, or turn WARN into CRITICAL on its own.
const ALERT_PANELS   = ["DEVICE_MANAGER", "MONITORING", "RULES_ENGINE"];

export function alertLevelFor(alerts = []) {
  if (alerts.some(alert => alert.severity === "CRITICAL")) return "critical";
  if (alerts.some(alert => alert.severity === "WARN")) return "warn";
  return null;
}

export function deriveNavAlertLevels(alerts = []) {
  const level = alertLevelFor(alerts);
  return Object.fromEntries(ALERT_PANELS.map(panelId => [panelId, level]));
}

function useNavAlerts() {
  const [feed, setFeed] = useState({ alerts: [], locations: [], unavailableLocations: [], generatedAt: null });

  useEffect(() => {
    const check = async () => {
      const targets = Object.values(LOCATIONS)
        .filter(loc => loc.id === "cabin" || isLocationDeployed(loc));
      const results = await Promise.allSettled(targets.map(async loc => {
        const response = await fetch(`${loc.apiBase}/api/alerts/active`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const snapshot = await response.json();
        return {
          location: loc.id,
          generatedAt: snapshot.generatedAt,
          alerts: (Array.isArray(snapshot.alerts) ? snapshot.alerts : [])
            .map(alert => ({ ...alert, location: alert.location || loc.id })),
        };
      }));
      const available = results.filter(result => result.status === "fulfilled").map(result => result.value);
      setFeed({
        alerts: available.flatMap(result => result.alerts),
        locations: available.map(result => result.location),
        unavailableLocations: results
          .map((result, index) => result.status === "rejected" ? targets[index].id : null)
          .filter(Boolean),
        generatedAt: available.map(result => result.generatedAt).filter(Boolean).sort().at(-1) || null,
      });
    };
    check();
    const t = setInterval(check, 30_000);
    return () => clearInterval(t);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    levels: deriveNavAlertLevels(feed.alerts),
    alerts: feed.alerts,
    locations: feed.locations,
    unavailableLocations: feed.unavailableLocations,
    generatedAt: feed.generatedAt,
  };
}

// ─── Draggable order ──────────────────────────────────────────────────────
// Manages a user-defined sort order for a list of items, persisted in
// localStorage. Alarm items auto-pin to the front regardless of saved order.
// Unknown items (newly registered devices) append to the tail.
function useDraggableOrder(storageKey, items, isAlarm) {
  const getId = (item) => item.deviceId || item.id || item.name;

  const [savedOrder, setSavedOrder] = useState(() => {
    try { return JSON.parse(localStorage.getItem(storageKey)) || []; }
    catch { return []; }
  });

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify(savedOrder));
  }, [storageKey, savedOrder]);

  const ordered = useMemo(() => {
    const idToItem = new Map(items.map(i => [getId(i), i]));
    // Saved IDs first (filter out stale), then new unknowns at tail
    const inOrder = [
      ...savedOrder.filter(id => idToItem.has(id)).map(id => idToItem.get(id)),
      ...items.filter(i => !savedOrder.includes(getId(i))),
    ];
    if (!isAlarm) return inOrder;
    return [...inOrder.filter(isAlarm), ...inOrder.filter(i => !isAlarm(i))];
  }, [items, savedOrder, isAlarm]); // eslint-disable-line react-hooks/exhaustive-deps

  const pinnedCount = isAlarm ? ordered.filter(isAlarm).length : 0;

  const reorder = useCallback((fromIdx, toIdx) => {
    if (fromIdx === toIdx || fromIdx < pinnedCount || toIdx < pinnedCount) return;
    const next = [...ordered];
    const [moved] = next.splice(fromIdx, 1);
    next.splice(toIdx, 0, moved);
    setSavedOrder(next.map(getId));
  }, [ordered, pinnedCount]); // eslint-disable-line react-hooks/exhaustive-deps

  return { ordered, pinnedCount, reorder };
}

// ─── Presence profile ─────────────────────────────────────────────────────
// ─── Hub locations: merge server-configured locations into LOCATIONS ──────
// LOCATIONS (module-level, not React state) is read synchronously from ~30
// call sites throughout this file, many of them outside any component that
// could easily receive it as a prop -- converting all of them to read from
// state instead is real, separately-scoped work (see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §1b/§3). This
// hook takes the lower-risk path for now: cabin/home stay exactly as
// hardcoded today (still the guaranteed-present fallback every other call
// site assumes), and any *additional* location the backend knows about
// (GET /api/locations, backed by the new hub_location table) gets merged
// into the same LOCATIONS object by id, so it starts showing up in
// LocationSwitcher and the My Places card grid without a source change.
// Bootstraps off LOCATIONS.cabin.apiBase, matching every other
// cross-cutting fetch in this file (usePresence, useNavAlerts, /api/system/
// health) that already treats cabin as the primary backend to reach first.

// Pure merge step, no fetch/state inside -- takes the current LOCATIONS
// object and a raw /api/locations response, returns a NEW object with each
// returned row merged in by id (existing fields preserved when the API
// value is missing/falsy). Split out from the hook below so it's directly
// unit-testable -- see src/App.test.jsx.
export function mergeHubLocations(current, apiList) {
  const merged = { ...current };
  for (const loc of apiList || []) {
    if (!loc?.id) continue;
    const existing = merged[loc.id];
    merged[loc.id] = {
      id: loc.id,
      label: loc.label || loc.id,
      apiBase: loc.apiBase || existing?.apiBase || null,
      wsBase: loc.wsBase || existing?.wsBase || null,
      grafanaUrl: loc.grafanaUrl || existing?.grafanaUrl || null,
      noderedUrl: loc.noderedUrl || existing?.noderedUrl || null,
      haUrl: loc.haUrl || existing?.haUrl || null,
      frigateUrl: loc.frigateUrl || existing?.frigateUrl || null,
      z2mUrl: loc.z2mUrl || existing?.z2mUrl || null,
      familyHubUrl: loc.familyHubUrl || existing?.familyHubUrl || null,
    };
  }
  return merged;
}

function useHubLocations() {
  const [version, setVersion] = useState(0);

  useEffect(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/locations`)
      .then(r => r.json())
      .then(list => {
        const hadAnyValidRow = (list || []).some(loc => loc?.id);
        if (!hadAnyValidRow) return;
        const merged = mergeHubLocations(LOCATIONS, list);
        // LOCATIONS itself isn't state -- mutate it in place (so the ~30
        // call sites reading it synchronously elsewhere in this file see
        // the update immediately) and bump `version` so components that
        // render from it (LocationSwitcher, FamilyHubPanel's card grid)
        // actually re-render to reflect the mutation.
        Object.assign(LOCATIONS, merged);
        setVersion(v => v + 1);
      })
      .catch(() => {}); // offline backend: cabin/home hardcoded defaults still work exactly as before
  }, []);

  return version;
}

// Found 2026-08-08 (user report): this was a purely manual value with a
// map-pin icon that read as "your detected location" -- nothing behind
// it derived it from anything real, despite AutomationRuleService (
// backend) using it for real security-event severity decisions. Backend
// now derives it from live per-person, per-location presence signals
// (MqttBridgeService.handlePresenceTopic -> PresenceService.
// recomputeFromSignals -- N people x M locations, not cabin/Nate-only)
// whenever any exist; autoDerived/signals below are surfaced so the
// toggle can show whether the current value is live-detected or a
// manual fallback (a location/instance with no presence automation
// configured yet still needs the manual path -- see PresenceService's
// class comment).
function usePresence() {
  const [profile, setProfileState] = useState("AT_HOME");
  const [options, setOptions]      = useState([]);
  const [autoDerived, setAutoDerived] = useState(false);
  const [signals, setSignals]      = useState([]);

  const refresh = useCallback(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/presence`)
      .then(r => r.json())
      .then(data => {
        setProfileState(data.profile);
        setOptions(data.options || []);
        setAutoDerived(!!data.autoDerived);
        setSignals(data.signals || []);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    // Presence is live, MQTT-driven state on the backend -- poll so a
    // signal that arrived from someone else's phone (not this browser's
    // own PUT) shows up here too, same reasoning as every other
    // cross-cutting live-state poll in this file.
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  const setProfile = (p) => {
    setProfileState(p); // optimistic
    setAutoDerived(false); // a manual PUT is never auto-derived -- see PresenceService.set()
    fetch(`${LOCATIONS.cabin.apiBase}/api/presence`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ profile: p }),
    }).then(r => r.json()).then(d => setProfileState(d.profile)).catch(() => {});
  };

  return { profile, setProfile, options, autoDerived, signals };
}

// Found 2026-08-08 (user question, following the presence fix above):
// "armed" already exists as a real, live, retained MQTT signal
// (cabin/security/armed_away, self-healing -- republishes on toggle and
// on HA restart, see docs/ontology.yaml's automation_cabin_security_
// publish_arm_state) -- cabin-backend just never subscribed to it, so
// the UI had no way to answer "is this actually armed" for a user
// looking at an ambiguous alert. Same wire-up pattern as usePresence,
// deliberately not folded into it -- arming and presence are different
// concerns with different sources of truth (a human toggle vs. a WiFi
// signal) even though both ride the same MQTT bridge.
function useSecurityState() {
  const [states, setStates] = useState({}); // { [location]: { armed, lastUpdated } }

  const refresh = useCallback(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/security`)
      .then(r => r.json())
      .then(setStates)
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  return states;
}

// ─── Display configs (bulk, for active profile) ────────────────────────────
function useDisplayConfigs(profile) {
  const [configs, setConfigs] = useState({}); // deviceId → DeviceDisplayConfig

  const refetch = useCallback(() => {
    if (!profile) return;
    fetch(`${LOCATIONS.cabin.apiBase}/api/devices/display-config?profile=${profile}`)
      .then(r => r.json())
      .then(list => {
        const map = {};
        list.forEach(c => { map[c.deviceId] = c; });
        setConfigs(map);
      })
      .catch(() => {});
  }, [profile]);

  useEffect(() => { refetch(); }, [refetch]);

  return { configs, refetch };
}

// ─── Presence toggle (toolbar widget) ─────────────────────────────────────
const PROFILE_LABELS = {
  AT_HOME: "At Home", AT_CABIN: "At Cabin", AWAY: "Away", BOTH_OCCUPIED: "Both Occupied",
};

// Exported for src/App.test.jsx -- builds the presence pin's tooltip text
// from real signals rather than a hardcoded string, so it says something
// concrete ("nate at cabin, emma at home") instead of just "auto" or
// "manual". Pure function, no fetch/state, for direct unit testing.
export function formatPresenceSignals(signals) {
  const present = (signals || []).filter(s => s.present);
  if (present.length === 0) return "No one currently detected present";
  return present.map(s => `${s.personId} at ${s.location}`).join(", ");
}

function PresenceToggle() {
  const { activeProfile, setProfile, presenceOptions, presenceAutoDerived, presenceSignals } = useApp();
  const opts = presenceOptions.length > 0
    ? presenceOptions
    : Object.entries(PROFILE_LABELS).map(([value, label]) => ({ value, label }));
  // Found 2026-08-08: this pin's icon reads as "your detected location,"
  // but the value behind it was purely a manual toggle with nothing real
  // driving it -- see PresenceService's class comment for why that
  // mattered beyond cosmetics (it feeds real security-severity logic).
  // Auto-derived now wins whenever any real signal exists; the select
  // below still allows a manual override for an instance/location with
  // no presence automation configured yet (or a guest with no tracked
  // phone) -- see usePresence's comment.
  const title = presenceAutoDerived
    ? `Live-detected: ${formatPresenceSignals(presenceSignals)}`
    : "Manually set — no live presence signal detected yet for this instance";
  return (
    <div className="presence-toggle" title={title}>
      <MapPin size={13} style={{ opacity: 0.6 }}/>
      {presenceAutoDerived && <span className="presence-live-dot" aria-label="Live-detected" />}
      <select className="presence-select" value={activeProfile}
        onChange={e => setProfile(e.target.value)}>
        {opts.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}

// Exported for src/App.test.jsx. Pure function so the "unknown" case
// (no armed_away signal ever received for this location) is impossible
// to mix up with "disarmed" -- those are very different things to tell
// a user looking at an ambiguous alert, and conflating them was exactly
// the kind of gap that prompted this feature. See SecurityBadge below.
export function formatArmedTitle(state) {
  if (!state) return "No armed/disarmed signal received yet for this location";
  const ts = new Date(state.lastUpdated).toLocaleTimeString();
  return `${state.armed ? "Armed" : "Disarmed"} (as of ${ts})`;
}

// Found 2026-08-08, directly following the presence fix: "armed" is
// exactly the same class of gap -- a real, live, self-healing MQTT
// signal (cabin/security/armed_away, an HA automation, see
// docs/ontology.yaml's automation_cabin_security_publish_arm_state)
// that cabin-backend had simply never subscribed to, leaving the UI
// with no way to answer "is this actually armed" for a user looking at
// an ambiguous alert. See useSecurityState's comment for why this is a
// separate hook/badge from presence rather than folded into it.
function SecurityBadge() {
  const { securityStates, activeLocation } = useApp();
  const loc = activeLocation !== "both" ? activeLocation : "cabin";
  const state = securityStates?.[loc];
  const title = formatArmedTitle(state);

  if (!state) {
    return (
      <span className="security-badge security-unknown" title={title}>
        <ShieldAlert size={13} style={{ opacity: 0.5 }}/> Unknown
      </span>
    );
  }
  return state.armed ? (
    <span className="security-badge security-armed" title={title}>
      <Lock size={13}/> Armed
    </span>
  ) : (
    <span className="security-badge security-disarmed" title={title}>
      <Unlock size={13}/> Disarmed
    </span>
  );
}

// ─── Navigation Rail ───────────────────────────────────────────────────────
function NavRail({ active, onSelect, alertLevels }) {
  const alerts = alertLevels;
  // Asteroid City loads a second, purpose-recolored crest file (2026-08-19
  // approval) -- an <img> can't be recolored via the host page's CSS, and
  // every other theme keeps loading the original file untouched.
  const { themeId } = useTheme();
  const crestSrc = themeId === "asteroidcity" ? "/hodgson-crest-asteroidcity.svg" : "/hodgson-crest.svg";

  return (
    <nav className="nav-rail">
      <img className="nav-logo" src={crestSrc} alt="" />
      {PANELS.map(p => {
        const level = alerts[p.id];
        const isCritical = level === "critical";
        const isWarn     = level === "warn";
        const Icon = p.icon;

        return (
          <button key={p.id}
            className={[
              "nav-item",
              active === p.id ? "nav-active" : "",
              isCritical ? "nav-critical" : "",
              isWarn && !isCritical ? "nav-warn" : "",
            ].filter(Boolean).join(" ")}
            onClick={() => onSelect(p.id)}
            title={`${p.label}${isCritical ? " — CRITICAL" : isWarn ? " — attention needed" : ""}`}>

            {/* Critical: swap icon for AlertTriangle with animated pulse */}
            {isCritical
              ? <AlertTriangle size={20} className="nav-alert-icon nav-alert-critical" />
              : <Icon size={20} className={isWarn ? "nav-alert-icon nav-alert-warn" : ""} />
            }
            <span className="nav-label">{p.label}</span>

            {/* Dot badge for warn; no dot for critical (whole icon already changes) */}
            {isWarn && !isCritical && <span className="nav-badge nav-badge-warn" />}
          </button>
        );
      })}
    </nav>
  );
}

// ─── Root App ──────────────────────────────────────────────────────────────
function App() {
  // ?panel=CAMERA_EVENTS in the URL opens directly to that panel — lets
  // Family Hub's "How's the cabin?" link-out jump straight to camera
  // activity instead of always landing on the default Monitoring panel.
  const [activePanel,    setActivePanel]    = useState(() => {
    const requested = new URLSearchParams(window.location.search).get("panel");
    return PANELS.some(p => p.id === requested) ? requested : "MONITORING";
  });
  const [activeLocation, setActiveLocation] = useState("cabin");
  // 2026-08-25: toolbar device-count toggle -- see countParentDevices'
  // own comment for why "157 devices" alone was misleading (every HA
  // sub-entity/service counted as its own device).
  const [deviceCountMode, setDeviceCountMode] = useState(() => localStorage.getItem("deviceCountMode") || "devices");
  useEffect(() => localStorage.setItem("deviceCountMode", deviceCountMode), [deviceCountMode]);
  const [devices,        setDevices]        = useState([]);
  const [workflows,      setWorkflows]      = useState([]);
  const [config,         setConfig]         = useState({});
  const [connected,      setConnected]      = useState(false);
  const [apiError,       setApiError]       = useState(null); // { message, at } | null -- see refreshDevices
  const {
    levels: alertLevels,
    alerts: activeAlerts,
    locations: activeAlertLocations,
    unavailableLocations: activeAlertUnavailableLocations,
    generatedAt: activeAlertsGeneratedAt,
  } = useNavAlerts();
  useHubLocations(); // merges GET /api/locations into LOCATIONS; re-renders this tree when it changes
  const { profile: activeProfile, setProfile, options: presenceOptions, autoDerived: presenceAutoDerived, signals: presenceSignals } = usePresence();
  const securityStates = useSecurityState();
  const { configs: displayConfigs, refetch: refreshDisplayConfigs } = useDisplayConfigs(activeProfile);
  const cameraAuth = useGoogleAuth();

  // locationCfg is null when "both" — individual components handle that case.
  const locationCfg = activeLocation !== "both" ? LOCATIONS[activeLocation] : null;

  // Found 2026-08-03 (external review): the "API offline" badge pinged
  // /actuator/health directly, which has no CORS configuration at all
  // (unlike every business endpoint, which carries @CrossOrigin) -- the
  // browser blocked reading that response every time regardless of
  // whether the backend was actually up, so the badge was permanently
  // wrong. Rather than add CORS to Actuator just to drive a status
  // badge, "connected" is now derived from whether the device fetch this
  // panel already depends on actually succeeded.
  //
  // Found 2026-08-07: "connected" required EVERY attempted fetch to
  // succeed, including home-hub's -- which is always going to fail
  // (isLocationDeployed() is false for it) until home-hub is actually
  // deployed. Viewing "Home" or "Both" therefore showed "API offline"
  // permanently regardless of cabin's real health. Only fetches for
  // *deployed* locations now count toward connected/apiError; an
  // undeployed location's fetch is still attempted (so its devices show
  // up the moment it does go live) but its failure is expected, not an
  // outage.
  const refreshDevices = useCallback(() => {
    // Fetch from cabin hub always; also fetch home hub when viewing home or both.
    const attempts = [];
    if (activeLocation === "cabin" || activeLocation === "both") {
      attempts.push({ loc: LOCATIONS.cabin, promise:
        fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
          .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
      });
    }
    if (activeLocation === "home" || activeLocation === "both") {
      attempts.push({ loc: LOCATIONS.home, promise:
        fetch(`${LOCATIONS.home.apiBase}/api/devices`)
          .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
      });
    }
    Promise.allSettled(attempts.map(a => a.promise)).then(results => {
      const succeeded = results.filter(r => r.status === "fulfilled");
      setDevices(succeeded.map(r => r.value).flat());

      const relevant = attempts
        .map((a, i) => ({ loc: a.loc, result: results[i] }))
        .filter(a => isLocationDeployed(a.loc));
      const relevantFailure = relevant.find(a => a.result.status === "rejected");

      setConnected(relevant.length === 0 || !relevantFailure);
      setApiError(relevantFailure
        ? { message: `${relevantFailure.loc.label}: ${relevantFailure.result.reason?.message || "unreachable"}`, at: new Date() }
        : null);
    });
  }, [activeLocation]);

  useEffect(() => {
    refreshDevices();
    const apiBase = locationCfg?.apiBase || LOCATIONS.cabin.apiBase;
    fetch(`${apiBase}/api/dashboard/config`)
      .then(r => r.json()).then(setConfig).catch(() => {});
    const t = setInterval(refreshDevices, 15000);
    return () => clearInterval(t);
  }, [refreshDevices, locationCfg]);

  // Real WorkflowRule membership (see workflowsForDevice) -- same
  // per-location fetch shape as refreshDevices above, just a slower
  // refresh interval since workflows change far less often than device
  // state. GET /api/rules/workflows is unauthenticated/read-only (see
  // RulesController's own doc comment), so this needs no auth token.
  const refreshWorkflows = useCallback(() => {
    const attempts = [];
    if (activeLocation === "cabin" || activeLocation === "both") {
      attempts.push(fetch(`${LOCATIONS.cabin.apiBase}/api/rules/workflows`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    if (activeLocation === "home" || activeLocation === "both") {
      attempts.push(fetch(`${LOCATIONS.home.apiBase}/api/rules/workflows`)
        .then(r => r.ok ? r.json() : []).catch(() => []));
    }
    Promise.all(attempts).then(results => setWorkflows(results.flat()));
  }, [activeLocation]);

  useEffect(() => {
    refreshWorkflows();
    const t = setInterval(refreshWorkflows, 30000);
    return () => clearInterval(t);
  }, [refreshWorkflows]);

  const locationLabel = activeLocation === "both"
    ? "Cabin + Home"
    : (LOCATIONS[activeLocation]?.label || "Hub");

  return (
    <AppContext.Provider value={{
      devices, config, refreshDevices,
      workflows, refreshWorkflows,
      activeLocation, locationCfg,
      activeAlerts, activeAlertLocations, activeAlertUnavailableLocations, activeAlertsGeneratedAt,
      activeProfile, setProfile, presenceOptions, presenceAutoDerived, presenceSignals,
      securityStates,
      displayConfigs, refreshDisplayConfigs,
      setActivePanel,
    }}>
      <div className="app-shell">
        <NavRail active={activePanel} onSelect={setActivePanel} alertLevels={alertLevels} />
        <main className="main-area">
          <div className="main-toolbar">
            <span className="platform-name">{locationLabel} — Orchestration Hub</span>
            <div className="toolbar-right">
              <LocationSwitcher active={activeLocation} onChange={setActiveLocation} />
              <PresenceToggle />
              <SecurityBadge />
              <ThemeSwitcher />
              {connected ? (
                <span className="api-status api-ok">
                  <CheckCircle size={12}/> API
                </span>
              ) : (
                <a
                  className="api-status api-err"
                  href={`${(locationCfg || LOCATIONS.cabin).apiBase}/actuator/health`}
                  target="_blank"
                  rel="noreferrer"
                  title={apiError
                    ? `${apiError.message} (as of ${apiError.at.toLocaleTimeString()}) — click to open /actuator/health`
                    : "click to open /actuator/health"}
                >
                  <AlertTriangle size={12}/> API offline
                </a>
              )}
              <div className="device-count-toggle">
                <button
                  className={`dc-btn ${deviceCountMode === "devices" ? "dc-active" : ""}`}
                  onClick={() => setDeviceCountMode("devices")}
                  title="Parent devices -- not itself a child of another device"
                >
                  {countParentDevices(devices)} devices
                </button>
                <button
                  className={`dc-btn ${deviceCountMode === "services" ? "dc-active" : ""}`}
                  onClick={() => setDeviceCountMode("services")}
                  title="Every discovered device/service, including each entity of a multi-service device"
                >
                  {devices.length} device services
                </button>
              </div>
            </div>
          </div>
          <div className="panel-area">
            {activePanel === "FAMILY_HUB"     && <FamilyHubPanel />}
            {activePanel === "FAMILY_CONFIG"  && <FamilyConfigPanel auth={cameraAuth} />}
            {activePanel === "DEVICE_MANAGER" && <DeviceManagerPanel />}
            {activePanel === "MONITORING"     && <MonitoringPanel active={true} />}
            {activePanel === "RULES_ENGINE"   && <RulesPanel auth={cameraAuth} />}
            {activePanel === "CAMERA_EVENTS"  && <CameraEventsPanel auth={cameraAuth} />}
            {activePanel === "OPPORTUNITY_MAP" && <OpportunityMapPanel auth={cameraAuth} />}
          </div>
        </main>
      </div>
    </AppContext.Provider>
  );
}

// Guarded so this module can be imported for its exported pure functions
// (isCameraEvent, mergeHubLocations) from a unit test without a real
// index.html/#root present -- see src/App.test.jsx. Always truthy in the
// actual app (index.html always has <div id="root">).
const rootEl = document.getElementById("root");
if (rootEl) {
  createRoot(rootEl).render(
    <ThemeProvider>
      <App />
    </ThemeProvider>
  );
}
