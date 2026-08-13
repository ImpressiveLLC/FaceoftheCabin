from app.catalog import classify_local_confidence, has_local_identity, known_vendor_note, local_identity_summary
from app.models import DiscoverRequest


def test_local_identity_summary_combines_vendor_model_description():
    request = DiscoverRequest(vendor="SONOFF", model="SNZB-04P", description="wireless contact sensor")
    assert local_identity_summary(request) == "SONOFF SNZB-04P -- wireless contact sensor"


def test_local_identity_summary_handles_missing_fields():
    request = DiscoverRequest(vendor="", model="", description="")
    assert local_identity_summary(request) == "an unidentified device"


def test_has_local_identity_true_when_any_field_present():
    assert has_local_identity(DiscoverRequest(vendor="SONOFF"))
    assert has_local_identity(DiscoverRequest(model="SNZB-04P"))
    assert has_local_identity(DiscoverRequest(description="a sensor"))


def test_has_local_identity_false_when_nothing_reported():
    assert not has_local_identity(DiscoverRequest())


# Vendor recognition and confidence calibration below is grounded in the
# real 13-device candidate population on this deployment (SONOFF, Third
# Reality, Tuya -- see backend GET /api/devices) rather than a guess.


def test_known_vendor_note_matches_case_insensitively():
    assert known_vendor_note("SONOFF") is not None
    assert known_vendor_note("sonoff") is not None
    assert known_vendor_note("Third Reality") is not None
    assert known_vendor_note("tuya") is not None


def test_known_vendor_note_none_for_unrecognized_or_missing_vendor():
    assert known_vendor_note("AcmeCo") is None
    assert known_vendor_note("") is None
    assert known_vendor_note(None) is None


def test_classify_local_confidence_medium_for_known_vendor_with_model():
    assert classify_local_confidence(DiscoverRequest(vendor="SONOFF", model="SNZB-04P")) == "medium"
    assert classify_local_confidence(DiscoverRequest(vendor="Third Reality", model="3RWS18BZ")) == "medium"
    assert classify_local_confidence(DiscoverRequest(vendor="Tuya", model="TS0001")) == "medium"


def test_classify_local_confidence_low_for_unrecognized_vendor():
    assert classify_local_confidence(DiscoverRequest(vendor="AcmeCo", model="X1")) == "low"


def test_classify_local_confidence_low_when_model_missing_even_for_known_vendor():
    # Vendor alone (no model) isn't a specific-enough identification to
    # earn "medium" -- e.g. a bridge/gateway status message that names its
    # vendor but not a concrete product.
    assert classify_local_confidence(DiscoverRequest(vendor="SONOFF")) == "low"
