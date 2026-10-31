"""Apply the 2026-10-31 routing proposals (PR #365) to the Ask context map, and measure routing before and after.

  python apply_routing.py --measure map.json            # print routing for the 150 phrasings against a map, no edits
  python apply_routing.py --apply map.json out.json     # write the changed map (indent 2, as the map is stored)
  python apply_routing.py --report before.json after.json

The proposals are data here, in one place, so the change is reviewable and repeatable. Matching is the replica in
route.py (same whole-word regex, word-count score, boost only when matched, declaration order on ties).
"""
import copy
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PHRASINGS = HERE / "phrasings.json"

AL = "ai-assistant/user-guide/alerts-and-notifications.md"
WF = "ai-assistant/user-guide/workflows.md"
AS = "ai-assistant/user-guide/access-and-storage.md"
DV = "ai-assistant/user-guide/devices.md"
MT = "ai-assistant/user-guide/maintenance.md"
OP = "ai-assistant/user-guide/operations.md"


def doc(path, section, audience):
    return {"path": path, "section": section, "audience": audience}


# alerts sections
S1 = doc(AL, "status-checks-what-needs-attention-right-now", "household")
S2 = doc(AL, "alert-levels-and-the-marks-on-the-tabs", "household")
S3 = doc(AL, "ignore-remind-me-in-and-restore", "household")
S4 = doc(AL, "open-device-from-an-alert", "household")
S5 = doc(AL, "phone-notifications-what-reaches-your-phone-today", "household")
S6 = doc(AL, "a-water-leak-alert-and-turning-the-water-back-on", "household")
S7 = doc(AL, "reset-mark-seen-and-why-there-is-no-undo", "household")
S8 = doc(AL, "backend-rules-card-versus-home-assistant-and-node-red", "household")
S9 = doc(AL, "optimization-opportunities-power-and-duplicate-readings", "administrator")
K19 = doc(OP, "opportunities-and-the-tech-id-service-k19", "administrator")
RULES_SAFETY = doc(OP, "rules-and-safety", "administrator")

# (id, description, keywords, documents, boost). Alerts, then admin: all declared before rules_workflows.
BEFORE_RULES = [
    ("alert_silencing", "Ignore, snooze and restore an alert",
     ["ignore", "ignored", "snooze", "snoozed", "remind me", "silence", "silenced", "restore an alert", "bring back", "go away", "mute"],
     [S3, S1], None),
    ("status_checks", "Status Checks card, alert levels and the marks on the tabs",
     ["status check", "what needs my attention", "needs attention", "need attention", "what's wrong", "alert", "alert level",
      "critical", "warning", "colored dot", "dot", "red dot", "tab", "open device", "door left open"],
     [S1, S2, S4], None),
    ("phone_notifications", "What reaches a phone today",
     ["notification", "push notification", "notify", "notified", "notify me", "phone alert", "alert my phone", "text me", "ntfy"],
     [S5, S2], None),
    ("leak_response", "A water leak alert and turning the water back on",
     ["leak", "water leak", "water back on", "turn the water", "main water", "water valve", "shut off the water", "flood"],
     [S6, S3, RULES_SAFETY], None),
    ("workflow_reset_undo", "Reset, Mark seen and why there is no undo",
     ["undo", "reset", "mark seen", "reverse", "take back", "firing"],
     [S7, S6], 1),
    ("optimization_opportunities", "Optimization Opportunities: Power and Duplicate readings",
     ["duplicate reading", "drawing power", "48 hour", "optimization opportunity", "power card"],
     [S9, K19], None),
    ("workflow_schedule", "Can a workflow run on a schedule",
     ["schedule", "scheduled", "every night", "every morning", "every day", "certain time", "time of day", "at a certain time", "on a timer"],
     [doc(WF, "can-a-workflow-run-on-a-schedule-or-at-a-time-of-day", "household")], None),
    ("workflow_run_history", "Turn a workflow on or off, run it by hand, see when it ran",
     ["fire now", "activate", "deactivate", "turn a workflow on", "mark seen", "reset", "undo", "workflow history", "history of a workflow",
      "workflow ran", "does nothing", "doesn't do anything", "recent firing", "workflow firing", "run a workflow", "workflow by hand",
      "workflow on or off"],
     [doc(WF, "turn-a-workflow-on-or-off-run-it-by-hand-and-see-when-it-ran", "household")], None),
    ("workflow_settings", "Workflow settings: cooldown and broken targets",
     ["cooldown", "stays fired", "fire again", "condition clears", "broken", "degraded", "replacement device", "different device", "over and over"],
     [doc(WF, "workflow-settings-after-it-fires-cooldown-and-broken-targets", "administrator")], None),
    ("workflow_create", "Create a workflow",
     ["create a workflow", "create a new workflow", "new workflow", "make a workflow", "save draft", "not available yet", "pick that trigger",
      "can something happen", "when someone arrives", "shut off the water", "start a workflow"],
     [doc(WF, "create-a-workflow", "administrator"), doc(WF, "what-a-workflow-is-and-where-it-runs", "household")], None),
    ("workflow_basics", "What a workflow is and where it runs",
     ["what is a workflow", "what can a workflow do", "workflow turn on", "where does a workflow run", "home assistant automation",
      "automations actually run", "run by the app", "by home assistant"],
     [doc(WF, "what-a-workflow-is-and-where-it-runs", "household")], None),
    ("access_sharing", "Guest links and demo links",
     ["guest access", "guest link", "share link", "shared", "read-only access", "revoke", "demo link", "adjuster", "contractor", "access link", "view activity"],
     [doc(AS, "share-read-only-access-with-a-link-guest-access", "administrator"), doc(AS, "give-someone-a-demo-link", "administrator")], None),
    ("managed_users", "Add someone without a Google account",
     ["managed user", "invite", "google account", "household member", "sign-in link", "magic link", "add someone", "add a user"],
     [doc(AS, "add-someone-without-a-google-account-managed-users", "administrator"), doc(AS, "who-can-see-and-change-what", "household")], None),
    ("who_can_change", "Who can see and change what",
     ["who is allowed", "who can", "allowed to", "permission", "can't create", "cannot create", "read-only account", "viewer"],
     [doc(AS, "who-can-see-and-change-what", "household")], None),
    ("storage_disk", "Disk space warnings and trimming old backups",
     ["disk", "disk space", "almost full", "storage warning", "free up", "trim", "trim old backups", "old backups", "storage"],
     [doc(AS, "disk-space-warnings", "household"), doc(AS, "trim-old-backups", "administrator")], None),
]

D = lambda s, a="household": doc(DV, s, a)
APPENDED = [
    ("device_add", "Pair, scan for, register or import a device",
     ["pair", "paired", "pairing", "add a device", "add a new", "new zigbee", "new zigbee sensor", "scan for devices", "network scan", "printer", "tv",
      "register manually", "manually", "home assistant entity", "import", "smartthings", "ring", "bring in"],
     [D("pair-a-zigbee-device"), D("scan-for-wifi-devices-at-home"), D("register-a-device-manually", "administrator"),
      D("import-devices-from-smartthings-or-ring", "administrator")], None),
    ("device_management", "What the device states mean, removing, finding a device",
     ["use this device", "not now", "saved for later", "available and assigned", "new device", "showed up", "remove a device", "removing a device",
      "removed", "get back", "bring a device back", "previously exposed", "can't find", "cannot find", "disappeared", "where did my",
      "parent devices only", "imported from", "smartthings device", "ring device", "group devices", "collapse", "filter", "delete its history"],
     [D("what-candidates-available-assigned-saved-for-later-and-ignored-mean"), D("remove-a-device-and-bring-it-back"),
      D("find-a-device-filters-groups-and-layout")], None),
    ("device_settings", "Rename a device, the Enabled switch, Monitoring display",
     ["rename a device", "renaming a device", "display name", "room", "area", "enabled switch", "stop using", "monitoring page", "tile",
      "hide a tile", "display override"],
     [D("rename-a-device-or-set-its-room-and-area"), D("the-enabled-switch"), D("change-how-a-device-shows-on-monitoring")], None),
    ("device_control", "Turn a device on or off, On the Way",
     ["heater", "turn it on", "turn it off", "on/off", "power button", "on the way", "warm up", "preheat", "lock", "unlock"],
     [D("turn-a-device-on-or-off-from-the-app"), D("on-the-way-warm-up-the-cabin-before-you-arrive")], None),
    ("device_health", "Not responding, late or blank device card",
     ["offline", "say offline", "says offline", "not responding", "late checking in", "blank", "last seen", "stopped reporting", "check-in"],
     [D("why-a-device-says-not-responding-late-or-has-a-blank-card")], None),
    ("zigbee_duplicate", "A Zigbee plug or sensor listed twice (W-88)",
     ["listed twice", "twice", "duplicate", "duplicate readings", "power reading", "power as unknown", "no power", "zigbee2mqtt", "two cards", "two lines"],
     [doc(MT, "a-zigbee-plug-or-sensor-is-listed-twice-charts-twice-or-shows-power-as-unknown-w-88-2026-10-21", "administrator")], None),
    ("camera_frames", "A camera is not delivering frames",
     ["camera", "video", "no events", "frames", "frigate", "blink camera", "blink bridge"],
     [doc(MT, "a-camera-is-not-delivering-frames", "administrator")], None),
]

# existing category edits: id -> (remove, add, documents to append)
EDITS = {
    "deployment_source": (["workflow", "manual"], ["github workflow", "deployed", "update", "roll back", "rollback"], []),
    "backup_restore_recipes": ([], ["restore from a backup"], []),
    "self_healing_recovery": ([], ["how do i restart", "restart a service", "restart one service", "blink bridge"], []),
    "health_verification": ([], ["everything working", "everything is working", "is it up"], []),
    "configuration_binding": ([], ["home assistant devices", "home assistant token", "not updating", "stopped updating", "token valid"], []),
    "health_triage": ([], ["incident", "postmortem", "root cause"], [doc(MT, "close-the-incident", "administrator")]),
    "rules_workflows": ([], ["built-in rule", "backend rule"], [S8]),
    "presence_roles_and_gaps": ([], ["counted but not assigned", "maintenance visit", "maintenance person", "primary and maintenance",
                                      "make someone a maintenance"], []),
}

# phrasing number (1-based, flattened) -> acceptable categories after the change (from the three proposals)
def _e(ids, *cats):
    return {i: set(cats) for i in ids}

EXPECTED = {}
for part in (
    _e([1, 2, 3, 4, 5, 14, 15, 20, 21], "status_checks"),
    _e([6, 7, 8, 9, 10, 12, 13], "alert_silencing"),
    _e([11], "alert_silencing", "leak_response"),
    _e([16, 17], "phone_notifications"),
    _e([22, 23, 29], "leak_response"),
    _e([38, 39, 40], "workflow_reset_undo", "workflow_run_history"),
    _e([24, 25], "optimization_opportunities", "zigbee_duplicate"),
    _e([18, 19, 26, 27, 34, 35], "workflow_basics", "rules_workflows"),
    _e([51], "workflow_basics", "rules_workflows", "who_can_change"),
    _e([105, 106, 107, 108, 109], "presence_roles_and_gaps"),
    _e([53, 54], "workflow_schedule"),
    _e([30, 31, 36, 37, 49, 50], "workflow_run_history"),
    _e([41, 42, 43, 44, 45, 46], "workflow_settings"),
    _e([28, 32, 33, 47, 48], "workflow_create"),
    _e([122, 123, 124], "access_sharing"),
    _e([125, 126], "managed_users"),
    _e([52], "who_can_change"),
    _e([112, 113, 114, 115], "storage_disk"),
    _e([131, 132], "deployment_source"),
    _e([116, 117], "backup_restore_recipes"),
    _e([118], "self_healing_recovery"),
    _e([119], "self_healing_recovery", "camera_frames"),
    _e([120, 121], "health_verification"),
    _e([129, 130], "configuration_binding"),
    _e([55, 56, 57, 58, 59, 60], "device_add"),
    _e([61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 74, 75, 76, 77, 78], "device_management"),
    _e([71, 72, 79, 80, 81, 82, 85, 86], "device_settings"),
    _e([83, 84, 91, 92], "device_control"),
    _e([87, 88, 133], "device_health"),
    _e([73, 89, 90, 134], "zigbee_duplicate"),
    _e([135, 136], "camera_frames"),
):
    EXPECTED.update(part)


def phrasings():
    data = json.loads(PHRASINGS.read_text(encoding="utf-8"))
    out, n = [], 0
    for topic, qs in data:
        for i, q in enumerate(qs, 1):
            n += 1
            out.append((n, f"{topic}-{i}", q))
    return out


def compile_cat(c):
    return [(re.compile(r"(?<![a-z0-9_])" + re.escape(k.lower().strip()) + r"(?:s|es)?(?![a-z0-9_])"), len(k.strip().split()))
            for k in c.get("keywordPatterns") or []]


def route(cats, q):
    n = re.sub(r"\s+", " ", q.lower())
    best, bs = None, 0
    for c in cats:
        s, m = 0, False
        for p, w in compile_cat(c):
            if p.search(n):
                s += w
                m = True
        if m:
            s += c.get("boost") or 0
        if s > bs:
            best, bs = c["id"], s
    return best


def routes(fx):
    return {n: route(fx["categories"], q) for n, _, q in phrasings()}


def apply(fx):
    fx = copy.deepcopy(fx)
    cats = fx["categories"]
    ids = [c["id"] for c in cats]
    for cid, (remove, add, docs) in EDITS.items():
        c = cats[ids.index(cid)]
        kws = [k for k in c["keywordPatterns"] if k not in remove]
        kws += [k for k in add if k not in kws]
        c["keywordPatterns"] = kws
        for d in docs:
            if d not in c["contextDocuments"]:
                c["contextDocuments"].append(d)

    def make(row):
        cid, desc, kws, docs, boost = row
        c = {"id": cid, "description": desc, "keywordPatterns": kws, "questions": [], "contextNodes": [], "oracleRefs": [], "contextDocuments": docs}
        if boost:
            c["boost"] = boost
        return c

    at = ids.index("rules_workflows")
    cats[at:at] = [make(r) for r in BEFORE_RULES]
    cats.extend(make(r) for r in APPENDED)
    return fx


def verdicts(fx):
    r = routes(fx)
    none = sum(1 for n in r if r[n] is None)
    wrong = sum(1 for n, ok in EXPECTED.items() if r[n] is not None and r[n] not in ok)
    miss = sum(1 for n, ok in EXPECTED.items() if r[n] is None)
    return r, none, miss, wrong


def main():
    mode = sys.argv[1]
    if mode == "--apply":
        fx = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        Path(sys.argv[3]).write_text(json.dumps(apply(fx), indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    elif mode == "--report":
        b = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        a = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))
        rb, nb, mb, wb = verdicts(b)
        ra, na, ma, wa = verdicts(a)
        print(f"all 150 phrasings: no category before {nb}, after {na}")
        print(f"in-scope phrasings ({len(EXPECTED)}): no category before {mb}, after {ma}; wrong category before {wb}, after {wa}")
        out_scope = [n for n in rb if n not in EXPECTED]
        changed = [(n, rb[n], ra[n]) for n in out_scope if rb[n] != ra[n]]
        print(f"out-of-scope phrasings ({len(out_scope)}): routing changed for {len(changed)}")
        for n, x, y in changed:
            print("  changed", n, x, "->", y)
        for n, ok in sorted(EXPECTED.items()):
            if ra[n] is None or ra[n] not in ok:
                print("  STILL", n, ra[n], "expected", sorted(ok))
    else:
        fx = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        for n, tid, q in phrasings():
            print(n, tid, route(fx["categories"], q))


if __name__ == "__main__":
    main()
