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
    suggestedType: Optional[str] = None
    suggestedCapabilities: list[str] = Field(default_factory=list)
    installGuide: InstallGuide
    sources: list[Source] = Field(default_factory=list)


class DiscoverResponse(BaseModel):
    matches: list[Match]
