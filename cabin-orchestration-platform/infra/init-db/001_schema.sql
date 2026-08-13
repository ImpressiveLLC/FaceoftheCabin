-- Enable TimescaleDB
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Device registry
CREATE TABLE IF NOT EXISTS device (
    device_id    TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL,
    capabilities TEXT[],
    protocol     TEXT,
    config       JSONB,
    created_at   TIMESTAMPTZ DEFAULT now(),
    updated_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS device_lifecycle_state_idx
    ON device ((config->>'lifecycleState'));

-- Time-series telemetry
CREATE TABLE IF NOT EXISTS telemetry (
    time      TIMESTAMPTZ NOT NULL,
    device_id TEXT NOT NULL,
    metric    TEXT NOT NULL,
    value     DOUBLE PRECISION,
    unit      TEXT,
    meta      JSONB
);
SELECT create_hypertable('telemetry', 'time', if_not_exists => TRUE);

-- Events / alerts
CREATE TABLE IF NOT EXISTS cabin_event (
    event_id    TEXT PRIMARY KEY,
    time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    device_id   TEXT,
    event_type  TEXT NOT NULL,
    severity    TEXT NOT NULL,
    payload     JSONB
);
CREATE INDEX ON cabin_event (time DESC);

-- Automation rules
CREATE TABLE IF NOT EXISTS automation_rule (
    rule_id     TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    enabled     BOOLEAN DEFAULT TRUE,
    trigger     JSONB NOT NULL,
    conditions  JSONB,
    actions     JSONB NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT now()
);
