"""Throwaway Home Assistant simulation for the cabin presence package (W-23).

Starts a disposable Home Assistant container of the SAME version production runs,
loads the real `../../cabin_security_presence.yaml`, and drives it the way the
Developer Tools would: it moves a fake phone in and out of fake zones, flaps the
WiFi sensor, and simulates Companion app reports. A logging stand-in replaces the
MQTT publisher, so the script records what *would* have been published.

It never touches production: no real coordinates, no MQTT broker, no secrets.

    python run_sim.py            # fast: the 3 minute debounce is shortened to 8 s
    python run_sim.py --full     # runs the shipped 3 minute delays unmodified (~10 min)

Needs Docker and the image (pulled automatically). ~5 minutes in fast mode.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

IMAGE = "ghcr.io/home-assistant/home-assistant:2026.7.4"  # keep equal to production
NAME = "ha-sim-presence"
PORT = 18123
HERE = Path(__file__).resolve().parent
PACKAGE = HERE.parent.parent / "cabin_security_presence.yaml"

# Fake geometry (never real places). Cabin and House are ~111 km apart.
CABIN = (45.0, -92.0)
HOUSE = (44.0, -92.0)
FAR = (47.0, -90.0)

CONFIG = """\
homeassistant:
  name: Home
  latitude: 10.0
  longitude: 10.0
  unit_system: metric
  time_zone: America/Chicago
  packages: !include_dir_named packages
default_config:
logger:
  default: warning
  logs:
    homeassistant.components.automation: info
    homeassistant.helpers.template: warning
"""

SUPPORT = """\
zone:
  - name: Cabin
    latitude: 45.0
    longitude: -92.0
    radius: 100
  - name: House
    latitude: 44.0
    longitude: -92.0
    radius: 200

# Stand-in for the real publisher (a python script that talks to MQTT): appends
# "<epoch> <topic> <payload>" to a file the test reads.
shell_command:
  cabin_security_publish: >-
    sh -c 'echo "$(date +%s) {{ topic }} {{ payload }}" >> /config/published.log'
"""

WIFI_STUB = """#!/bin/sh
# Test stand-in for check_authorized_user_phone_wifi.sh: ON/OFF comes from a flag file.
cat /config/wifi_flag 2>/dev/null || echo OFF
"""


class Sim:
    def __init__(self, full):
        self.full = full
        self.cfg = Path(tempfile.mkdtemp(prefix="ha-sim-"))
        self.token = None
        self.results = []

    # ---- container + HA plumbing -------------------------------------------------
    def setup(self):
        self.setup_files()
        subprocess.run(["docker", "rm", "-f", NAME], capture_output=True)
        out = subprocess.run(["docker", "run", "-d", "--name", NAME, "-v", f"{self.cfg}:/config",
                              "-p", f"{PORT}:8123", IMAGE], capture_output=True, text=True)
        if out.returncode:
            sys.exit("docker run failed: " + out.stderr)
        subprocess.run(["docker", "exec", NAME, "chmod", "+x", "/config/check_authorized_user_phone_wifi.sh"], capture_output=True)

    def setup_files(self):
        (self.cfg / "packages").mkdir()
        (self.cfg / "configuration.yaml").write_text(CONFIG, encoding="utf-8", newline="\n")
        (self.cfg / "packages" / "sim_support.yaml").write_text(SUPPORT, encoding="utf-8", newline="\n")
        (self.cfg / "check_authorized_user_phone_wifi.sh").write_text(WIFI_STUB, encoding="utf-8", newline="\n")
        (self.cfg / "wifi_flag").write_text("OFF\n", encoding="utf-8", newline="\n")
        (self.cfg / "published.log").write_text("", encoding="utf-8")
        text = PACKAGE.read_text(encoding="utf-8")
        if not self.full:
            # Only numbers change: the 3 minute debounce, the 60 s WiFi poll and the 5 minute heartbeat tick.
            text = (text.replace('"00:03:00"', '"00:00:08"').replace("scan_interval: 60", "scan_interval: 5")
                        .replace('minutes: "/5"', 'seconds: "/5"'))
        (self.cfg / "packages" / "cabin_security_presence.yaml").write_text(text, encoding="utf-8", newline="\n")
        self.delay = 190 if self.full else 8

    def teardown(self):
        subprocess.run(["docker", "rm", "-f", NAME], capture_output=True)
        shutil.rmtree(self.cfg, ignore_errors=True)

    def http(self, method, path, body=None, auth=True, form=False, timeout=30):
        data, headers = None, {}
        if body is not None:
            if form:
                from urllib.parse import urlencode
                data, headers["Content-Type"] = urlencode(body).encode(), "application/x-www-form-urlencoded"
            else:
                data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
        if auth and self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(f"http://localhost:{PORT}{path}", data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw and raw.lstrip()[0] in "[{" else raw

    def wait_up(self, seconds=240):
        end = time.time() + seconds
        while time.time() < end:
            try:
                self.http("GET", "/api/onboarding", auth=False, timeout=5)
                return
            except Exception:
                time.sleep(3)
        sys.exit("Home Assistant did not come up:\n" + subprocess.run(["docker", "logs", "--tail", "30", NAME], capture_output=True, text=True).stdout)

    def onboard(self):
        client = f"http://localhost:{PORT}/"
        code = self.http("POST", "/api/onboarding/users", {"client_id": client, "name": "sim", "username": "sim",
                                                           "password": "sim-only-password", "language": "en"}, auth=False)["auth_code"]
        tok = self.http("POST", "/auth/token", {"grant_type": "authorization_code", "code": code, "client_id": client}, auth=False, form=True)
        self.token = tok["access_token"]

    def wait_automations(self, seconds=90):
        end = time.time() + seconds
        while time.time() < end:
            states = {s["entity_id"] for s in self.http("GET", "/api/states")}
            if "automation.cabin_presence_publish_phone_last_seen_heartbeat" in states:
                time.sleep(4)
                return
            time.sleep(3)

    # ---- helpers -----------------------------------------------------------------
    def set_state(self, entity, state, attrs=None):
        self.http("POST", f"/api/states/{entity}", {"state": state, "attributes": attrs or {}})

    def phone_at(self, where, zones):
        lat, lon = where
        self.set_state("device_tracker.nates_s23", zones[0] if zones else "not_home",
                       {"latitude": lat, "longitude": lon, "gps_accuracy": 10, "source_type": "gps",
                        "in_zones": zones})

    def render(self, template, variables=None):
        body = {"template": template}
        if variables:
            body["variables"] = variables
        return self.http("POST", "/api/template", body).strip()

    def published(self, topic=None):
        rows = []
        for line in (self.cfg / "published.log").read_text(encoding="utf-8").splitlines():
            parts = line.split(" ", 2)
            if len(parts) == 3 and (topic is None or parts[1] == topic):
                rows.append((int(parts[0]), parts[1], parts[2]))
        return rows

    def count(self, topic, payload=None):
        return len([r for r in self.published(topic) if payload is None or r[2] == payload])

    def wait_for(self, topic, payload, before, seconds=15):
        end = time.time() + seconds
        while time.time() < end:
            if self.count(topic, payload) > before:
                return True
            time.sleep(0.5)
        return False

    def check(self, name, ok, detail=""):
        self.results.append((name, ok, detail))
        print(("  PASS  " if ok else "  FAIL  ") + name + (f"   [{detail}]" if detail and not ok else ""), flush=True)

    # ---- the scenarios -----------------------------------------------------------
    def run(self):
        P = "cabin/presence/nate"
        HB = "cabin/presence/nate/last_seen"
        print("\n== loading ==")
        ids = {s["entity_id"]: s["state"] for s in self.http("GET", "/api/states") if s["entity_id"].startswith("automation.")}
        for a in ("automation.cabin_security_publish_nate_presence_from_phone_wifi",
                  "automation.cabin_security_publish_phone_based_presence_at_startup",
                  "automation.cabin_presence_publish_nate_presence_from_phone_gps_zone",
                  "automation.cabin_presence_note_home_assistant_start",
                  "automation.cabin_presence_publish_phone_last_seen_heartbeat"):
            self.check(f"{a.split('.')[1]} loaded and on", ids.get(a) == "on", str(ids.get(a)))
        log = self.http("GET", "/api/error_log")
        bad = [l for l in str(log).splitlines() if re.search(r"(?i)cabin[ _](security|presence)", l) and re.search(r"ERROR|Invalid|not found|failed|disabled", l)]
        self.check("no config errors mention the package", not bad, "; ".join(bad[:2]))

        print("   (letting start-up noise settle: the stand-in WiFi sensor's first poll and the start-up republish)", flush=True)
        time.sleep(35)
        (self.cfg / "published.log").write_text("", encoding="utf-8")
        print("\n== zone crossings (the Developer Tools zone simulation) ==")
        self.phone_at(FAR, [])                       # create the tracker away from every zone
        time.sleep(3)
        n_home = self.count(P, "home"); n_away = self.count(P, "not_home")
        self.phone_at(CABIN, ["zone.cabin"])
        self.check("entering the Cabin zone publishes home", self.wait_for(P, "home", n_home))
        n_home = self.count(P, "home"); n_away = self.count(P, "not_home")
        self.phone_at(FAR, [])
        time.sleep(self.delay / 2)
        self.check("leaving does NOT publish not_home immediately (debounce)", self.count(P, "not_home") == n_away)
        self.check(f"leaving publishes not_home after the {self.delay}s delay", self.wait_for(P, "not_home", n_away, self.delay + 15))

        n_home = self.count(P, "home"); n_away = self.count(P, "not_home")
        self.phone_at(CABIN, ["zone.cabin"]); self.wait_for(P, "home", n_home)
        n_home = self.count(P, "home")
        self.phone_at(FAR, [])
        time.sleep(self.delay / 3)
        self.phone_at(CABIN, ["zone.cabin"])         # came back inside the window
        self.check("re-entering inside the debounce window publishes home again", self.wait_for(P, "home", n_home))
        time.sleep(self.delay + 8)
        self.check("...and the pending not_home was cancelled", self.count(P, "not_home") == n_away)

        n_home = self.count(P, "home"); n_away = self.count(P, "not_home")
        self.phone_at(HOUSE, ["zone.house"])         # drive to the house
        self.check("going from the cabin to the house publishes not_home for the cabin", self.wait_for(P, "not_home", n_away, self.delay + 15))
        self.check("...and never publishes home on the cabin topic for the House zone", self.count(P, "home") == n_home)

        print("\n== startup republish (must not overwrite a correct GPS home) ==")
        tpl = ("{{ 'home' if (is_state('binary_sensor.nate_phone_on_wifi', 'on') "
               "or 'zone.cabin' in (state_attr('device_tracker.nates_s23', 'in_zones') or [])) else 'not_home' }}")
        self.phone_at(CABIN, ["zone.cabin"]); time.sleep(1)
        before = self.count(P, "home")
        self.http("POST", "/api/services/automation/trigger", {"entity_id": "automation.cabin_security_publish_phone_based_presence_at_startup", "skip_condition": True})
        self.check("startup action publishes home when GPS has the phone in the Cabin zone", self.wait_for(P, "home", before, 25))
        self.phone_at(HOUSE, ["zone.house"]); time.sleep(self.delay + 10)
        before = self.count(P, "not_home")
        self.http("POST", "/api/services/automation/trigger", {"entity_id": "automation.cabin_security_publish_phone_based_presence_at_startup", "skip_condition": True})
        self.check("startup action publishes not_home when the phone is elsewhere", self.wait_for(P, "not_home", before, 25))

        print("\n== WiFi backup ==")
        def wifi(v): (self.cfg / "wifi_flag").write_text(v + "\n", encoding="utf-8", newline="\n")
        self.phone_at(FAR, []); time.sleep(self.delay + 8)
        n_home = self.count(P, "home")
        wifi("ON")
        self.check("WiFi on still publishes home (backup path works)", self.wait_for(P, "home", n_home, 30))
        n_away = self.count(P, "not_home")
        wifi("OFF")
        self.check("WiFi off with the phone away from the Cabin zone publishes not_home", self.wait_for(P, "not_home", n_away, 30 + self.delay))
        wifi("ON"); time.sleep(12)
        self.phone_at(CABIN, ["zone.cabin"]); time.sleep(2)
        n_away = self.count(P, "not_home")
        wifi("OFF"); time.sleep(self.delay + 20)
        self.check("WiFi off while GPS has the phone IN the Cabin zone is suppressed (no false away)", self.count(P, "not_home") == n_away)
        cond = "{{ trigger.to_state.state == 'on' or 'zone.cabin' not in (state_attr('device_tracker.nates_s23', 'in_zones') or []) }}"
        self.phone_at(CABIN, ["zone.cabin"])
        self.check("condition: off + in zone -> blocked", self.render(cond, {"trigger": {"to_state": {"state": "off"}}}) == "False")
        self.check("condition: on + in zone -> allowed", self.render(cond, {"trigger": {"to_state": {"state": "on"}}}) == "True")
        self.phone_at(FAR, [])
        self.check("condition: off + away -> allowed", self.render(cond, {"trigger": {"to_state": {"state": "off"}}}) == "True")

        print("\n== heartbeat (cabin/presence/nate/last_seen) ==")
        bat = "sensor.nates_s23_battery_level"
        STAMP = "%Y-%m-%dT%H:%M:%SZ"
        def last_hb():
            rows = self.published(HB)
            return rows[-1][2] if rows else None
        def hb_offset(stamp, since):
            """Seconds between a published heartbeat and the wall-clock moment this script made the phone 'report'."""
            return (datetime.strptime(stamp, STAMP).replace(tzinfo=timezone.utc) - since).total_seconds()
        base = self.count(HB)
        time.sleep(12)
        self.check("no heartbeat while the phone's sensor does not exist", self.count(HB) == base)
        t1 = datetime.now(timezone.utc)
        self.set_state(bat, "80")
        self.check("a phone report is published as a heartbeat on the next tick", self.wait_for(HB, None, base, 20))
        stamp = last_hb()
        self.check("the heartbeat is a UTC ISO time", bool(stamp and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", stamp)), repr(stamp))
        # Independent truth: the wall clock when this script reported. (Reading last_reported back over REST is
        # not usable as truth: after a same-value re-set it returned the previous, cached value.)
        self.check("...equal to when the phone reported (within 3 s), not the tick time", stamp is not None and abs(hb_offset(stamp, t1)) <= 3, f"{stamp!r} vs {t1.strftime(STAMP)}")
        time.sleep(8)                            # at least one more tick passes with the phone silent
        first = last_hb()
        self.check("while the phone is silent the heartbeat does NOT advance, so it ages out", first == stamp, f"{first!r} vs {stamp!r}")
        t2 = datetime.now(timezone.utc)
        self.set_state(bat, "80")                # SAME value again: last_reported advances, no state_changed fires
        end, newer = time.time() + 20, None
        while time.time() < end:
            if last_hb() != first:
                newer = last_hb(); break
            time.sleep(1)
        self.check("an UNCHANGED re-report advances the heartbeat (a plain state trigger would miss this)", newer is not None and abs(hb_offset(newer, t2)) <= 3, f"{newer!r} vs {t2.strftime(STAMP)}")

        print("   (restart guard: a Home Assistant start newer than the phone's last report must publish nothing)", flush=True)
        self.http("POST", "/api/services/automation/trigger", {"entity_id": "automation.cabin_presence_note_home_assistant_start", "skip_condition": True})
        time.sleep(6)                            # let a tick that was already in flight finish, then count
        n = self.count(HB)
        time.sleep(14)                           # several ticks
        self.check("after a restart marker newer than the last report, nothing is published (restored sensors look fresh but are not)", self.count(HB) == n, f"{self.count(HB) - n} published")
        # Fail closed: the guard must not silently pass if the marker entity is missing (an automation's entity
        # id comes from its alias, not its `id:`; a first version looked up the wrong one and let everything through).
        block = re.search(r"cabin_presence_publish_nate_last_seen.*?value_template: >-\n(.*?)\n    actions:", PACKAGE.read_text(encoding="utf-8"), re.S)
        cond_tpl = "\n".join(line.strip() for line in block.group(1).splitlines()) if block else ""
        marker = "cabin_presence_note_home_assistant_start"
        self.check("the guard's template names the real marker entity", marker in cond_tpl and "states.automation." + marker in cond_tpl)
        self.check("fail closed: with the marker entity missing the condition is false",
                   self.render(cond_tpl.replace(marker, "cabin_presence_no_such_marker")) == "False")
        t3 = datetime.now(timezone.utc)
        self.set_state(bat, "79")
        self.check("...and a genuine report after the restart resumes the heartbeat", self.wait_for(HB, None, n, 20))
        self.check("...carrying the new report's time", abs(hb_offset(last_hb(), t3)) <= 3, f"{last_hb()!r} vs {t3.strftime(STAMP)}")
        n = self.count(HB)
        self.set_state(bat, "unavailable"); time.sleep(14)
        self.check("an unavailable sensor is never published as a heartbeat", self.count(HB) == n)

        print("\n== restart: no heartbeat may be published at startup ==")
        n = self.count(HB)
        subprocess.run(["docker", "restart", NAME], capture_output=True)
        self.wait_up(); time.sleep(40)
        self.check("a Home Assistant restart publishes no heartbeat (the broker keeps the last real one)", self.count(HB) == n)


    def report(self):
        fails = [r for r in self.results if not r[1]]
        print(f"\n{len(self.results) - len(fails)} passed, {len(fails)} failed")
        return 1 if fails else 0


def check_config_only(full):
    """Load the package into a real Home Assistant of the production version and print its config check (~20 s)."""
    sim = Sim(full)
    try:
        sim.setup_files()
        out = subprocess.run(["docker", "run", "--rm", "-v", f"{sim.cfg}:/config", IMAGE,
                              "python", "-m", "homeassistant", "--script", "check_config", "-c", "/config"],
                             capture_output=True, text=True)
        print(out.stdout[-4000:], out.stderr[-2000:])
        return out.returncode
    finally:
        shutil.rmtree(sim.cfg, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", action="store_true", help="use the shipped 3 minute delays unmodified")
    ap.add_argument("--keep", action="store_true", help="leave the container running afterwards")
    ap.add_argument("--check-config", action="store_true", help="only run Home Assistant's own config check on the package, then exit")
    args = ap.parse_args()
    if args.check_config:
        return check_config_only(args.full)
    sim = Sim(args.full)
    try:
        sim.setup()
        print("waiting for Home Assistant...", flush=True)
        sim.wait_up()
        sim.onboard()
        sim.wait_automations()
        sim.run()
        return sim.report()
    finally:
        if not args.keep:
            sim.teardown()


if __name__ == "__main__":
    sys.exit(main())
