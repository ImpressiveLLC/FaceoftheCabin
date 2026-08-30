"""First-line lookup: reporting relationships already confirmed at
discovery time by the device's own vendor data (Zigbee2MQTT's
`definition.exposes[]`, forwarded here as `vendorReportedFields` --
see Zigbee2MqttAdapter.extractVendorReportedFields() on the Java side).
Zero network call, zero cost, zero auth, and instant -- this must never
wait behind the same 25s budget the Anthropic/search-backed path needs,
since there's nothing to wait for.

D7 (docs/ontology/DECISIONS.md), D4's provenance mixin: this is the
concrete "vendor_spec" source those decisions were written for. Not
"unverified" the way a no-citation web-search result is capped -- Z2M's
own device database is the same data Z2M itself uses to configure the
device, which is a stronger claim than an uncited AI guess, even though
neither involves a live citation. Keep that distinction visible in the
copy shown to a person reviewing the match, not just in the confidence
number.
"""

from .catalog import classify_local_confidence, has_local_identity, known_vendor_note, local_identity_summary
from .models import DiscoverRequest, InstallGuide, Match


def try_vendor_spec(request: DiscoverRequest) -> Match | None:
    """Returns a Match if the caller already told us what this device
    reports -- None if there's nothing to work with, so the caller falls
    through to the next provider in the chain rather than treating an
    absent field as a confirmed "reports nothing"."""
    reported_fields = request.discoveryAttributes.get("vendorReportedFields")
    if not reported_fields or not isinstance(reported_fields, list):
        return None

    identity = local_identity_summary(request)
    note = known_vendor_note(request.vendor)
    summary = f"{identity} ({note})" if note else identity
    fields_text = ", ".join(sorted(set(reported_fields)))
    summary = f"{summary} -- confirmed by its own Zigbee device database to report: {fields_text}."

    return Match(
        summary=summary,
        # Same ceiling classify_local_confidence already uses for a known
        # vendor+model -- deliberately not raised to "high" just because
        # this claim is vendor-confirmed rather than a name match, since
        # "high" is reserved for a citation-backed web result elsewhere in
        # this module's own contract. Vendor-confirmed and citation-backed
        # are two different kinds of certainty; conflating them into one
        # number would lose the distinction the copy above exists to keep.
        confidence=classify_local_confidence(request),
        suggestedName=identity if has_local_identity(request) else None,
        suggestedType=None,
        suggestedCapabilities=[],
        suggestedReportedFields=sorted(set(reported_fields)),
        suggestedReportedFieldsSource="vendor_spec",
        installGuide=InstallGuide(
            mode="linkonly",
            content=(
                "Identified from this device's own Zigbee2MQTT device database entry -- "
                "no external lookup was needed or attempted."
            ),
        ),
        sources=[],
    )
