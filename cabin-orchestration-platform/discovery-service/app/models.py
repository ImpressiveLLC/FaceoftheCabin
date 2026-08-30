"""Request/response shapes for the discovery service.

Mirrors cabin-backend's DeviceDiscoveryResult record (see
backend/src/main/java/com/cabin/orchestrator/devices/model/
DeviceDiscoveryResult.java) field-for-field on the `Match` side so the
Java DiscoverResponse deserialization stays a straight mapping.
"""

from typing import Literal, Optional

from pydantic import BaseModel, Field


class DiscoverRequest(BaseModel):
    vendor: str = ""
    model: str = ""
    description: str = ""
    protocolAdapter: str = ""
    connectionString: str = ""
    deviceType: str = ""
    capabilities: list[str] = Field(default_factory=list)
    discoveryAttributes: dict = Field(default_factory=dict)


class Source(BaseModel):
    url: str
    title: str
    snippet: str
    fetchedAt: str  # ISO-8601, matches java.time.Instant's JSON form


class InstallGuide(BaseModel):
    mode: Literal["extract", "summary", "linkonly"]
    content: str


class Match(BaseModel):
    summary: str
    confidence: Literal["high", "medium", "low"]
    suggestedName: Optional[str] = None
    suggestedType: Optional[str] = None
    suggestedCapabilities: list[str] = Field(default_factory=list)
    # D7 (docs/ontology/DECISIONS.md): specific measurement-type names this
    # device reports, distinct from suggestedCapabilities' broader
    # DeviceCapability buckets -- see local_catalog.py, the one provider
    # that currently populates this.
    suggestedReportedFields: list[str] = Field(default_factory=list)
    # D4's provenance mixin, applied to suggestedReportedFields
    # specifically. None for a match that doesn't make this kind of claim
    # at all -- never defaulted to a value implying one.
    suggestedReportedFieldsSource: Optional[Literal["vendor_spec", "empirical_observation", "type_inferred", "manual_override"]] = None
    installGuide: InstallGuide
    sources: list[Source] = Field(default_factory=list)


class DiscoverResponse(BaseModel):
    matches: list[Match]
