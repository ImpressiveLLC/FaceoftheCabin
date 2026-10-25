FROM python:3.12-alpine

RUN apk add --no-cache ffmpeg

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY blinkbridge /app/blinkbridge

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 BLINKBRIDGE_CONFIG=/config/config.json

# /healthz answers 503 when the poll loop is stale or Blink has not answered for 30 min.
# Docker marks the container unhealthy; scripts/host/blinkbridge-watchdog (or autoheal) restarts it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q -T 4 -O /dev/null http://127.0.0.1:8811/healthz || exit 1

ENTRYPOINT ["python", "-m", "blinkbridge.main"]
