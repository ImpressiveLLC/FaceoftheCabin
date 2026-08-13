"""Local-catalog step: organize what the discovering integration already
told us (Zigbee2MQTT's `definition.model/vendor/description/exposes`, Home
Assistant's `device_class`/`manufacturer`, etc.) into a starting point for
the external lookup. No network calls, no new vendored dataset -- this is
free, already-known metadata, just structured.
"""

from .models import DiscoverRequest

# Recognized Zigbee/smart-home vendor names, calibrated against the real
# candidate population on this deployment (13 unreviewed devices: SONOFF,
# Third Reality, Tuya) plus other common Zigbee2MQTT-ecosystem vendors.
# Keyed lowercase; values are short, locally-authored vendor descriptions
# -- not a web citation, so they never populate a Match's `sources` list.
KNOWN_VENDORS = {
    "sonoff": "SONOFF (eWeLink) -- Zigbee and Wi-Fi smart home sensors and switches",
    "third reality": "Third Reality -- Zigbee smart home sensors and plugs",
    "tuya": "Tuya -- generic Zigbee/Wi-Fi smart home modules, often white-labeled",
    "aqara": "Aqara (Lumi/Xiaomi ecosystem) -- Zigbee smart home sensors",
    "lumi": "Aqara (Lumi/Xiaomi ecosystem) -- Zigbee smart home sensors",
    "ikea": "IKEA TRADFRI -- Zigbee smart lighting",
    "philips": "Philips Hue (Signify) -- Zigbee smart lighting",
    "signify": "Philips Hue (Signify) -- Zigbee smart lighting",
    "xiaomi": "Xiaomi -- Zigbee/Wi-Fi smart home ecosystem",
}


def known_vendor_note(vendor: str | None) -> str | None:
    if not vendor:
        return None
    return KNOWN_VENDORS.get(vendor.strip().lower())


def local_identity_summary(request: DiscoverRequest) -> str:
    """A short, human-readable identity string built purely from what was
    already reported -- used both as a starting point for the AI/search
    prompt and as the entire result when that step is unavailable."""
    parts = [p for p in (request.vendor, request.model) if p]
    identity = " ".join(parts)
    if request.description:
        identity = f"{identity} -- {request.description}".strip(" -")
    return identity or "an unidentified device"


def has_local_identity(request: DiscoverRequest) -> bool:
    return bool(request.vendor or request.model or request.description)


def classify_local_confidence(request: DiscoverRequest) -> str:
    """Confidence justified by local discovery data alone, with no web
    citation involved. A vendor+model pair reported directly by the
    device's own Zigbee/MQTT handshake and matching a recognized vendor is
    reliable identification, not a guess -- it was previously scored "low"
    the same as a device we know nothing about at all, which understated
    how much the discovering integration already told us.
    """
    if request.vendor and request.model and known_vendor_note(request.vendor):
        return "medium"
    return "low"
