from app.catalog import has_local_identity, local_identity_summary
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
