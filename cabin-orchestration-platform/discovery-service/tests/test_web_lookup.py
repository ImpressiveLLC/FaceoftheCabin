"""No real network calls in these tests -- urllib.request.urlopen is mocked
throughout so this suite runs offline."""

from unittest.mock import MagicMock, patch

from app.models import DiscoverRequest
from app.web_lookup import run_discovery

FAKE_RESULT_HTML = """
<div class="result">
  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsonoff.tech%2Fproduct%2Fsnzb-04p">
    SONOFF SNZB-04P Contact Sensor
  </a>
  <a class="result__snippet">Wireless door/window contact sensor for Zigbee.</a>
</div>
"""


def _mock_urlopen(html: str, status: int = 200):
    response = MagicMock()
    response.read.return_value = html.encode("utf-8")
    response.__enter__.return_value = response
    return response


def test_nothing_reported_at_all_never_calls_the_network():
    with patch("app.web_lookup.urllib.request.urlopen") as mock_urlopen:
        matches = run_discovery(DiscoverRequest())

    mock_urlopen.assert_not_called()
    assert matches[0].confidence == "low"
    assert matches[0].sources == []
    assert "nothing to search for" in matches[0].installGuide.content


def test_real_result_produces_a_medium_confidence_match_with_a_real_source():
    with patch("app.web_lookup.urllib.request.urlopen", return_value=_mock_urlopen(FAKE_RESULT_HTML)):
        matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    match = matches[0]
    assert match.confidence == "medium"
    assert len(match.sources) == 1
    # The DuckDuckGo redirect wrapper must be unwrapped to the real product URL.
    assert match.sources[0].url == "https://sonoff.tech/product/snzb-04p"
    assert "SONOFF SNZB-04P Contact Sensor" in match.sources[0].title
    assert "contact sensor" in match.installGuide.content.lower()


def test_no_parseable_result_falls_back_to_local_only():
    with patch("app.web_lookup.urllib.request.urlopen", return_value=_mock_urlopen("<html><body>no results</body></html>")):
        matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    assert matches[0].confidence == "medium"  # known vendor -- classify_local_confidence, not the search result
    assert matches[0].sources == []
    assert "no web search result" in matches[0].installGuide.content.lower()


def test_network_failure_falls_back_gracefully():
    with patch("app.web_lookup.urllib.request.urlopen", side_effect=OSError("connection reset")):
        matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    assert matches[0].confidence == "medium"
    assert matches[0].sources == []
    assert "failed" in matches[0].installGuide.content.lower()


def test_description_alone_is_searched_when_vendor_and_model_are_both_blank():
    # A network-scanned (non-Zigbee) device -- e.g. the LG webOS TV found
    # by netscan -- reports only `description` (its own mDNS-broadcast
    # service name), never vendor/model. Revised 2026-09-14: this is
    # still searchable, since it's the device's own public LAN broadcast,
    # not a private friendly_name assigned inside this app.
    with patch("app.web_lookup.urllib.request.urlopen", return_value=_mock_urlopen(FAKE_RESULT_HTML)) as mock_urlopen:
        matches = run_discovery(DiscoverRequest(description="LG webOS TV OLED42C5PUA"))

    mock_urlopen.assert_called_once()
    assert matches[0].confidence == "medium"
    assert len(matches[0].sources) == 1
