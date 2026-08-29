"""The load-bearing contract for this whole service: never present a
fabricated or uncited claim as trustworthy. Every test that exercises a
mocked Anthropic response constructs the response shape by hand (no real
API calls) so these run offline and don't need a real API key."""

from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from app.anthropic_lookup import run_discovery
from app.models import DiscoverRequest


def _text_block(text: str, citations: list | None = None):
    return SimpleNamespace(type="text", text=text, citations=citations)


def _citation(url: str, title: str = "Example", cited_text: str = "some cited text"):
    return SimpleNamespace(url=url, title=title, cited_text=cited_text)


def test_no_api_key_falls_back_to_local_only(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    matches = run_discovery(DiscoverRequest(vendor="AcmeCo", model="X1"))

    assert len(matches) == 1
    assert matches[0].confidence == "low"
    assert matches[0].sources == []


def test_no_api_key_but_known_vendor_gets_medium_confidence_from_local_data(monkeypatch):
    # Calibrated against the real candidate population on this deployment:
    # SONOFF SNZB-04P is one of the 13 unreviewed cabin devices, and the
    # device's own Zigbee handshake -- not a guess -- is what reported this
    # vendor+model. That's more than "low" deserves, even with zero web
    # lookups performed.
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    assert matches[0].confidence == "medium"
    assert matches[0].sources == []
    assert matches[0].suggestedName == "SONOFF SNZB-04P"
    assert "SONOFF" in matches[0].summary


def test_no_local_identity_never_calls_the_api(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        matches = run_discovery(DiscoverRequest())

        mock_client_cls.assert_not_called()
        assert matches[0].confidence == "low"
        assert matches[0].sources == []


def test_response_with_real_citations_produces_matches_with_sources(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    fake_response = SimpleNamespace(content=[
        _text_block(
            "This is a SONOFF SNZB-04P wireless contact sensor.\n"
            '```json\n{"confidence": "high", "suggestedName": "SONOFF SNZB-04P Contact Sensor", '
            '"suggestedType": "CONTACT_SENSOR", '
            '"suggestedCapabilities": ["TELEMETRY", "ACCESS_CONTROL"]}\n```',
            citations=[_citation("https://sonoff.tech/product/snzb-04p", "SNZB-04P product page")],
        )
    ])
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.return_value = fake_response
        matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    match = matches[0]
    assert match.suggestedName == "SONOFF SNZB-04P Contact Sensor"
    assert match.confidence == "high"
    assert match.suggestedType == "CONTACT_SENSOR"
    assert match.suggestedCapabilities == ["TELEMETRY", "ACCESS_CONTROL"]
    assert len(match.sources) == 1
    assert match.sources[0].url == "https://sonoff.tech/product/snzb-04p"


def test_response_with_no_citations_forces_low_confidence_even_if_claimed_high(monkeypatch):
    # The core "no hallucinated sources" contract: even if the model's own
    # trailing JSON claims high confidence, the absence of any real
    # citation must cap it at low and leave sources empty. Otherwise a
    # confident-sounding but ungrounded claim could reach the review UI
    # looking verified when it isn't.
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    fake_response = SimpleNamespace(content=[
        _text_block(
            "This is probably a generic contact sensor.\n"
            '```json\n{"confidence": "high", "suggestedType": "CONTACT_SENSOR", '
            '"suggestedCapabilities": []}\n```',
            citations=None,
        )
    ])
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.return_value = fake_response
        matches = run_discovery(DiscoverRequest(vendor="UnknownBrand", model="X1"))

    assert len(matches) == 1
    assert matches[0].confidence == "low"
    assert matches[0].sources == []


def test_invalid_suggested_type_is_dropped_not_passed_through(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    fake_response = SimpleNamespace(content=[
        _text_block(
            "Some device.\n```json\n{"
            '"confidence": "medium", "suggestedType": "NOT_A_REAL_TYPE", "suggestedCapabilities": ["NOT_REAL"]}'
            "\n```",
            citations=[_citation("https://example.com/spec")],
        )
    ])
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.return_value = fake_response
        matches = run_discovery(DiscoverRequest(vendor="Acme", model="Y2"))

    assert matches[0].suggestedType is None
    assert matches[0].suggestedCapabilities == []


def test_workspace_id_env_var_is_sent_as_a_header_when_set(monkeypatch):
    # 2026-08-29: an identity-linked API key needs this header on every
    # request or Anthropic rejects it with a 400 -- confirmed against the
    # real error message ("anthropic-workspace-id is required when
    # authenticating with an identity-linked API key...") the very key
    # this was built for actually returned.
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    monkeypatch.setenv("ANTHROPIC_WORKSPACE_ID", "wrkspc_abc123")
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.return_value = SimpleNamespace(content=[])
        run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    mock_client_cls.assert_called_once_with(
        api_key="test-key", default_headers={"anthropic-workspace-id": "wrkspc_abc123"})


def test_no_workspace_id_env_var_omits_the_header_entirely(monkeypatch):
    # An older-style, workspace-scoped key doesn't need (and per the SDK's
    # own header-merging, shouldn't be sent) this header at all -- must
    # stay opt-in, not become a hard requirement for every deployment.
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    monkeypatch.delenv("ANTHROPIC_WORKSPACE_ID", raising=False)
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.return_value = SimpleNamespace(content=[])
        run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    mock_client_cls.assert_called_once_with(api_key="test-key")


def test_api_exception_falls_back_gracefully(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    with patch("app.anthropic_lookup.anthropic.Anthropic") as mock_client_cls:
        mock_client_cls.return_value.messages.create.side_effect = RuntimeError("connection reset")
        matches = run_discovery(DiscoverRequest(vendor="SONOFF", model="SNZB-04P"))

    assert len(matches) == 1
    # The network call failed, but vendor+model identification came from
    # the device's own discovery handshake, not the network -- a known
    # vendor still earns "medium", it isn't dragged down to "low" just
    # because the (independent) web lookup failed.
    assert matches[0].confidence == "medium"
    assert matches[0].sources == []
    assert "failed" in matches[0].installGuide.content.lower()
