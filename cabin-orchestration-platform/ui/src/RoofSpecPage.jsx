import React, { useCallback, useEffect, useState } from "react";

// Roof Monitoring page. Reached two ways: /roof/{token} for a "roof_spec"
// share link (a guest -- the link's label is the author and the token is the
// only credential), and inline in Config for the signed-in owner. The backend
// (RoofSpecController + GoogleAuthInterceptor) is the boundary: a roof_spec
// link can read the thread and POST one comment/input endpoint, nothing else.

const ITEMS = [
  { key: "furring", n: 1, title: "Furring height and air gap",
    ask: "Furring height and the resulting air-gap depth, plus the eave intake and ridge outlet detail, so the probes sit in the real airflow." },
  { key: "foam", n: 2, title: "Foam product and cable pass-through",
    ask: "Which foam (EPS, XPS, polyiso) and facers, and whether small sealed holes (about 1/2 in) for a cable are acceptable. I would seal each with closed-cell foam or sealant." },
  { key: "blocking", n: 3, title: "Ridge, eave and hatch blocking",
    ask: "Where the blocking lands, so cable routes avoid cut lines and panel fabrication." },
  { key: "cables", n: 4, title: "Leaving the cables undisturbed",
    ask: "Whether the crew is comfortable working around pre-run cables, labeled probes and sealed grommets, and which ones would have to move." },
  { key: "walk", n: 5, title: "A short field walk",
    ask: "Whether a 15-minute walk with me before the foam goes down works, and if so when. If it does not fit, say so and I will work from photos." },
  { key: "orientation", n: 6, title: "Which way each plane faces",
    ask: "Compass direction of each roof plane (for example, plane A faces south-east)." },
];

export function formatEntryTime(value) {
  return new Date(value).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function Thread({ entries, owner, onDelete }) {
  if (!entries.length) return null;
  return (
    <ul className="roof-thread">
      {entries.map(e => (
        <li key={e.id} className={e.authorKind === "owner" ? "roof-entry roof-entry-owner" : "roof-entry"}>
          <div className="roof-entry-meta">
            <strong>{e.authorKind === "owner" ? "Nate" : e.author}</strong> · {formatEntryTime(e.createdAt)}
            {owner && <button className="btn-ghost roof-entry-delete" onClick={() => onDelete(e.id)}>Remove</button>}
          </div>
          <div className="roof-entry-body">{e.body}</div>
        </li>
      ))}
    </ul>
  );
}

function EntryBox({ placeholder, buttonLabel, kind, itemKey, onPost, disabled }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const submit = async () => {
    if (!text.trim()) return;
    setBusy(true); setError(null);
    const err = await onPost({ kind, itemKey, body: text.trim() });
    setBusy(false);
    if (err) setError(err); else setText("");
  };
  return (
    <div className="roof-entry-box">
      <textarea rows={3} value={text} placeholder={placeholder} maxLength={4000} disabled={disabled}
        onChange={e => setText(e.target.value)} />
      <button className="btn-primary" onClick={submit} disabled={busy || disabled || !text.trim()}>
        {busy ? "Saving…" : buttonLabel}
      </button>
      {error && <p className="action-result action-error">{error}</p>}
    </div>
  );
}

export function RoofSpecPage({ token, authedFetch, owner = false, apiBase = "" }) { // exported for src/RoofSpecPage.test.jsx
  const [entries, setEntries] = useState(null);
  const [invalid, setInvalid] = useState(false);
  const doFetch = authedFetch || fetch;
  const withToken = useCallback((url) => token ? `${url}${url.includes("?") ? "&" : "?"}t=${encodeURIComponent(token)}` : url, [token]);

  const load = useCallback(() => {
    doFetch(withToken(`${apiBase}/api/roof-spec/entries`))
      .then(r => { if (r.status === 401) { setInvalid(true); return null; } return r.ok ? r.json() : null; })
      .then(body => { if (Array.isArray(body)) setEntries(body); else setEntries(prev => prev || []); })
      .catch(() => setEntries(prev => prev || []));
  }, [doFetch, withToken, apiBase]);

  useEffect(() => { load(); }, [load]);

  const post = async (payload) => {
    try {
      const r = await doFetch(withToken(`${apiBase}/api/roof-spec/entries`), {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
      });
      if (r.status === 401) { setInvalid(true); return "This link is no longer valid."; }
      if (!r.ok) {
        const b = await r.json().catch(() => ({}));
        return b.message || `Could not save (HTTP ${r.status}). Try again.`;
      }
      load();
      return null;
    } catch {
      return "Could not reach the cabin server. Try again.";
    }
  };

  const remove = async (id) => {
    await doFetch(`${apiBase}/api/roof-spec/entries/${encodeURIComponent(id)}`, { method: "DELETE" });
    load();
  };

  if (invalid) {
    return (
      <div className="roof-spec">
        <h1>Roof Performance Monitoring</h1>
        <p>This link isn't valid, has expired, or has been revoked. Contact Nate for a new one.</p>
      </div>
    );
  }

  const all = entries || [];
  const forKey = (k) => all.filter(e => e.itemKey === k);
  const general = all.filter(e => !e.itemKey);

  return (
    <div className="roof-spec">
      <svg width="0" height="0" style={{position: "absolute"}} aria-hidden="true"><defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="currentColor"/></marker></defs></svg>
      <h1>Roof Performance Monitoring</h1>
      <p className="roof-mut">Cumberland, WI cabin. Collins Roofing and Sheet Metal estimate 1100076160 (138T 24 ga, 2 in R-10 vented deck). A personal project of Nate's, shared for feedback.</p>

      <div className="roof-note">
        <p><b>What this is.</b> This is my own project, for my own roof. It is not part of the Collins job and not a change to the contract, the schedule, the materials or the warranty. All of that stays exactly as quoted.</p>
        <p><b>What I am hoping for.</b> You know roofs far better than I do. If you see a flaw, a wrong assumption or a better way, tell me. Every comment makes this better, and none of it is required. Skip anything that is not worth your time.</p>
        <p><b>The one place it touches your work.</b> After tear-off, before foam, I would run low-voltage cable and small probes on the open deck. If that is inconvenient, or you would rather I not, say so and I will change it or drop it.</p>
        <p>Feel free to pass this page to anyone at Collins who might have a view. Nothing is expected back.</p>
      </div>

      <h2>1. What I am measuring, and why</h2>
      <p>I would like to see whether a vented, insulated metal roof behaves the way it is designed to through a real winter. Wired probes would track heat flow through the R-10 foam, airflow in the vent channel, condensation margin under the panel, deck moisture, and first water at the penetrations. Everything is low voltage and everything is on my side of the work.</p>
      <ul>
        <li>Is the vent channel actually moving air, and does it stop if intake or ridge is blocked?</li>
        <li>Does the deck dry out or slowly wet over the winter?</li>
        <li>How close does the underside of the panel get to condensing?</li>
        <li>When do the eaves favor an ice dam?</li>
        <li>Does the hatch flashing or the vent stack boot ever let water in?</li>
      </ul>
      <p className="roof-mut">If you have measured any of this on other jobs, or know what these numbers usually look like, that is exactly the feedback I would value.</p>

      <h2>2. What I understand about the assembly</h2>
      <p>From your estimate and our emails. Rows marked <span className="roof-tag">confirm</span> are my guesses.</p>
      <div className="roof-tw"><table>
        <thead><tr><th>Item</th><th>What I have</th><th>Source</th></tr></thead>
        <tbody>
          <tr><td>Roof</td><td>22 x 26 ft footprint, 3:12 pitch, 36 in overhang, two planes about 26 x 14.4 ft, about 7.5 squares</td><td>My email 10/13, estimate</td></tr>
          <tr><td>Existing deck</td><td>Original tongue-and-groove spruce on rafters; I remove shingles and may patch deck</td><td>10/13, CR 1</td></tr>
          <tr><td>New assembly</td><td>2 in R-10 foam, vertical furring, 7/16 in OSB air gap, HT ice and water, 138T 24 ga painted 16 in panels, 4 S-5 T-5 clamps</td><td>Estimate 10/23</td></tr>
          <tr><td>Penetrations</td><td>One plumbing vent stack, ridge vent, new egress hatch near the ridge (30 x 36 in raised opening, I prep)</td><td>10/22, 10/23</td></tr>
          <tr><td>Schedule</td><td>Crew start week of Nov 23, 2026</td><td>Scheduled by Aaron</td></tr>
          <tr><td>Furring height, foam type, plane orientation</td><td>Not stated</td><td><span className="roof-tag">confirm</span></td></tr>
        </tbody>
      </table></div>

      <h2>3. Where the sensors would go</h2>
      <figure>
<svg viewBox="0 0 760 360" role="img" aria-label="Roof cross-section from loft up to panel with sensor stations: loft air, deck underside, foam underside, vent channel, OSB underside, panel surface">
<text x="10" y="18" fontSize="13" fontWeight="700">Section, eave to ridge (not to scale). Layers top to bottom.</text>
<line x1="40" y1="300" x2="700" y2="90" stroke="currentColor" strokeWidth="14" opacity="0.18"/>
<g transform="rotate(-17.6 370 195)">
<rect x="60" y="120" width="620" height="10" fill="var(--acc)" opacity="0.8"/><text x="70" y="115" fontSize="11">138T metal panel</text>
<rect x="60" y="130" width="620" height="8"  className="p"/><text x="70" y="148" fontSize="10">HT ice and water over 7/16 OSB</text>
<rect x="60" y="150" width="620" height="22" fill="none" stroke="currentColor" strokeDasharray="4 3"/><text x="70" y="166" fontSize="10">vent channel (furring gap, vertical furring)</text>
<rect x="60" y="172" width="620" height="26"  className="p"/><text x="70" y="190" fontSize="11">2 in R-10 foam</text>
<rect x="60" y="198" width="620" height="12" fill="currentColor" opacity="0.25"/><text x="70" y="208" fontSize="10">T&amp;G spruce deck</text>
</g>
<g fontSize="12" fontWeight="700">
<circle cx="300" cy="284" r="9" fill="var(--warn)"/><text x="300" y="288" textAnchor="middle" fill="#fff" style={{fill: "#fff"}}>1</text>
<circle cx="430" cy="216" r="9" fill="var(--warn)"/><text x="430" y="220" textAnchor="middle" style={{fill: "#fff"}}>2</text>
<circle cx="500" cy="170" r="9" fill="var(--warn)"/><text x="500" y="174" textAnchor="middle" style={{fill: "#fff"}}>3</text>
<circle cx="560" cy="130" r="9" fill="var(--warn)"/><text x="560" y="134" textAnchor="middle" style={{fill: "#fff"}}>4</text>
<circle cx="610" cy="100" r="9" fill="var(--warn)"/><text x="610" y="104" textAnchor="middle" style={{fill: "#fff"}}>5</text>
</g>
<g fontSize="12"><text x="20" y="326">1 Loft air T/RH (existing)</text><text x="20" y="344">2 Deck underside T and wood moisture (warm side)</text>
<text x="330" y="326">3 Foam top / channel floor T</text><text x="330" y="344">4 Vent channel air T and RH</text>
<text x="560" y="326">5 Panel underside T (outdoor ref)</text></g>
</svg>
        <figcaption>Probes 2 to 4 are the performance core. Temperature drop across the known R-10 foam gives heat flow; channel humidity and deck moisture show whether the roof is drying or wetting.</figcaption>
      </figure>
      <figure>
<svg viewBox="0 0 760 330" role="img" aria-label="Plan of two roof planes with three stations each at eave, mid and upper, hatch, vent stack and cable routes to a loft hub">
<rect  className="p" x="40" y="30" width="480" height="110" rx="4"/><text x="50" y="50" fontSize="12" fontWeight="700">Plane A (confirm: sun side or shade side)</text>
<rect  className="p" x="40" y="170" width="480" height="110" rx="4"/><text x="50" y="190" fontSize="12" fontWeight="700">Plane B (the opposite exposure)</text>
<line x1="40" y1="155" x2="520" y2="155" stroke="currentColor" strokeWidth="3"/><text x="530" y="159" fontSize="11">ridge vent</text>
<text x="46" y="296" fontSize="11">eave (intake)</text>
<g fontSize="11"><rect  className="p" x="80" y="100" width="46" height="26"/><text x="103" y="117" textAnchor="middle">A-eave</text>
<rect  className="p" x="240" y="75" width="46" height="26"/><text x="263" y="92" textAnchor="middle">A-mid</text>
<rect  className="p" x="400" y="40" width="50" height="26"/><text x="425" y="57" textAnchor="middle">A-upper</text>
<rect  className="p" x="80" y="230" width="46" height="26"/><text x="103" y="247" textAnchor="middle">B-eave</text>
<rect  className="p" x="240" y="205" width="46" height="26"/><text x="263" y="222" textAnchor="middle">B-mid</text>
<rect  className="p" x="400" y="175" width="50" height="26"/><text x="425" y="192" textAnchor="middle">B-upper</text></g>
<rect x="330" y="118" width="50" height="34" fill="var(--warn)" opacity="0.35" stroke="var(--warn)"/><text x="355" y="135" textAnchor="middle" fontSize="11">hatch</text><text x="355" y="147" textAnchor="middle" fontSize="10">leak rope</text>
<circle cx="170" cy="215" r="10" fill="var(--warn)" opacity="0.5"/><text x="150" y="266" fontSize="11">vent stack, leak rope</text>
<rect  className="p" x="590" y="100" width="150" height="70"/><text x="665" y="125" textAnchor="middle" fontSize="12" fontWeight="700">Loft hub</text><text x="665" y="143" textAnchor="middle" fontSize="11">ESP32, power, Wi-Fi</text><text x="665" y="158" textAnchor="middle" fontSize="11">to cabin HA</text>
<path  className="lv" d="M450 53 C530 53 560 120 590 125"/><path  className="lv" d="M450 188 C530 188 560 150 590 150"/>
<text x="530" y="70" fontSize="10">cable A</text><text x="530" y="205" fontSize="10">cable B</text>
</svg>
        <figcaption>Six stations (eave, mid, upper on each plane) show how conditions change along the airflow path and between sun and shade. One short cable each to the loft hub; no wireless through the metal.</figcaption>
      </figure>

      <h2>4. Sensor list</h2>
      <p>Low voltage only. Models and cold ratings are not final. Radios do not work well through a metal roof and foam, so every roof sensor is wired.</p>
      <div className="roof-tw"><table>
        <thead><tr><th>#</th><th>Sensor</th><th>Qty (est.)</th><th>Where</th><th>Tells me</th></tr></thead>
        <tbody>
          <tr><td className="roof-id">S1</td><td>Waterproof digital temperature probe (1-Wire, rated below -30 C)</td><td>18</td><td>3 per station: deck underside, foam top, vent channel air</td><td>Temperature gradient and heat flow through the foam</td></tr>
          <tr><td className="roof-id">S2</td><td>Wired humidity and temperature probe</td><td>4</td><td>Vent channel at A-eave, A-upper, B-eave, B-upper</td><td>Whether the channel is drying; dew-point margin</td></tr>
          <tr><td className="roof-id">S3</td><td>Wood moisture sensor</td><td>6</td><td>On the T&amp;G deck under the foam, one per station</td><td>Deck moisture trend</td></tr>
          <tr><td className="roof-id">S4</td><td>Leak rope or pad sensor</td><td>3</td><td>Hatch flashing, vent stack boot, one ridge point</td><td>First water at the high-risk penetrations</td></tr>
          <tr><td className="roof-id">S5</td><td>Panel underside temperature probe</td><td>2</td><td>Eave and ridge, underside accessible before panel set</td><td>Snow-melt and ice-dam risk, sun exposure</td></tr>
          <tr><td className="roof-id">H</td><td>ESP32 hub, supply, enclosure</td><td>1</td><td>Loft, near the cable entry</td><td>Collects and timestamps readings</td></tr>
          <tr><td className="roof-id">C</td><td>Outdoor-rated water-block CAT5e/6, labeled both ends</td><td>3 runs plus pull string</td><td>Hub to each plane and to hatch/stack</td><td>Power and data</td></tr>
        </tbody>
      </table></div>

      <h2>5. How it could be installed</h2>
      <ol>
        <li><b>Me, after tear-off, before foam:</b> pull cables from the loft through the deck at the ridge side, lay probes S1, S3 and S4 on the open deck, label every end, photograph each station with a tape measure in frame.</li>
        <li><b>Collins, normal sequence, unchanged:</b> foam, furring, OSB, ice and water, panels. Foam cut around the cables; I seal the pass-throughs.</li>
        <li><b>Me, before the OSB closes:</b> place the channel probes on the furring faces and route them through sealed grommets.</li>
        <li><b>Optional 15-minute walk:</b> a look at ridge, eave and hatch blocking to make sure nothing gets pinched.</li>
        <li><b>Me, afterward:</b> commission the probes and record the first cold week as a baseline.</li>
      </ol>
      <div className="roof-warn">Every cable hole through the deck and foam gets air-sealed with closed-cell foam or sealant, and I will keep the number of penetrations low. A cable hole is a leak path, the opposite of what I am measuring.</div>

      <h2>6. What I would calculate</h2>
      <div className="roof-tw"><table>
        <thead><tr><th>Metric</th><th>How</th><th>Meaning</th></tr></thead>
        <tbody>
          <tr><td>Heat flow through the roof</td><td>(deck-underside T minus foam-top T) divided by R-10</td><td>Insulation performance; a rise at the same weather is a flag</td></tr>
          <tr><td>Vent channel lift</td><td>Channel air T upper minus eave, against outdoor T</td><td>Proxy for airflow; a collapse suggests a blocked intake or ridge</td></tr>
          <tr><td>Dew-point margin</td><td>Panel underside T minus channel dew point</td><td>Near zero means condensation under the metal</td></tr>
          <tr><td>Deck moisture trend</td><td>Moisture sensor over weeks, per station</td><td>Drying, steady or wetting</td></tr>
          <tr><td>Warm-air leakage flag</td><td>Warm, humid deck-side reading with a warm loft and cold outdoors</td><td>Air escaping from the living space into the assembly</td></tr>
          <tr><td>Ice-dam risk</td><td>Eave panel T near freezing while the upper roof is above, with snow</td><td>Refreeze condition at the eave</td></tr>
          <tr><td>Leak event</td><td>Any S4 sensor wet</td><td>Alarm with location and time</td></tr>
        </tbody>
      </table></div>
      <p className="roof-mut">These are indications from point measurements. They do not replace an infrared survey, a blower-door test or an inspection.</p>

      <h2>7. Where your experience would help most</h2>
      <p>Each box below is optional and is saved to this page when you press the button. Short answers, guesses and "no" are all useful. I read every one.</p>
      {ITEMS.map(it => (
        <div className="roof-item" key={it.key}>
          <h3>{it.n}. {it.title}</h3>
          <p>{it.ask}</p>
          <Thread entries={forKey(it.key)} owner={owner} onDelete={remove} />
          <EntryBox kind="input" itemKey={it.key} onPost={post} buttonLabel="Save answer"
            placeholder={owner ? "Add a note or reply" : "Optional. Your answer, a guess, or a better idea."} />
        </div>
      ))}

      <h2>8. Anything else</h2>
      <p>Flaws, errors, things I have not thought of, sensors you would add or drop, what you would watch for on a roof like this. Anything at all.</p>
      <Thread entries={general} owner={owner} onDelete={remove} />
      <EntryBox kind="comment" itemKey={null} onPost={post} buttonLabel="Post comment"
        placeholder={owner ? "Add a note or reply" : "Optional. Anything you want me to know."} />
      <p className="roof-mut">Comments show your name from the link and the time. Only Nate and people with this link can see this page.</p>
    </div>
  );
}
