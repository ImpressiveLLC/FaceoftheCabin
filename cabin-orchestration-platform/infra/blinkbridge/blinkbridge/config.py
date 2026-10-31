"""Settings: the legacy config.json shape plus env overrides. Loaded explicitly, never at import."""
import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Mapping, Optional

MIN_POLL_INTERVAL = 30.0  # never poll Blink faster than this


def slug(camera_name: str) -> str:
    """mediamtx path for a Blink camera: 'Outdoor 4 - DHEE' -> 'outdoor_4_-_dhee'."""
    return camera_name.replace(" ", "_").lower()


@dataclass
class Settings:
    username: str = ""
    password: str = ""
    config_dir: Path = Path("/config")
    rtsp_base: str = "rtsp://mediamtx:8554"
    enabled: List[str] = field(default_factory=list)
    disabled: List[str] = field(default_factory=list)
    motion_liveview: List[str] = field(default_factory=list)
    # Camera record keys whose change counts as activity (diffed every poll). Blink's motion flag is clip
    # derived and never fires without cloud clips; these catch what the homescreen record does expose.
    motion_signal_keys: List[str] = field(default_factory=lambda: ["thumbnail", "last_record", "video"])
    log_level: str = "INFO"
    http_port: int = 8811
    ffmpeg_bin: str = "ffmpeg"

    poll_interval: float = MIN_POLL_INTERVAL
    poll_jitter: float = 10.0          # added 0..jitter => a poll every 30-40 s
    step_interval: float = 5.0         # supervisor tick (camera reconcile cadence)
    liveview_max: float = 300.0        # manual /start session cap
    motion_max: float = 90.0           # motion-triggered session cap

    # every network / process step is bounded by one of these
    refresh_timeout: float = 30.0
    login_timeout: float = 60.0
    open_timeout: float = 30.0
    warmup: float = 1.5
    teardown_timeout: float = 8.0
    ffmpeg_stall: float = 20.0         # no ffmpeg progress for this long => stuck, kill it
    cycle_timeout: float = 120.0       # a whole supervisor tick can never exceed this
    inline_open_timeout: float = 15.0  # /start waits at most this for the first open attempt

    # backoff
    cam_backoff_base: float = 5.0
    cam_backoff_cap: float = 300.0
    poll_backoff_base: float = 30.0
    poll_backoff_cap: float = 600.0
    throttle_floor: float = 300.0      # after a throttle signal, leave Blink alone this long
    throttle_cap: float = 1800.0
    auth_backoff_base: float = 60.0
    auth_backoff_cap: float = 900.0
    healthy_session: float = 30.0      # a session this old resets its camera's backoff

    # liveness
    max_log_gap: float = 35.0          # emit a status line at least this often
    loop_stale: float = 120.0          # /healthz fails if the loop has not ticked in this long
    poll_stale: float = 1800.0         # ...or no successful Blink poll in this long
    startup_grace: float = 600.0
    camera_missing_unhealthy: float = 900.0  # a configured camera absent from Blink this long => unhealthy
    rediscover_base: float = 60.0      # backoff between re-discovery (re-login) attempts
    rediscover_cap: float = 600.0
    watchdog_exit_after: float = 600.0  # in-process dead-man: exit(70) if the loop is this stale

    @property
    def cred_path(self) -> Path:
        return self.config_dir / ".cred.json"

    @property
    def login_blob(self) -> dict:
        return {"username": self.username, "password": self.password}


def load(path: Optional[str] = None, env: Optional[Mapping[str, str]] = None) -> Settings:
    env = os.environ if env is None else env
    path = path or env.get("BLINKBRIDGE_CONFIG", "config.json")
    raw: dict = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
    cams = raw.get("cameras", {})
    blink = raw.get("blink", {})
    login = blink.get("login", {})
    rtsp = raw.get("rtsp_server", {})
    s = Settings()
    s.username = env.get("BLINK_USERNAME") or login.get("username", "")
    s.password = env.get("BLINK_PASSWORD") or login.get("password", "")
    s.config_dir = Path(raw.get("paths", {}).get("config", "/config"))
    if env.get("MEDIAMTX_URL"):
        s.rtsp_base = env["MEDIAMTX_URL"].rstrip("/")
    elif rtsp:
        s.rtsp_base = f"rtsp://{rtsp.get('address', 'mediamtx')}:{rtsp.get('port', 8554)}"
    s.enabled = list(cams.get("enabled", []))
    s.disabled = list(cams.get("disabled", []))
    s.motion_liveview = list(cams.get("motion_liveview_enabled", []))
    if "motion_signal_keys" in cams:
        s.motion_signal_keys = list(cams["motion_signal_keys"])
    s.log_level = str(raw.get("log_level", "INFO")).upper()
    s.poll_interval = max(float(blink.get("poll_interval", MIN_POLL_INTERVAL)), MIN_POLL_INTERVAL)
    s.http_port = int(env.get("BLINKBRIDGE_PORT", s.http_port))
    return s
