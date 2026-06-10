"""
Cabin Orchestration Platform — Service Watchdog
Polls all critical services every 60 seconds.
Publishes health status to MQTT: cabin/system/health
Restarts dead containers from their last known image.
"""

import json
import subprocess
import time
import socket
import os
import logging
from datetime import datetime, timezone

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("watchdog")

SERVICES = [
    {"name": "cabin-mqtt",          "check": "tcp", "host": "localhost", "port": 1883},
    {"name": "cabin-postgres",      "check": "tcp", "host": "localhost", "port": 5432},
    {"name": "cabin-kafka",         "check": "tcp", "host": "localhost", "port": 9092},
    {"name": "cabin-grafana",       "check": "tcp", "host": "localhost", "port": 3000},
    {"name": "cabin-nodered",       "check": "tcp", "host": "localhost", "port": 1880},
    {"name": "cabin-homeassistant", "check": "tcp", "host": "localhost", "port": 8123},
    {"name": "cabin-frigate",       "check": "tcp", "host": "localhost", "port": 5000},
]

MQTT_HOST = os.getenv("MQTT_HOST", "localhost")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))


def tcp_alive(host: str, port: int, timeout: float = 3.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def restart_container(name: str):
    log.warning(f"Restarting container: {name}")
    try:
        subprocess.run(["docker", "restart", name], check=True, timeout=30)
        log.info(f"Restarted {name} successfully")
    except Exception as e:
        log.error(f"Failed to restart {name}: {e}")


def publish_health(results: dict):
    """Publish to MQTT via mosquitto_pub (available from host or another container)."""
    payload = json.dumps({
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "services": results
    })
    try:
        subprocess.run(
            ["mosquitto_pub", "-h", MQTT_HOST, "-p", str(MQTT_PORT),
             "-t", "cabin/system/health", "-m", payload],
            timeout=5, check=False
        )
    except Exception as e:
        log.warning(f"MQTT publish failed: {e}")


def check_all():
    results = {}
    for svc in SERVICES:
        alive = tcp_alive(svc["host"], svc["port"])
        results[svc["name"]] = "UP" if alive else "DOWN"
        if not alive:
            log.error(f"Service DOWN: {svc['name']}")
            restart_container(svc["name"])
    publish_health(results)
    up = sum(1 for v in results.values() if v == "UP")
    log.info(f"Health check: {up}/{len(SERVICES)} services UP")


if __name__ == "__main__":
    log.info("Cabin watchdog started")
    while True:
        try:
            check_all()
        except Exception as e:
            log.error(f"Watchdog cycle error: {e}")
        time.sleep(60)
