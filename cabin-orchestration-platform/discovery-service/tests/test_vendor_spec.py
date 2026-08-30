"""D7/D4 (docs/ontology/DECISIONS.md): vendor_spec is the first-line,
zero-network-call provider -- these tests never mock or need
app.anthropic_lookup.anthropic.Anthropic, unlike that module's own tests,
since a real vendorReportedFields value must never reach the network at all."""

from app.models import DiscoverRequest
from app.vendor_spec import try_vendor_spec


def test_returns_none_when_no_vendor_reported_fields_present():
    # No discoveryAttributes at all -- the common case for a device Z2M
    # never exposed measurement info for, or a non-Zigbee integration.
    request = DiscoverRequest(vendor="SONOFF", model="SNZB-04P")
    assert try_vendor_spec(request) is None


def test_returns_none_for_a_present_but_empty_list():
    # Zigbee2MqttAdapter only ever sets this key when the list is
    # non-empty (see its own comment on why) -- but a caller sending an
    # explicit [] must still fall through, not be treated as a confirmed
    # "reports nothing" match.
    request = DiscoverRequest(vendor="SONOFF", model="SNZB-04P",
                               discoveryAttributes={"vendorReportedFields": []})
    assert try_vendor_spec(request) is None


def test_returns_none_for_a_non_list_value_instead_of_crashing():
    request = DiscoverRequest(vendor="SONOFF", model="SNZB-04P",
                               discoveryAttributes={"vendorReportedFields": "temperature"})
    assert try_vendor_spec(request) is None


def test_confirmed_match_carries_the_fields_and_vendor_spec_provenance():
    request = DiscoverRequest(
        vendor="SONOFF", model="SNZB-02WD", description="Waterproof temperature and humidity sensor",
        discoveryAttributes={"vendorReportedFields": ["temperature", "humidity", "battery"]})

    match = try_vendor_spec(request)

    assert match is not None
    assert match.suggestedReportedFields == ["battery", "humidity", "temperature"]
    assert match.suggestedReportedFieldsSource == "vendor_spec"
    assert match.sources == []
    assert "confirmed by its own Zigbee device database" in match.summary
    assert "no external lookup" in match.installGuide.content.lower()


def test_confidence_matches_the_same_ceiling_local_only_matches_already_use():
    # Deliberately not "high" -- vendor-confirmed and citation-backed are
    # two different kinds of certainty, and "high" is reserved for the
    # citation-backed case elsewhere in this service's contract.
    known_vendor = DiscoverRequest(vendor="SONOFF", model="SNZB-02WD",
                                    discoveryAttributes={"vendorReportedFields": ["temperature"]})
    unknown_vendor = DiscoverRequest(vendor="AcmeCo", model="X1",
                                      discoveryAttributes={"vendorReportedFields": ["temperature"]})

    assert try_vendor_spec(known_vendor).confidence == "medium"
    assert try_vendor_spec(unknown_vendor).confidence == "low"


def test_suggested_name_falls_back_to_none_with_no_local_identity_at_all():
    # Not a realistic case in production (vendorReportedFields implies Z2M
    # already reported something), but the function must not crash on it.
    request = DiscoverRequest(discoveryAttributes={"vendorReportedFields": ["temperature"]})
    match = try_vendor_spec(request)
    assert match.suggestedName is None
