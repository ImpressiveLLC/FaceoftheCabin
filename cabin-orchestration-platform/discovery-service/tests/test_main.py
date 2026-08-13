from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.models import InstallGuide, Match

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_discover_returns_matches_from_run_discovery():
    fake_match = Match(
        summary="A test device",
        confidence="low",
        suggestedType=None,
        suggestedCapabilities=[],
        installGuide=InstallGuide(mode="linkonly", content="no lookup configured"),
        sources=[],
    )
    with patch("app.main.run_discovery", return_value=[fake_match]) as mock_run:
        response = client.post("/discover", json={"vendor": "SONOFF", "model": "SNZB-04P"})

    assert response.status_code == 200
    body = response.json()
    assert len(body["matches"]) == 1
    assert body["matches"][0]["summary"] == "A test device"
    mock_run.assert_called_once()
