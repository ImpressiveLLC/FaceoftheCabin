"use strict";
// Grading session panel: preconditions, refresh instructions, and the single "Done, move to gold" button.
(() => {
  const $ = id => document.getElementById(id);
  let token = "", approved = 0, blockers = 1, confirming = false, finished = false;
  const esc = text => String(text ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));

  async function call(path, body) {
    const options = body === undefined ? {} : {method: "POST", headers: {"Content-Type": "application/json", "X-Review-Token": token}, body: JSON.stringify(body)};
    const response = await fetch(path, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || response.statusText);
    return data;
  }

  function renderDone() {
    const button = $("done"), why = $("done-why");
    button.hidden = finished;
    if (finished) { why.textContent = ""; return; }
    button.textContent = confirming ? `Yes, move ${approved} approved example${approved === 1 ? "" : "s"} to gold` : "Done, move to gold";
    button.classList.toggle("confirm", confirming);
    button.disabled = blockers > 0 || approved === 0;
    why.textContent = blockers > 0 ? "Fix the red lines on the left first." : approved === 0 ? "Approve or correct at least one example first." : confirming ? "Click again to confirm, or reload the page to cancel." : "";
  }

  async function refresh() {
    $("preflight").innerHTML = '<li class="check pending">Checking…</li>';
    try {
      const state = await call("/api/state");
      token = state.token;
      approved = Object.values(state.reviews || {}).filter(r => r.decision === "approved" && !r.seeded).length;
      const report = await call("/api/preflight");
      blockers = report.blockers;
      $("preflight").innerHTML = report.checks.map(c => `<li class="check ${c.status}"><b>${c.status === "ok" ? "✓" : c.status === "warn" ? "!" : "✕"}</b><span>${esc(c.label)}${c.detail ? `<small>${esc(c.detail)}</small>` : ""}${c.status !== "ok" && c.fix ? `<em>${esc(c.fix)}</em>` : ""}</span></li>`).join("");
    } catch (error) {
      blockers = 1;
      $("preflight").innerHTML = `<li class="check fail"><b>✕</b><span>Could not check<small>${esc(error.message)}</small></span></li>`;
    }
    renderDone();
  }

  async function done() {
    if (!confirming) { confirming = true; renderDone(); return; }
    $("done").disabled = true;
    $("done-why").textContent = "Working: checking the package, then saving…";
    try {
      const result = await call("/api/finish", {});
      finished = true;
      const link = result.pr_url ? `<p><a class="done-link" href="${esc(result.pr_url)}" target="_blank" rel="noopener">Open the pull request</a></p>` : "";
      const commands = (result.commands || []).length ? `<p>Run these in the repo:</p><pre>${esc(result.commands.join("\n"))}</pre>` : "";
      $("done-result").innerHTML = `<div class="result ok"><strong>${esc(result.message)}</strong><p>${result.count} example${result.count === 1 ? "" : "s"}. ${result.tests ? "Package checks: " + esc(result.tests) + "." : ""}</p>${link}${commands}<p><b>Next:</b> tell Code or Cowork "gold PR ready". Then stop this tool. Next session starts with the checklist on the left.</p></div>`;
      $("done-why").textContent = "";
    } catch (error) {
      confirming = false;
      $("done-result").innerHTML = `<div class="result bad"><strong>Nothing was moved.</strong><p>${esc(error.message)}</p></div>`;
    }
    renderDone();
  }

  document.addEventListener("click", event => {
    const copy = event.target.closest("button.copy");
    if (!copy) return;
    const text = copy.dataset.copy.replace(/&amp;/g, "&");
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(() => { copy.textContent = "Copied"; }, () => { copy.textContent = "Select and copy the command shown"; });
  });
  window.addEventListener("DOMContentLoaded", () => {
    $("recheck").addEventListener("click", () => { confirming = false; refresh(); });
    $("done").addEventListener("click", done);
    refresh();
    // Approvals change while grading: keep the count honest without a reload.
    document.addEventListener("click", () => setTimeout(async () => { try { const s = await call("/api/state"); approved = Object.values(s.reviews || {}).filter(r => r.decision === "approved" && !r.seeded).length; renderDone(); } catch (_) {} }, 600));
  });
})();
