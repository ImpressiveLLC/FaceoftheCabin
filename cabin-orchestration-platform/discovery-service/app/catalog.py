"""Local-catalog step: organize what the discovering integration already
told us (Zigbee2MQTT's `definition.model/vendor/description/exposes`, Home
Assistant's `device_class`/`manufacturer`, etc.) into a starting point for
the external lookup. No network calls, no new vendored dataset -- this is
free, already-known metadata, just structured.
"""

from .models import DiscoverRequest


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
