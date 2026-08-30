"""cabin-discovery: self-discovery / assisted-onboarding lookup service.

Stateless JSON API, no device-registry or database access of its own --
cabin-backend owns all persistence and every mutation to a real device.
This service only ever answers "what might this device be", with full
source provenance on every claim.

/discover tries providers in order, cheapest and most certain first:
vendor_spec (Z2M's own exposes[], instant, free, no network call) before
ever falling through to the slower Anthropic-backed lookup. Each
provider returns a Match or None -- None means "nothing to work with
here", not "confirmed empty", so the chain keeps going.
"""

import logging

from fastapi import FastAPI

from .anthropic_lookup import run_discovery
from .models import DiscoverRequest, DiscoverResponse
from .vendor_spec import try_vendor_spec

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="cabin-discovery")


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/discover", response_model=DiscoverResponse)
def discover(request: DiscoverRequest) -> DiscoverResponse:
    vendor_match = try_vendor_spec(request)
    if vendor_match is not None:
        return DiscoverResponse(matches=[vendor_match])
    matches = run_discovery(request)
    return DiscoverResponse(matches=matches)
