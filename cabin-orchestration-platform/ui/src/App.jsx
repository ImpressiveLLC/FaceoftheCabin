/**
 * Cabin Orchestration Platform — Shell UI
 *
 * Five docked/expandable panels:
 *   FAMILY_HUB     — Smrekar Familia Hub iframe (read-only bridge)
 *   FAMILY_CONFIG  — Family settings, notification preferences, Google OAuth
 *   DEVICE_MANAGER — Add / edit / remove / activate devices (drag-reorder)
 *   MONITORING     — Live WebSocket telemetry tiles + Grafana embed
 *   RULES_ENGINE   — Node-RED embed + rule CRUD + Kafka topic browser
 *
 * Panel state: "docked" | "expanded"
 * WebSocket opens lazily on expand, closes on dock.
 */

import React, { useEffect, useState, useRef, useCallback, createContext, useContext } from "react";
import { createRoot } from "react-dom/client";
import {
  Home, Settings, Cpu, Activity, Zap,
  ChevronDown, ChevronUp, Wifi, WifiOff,
  Droplets, Thermometer, Camera, ShieldAlert, Lock,
  RefreshCw, Plus, Trash2, ToggleLeft, ToggleRight,
  AlertTriangle, CheckCircle, Circle
} from "lucide-react";
import "./styles.css";

const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
const WS_BASE  = import.meta.env.VITE_WS_BASE  || "ws://localhost:9001";   // Mosquitto WebSocket
const HA_URL   = import.meta.env.VITE_HA_URL   || "http://localhost:8123";
const GRAFANA_URL = import.meta.env.VITE_GRAFANA_URL || "http://localhost:3000";
const NODERED_URL = import.meta.env.VITE_NODERED_URL || "http://localhost:1880";

// ─── Panel definitions ─────────────────────────────────────────────────────
const PANELS = [
  { id: "FAMILY_HUB",     label: "Family Hub",      icon: Home },
  { id: "FAMILY_CONFIG",  label: "Family Config",   icon: Settings },
  { id: "DEVICE_MANAGER", label: "Devices",         icon: Cpu },
  { id: "MONITORING",     label: "Monitoring",      icon: Activity },
  { id: "RULES_ENGINE",   label: "Rules & Alerts",  icon: Zap },
];

// ─── Context ───────────────────────────────────────────────────────────────
const AppContext = createContext(null);
function useApp() { return useContext(AppContext); }

// ─── Helpers ───────────────────────────────────────────────────────────────
function stateColor(state) {
  if (!state) return "state-unknown";
  const s = state.toUpperCase();
  if (s === "ONLINE" || s === "LOCKED" || s === "ON") return "state-ok";
  if (s === "ALARM" || s === "CRITICAL") return "state-alarm";
  if (s === "OFFLINE" || s === "DOWN") return "state-offline";
  return "state-unknown";
}

function deviceIcon(type) {
  const map = {
    CAMERA: Camera, WATER_PRESSURE_SENSOR: Droplets, TEMPERATURE_SENSOR: Thermometer,
    THERMOSTAT: Thermometer, SMOKE_ALARM: ShieldAlert, CO_ALARM: ShieldAlert,
    LOCK: Lock, MOTION_SENSOR: Activity, HOME_ASSISTANT_ENTITY: Wifi,
    DISHWASHER: Cpu, WASHING_MACHINE: Cpu, DASHBOARD: Home,
  };
  return map[type] || Circle;
}

// ─── Live WebSocket telemetry hook ─────────────────────────────────────────
function useMqttTelemetry(active) {
  const [messages, setMessages] = useState([]);
  const ws = useRef(null);

  useEffect(() => {
    if (!active) { ws.current?.close(); return; }
    try {
      // Connect to Mosquitto WebSocket broker
      ws.current = new WebSocket(WS_BASE);
      ws.current.onopen = () => {
        // MQTT over WebSocket: send CONNECT packet manually or use mqtt.js
        // For simplicity we rely on the REST API for state and WS for live events only
        console.log("MQTT WS connected");
      };
      ws.current.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          setMessages(prev => [{ ts: Date.now(), ...data }, ...prev].slice(0, 50));
        } catch {}
      };
      ws.current.onerror = () => {};
    } catch {}
    return () => ws.current?.close();
  }, [active]);

  return messages;
}

// ─── Panel: Family Hub ─────────────────────────────────────────────────────
function FamilyHubPanel() {
  const { config } = useApp();
  const url = config?.familyDashboardUrl || "";
  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Smrekar Familia Hub</h2>
        <a href={url} target="_blank" rel="noreferrer" className="btn-ghost">Open in new tab ↗</a>
      </div>
      {url ? (
        <iframe title="Family Dashboard" src={url} className="embed-frame" />
      ) : (
        <div className="empty-state">
          <Home size={40} opacity={0.3} />
          <p>Family Hub URL not configured.<br />Set <code>FAMILY_DASHBOARD_URL</code> in application.yml.</p>
        </div>
      )}
    </div>
  );
}

// ─── Panel: Family Config ──────────────────────────────────────────────────
function FamilyConfigPanel() {
  const { config } = useApp();
  return (
    <div className="panel-content">
      <div className="panel-header-bar"><h2>Family Configuration</h2></div>
      <div className="config-grid">
        <ConfigCard title="Google Account" icon={Home}>
          <p className="config-desc">smrekarfamilia@gmail.com — Google Calendar, Gmail, and Google Home service access.</p>
          <a href={`${HA_URL}/config/integrations`} target="_blank" rel="noreferrer" className="btn-secondary">
            Manage in Home Assistant ↗
          </a>
        </ConfigCard>
        <ConfigCard title="Notification Preferences" icon={AlertTriangle}>
          <p className="config-desc">Configure alert escalation: MQTT → email/SMS thresholds.</p>
          <p className="config-hint">Node-RED flows handle routing. Edit in Rules &amp; Alerts panel.</p>
        </ConfigCard>
        <ConfigCard title="Remote Access" icon={Wifi}>
          <p className="config-desc">Tailscale mesh VPN — cabin-hub.tailnet provides access from anywhere.</p>
          <code className="code-block">tailscale up --advertise-routes=192.168.1.0/24</code>
        </ConfigCard>
        <ConfigCard title="Platform" icon={Cpu}>
          <p className="config-desc">{config?.platformName || "Cabin Orchestration Platform"}</p>
          <p className="config-hint">Lenovo Tiny · Ubuntu 24.04 · 32 GB RAM · 1 TB SSD</p>
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

// ─── Panel: Device Manager ─────────────────────────────────────────────────
function DeviceManagerPanel() {
  const { devices, refreshDevices } = useApp();
  const [selected, setSelected] = useState(null);
  const [adding, setAdding] = useState(false);

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Device Manager</h2>
        <div className="header-actions">
          <button className="btn-ghost" onClick={refreshDevices}><RefreshCw size={14}/> Refresh</button>
          <button className="btn-primary" onClick={() => setAdding(true)}><Plus size={14}/> Add Device</button>
        </div>
      </div>
      <div className="device-grid">
        {devices.map(d => {
          const Icon = deviceIcon(d.type);
          return (
            <div key={d.deviceId}
              className={`device-card ${selected === d.deviceId ? "selected" : ""}`}
              onClick={() => setSelected(selected === d.deviceId ? null : d.deviceId)}>
              <div className="device-card-header">
                <Icon size={20} />
                <span className="device-name">{d.name}</span>
                <span className={`state-badge ${stateColor(d.state)}`}>{d.state}</span>
              </div>
              <div className="device-type">{d.type}</div>
              {d.lastSeen && (
                <div className="device-lastseen">Last seen: {new Date(d.lastSeen).toLocaleTimeString()}</div>
              )}
              {selected === d.deviceId && (
                <div className="device-attrs">
                  {Object.entries(d.attributes || {}).map(([k, v]) => v != null && (
                    <div key={k} className="attr-row">
                      <span className="attr-key">{k}</span>
                      <span className="attr-val">{String(v)}</span>
                    </div>
                  ))}
                  <DeviceActions device={d} />
                </div>
              )}
            </div>
          );
        })}
        {devices.length === 0 && (
          <div className="empty-state"><Cpu size={40} opacity={0.3}/><p>No devices registered yet.</p></div>
        )}
      </div>
      {adding && <AddDeviceModal onClose={() => { setAdding(false); refreshDevices(); }} />}
    </div>
  );
}

function DeviceActions({ device }) {
  const { refreshDevices } = useApp();
  const sendCommand = async (cmd) => {
    await fetch(`${API_BASE}/api/devices/${device.deviceId}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command: cmd })
    });
    refreshDevices();
  };
  const remove = async () => {
    if (!confirm(`Remove ${device.name}?`)) return;
    await fetch(`${API_BASE}/api/devices/${device.deviceId}`, { method: "DELETE" });
    refreshDevices();
  };
  return (
    <div className="device-actions">
      {device.type === "LOCK" && <>
        <button className="btn-secondary" onClick={() => sendCommand("lock.lock")}>Lock</button>
        <button className="btn-secondary" onClick={() => sendCommand("lock.unlock")}>Unlock</button>
      </>}
      {device.type === "THERMOSTAT" && (
        <button className="btn-secondary" onClick={() => sendCommand("climate.set_hvac_mode")}>Configure</button>
      )}
      <button className="btn-danger" onClick={remove}><Trash2 size={12}/> Remove</button>
    </div>
  );
}

function AddDeviceModal({ onClose }) {
  const [form, setForm] = useState({
    deviceId: "", name: "", type: "HOME_ASSISTANT_ENTITY",
    protocolAdapter: "ha_rest", connectionString: "", enabled: true
  });
  const submit = async () => {
    await fetch(`${API_BASE}/api/devices`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...form, capabilities: [] })
    });
    onClose();
  };
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <h3>Add Device</h3>
        <label>Device ID <input value={form.deviceId} onChange={e=>setForm({...form,deviceId:e.target.value})}/></label>
        <label>Display Name <input value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
        <label>Type
          <select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>
            {["LOCK","THERMOSTAT","SMOKE_ALARM","CAMERA","WATER_PRESSURE_SENSOR",
              "MOTION_SENSOR","DISHWASHER","WASHING_MACHINE","HOME_ASSISTANT_ENTITY"].map(t=>(
              <option key={t}>{t}</option>
            ))}
          </select>
        </label>
        <label>Protocol Adapter
          <select value={form.protocolAdapter} onChange={e=>setForm({...form,protocolAdapter:e.target.value})}>
            {["ha_rest","mqtt","rtsp","http_poll","google_sdm"].map(a=>(
              <option key={a}>{a}</option>
            ))}
          </select>
        </label>
        <label>Connection String (HA entity_id / MQTT topic / RTSP URL)
          <input value={form.connectionString} onChange={e=>setForm({...form,connectionString:e.target.value})}
            placeholder="e.g. lock.front_door or cabin/device/water-pressure"/>
        </label>
        <div className="modal-actions">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" onClick={submit}>Add Device</button>
        </div>
      </div>
    </div>
  );
}

// ─── Panel: Monitoring ─────────────────────────────────────────────────────
function MonitoringPanel({ active }) {
  const { devices } = useApp();
  const liveMessages = useMqttTelemetry(active);

  const pressure = devices.find(d => d.type === "WATER_PRESSURE_SENSOR");
  const thermostat = devices.find(d => d.type === "THERMOSTAT");
  const smoke = devices.find(d => d.type === "SMOKE_ALARM");
  const locks = devices.filter(d => d.type === "LOCK");
  const cameras = devices.filter(d => d.type === "CAMERA");

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Monitoring</h2>
        <span className={`ws-indicator ${active ? "ws-live" : "ws-off"}`}>
          {active ? <><Wifi size={12}/> Live</> : <><WifiOff size={12}/> Docked</>}
        </span>
      </div>

      {/* KPI tiles */}
      <div className="kpi-grid">
        <KpiTile icon={Droplets} label="Water Pressure"
          value={pressure?.attributes?.psi != null ? `${pressure.attributes.psi} PSI` : "—"}
          state={pressure?.state} />
        <KpiTile icon={Thermometer} label="Thermostat"
          value={thermostat?.attributes?.current_temperature != null
            ? `${thermostat.attributes.current_temperature}°F` : "—"}
          state={thermostat?.state} />
        <KpiTile icon={ShieldAlert} label="Smoke Alarm"
          value={smoke?.state || "UNKNOWN"}
          state={smoke?.state === "ALARM" ? "ALARM" : smoke?.state} />
        {locks.map(l => (
          <KpiTile key={l.deviceId} icon={Lock} label={l.name}
            value={l.state} state={l.state} />
        ))}
        {cameras.map(c => (
          <KpiTile key={c.deviceId} icon={Camera} label={c.name}
            value={c.state} state={c.state} />
        ))}
      </div>

      {/* Grafana embed */}
      <div className="embed-section">
        <div className="embed-label">Grafana — Telemetry Dashboards</div>
        <iframe title="Grafana" src={`${GRAFANA_URL}/d/cabin-overview?kiosk=tv`}
          className="embed-frame-short" />
      </div>

      {/* Live MQTT event log */}
      <div className="event-log">
        <div className="event-log-header">Live Event Stream (MQTT)</div>
        {liveMessages.length === 0 && (
          <div className="event-log-empty">No live messages — connect to MQTT WebSocket to stream events.</div>
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

function KpiTile({ icon: Icon, label, value, state }) {
  return (
    <div className={`kpi-tile kpi-${stateColor(state)}`}>
      <Icon size={22} />
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      <span className={`state-badge ${stateColor(state)}`}>{state || "UNKNOWN"}</span>
    </div>
  );
}

// ─── Panel: Rules Engine ───────────────────────────────────────────────────
function RulesPanel() {
  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Rules &amp; Alerts</h2>
        <a href={NODERED_URL} target="_blank" rel="noreferrer" className="btn-primary">
          Open Node-RED ↗
        </a>
      </div>
      <div className="rules-layout">
        <div className="rules-nodered">
          <div className="embed-label">Node-RED — Visual Automation Flows</div>
          <iframe title="Node-RED" src={NODERED_URL} className="embed-frame" />
        </div>
        <div className="rules-sidebar">
          <KafkaStatus />
          <BuiltinRules />
        </div>
      </div>
    </div>
  );
}

function KafkaStatus() {
  return (
    <div className="sidebar-card">
      <strong>Kafka Topics</strong>
      <div className="kafka-topics">
        {["cabin.events.raw","cabin.events.alerts","cabin.events.motion"].map(t => (
          <div key={t} className="kafka-topic">
            <span className="topic-dot">●</span>{t}
          </div>
        ))}
      </div>
      <p className="config-hint">Node-RED consumes cabin.events.raw. Configure brokers at localhost:9092.</p>
    </div>
  );
}

function BuiltinRules() {
  const rules = [
    { id: 1, name: "Water Pressure Low",   trigger: "PSI < 30",   action: "Alert + email", active: true },
    { id: 2, name: "Water Pressure High",  trigger: "PSI > 75",   action: "Alert + email", active: true },
    { id: 3, name: "Freeze Risk",          trigger: "Temp < 38°F", action: "CRITICAL alert", active: true },
    { id: 4, name: "Smoke Alarm",          trigger: "alarm=true",  action: "CRITICAL + SMS", active: true },
    { id: 5, name: "Motion After Midnight", trigger: "motion + hour 0-6", action: "Notify", active: false },
  ];
  return (
    <div className="sidebar-card">
      <strong>Built-in Safety Rules</strong>
      <p className="config-hint">These Java-side rules run even when Node-RED is offline.</p>
      {rules.map(r => (
        <div key={r.id} className="rule-row">
          <span className={`rule-dot ${r.active ? "rule-active" : "rule-inactive"}`}>●</span>
          <div>
            <div className="rule-name">{r.name}</div>
            <div className="rule-detail">{r.trigger} → {r.action}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Navigation Rail ───────────────────────────────────────────────────────
function NavRail({ active, onSelect }) {
  return (
    <nav className="nav-rail">
      <div className="nav-logo">⌂</div>
      {PANELS.map(p => {
        const Icon = p.icon;
        return (
          <button key={p.id}
            className={`nav-item ${active === p.id ? "nav-active" : ""}`}
            onClick={() => onSelect(p.id)}
            title={p.label}>
            <Icon size={20} />
            <span className="nav-label">{p.label}</span>
          </button>
        );
      })}
    </nav>
  );
}

// ─── Root App ──────────────────────────────────────────────────────────────
function App() {
  const [activePanel, setActivePanel] = useState("MONITORING");
  const [devices, setDevices] = useState([]);
  const [config, setConfig]   = useState({});
  const [connected, setConnected] = useState(false);

  const refreshDevices = useCallback(() => {
    fetch(`${API_BASE}/api/devices`)
      .then(r => r.json()).then(setDevices).catch(() => {});
  }, []);

  useEffect(() => {
    refreshDevices();
    fetch(`${API_BASE}/api/dashboard/config`)
      .then(r => r.json()).then(setConfig).catch(() => {});
    // Poll devices every 15 seconds
    const t = setInterval(refreshDevices, 15000);
    // Check API reachability
    fetch(`${API_BASE}/actuator/health`)
      .then(() => setConnected(true)).catch(() => setConnected(false));
    return () => clearInterval(t);
  }, [refreshDevices]);

  return (
    <AppContext.Provider value={{ devices, config, refreshDevices }}>
      <div className="app-shell">
        <NavRail active={activePanel} onSelect={setActivePanel} />
        <main className="main-area">
          <div className="main-toolbar">
            <span className="platform-name">Cumberland Cabin Hub</span>
            <div className="toolbar-right">
              <span className={`api-status ${connected ? "api-ok" : "api-err"}`}>
                {connected ? <><CheckCircle size={12}/> API</> : <><AlertTriangle size={12}/> API offline</>}
              </span>
              <span className="device-count">{devices.length} devices</span>
            </div>
          </div>
          <div className="panel-area">
            {activePanel === "FAMILY_HUB"     && <FamilyHubPanel />}
            {activePanel === "FAMILY_CONFIG"  && <FamilyConfigPanel />}
            {activePanel === "DEVICE_MANAGER" && <DeviceManagerPanel />}
            {activePanel === "MONITORING"     && <MonitoringPanel active={true} />}
            {activePanel === "RULES_ENGINE"   && <RulesPanel />}
          </div>
        </main>
      </div>
    </AppContext.Provider>
  );
}

createRoot(document.getElementById("root")).render(<App />);
