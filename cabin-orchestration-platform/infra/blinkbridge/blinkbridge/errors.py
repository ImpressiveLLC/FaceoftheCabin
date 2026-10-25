"""Error classes the supervisor reasons about; everything from blinkpy is mapped onto these."""
import re


class BlinkError(Exception):
    """Base class."""


class ThrottledError(BlinkError):
    """Blink says slow down (or its endpoint looks down). Back off hard, never retry fast."""


class AuthError(BlinkError):
    """Token expired / login rejected / 2FA wanted. Needs a fresh login, not a retry."""


class TransientError(BlinkError):
    """Network blip, timeout, reset connection. Retry with backoff."""


THROTTLE_RE = re.compile(r"throttl|possibly down|too many requests|429", re.I)
AUTH_RE = re.compile(r"login|auth|token|2fa|unauthori[sz]ed|401|403", re.I)


def classify(exc: BaseException) -> BlinkError:
    """Map any exception to a BlinkError subclass (idempotent)."""
    if isinstance(exc, BlinkError):
        return exc
    name = type(exc).__name__
    text = f"{name}: {exc}" if str(exc) else name
    if THROTTLE_RE.search(text):
        return ThrottledError(text)
    if AUTH_RE.search(text):
        return AuthError(text)
    return TransientError(text)
