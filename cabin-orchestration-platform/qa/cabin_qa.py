#!/usr/bin/env python3
"""
FaceoftheCabin Platform QA Runner  v1.0.0
------------------------------------------
Device-aware validation and remediation for the FaceoftheCabin cabin/home
automation platform. Git-portable: works on Windows (ilikethelights), Linux
(M920q cabin hub), or any future machine that clones the repo.

Usage:
  python3 qa/cabin_qa.py                     # detect machine, run all checks
  python3 qa/cabin_qa.py --fix               # auto-remediate safe failures
  python3 qa/cabin_qa.py --group env,network # run specific check groups
  python3 qa/cabin_qa.py --machine ilikethelights
  python3 qa/cabin_qa.py --json              # machine-readable JSON output
  python3 qa/cabin_qa.py --list              # list groups and exit
"""

import argparse
import io
import json
import os
import platform
import socket
import subprocess
import sys
import urllib.request
import urllib.error
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# Force UTF-8 output on Windows so box/arrow/check characters survive the console
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ── Version ───────────────────────────────────────────────────────────────────

VERSION = "1.0.0"
REPO_ROOT_MARKER = "CLAUDE.md"
PLATFORM_DIR = "cabin-orchestration-platform"
CABIN_TAILSCALE_IP = "100.77.44.113"
TAILSCALE_ACCOUNT_HINT = "nhsmrekar@gmail.com"

# ── Result codes ──────────────────────────────────────────────────────────────

PASS = "PASS"
WARN = "WARN"
FAIL = "FAIL"
SKIP = "SKIP"

# ── ANSI color (Windows Terminal supports it; old cmd does not) ───────────────

def _supports_ansi():
    if os.environ.get("NO_COLOR") or os.environ.get("TERM") == "dumb":
        return False
    if os.environ.get("FORCE_COLOR"):
        return True
    if not sys.stdout.isatty():
        return False
    if platform.system() == "Windows":
        # Enable VT processing on Windows 10+
        try:
            import ctypes
            kernel = ctypes.windll.kernel32
            kernel.SetConsoleMode(kernel.GetStdHandle(-11), 7)
            return True
        except Exception:
            return False
    return True

_COLOR = _supports_ansi()
_C = {
    PASS:  "\033[92m",
    WARN:  "\033[93m",
    FAIL:  "\033[91m",
    SKIP:  "\033[90m",
    "DIM": "\033[90m",
    "RST": "\033[0m",
    "BOLD": "\033[1m",
    "CYN": "\033[96m",
}

def _c(key, text):
    return f"{_C.get(key,'')}{text}{_C['RST']}" if _COLOR else text

def _bold(text):
    return f"{_C['BOLD']}{text}{_C['RST']}" if _COLOR else text

# ── Data types ────────────────────────────────────────────────────────────────

@dataclass
class Result:
    name: str
    group: str
    status: str          # PASS | WARN | FAIL | SKIP
    message: str
    detail: str = ""
    # Remediation: either a shell command (auto-runnable) or a prose description
    fix_cmd: str = ""
    fix_desc: str = ""
    fixed: bool = False  # set True if --fix applied this

@dataclass
class MachineProfile:
    hostname: str
    role: str                         # dev | cabin-hub | home-hub | unknown
    os: str                           # windows | linux | darwin | unknown
    mqtt_url: str = f"tcp://{CABIN_TAILSCALE_IP}:1883"
    ha_url: str = f"http://{CABIN_TAILSCALE_IP}:8123"
    backend_port: int = 8090
    tailscale_ip: str = ""
    # Containers expected FROM the FaceoftheCabin stack on this machine
    fc_containers: list = field(default_factory=list)
    # Containers that exist independently (don't treat as missing)
    existing_containers: list = field(default_factory=list)
    # Ports owned by the existing stack (FaceoftheCabin must not collide)
    existing_ports: dict = field(default_factory=dict)

# ── Known machine profiles ────────────────────────────────────────────────────

_MACHINES = {
    "ilikethelights": MachineProfile(
        hostname="ilikethelights",
        role="dev",
        os="windows",
        mqtt_url=f"tcp://{CABIN_TAILSCALE_IP}:1883",
        ha_url=f"http://{CABIN_TAILSCALE_IP}:8123",
        backend_port=8090,
        tailscale_ip="",  # changes; detect at runtime
        fc_containers=["cabin-postgres", "cabin-kafka"],
        existing_containers=[],
        existing_ports={},
    ),
    "nates-little-m920q": MachineProfile(
        hostname="nates-little-m920q",
        role="cabin-hub",
        os="linux",
        mqtt_url="tcp://localhost:1883",
        ha_url="http://localhost:8123",
        backend_port=8090,
        tailscale_ip=CABIN_TAILSCALE_IP,
        fc_containers=["cabin-postgres", "cabin-kafka", "cabin-grafana", "cabin-backend", "cabin-ui"],
        existing_containers=["mosquitto", "homeassistant", "nodered", "frigate",
                             "zigbee2mqtt", "homepage", "uptime-kuma", "mediamtx"],
        existing_ports={
            1883: "mosquitto",
            8123: "homeassistant",
            1880: "nodered",
            5000: "frigate",
            8080: "zigbee2mqtt",
            3000: "homepage",
            3001: "uptime-kuma",
        },
    ),
}

def detect_machine() -> MachineProfile:
    h = platform.node().lower().split(".")[0]
    if h in _MACHINES:
        return _MACHINES[h]
    return MachineProfile(
        hostname=h,
        role="unknown",
        os={"Windows": "windows", "Linux": "linux", "Darwin": "darwin"}.get(
            platform.system(), "unknown"),
    )

# ── Repo root ─────────────────────────────────────────────────────────────────

def find_repo_root() -> Optional[Path]:
    p = Path(__file__).resolve()
    for _ in range(12):
        if (p / REPO_ROOT_MARKER).exists():
            return p
        p = p.parent
    # Fallback: walk up from CWD
    p = Path.cwd()
    for _ in range(12):
        if (p / REPO_ROOT_MARKER).exists():
            return p
        if p.parent == p:
            break
        p = p.parent
    return None

# ── Shell / network helpers ───────────────────────────────────────────────────

def _run(cmd: str, timeout: int = 12) -> tuple:
    """Return (rc, stdout, stderr). Always captures; shell=True."""
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout.strip(), r.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "timeout"
    except Exception as e:
        return -1, "", str(e)

def _run_ps(ps_expr: str, timeout: int = 15) -> tuple:
    """Run a PowerShell expression on Windows."""
    cmd = f'powershell -NonInteractive -Command "{ps_expr}"'
    return _run(cmd, timeout=timeout)

def _tcp(host: str, port: int, timeout: float = 3.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False

def _http(url: str, token: str = "", timeout: float = 6.0) -> tuple:
    """Return (http_status, body_str). status=-1 on connection failure."""
    try:
        req = urllib.request.Request(url)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", errors="replace")
        except Exception:
            body = str(e)
        return e.code, body
    except Exception as e:
        return -1, str(e)

def _load_env(path: Path) -> dict:
    env = {}
    if not path.exists():
        return env
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return env
    for line in content.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    return env

def _is_win():
    return platform.system() == "Windows"

# ── Check runner ──────────────────────────────────────────────────────────────

class QARunner:
    def __init__(self, machine: MachineProfile, repo_root: Optional[Path],
                 fix: bool = False, json_out: bool = False):
        self.machine = machine
        self.repo_root = repo_root
        self.plat = (repo_root / PLATFORM_DIR) if repo_root else None
        self.fix = fix
        self.json_out = json_out
        self.results: list[Result] = []
        self._env: dict = {}       # cabin-orchestration-platform/.env
        self._ui_env: dict = {}    # ui/.env.local
        self._ha_token: str = ""
        self._load_envs()

    def _load_envs(self):
        if self.plat:
            self._env = _load_env(self.plat / ".env")
            self._ui_env = _load_env(self.plat / "ui" / ".env.local")
            self._ha_token = self._env.get("HA_TOKEN", "")

    def _emit(self, r: Result):
        self.results.append(r)
        if self.json_out:
            return
        icon = {"PASS": "✓", "WARN": "!", "FAIL": "✗", "SKIP": "–"}.get(r.status, "?")
        label = _c(r.status, f"[{r.status}]")
        print(f"  {label:<22} {icon} {r.name}: {r.message}")
        if r.detail:
            for line in r.detail.splitlines():
                print(f"               {_c('DIM', line)}")

    def _try_fix(self, r: Result) -> bool:
        if not r.fix_cmd:
            return False
        print(f"               → {r.fix_desc or 'Applying fix...'}")
        rc, out, err = _run(r.fix_cmd, timeout=60)
        if rc == 0:
            print(f"               {_c(PASS, '✓ Done')}")
            return True
        print(f"               {_c(FAIL, '✗ Failed:')} {(err or out)[:120]}")
        return False

    def _section(self, title: str):
        if not self.json_out:
            print(_bold(f"\n-- {title} {'-' * max(1, 55 - len(title))}"))

    # ─── GROUP: system ────────────────────────────────────────────────────────

    def check_system(self):
        self._section("System Prerequisites")

        # Java
        rc, out, _ = _run("java --version 2>&1")
        if rc == 0 and "21" in out:
            self._emit(Result("java", "system", PASS, out.splitlines()[0]))
        elif rc == 0:
            ver = out.splitlines()[0]
            self._emit(Result("java", "system", FAIL,
                f"Wrong Java version: {ver} (need 21)",
                fix_desc="Install Java 21 Temurin",
                fix_cmd="winget install EclipseAdoptium.Temurin.21.JDK" if _is_win()
                        else "sudo apt install -y openjdk-21-jdk"))
        else:
            self._emit(Result("java", "system", FAIL, "Java not found on PATH",
                detail="Need JDK 21 on PATH before running the backend.",
                fix_desc="Install Java 21 Temurin",
                fix_cmd="winget install EclipseAdoptium.Temurin.21.JDK" if _is_win()
                        else "sudo apt install -y openjdk-21-jdk"))

        # Maven
        rc, out, _ = _run("mvn --version 2>&1")
        if rc == 0:
            self._emit(Result("maven", "system", PASS, out.splitlines()[0]))
        else:
            detail = (
                "The repo's mvnw wrapper is a stub (just calls `mvn`). "
                "Install Maven separately and add to PATH.\n"
                "Windows: download apache-maven-3.9.16-bin.zip → extract to "
                "C:\\Program Files\\Maven → add bin\\ to user PATH."
            )
            self._emit(Result("maven", "system", FAIL, "mvn not found on PATH", detail=detail,
                fix_desc="Install Maven (apt only — Windows requires manual install)",
                fix_cmd="" if _is_win() else "sudo apt install -y maven"))

        # Node
        rc, out, _ = _run("node --version 2>&1")
        if rc == 0:
            self._emit(Result("node", "system", PASS, f"Node.js {out}"))
        else:
            self._emit(Result("node", "system", FAIL, "Node.js not found on PATH",
                fix_cmd="winget install OpenJS.NodeJS.LTS" if _is_win()
                        else "sudo apt install -y nodejs npm"))

        # npm + execution policy (Windows)
        rc, out, _ = _run("npm --version 2>&1")
        if rc == 0:
            self._emit(Result("npm", "system", PASS, f"npm v{out}"))
        elif _is_win():
            self._emit(Result("npm", "system", FAIL,
                "npm not usable — PowerShell execution policy blocking scripts",
                detail="Run in PowerShell: Set-ExecutionPolicy RemoteSigned -Scope CurrentUser",
                fix_desc="Set RemoteSigned execution policy for current user",
                fix_cmd='powershell -Command "Set-ExecutionPolicy RemoteSigned -Scope CurrentUser -Force"'))
        else:
            self._emit(Result("npm", "system", FAIL, "npm not found"))

        # Docker
        rc, out, _ = _run("docker info --format '{{.ServerVersion}}' 2>&1")
        if rc == 0 and out and "error" not in out.lower():
            self._emit(Result("docker", "system", PASS, f"Docker Engine v{out}"))
        else:
            self._emit(Result("docker", "system", FAIL,
                "Docker not running or not installed",
                detail="Start Docker Desktop (Windows) or: sudo systemctl start docker (Linux)"))

        # Python (self-check)
        self._emit(Result("python", "system", PASS,
            f"Python {sys.version.split()[0]} (this script)"))

    # ─── GROUP: git ───────────────────────────────────────────────────────────

    def check_git(self):
        self._section("Git Repository")

        if not self.repo_root:
            self._emit(Result("repo-root", "git", FAIL,
                "Cannot find repo root — CLAUDE.md not found in any parent directory",
                detail="Run cabin_qa.py from inside the FaceoftheCabin repo."))
            return

        self._emit(Result("repo-root", "git", PASS, str(self.repo_root)))

        # Remote points to right org
        rc, origin, _ = _run(f"git -C \"{self.repo_root}\" remote get-url origin")
        if "ImpressiveLLC/FaceoftheCabin" in origin:
            self._emit(Result("git-remote", "git", PASS, origin))
        elif "smrekarfamilia-sudo" in origin or "smrekar-platform" in origin:
            self._emit(Result("git-remote", "git", FAIL,
                f"Old remote still configured: {origin}",
                detail="Update: git remote set-url origin https://github.com/ImpressiveLLC/FaceoftheCabin.git",
                fix_desc="Update remote to ImpressiveLLC/FaceoftheCabin",
                fix_cmd=f"git -C \"{self.repo_root}\" remote set-url origin "
                        "https://github.com/ImpressiveLLC/FaceoftheCabin.git"))
        else:
            self._emit(Result("git-remote", "git", WARN, f"Unexpected remote: {origin}"))

        # Branch
        rc, branch, _ = _run(f"git -C \"{self.repo_root}\" branch --show-current")
        self._emit(Result("git-branch", "git",
            PASS if branch == "main" else WARN, f"Branch: {branch}"))

        # Working tree
        rc, dirty, _ = _run(f"git -C \"{self.repo_root}\" status --porcelain")
        if dirty:
            n = len(dirty.splitlines())
            self._emit(Result("git-clean", "git", WARN,
                f"{n} uncommitted change(s)", detail=dirty[:500]))
        else:
            self._emit(Result("git-clean", "git", PASS, "Working tree clean"))

        # .gitignore encodes .env
        gi_path = self.plat / ".gitignore" if self.plat else None
        if gi_path and gi_path.exists():
            content = gi_path.read_text(encoding="utf-8", errors="replace")
            if ".env" in content:
                self._emit(Result("gitignore-env", "git", PASS, ".env in .gitignore"))
            else:
                self._emit(Result("gitignore-env", "git", FAIL,
                    ".env NOT in .gitignore — secret files may be committed",
                    fix_desc="Add .env to .gitignore",
                    fix_cmd=f'echo ".env" >> "{gi_path}"'))

        # Sync (non-blocking — skip on timeout)
        _run(f"git -C \"{self.repo_root}\" fetch origin main --quiet", timeout=10)
        rc, behind, _ = _run(
            f"git -C \"{self.repo_root}\" rev-list --count HEAD..origin/main")
        rc2, ahead, _ = _run(
            f"git -C \"{self.repo_root}\" rev-list --count origin/main..HEAD")
        if behind == "0" and ahead == "0":
            self._emit(Result("git-sync", "git", PASS, "In sync with origin/main"))
        elif behind not in ("0", ""):
            self._emit(Result("git-sync", "git", WARN,
                f"{behind} commits behind origin/main — pull recommended",
                fix_desc="Pull latest",
                fix_cmd=f"git -C \"{self.repo_root}\" pull origin main"))
        elif ahead not in ("0", ""):
            self._emit(Result("git-sync", "git", WARN,
                f"{ahead} local commit(s) not pushed"))

    # ─── GROUP: env ──────────────────────────────────────────────────────────

    def check_env(self):
        self._section("Environment Configuration")

        if not self.plat:
            self._emit(Result("env-plat", "env", SKIP, "Repo root unknown — skipping env checks"))
            return

        env_path = self.plat / ".env"

        # .env file
        if env_path.exists():
            self._emit(Result("env-file", "env", PASS, f"{env_path}"))
        else:
            example = self.plat / "infra" / ".env.m920q.example"
            self._emit(Result("env-file", "env", FAIL,
                f".env not found at {env_path}",
                detail="Copy infra/.env.m920q.example and fill in secrets.",
                fix_desc="Create .env from example" if example.exists() else "",
                fix_cmd=f'copy "{example}" "{env_path}"' if example.exists() and _is_win()
                        else (f'cp "{example}" "{env_path}"' if example.exists() else "")))

        # MQTT_URL
        mqtt = self._env.get("MQTT_URL", "")
        if not mqtt:
            self._emit(Result("mqtt-url", "env", FAIL, "MQTT_URL not set in .env",
                detail=f"Add: MQTT_URL=tcp://{CABIN_TAILSCALE_IP}:1883"))
        elif "localhost" in mqtt or "127.0.0.1" in mqtt:
            if self.machine.role == "cabin-hub":
                self._emit(Result("mqtt-url", "env", PASS,
                    f"MQTT_URL={mqtt} (localhost OK on cabin hub)"))
            else:
                self._emit(Result("mqtt-url", "env", WARN,
                    f"MQTT_URL points to localhost: {mqtt}",
                    detail=f"For remote dev, set MQTT_URL=tcp://{CABIN_TAILSCALE_IP}:1883"))
        else:
            self._emit(Result("mqtt-url", "env", PASS, f"MQTT_URL={mqtt}"))

        # HA_URL
        ha_url = self._env.get("HA_URL", "")
        if not ha_url:
            self._emit(Result("ha-url", "env", WARN, "HA_URL not set — using default localhost:8123"))
        elif "localhost" in ha_url and self.machine.role == "dev":
            self._emit(Result("ha-url", "env", WARN,
                f"HA_URL=localhost for a dev machine",
                detail=f"Set HA_URL=http://{CABIN_TAILSCALE_IP}:8123"))
        else:
            self._emit(Result("ha-url", "env", PASS, f"HA_URL={ha_url}"))

        # HA_TOKEN
        if not self._ha_token:
            self._emit(Result("ha-token", "env", FAIL, "HA_TOKEN not set in .env",
                detail="HA → Profile → Security → Long-Lived Access Tokens → Create Token"))
        elif len(self._ha_token) < 100:
            self._emit(Result("ha-token", "env", WARN,
                f"HA_TOKEN looks short ({len(self._ha_token)} chars) — may be invalid"))
        else:
            self._emit(Result("ha-token", "env", PASS,
                f"HA_TOKEN set ({len(self._ha_token)} chars)"))

        # POSTGRES_PASSWORD
        pg = self._env.get("POSTGRES_PASSWORD", "")
        if pg:
            self._emit(Result("postgres-pw", "env", PASS, "POSTGRES_PASSWORD set"))
        else:
            self._emit(Result("postgres-pw", "env", WARN,
                "POSTGRES_PASSWORD not set — Spring Boot will use default 'cabin_dev_password'"))

        # ui/.env.local
        ui_env_path = self.plat / "ui" / ".env.local"
        if ui_env_path.exists():
            self._emit(Result("ui-env-file", "env", PASS, str(ui_env_path)))
        else:
            self._emit(Result("ui-env-file", "env", WARN,
                "ui/.env.local not found — UI will use fallback URLs (cabin-hub hostname)"))

        api_base = self._ui_env.get("VITE_CABIN_API_BASE", "")
        if ":8080" in api_base:
            self._emit(Result("ui-api-port", "env", FAIL,
                f"VITE_CABIN_API_BASE still uses old port 8080: {api_base}",
                detail="Backend moved to 8090. Update ui/.env.local, then restart Vite."))
        elif api_base:
            self._emit(Result("ui-api-port", "env", PASS, f"VITE_CABIN_API_BASE={api_base}"))
        else:
            self._emit(Result("ui-api-port", "env", WARN,
                "VITE_CABIN_API_BASE not set — UI falls back to cabin-hub:8090"))

        z2m = self._ui_env.get("VITE_CABIN_Z2M_URL", "")
        if z2m:
            self._emit(Result("ui-z2m-url", "env", PASS, f"VITE_CABIN_Z2M_URL={z2m}"))
        else:
            self._emit(Result("ui-z2m-url", "env", WARN,
                "VITE_CABIN_Z2M_URL not set — Z2M link falls back to cabin-hub:8080"))

    # ─── GROUP: auth ─────────────────────────────────────────────────────────

    def check_auth(self):
        self._section("Authentication & Interoperability")

        # Tailscale
        rc, ts_out, _ = _run("tailscale status --json 2>&1", timeout=8)
        if rc == 0 and ts_out.startswith("{"):
            try:
                ts = json.loads(ts_out)
                state = ts.get("BackendState", "")
                self_node = ts.get("Self", {})
                ts_dns = self_node.get("DNSName", "").rstrip(".")
                ts_ips = self_node.get("TailscaleIPs", [])
                ts_user = ts.get("User", {})
                # User is a dict keyed by ID; LoginName is the email
                ts_email = ""
                if ts_user:
                    first = next(iter(ts_user.values()), {})
                    ts_email = first.get("LoginName", "")

                if state == "Running":
                    self._emit(Result("tailscale-state", "auth", PASS,
                        f"Tailscale up — {ts_dns} {ts_ips}"))
                    if ts_email:
                        if TAILSCALE_ACCOUNT_HINT.split("@")[1] in ts_email:
                            self._emit(Result("tailscale-account", "auth", PASS,
                                f"Logged in as: {ts_email}"))
                        else:
                            self._emit(Result("tailscale-account", "auth", WARN,
                                f"Tailscale account: {ts_email} (expected {TAILSCALE_ACCOUNT_HINT})"))
                else:
                    self._emit(Result("tailscale-state", "auth", WARN,
                        f"Tailscale BackendState={state}",
                        fix_desc="Bring Tailscale online",
                        fix_cmd="tailscale up"))
            except json.JSONDecodeError:
                self._emit(Result("tailscale-state", "auth", WARN,
                    "Tailscale present but status unreadable"))
        else:
            self._emit(Result("tailscale-state", "auth", WARN,
                "Tailscale not found or not responding",
                detail="Install from https://tailscale.com/download — needed for cross-device access"))

        # HA token validity
        if self._ha_token:
            ha_url = self._env.get("HA_URL", f"http://{CABIN_TAILSCALE_IP}:8123")
            status, body = _http(f"{ha_url}/api/", token=self._ha_token)
            if status == 200:
                try:
                    msg = json.loads(body).get("message", "ok")
                except Exception:
                    msg = "ok"
                self._emit(Result("ha-token-valid", "auth", PASS,
                    f"HA token accepted — {msg}"))
            elif status == 401:
                self._emit(Result("ha-token-valid", "auth", FAIL,
                    "HA token rejected (401)",
                    detail=f"Token is invalid or revoked. Go to {ha_url}/profile#security and create a new Long-Lived Access Token, then update .env"))
            elif status == -1:
                self._emit(Result("ha-token-valid", "auth", WARN,
                    f"Cannot reach HA to validate token: {body[:80]}",
                    detail="Verify Tailscale is connected and cabin hub is online."))
            else:
                self._emit(Result("ha-token-valid", "auth", WARN,
                    f"HA responded {status} — token status unknown"))

        # Cross-device: can we reach the cabin hub at all?
        ts_ping_ok = False
        rc, out, _ = _run(f"tailscale ping --c 1 {CABIN_TAILSCALE_IP} 2>&1", timeout=8)
        if rc == 0 and "pong" in out.lower():
            ts_ping_ok = True
            self._emit(Result("tailscale-ping-cabin", "auth", PASS,
                f"Tailscale ping to cabin hub {CABIN_TAILSCALE_IP} OK"))
        else:
            # Fallback: raw TCP to a known cabin port
            if _tcp(CABIN_TAILSCALE_IP, 22, timeout=3) or _tcp(CABIN_TAILSCALE_IP, 8123, timeout=3):
                ts_ping_ok = True
                self._emit(Result("tailscale-ping-cabin", "auth", PASS,
                    f"Cabin hub {CABIN_TAILSCALE_IP} reachable (TCP)"))
            else:
                self._emit(Result("tailscale-ping-cabin", "auth", WARN,
                    f"Cabin hub {CABIN_TAILSCALE_IP} not reachable",
                    detail="Check Tailscale is connected and the M920q is powered on."))

    # ─── GROUP: network ───────────────────────────────────────────────────────

    def check_network(self):
        self._section("Service Connectivity")

        # MQTT (TCP 1883)
        ok = _tcp(CABIN_TAILSCALE_IP, 1883)
        self._emit(Result("mqtt-tcp", "network",
            PASS if ok else FAIL,
            f"Cabin MQTT {CABIN_TAILSCALE_IP}:1883 — {'reachable' if ok else 'unreachable'}",
            detail="" if ok else
                "Backend cannot subscribe to Z2M topics without MQTT. "
                "Confirm M920q is online and Tailscale connected."))

        # MQTT WebSocket (9001) — needed for live UI tile updates
        ws_ok = _tcp(CABIN_TAILSCALE_IP, 9001)
        self._emit(Result("mqtt-ws", "network",
            PASS if ws_ok else WARN,
            f"Cabin MQTT WebSocket {CABIN_TAILSCALE_IP}:9001 — {'reachable' if ws_ok else 'not open'}",
            detail="" if ws_ok else
                "Port 9001 not listening. Enable WebSocket in mosquitto.conf:\n"
                "  listener 9001\n  protocol websockets\n"
                "Then: docker exec mosquitto mosquitto -c /mosquitto/config/mosquitto.conf --restart\n"
                "Without this, live device-state tiles in the UI won't update."))

        # Home Assistant
        ha_status, _ = _http(f"http://{CABIN_TAILSCALE_IP}:8123/api/")
        if ha_status in (200, 401):
            self._emit(Result("ha-api", "network", PASS,
                f"Cabin HA {CABIN_TAILSCALE_IP}:8123 responding ({ha_status})"))
        else:
            self._emit(Result("ha-api", "network", FAIL,
                f"Cabin HA {CABIN_TAILSCALE_IP}:8123 returned {ha_status}",
                detail="HA may not be running. Check: docker ps on M920q."))

        # Zigbee2MQTT UI
        z2m_status, _ = _http(f"http://{CABIN_TAILSCALE_IP}:8080")
        if z2m_status in (200, 301, 302):
            self._emit(Result("z2m-ui", "network", PASS,
                f"Z2M {CABIN_TAILSCALE_IP}:8080 responding ({z2m_status})"))
        else:
            self._emit(Result("z2m-ui", "network", FAIL,
                f"Z2M {CABIN_TAILSCALE_IP}:8080 returned {z2m_status}",
                detail="Zigbee2MQTT container may be down. Check: docker logs zigbee2mqtt"))

        # Grafana
        g_status, _ = _http(f"http://{CABIN_TAILSCALE_IP}:3002")
        self._emit(Result("grafana", "network",
            PASS if g_status in (200, 302) else WARN,
            f"Grafana {CABIN_TAILSCALE_IP}:3002 — {g_status}"))

        # Node-RED
        nr_status, _ = _http(f"http://{CABIN_TAILSCALE_IP}:1880")
        self._emit(Result("nodered", "network",
            PASS if nr_status in (200, 301) else WARN,
            f"Node-RED {CABIN_TAILSCALE_IP}:1880 — {nr_status}"))

    # ─── GROUP: containers ────────────────────────────────────────────────────

    def check_containers(self):
        self._section("Docker Containers")

        rc, ps_out, _ = _run(
            "docker ps --format '{{.Names}}\\t{{.Ports}}\\t{{.Status}}'")
        if rc != 0:
            self._emit(Result("docker-ps", "containers", FAIL,
                "Cannot list containers — is Docker running?"))
            return

        running: dict = {}
        for line in ps_out.splitlines():
            parts = line.split("\t")
            if parts and parts[0]:
                running[parts[0]] = {
                    "ports": parts[1] if len(parts) > 1 else "",
                    "status": parts[2] if len(parts) > 2 else "",
                }
        self._emit(Result("docker-ps", "containers", PASS,
            f"{len(running)} containers currently running"))

        # Port conflict check (M920q only)
        if self.machine.role == "cabin-hub":
            for port, service in self.machine.existing_ports.items():
                for name, info in running.items():
                    if (f":{port}->" in info["ports"] or
                            f"0.0.0.0:{port}->" in info["ports"]):
                        if name not in self.machine.existing_containers and \
                                not name.startswith("cabin-"):
                            self._emit(Result(f"port-conflict-{port}", "containers", WARN,
                                f"Unexpected container '{name}' on port {port} ({service})"))

            # Warn if FaceoftheCabin duplicate-service containers are running
            for ghost in ["cabin-mqtt", "cabin-homeassistant", "cabin-nodered",
                          "cabin-frigate"]:
                if ghost in running:
                    self._emit(Result(f"fc-ghost-{ghost}", "containers", WARN,
                        f"'{ghost}' running — conflicts with existing cabin stack",
                        detail="Use docker-compose.m920q.yml overlay; "
                               "it disables these via `profiles: [disabled]`."))

        # Expected FaceoftheCabin containers
        for c in self.machine.fc_containers:
            if c in running:
                status = running[c].get("status", "")
                ok = "healthy" in status.lower() or "up" in status.lower()
                self._emit(Result(f"container-{c}", "containers",
                    PASS if ok else WARN, f"{c}: {status}"))
            else:
                infra = (self.plat / "infra") if self.plat else Path("infra")
                if self.machine.role == "cabin-hub":
                    up_cmd = (f'cd "{infra}" && docker compose '
                              '-f docker-compose.yml -f docker-compose.m920q.yml up -d --build')
                else:
                    up_cmd = f'cd "{infra}" && docker compose up -d'
                self._emit(Result(f"container-{c}", "containers", FAIL,
                    f"{c} not running",
                    fix_desc=f"Start FaceoftheCabin Docker stack",
                    fix_cmd=up_cmd))

    # ─── GROUP: backend ───────────────────────────────────────────────────────

    def check_backend(self):
        self._section("Spring Boot Backend")

        port = self.machine.backend_port

        # Port conflict: is something wrong using 8090?
        lsof = f"netstat -ano | findstr :{port}" if _is_win() \
               else f"lsof -i :{port} -sTCP:LISTEN"
        rc, lsof_out, _ = _run(lsof)
        port_occupied = rc == 0 and lsof_out.strip()

        if not _tcp("localhost", port, timeout=2):
            detail = (
                f"cd cabin-orchestration-platform/backend && mvn spring-boot:run\n"
                "Or on M920q via Docker overlay."
            )
            self._emit(Result("backend-up", "backend", FAIL,
                f"Backend not responding on localhost:{port}",
                detail=detail))
            return

        self._emit(Result("backend-up", "backend", PASS,
            f"Backend responding on localhost:{port}"))

        # Actuator health
        status, body = _http(f"http://localhost:{port}/actuator/health")
        if status == 200:
            try:
                h = json.loads(body)
                overall = h.get("status", "UNKNOWN")
                components = h.get("components", {})
                db_status = components.get("db", {}).get("status", "?")
                self._emit(Result("backend-health", "backend",
                    PASS if overall == "UP" else WARN,
                    f"Actuator: {overall} (db={db_status})"))
            except Exception:
                self._emit(Result("backend-health", "backend", WARN,
                    f"Health endpoint {status} — cannot parse response"))
        else:
            self._emit(Result("backend-health", "backend", FAIL,
                f"Actuator health endpoint returned HTTP {status}"))

        # Devices
        status, body = _http(f"http://localhost:{port}/api/devices")
        if status == 200:
            try:
                devices = json.loads(body)
                total = len(devices)
                online = sum(1 for d in devices if d.get("state") == "ONLINE")
                z2m_devs = [d for d in devices if d.get("deviceId", "").startswith("z2m-")]
                cabin_devs = [d for d in devices if d.get("location") == "cabin"]

                if total == 0:
                    self._emit(Result("devices", "backend", FAIL,
                        "No devices registered — backend may not have connected to MQTT"))
                else:
                    self._emit(Result("devices", "backend",
                        PASS if online > 0 else WARN,
                        f"{total} devices ({online} online, {len(z2m_devs)} z2m-, "
                        f"{len(cabin_devs)} cabin)"))

                if len(z2m_devs) == 0 and total > 0:
                    mqtt_url = self._env.get("MQTT_URL", "not set")
                    self._emit(Result("devices-z2m", "backend", FAIL,
                        "No z2m- devices registered — wrong MQTT broker or topic",
                        detail=f"Current MQTT_URL in .env: {mqtt_url}\n"
                               f"Expected: tcp://{CABIN_TAILSCALE_IP}:1883\n"
                               "Restart backend after fixing .env."))
                elif z2m_devs:
                    offline = [d for d in z2m_devs if d.get("state") != "ONLINE"]
                    status_str = PASS if online > 0 else WARN
                    self._emit(Result("devices-z2m", "backend", status_str,
                        f"{len(z2m_devs)} Z2M devices, {len(z2m_devs)-len(offline)} online"))

            except Exception as e:
                self._emit(Result("devices", "backend", WARN,
                    f"Devices endpoint {status} — parse failed: {e}"))
        else:
            self._emit(Result("devices", "backend", FAIL,
                f"Devices endpoint returned HTTP {status}"))

        # System/bridge health
        status, body = _http(f"http://localhost:{port}/api/system/health")
        if status == 200:
            try:
                h = json.loads(body)
                z2m = h.get("zigbeeBridge", "unknown")
                mqtt = h.get("mqttConnected", None)
                parts = [f"z2m={z2m}"]
                if mqtt is not None:
                    parts.append(f"mqtt={'connected' if mqtt else 'disconnected'}")
                bridge_ok = z2m == "online"
                self._emit(Result("z2m-bridge", "backend",
                    PASS if bridge_ok else WARN,
                    f"System health — {', '.join(parts)}",
                    detail="" if bridge_ok else
                        "Z2M bridge offline. Ensure Z2M container is running on M920q "
                        "and backend can reach MQTT at cabin Tailscale IP."))
            except Exception:
                pass

    # ─── GROUP: ui ────────────────────────────────────────────────────────────

    def check_ui(self):
        self._section("React UI")

        if not self.plat:
            self._emit(Result("ui-plat", "ui", SKIP, "Repo root unknown — skipping UI checks"))
            return

        ui_dir = self.plat / "ui"

        # node_modules
        nm = ui_dir / "node_modules"
        if nm.exists() and any(True for _ in nm.iterdir()):
            self._emit(Result("node-modules", "ui", PASS, "node_modules present"))
        else:
            self._emit(Result("node-modules", "ui", FAIL, "node_modules missing",
                fix_desc="Install UI dependencies",
                fix_cmd=f'cd "{ui_dir}" && npm install'))

        # Vite dev server
        vite_port = None
        for p in range(5173, 5182):
            if _tcp("localhost", p, timeout=0.4):
                vite_port = p
                break
        if vite_port:
            self._emit(Result("vite-server", "ui", PASS,
                f"Vite dev server running on port {vite_port}"))
        else:
            self._emit(Result("vite-server", "ui", WARN,
                "Vite dev server not running",
                detail=f"cd \"{ui_dir}\" && npm run dev"))

        # .env.local correctness
        api_base = self._ui_env.get("VITE_CABIN_API_BASE", "")
        if ":8080" in api_base:
            self._emit(Result("ui-api-port", "ui", FAIL,
                f"VITE_CABIN_API_BASE uses old port 8080: {api_base}",
                detail="Backend is on 8090 now. Update ui/.env.local and restart Vite."))
        elif ":8090" in api_base:
            self._emit(Result("ui-api-port", "ui", PASS, f"VITE_CABIN_API_BASE={api_base}"))
        else:
            self._emit(Result("ui-api-port", "ui", WARN,
                f"VITE_CABIN_API_BASE not set or not on 8090: '{api_base}'"))

        z2m = self._ui_env.get("VITE_CABIN_Z2M_URL", "")
        if "localhost" in z2m:
            self._emit(Result("ui-z2m-url", "ui", WARN,
                f"VITE_CABIN_Z2M_URL points to localhost: {z2m}",
                detail=f"Set: VITE_CABIN_Z2M_URL=http://{CABIN_TAILSCALE_IP}:8080"))
        elif z2m:
            self._emit(Result("ui-z2m-url", "ui", PASS, f"VITE_CABIN_Z2M_URL={z2m}"))
        else:
            self._emit(Result("ui-z2m-url", "ui", WARN,
                "VITE_CABIN_Z2M_URL not set — Z2M Advanced link falls back to cabin-hub:8080"))

        # Verify UI can actually reach backend
        port = self.machine.backend_port
        if _tcp("localhost", port, timeout=1):
            status, body = _http(f"http://localhost:{port}/api/devices")
            self._emit(Result("ui-backend-reachable", "ui",
                PASS if status == 200 else WARN,
                f"UI→backend roundtrip: /api/devices returned {status}"))

    # ─── Run all / selected groups ────────────────────────────────────────────

    def run(self, groups: list):
        dispatch = {
            "system":     self.check_system,
            "git":        self.check_git,
            "env":        self.check_env,
            "auth":       self.check_auth,
            "network":    self.check_network,
            "containers": self.check_containers,
            "backend":    self.check_backend,
            "ui":         self.check_ui,
        }
        for g in groups:
            fn = dispatch.get(g)
            if fn:
                fn()
            else:
                print(f"  Unknown group '{g}' — skipping")

        # Auto-fix pass
        if self.fix and not self.json_out:
            print(_bold("\n-- Auto-Fix Pass " + "-" * 42))
            did_any = False
            for r in self.results:
                if r.status == FAIL and r.fix_cmd:
                    did_any = True
                    r.fixed = self._try_fix(r)
            if not did_any:
                print("  No auto-fixable failures found.")

# ── Summary ───────────────────────────────────────────────────────────────────

def _summary(results: list, machine: MachineProfile, elapsed: float):
    counts = {PASS: 0, WARN: 0, FAIL: 0, SKIP: 0}
    for r in results:
        counts[r.status] = counts.get(r.status, 0) + 1

    print(_bold("\n== Summary " + "=" * 50))
    print(f"  Machine : {machine.hostname} ({machine.role} / {machine.os})")
    print(f"  Time    : {elapsed:.1f}s")
    print(f"  Results : "
          f"{_c(PASS, str(counts[PASS])+' passed')}  "
          f"{_c(WARN, str(counts[WARN])+' warnings')}  "
          f"{_c(FAIL, str(counts[FAIL])+' failed')}  "
          f"{_c(SKIP, str(counts[SKIP])+' skipped')}")

    failures = [r for r in results if r.status == FAIL and not r.fixed]
    warnings = [r for r in results if r.status == WARN]

    if failures:
        print(_c(FAIL, "\n  Failures (must fix):"))
        for r in failures:
            print(f"    • [{r.group}] {r.name}: {r.message}")
            if r.fix_cmd:
                print(f"      $ {r.fix_cmd}")
            elif r.fix_desc:
                print(f"      ↳ {r.fix_desc}")

    if warnings:
        print(_c(WARN, "\n  Warnings (should fix):"))
        for r in warnings:
            print(f"    • [{r.group}] {r.name}: {r.message}")

    if not failures and not warnings:
        print(_c(PASS, "\n  All checks passed — platform is healthy ✓"))

    overall = FAIL if failures else (WARN if warnings else PASS)
    print(f"\n  Overall : {_c(overall, overall)}\n")
    return overall

# ── Main ──────────────────────────────────────────────────────────────────────

ALL_GROUPS = ["system", "git", "env", "auth", "network", "containers", "backend", "ui"]

def main():
    ap = argparse.ArgumentParser(
        description="FaceoftheCabin QA Runner",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    ap.add_argument("--fix", action="store_true",
        help="Attempt auto-remediation of safe failures")
    ap.add_argument("--machine", metavar="HOSTNAME",
        help="Override machine detection (e.g. ilikethelights)")
    ap.add_argument("--group", metavar="GROUPS",
        help=f"Comma-separated groups: {','.join(ALL_GROUPS)}")
    ap.add_argument("--json", dest="json_out", action="store_true",
        help="Output results as JSON (for CI / cross-machine comparison)")
    ap.add_argument("--list", action="store_true",
        help="List check groups and exit")
    args = ap.parse_args()

    if args.list:
        print(f"Groups: {', '.join(ALL_GROUPS)}")
        return 0

    machine = (_MACHINES.get(args.machine.lower()) if args.machine
               else detect_machine())
    if not machine:
        machine = MachineProfile(hostname=args.machine or platform.node(),
                                 role="unknown", os="unknown")

    repo_root = find_repo_root()
    groups = ([g.strip() for g in args.group.split(",")]
              if args.group else ALL_GROUPS)

    t0 = __import__("time").time()

    if not args.json_out:
        print(_bold(f"\nFaceoftheCabin QA Runner  v{VERSION}"))
        print(f"Machine : {machine.hostname} ({machine.role} / {machine.os})")
        print(f"Repo    : {repo_root or 'not found'}")
        print(f"Run at  : {datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}")
        print(f"Fix     : {'ON' if args.fix else 'off'}")

    runner = QARunner(machine, repo_root, fix=args.fix, json_out=args.json_out)
    runner.run(groups)

    elapsed = __import__("time").time() - t0

    if args.json_out:
        report = {
            "version": VERSION,
            "machine": machine.hostname,
            "role": machine.role,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "elapsed_s": round(elapsed, 2),
            "results": [asdict(r) for r in runner.results],
            "counts": {
                s: sum(1 for r in runner.results if r.status == s)
                for s in [PASS, WARN, FAIL, SKIP]
            },
        }
        print(json.dumps(report, indent=2))
        failures = [r for r in runner.results if r.status == FAIL and not r.fixed]
        return 2 if failures else 0

    overall = _summary(runner.results, machine, elapsed)
    return 0 if overall == PASS else (2 if overall == FAIL else 1)


if __name__ == "__main__":
    sys.exit(main())
