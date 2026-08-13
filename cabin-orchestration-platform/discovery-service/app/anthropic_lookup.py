"""External lookup step: Claude with the native web-search tool, used to
identify a device and find setup/install guidance, with every claim tied
to a real citation the API itself returned (not something the model can
fabricate, since citations come from actual search results).

Contract this module must uphold (see the "never skip the click" /
provenance requirements the user gave for this feature): if the model
doesn't produce a real, cited match, we return confidence="low" and an
empty sources list -- we never invent a URL or claim a source that isn't
a genuine citation.

NOTE for whoever deploys this: the web-search tool identifier
("web_search_20250305" below) and the citation shape on text blocks are
Anthropic API surface details that can move between API versions --
verify both against the current Anthropic API docs before relying on
this in production, and update ANTHROPIC_WEB_SEARCH_TOOL_TYPE below if
it's changed. Anything that doesn't parse as expected falls through to
_low_confidence_fallback() rather than guessing.
"""

import json
import logging
import os
import re
from datetime import datetime, timezone

import anthropic

from .catalog import has_local_identity, local_identity_summary
from .models import DiscoverRequest, InstallGuide, Match, Source

logger = logging.getLogger(__name__)

ANTHROPIC_WEB_SEARCH_TOOL_TYPE = "web_search_20250305"
MODEL = "claude-sonnet-5"
MAX_SEARCH_USES = 3

# Kept in sync by hand with DeviceType.java / DeviceCapability.java -- the
# model must pick from these closed sets so suggestedType/
# suggestedCapabilities round-trip cleanly through the Java enums on the
# other end instead of producing a value DeviceType.valueOf()/
# DeviceCapability.valueOf() would reject.
VALID_DEVICE_TYPES = [
    "SMOKE_ALARM", "CO_ALARM", "WATER_LEAK_SENSOR", "CAMERA", "LOCK",
    "MOTION_SENSOR", "CONTACT_SENSOR", "THERMOSTAT", "TEMPERATURE_SENSOR",
    "HUMIDITY_SENSOR", "WATER_PRESSURE_SENSOR", "POWER_METER", "DISHWASHER",
    "WASHING_MACHINE", "DRYER", "ROUTER", "UPS", "GOOGLE_HOME_DEVICE",
    "HOME_ASSISTANT_ENTITY", "DASHBOARD",
]
VALID_CAPABILITIES = [
    "TELEMETRY", "COMMAND", "STREAM", "ALARM", "PRESENCE", "CLIMATE",
    "ACCESS_CONTROL", "APPLIANCE", "POWER_MONITOR",
]

SYSTEM_PROMPT = f"""You are helping identify a smart-home device from limited
discovery metadata (vendor/model strings, a protocol description, and raw
capability hints from a Zigbee/MQTT/Home-Assistant discovery scan) so a
person can decide whether to add it to their home automation system.

Search the web for the specific product. Then respond with:
1. A short (2-4 sentence) plain-language summary of what the device is.
2. If you found real setup/pairing instructions, a short extract or
   summary of them (say which). If you found nothing concrete, say so
   plainly -- do not invent generic instructions.
3. End your response with exactly one fenced ```json block, no other text
   inside it, in this exact shape:
{{"confidence": "high"|"medium"|"low", "suggestedType": one of {VALID_DEVICE_TYPES} or null, "suggestedCapabilities": a subset of {VALID_CAPABILITIES}}}

Rules:
- Only claim "high" confidence if you found a clear, specific match for
  the exact vendor/model given -- not a generic guess at the product
  category.
- If you are not confident you found the actual device (wrong region,
  discontinued lookalike, no real match at all), say so and use
  confidence "low" -- never present a guess as if it were verified.
- Never state a fact about the device without it being something you
  actually found via search. Citations are attached automatically by the
  search tool to text you write based on search results -- write your
  summary so that grounded claims come from what you searched, not from
  general knowledge you already had before searching.
"""


def run_discovery(request: DiscoverRequest) -> list[Match]:
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not api_key:
        logger.info("ANTHROPIC_API_KEY not set -- returning local-catalog-only result")
        return [_low_confidence_fallback(request, reason="No external lookup is configured on this deployment.")]

    if not has_local_identity(request):
        return [_low_confidence_fallback(
            request, reason="No vendor, model, or description was reported by discovery -- nothing to search for.")]

    try:
        client = anthropic.Anthropic(api_key=api_key)
        user_prompt = _build_user_prompt(request)
        response = client.messages.create(
            model=MODEL,
            max_tokens=1024,
            system=SYSTEM_PROMPT,
            tools=[{
                "type": ANTHROPIC_WEB_SEARCH_TOOL_TYPE,
                "name": "web_search",
                "max_uses": MAX_SEARCH_USES,
            }],
            messages=[{"role": "user", "content": user_prompt}],
        )
        return _parse_response(response, request)
    except Exception as e:  # noqa: BLE001 -- any API/parsing failure must degrade, not crash the request
        logger.warning("Anthropic lookup failed for %s: %s", local_identity_summary(request), e)
        return [_low_confidence_fallback(
            request, reason=f"The external lookup failed ({type(e).__name__}); showing local discovery data only.")]


def _build_user_prompt(request: DiscoverRequest) -> str:
    return (
        f"Vendor: {request.vendor or '(unknown)'}\n"
        f"Model: {request.model or '(unknown)'}\n"
        f"Description from discovery: {request.description or '(none)'}\n"
        f"Protocol: {request.protocolAdapter or '(unknown)'}\n"
        f"Raw discovery attributes: {json.dumps(request.discoveryAttributes)[:2000]}\n\n"
        "Identify this specific device and find real setup/pairing documentation for it."
    )


def _parse_response(response, request: DiscoverRequest) -> list[Match]:
    text_parts: list[str] = []
    sources: list[Source] = []
    seen_urls: set[str] = set()
    now = datetime.now(timezone.utc).isoformat()

    for block in response.content:
        if getattr(block, "type", None) != "text":
            continue
        text_parts.append(block.text)
        for citation in (getattr(block, "citations", None) or []):
            url = getattr(citation, "url", None)
            if not url or url in seen_urls:
                continue
            seen_urls.add(url)
            sources.append(Source(
                url=url,
                title=getattr(citation, "title", None) or url,
                snippet=(getattr(citation, "cited_text", None) or "")[:400],
                fetchedAt=now,
            ))

    full_text = "\n".join(text_parts).strip()
    if not full_text:
        return [_low_confidence_fallback(request, reason="The model returned no usable response.")]

    meta = _extract_trailing_json(full_text)
    prose = re.sub(r"```json.*?```", "", full_text, flags=re.DOTALL).strip()

    # No real citations came back at all -- per this module's contract,
    # that caps confidence at "low" regardless of what the model claimed,
    # rather than trusting an uncited "high".
    confidence = (meta.get("confidence") if meta else None) or "low"
    if not sources:
        confidence = "low"

    suggested_type = meta.get("suggestedType") if meta else None
    if suggested_type not in VALID_DEVICE_TYPES:
        suggested_type = None
    suggested_caps = [c for c in (meta.get("suggestedCapabilities") if meta else []) or [] if c in VALID_CAPABILITIES]

    install_guide = InstallGuide(
        mode="summary" if sources else "linkonly",
        content=prose or "No setup documentation was found for this device.",
    )

    return [Match(
        summary=prose[:600] or local_identity_summary(request),
        confidence=confidence,
        suggestedType=suggested_type,
        suggestedCapabilities=suggested_caps,
        installGuide=install_guide,
        sources=sources,
    )]


def _extract_trailing_json(text: str) -> dict | None:
    match = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL)
    if not match:
        return None
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return None


def _low_confidence_fallback(request: DiscoverRequest, reason: str) -> Match:
    return Match(
        summary=local_identity_summary(request),
        confidence="low",
        suggestedType=None,
        suggestedCapabilities=[],
        installGuide=InstallGuide(mode="linkonly", content=reason),
        sources=[],
    )
