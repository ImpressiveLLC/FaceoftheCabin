"""Publish only the two allow-listed cabin security states to local MQTT."""

import sys

from paho.mqtt import publish


ALLOWED = {
    "cabin/security/armed_away": {"ON", "OFF"},
    "cabin/presence/nate": {"home", "not_home"},
}


def main() -> int:
    if len(sys.argv) != 3:
        return 2

    topic, payload = sys.argv[1:]
    if topic not in ALLOWED or payload not in ALLOWED[topic]:
        return 3

    publish.single(
        topic,
        payload=payload,
        qos=1,
        retain=True,
        hostname="127.0.0.1",
        port=1883,
        client_id="homeassistant-cabin-security",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
