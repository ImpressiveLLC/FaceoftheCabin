"""External lookup step: a single keyless HTTP GET against DuckDuckGo's
HTML search endpoint -- no API key, no account, no vendor SDK. Replaces an
earlier Anthropic-backed provider (2026-09-14): identifying a smart-home
device from a vendor+model string is a couple of cacheable sentences, not a
task that needs an LLM in the loop, and it shouldn't need a paid/keyed
vendor API either.

Privacy (D8's enrichment policy, docs/ontology/DECISIONS.md): only vendor
and model are ever sent externally -- never `description` or raw
discoveryAttributes, since for a network-scanned (non-Zigbee) device those
can carry an mDNS instance name a person chose themselves (a "friendly
name" in D8's own terms). A device identified only by description (no
vendor/model) gets the same local-only result it always did; this module
does not search for it. That's a real, known gap, not an oversight -- see
the D-decision this shipped under for the reasoning.

Contract this must uphold (same as the module it replaces): never invent a
source. A real citation is only ever a URL DuckDuckGo itself returned; if
nothing parses, or the request fails, confidence falls back to whatever the
local discovery data alone justifies (catalog.classify_local_confidence)
and sources stays empty.

NOTE for whoever maintains this: DuckDuckGo's HTML markup is not a
documented, versioned API and can change without notice. That is an
accepted tradeoff for a zero-key, zero-cost lookup -- every failure mode
(no match, changed markup, timeout, non-200) degrades to the local-only
fallback below, it never raises past this module.
"""

import logging
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from .catalog import classify_local_confidence, has_local_identity, local_identity_summary
from .models import DiscoverRequest, InstallGuide, Match, Source

logger = logging.getLogger(__name__)

SEARCH_URL = "https://html.duckduckgo.com/html/"
REQUEST_TIMEOUT_SECONDS = 8
# Live-verified 2026-09-14: an honest, self-identifying User-Agent (e.g.
# "cabin-discovery/1.0 (+https://...)") gets a 202 holding page with no
# results at all -- DuckDuckGo's HTML endpoint only returns real results
# for a request that looks like an ordinary browser. This is the accepted
# tradeoff of a keyless, unofficial endpoint instead of a paid/keyed search
# API; flagged here rather than left as an unexplained magic string.
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

# DuckDuckGo's html.duckduckgo.com result markup as of 2026-09: a result
# link carries class="result__a", a snippet carries class="result__snippet".
# Best-effort regex, not a full HTML parser -- this endpoint is small,
# template-stable HTML, and a failed match here just falls through to the
# local-only result below.
RESULT_LINK_RE = re.compile(r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', re.DOTALL)
SNIPPET_RE = re.compile(r'class="result__snippet"[^>]*>(.*?)</a>', re.DOTALL)
TAG_RE = re.compile(r"<[^>]+>")


def run_discovery(request: DiscoverRequest) -> list[Match]:
    query = f"{request.vendor} {request.model}".strip()
    if not query:
        return [_local_only_fallback(
            request, reason="No vendor or model was reported by discovery -- nothing safe to search for "
                             "(a description alone may contain a locally-chosen name, so it is never sent externally).")]

    try:
        title, url, snippet = _search_duckduckgo(query)
    except Exception as e:  # noqa: BLE001 -- any network/parse failure must degrade, not crash the request
        logger.warning("Web lookup failed for %s: %s", local_identity_summary(request), e)
        return [_local_only_fallback(
            request, reason=f"The external lookup failed ({type(e).__name__}); showing local discovery data only.")]

    if url is None:
        return [_local_only_fallback(request, reason="No web search result was found for this device.")]

    identity = local_identity_summary(request)
    summary = f"{identity} -- top web result: {title}" if title else identity

    return [Match(
        summary=summary[:600],
        # Capped at "medium": a real, cited search-engine hit is more than
        # a bare local guess, but this module has no way to independently
        # confirm the result is actually the right product -- "high" stays
        # reserved for a match with stronger verification than one search
        # result, same restraint the module this replaces used.
        confidence="medium",
        suggestedName=identity,
        suggestedType=None,
        suggestedCapabilities=[],
        installGuide=InstallGuide(
            mode="summary" if snippet else "linkonly",
            content=snippet if snippet else f"No summary text was found -- see {url}.",
        ),
        sources=[Source(url=url, title=title or url, snippet=snippet[:400], fetchedAt=_now_iso())],
    )]


def _search_duckduckgo(query: str) -> tuple[str | None, str | None, str | None]:
    """Returns (title, url, snippet) for the first result, or (None, None, None)
    if the page returned no parseable result."""
    encoded = urllib.parse.urlencode({"q": query})
    req = urllib.request.Request(
        f"{SEARCH_URL}?{encoded}",
        headers={"User-Agent": USER_AGENT},
    )
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        html = response.read().decode("utf-8", errors="replace")

    link_match = RESULT_LINK_RE.search(html)
    if not link_match:
        return None, None, None

    url = _clean_ddg_redirect(link_match.group(1))
    title = _strip_tags(link_match.group(2))
    snippet_match = SNIPPET_RE.search(html)
    snippet = _strip_tags(snippet_match.group(1)) if snippet_match else ""
    return title, url, snippet


def _clean_ddg_redirect(href: str) -> str:
    """DuckDuckGo's HTML results link through //duckduckgo.com/l/?uddg=<real-url>
    rather than linking directly -- unwrap it so the stored source is the
    real product page, not a DuckDuckGo redirect URL."""
    if "uddg=" not in href:
        return href
    parsed = urllib.parse.urlparse(href if href.startswith("http") else f"https:{href}")
    qs = urllib.parse.parse_qs(parsed.query)
    real = qs.get("uddg", [None])[0]
    return urllib.parse.unquote(real) if real else href


def _strip_tags(text: str) -> str:
    return TAG_RE.sub("", text).strip()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _local_only_fallback(request: DiscoverRequest, reason: str) -> Match:
    identity = local_identity_summary(request)
    return Match(
        summary=identity,
        confidence=classify_local_confidence(request),
        suggestedName=identity if has_local_identity(request) else None,
        suggestedType=None,
        suggestedCapabilities=[],
        installGuide=InstallGuide(mode="linkonly", content=reason),
        sources=[],
    )
