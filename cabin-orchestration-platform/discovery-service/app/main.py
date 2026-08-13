"""cabin-discovery: self-discovery / assisted-onboarding lookup service.

Stateless JSON API, no device-registry or database access of its own --
cabin-backend owns all persistence and every mutation to a real device.
This service only ever answers "what might this device be", with full
source provenance on every claim.
"""

import logging

from fastapi import FastAPI

from .anthropic_lookup import run_discovery
from .models import DiscoverRequest, DiscoverResponse

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="cabin-discovery")


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/discover", response_model=DiscoverResponse)
def discover(request: DiscoverRequest) -> DiscoverResponse:
    matches = run_discovery(request)
    return DiscoverResponse(matches=matches)
