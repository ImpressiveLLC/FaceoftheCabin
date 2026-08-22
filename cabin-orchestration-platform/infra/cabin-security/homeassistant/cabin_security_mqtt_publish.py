"""Publish only the allow-listed cabin security states to local MQTT."""

import sys

from paho.mqtt import publish


ALLOWED = {
    "cabin/security/armed_away": {"ON", "OFF"},
    "cabin/presence/nate": {"home", "not_home"},
    # Reconciled 2026-08-21 -- live-only until now (confirmed present on
    # the M920q's real deployed copy of this script via SSH, absent from
    # git), no matching entry existed here. Publisher/automation for this
    # topic isn't in this package's own cabin_security.yaml either --
    # likely added directly alongside the separate, newer home_presence.yaml
    # package (present live, not yet in this repo) -- left as an
    # observation for whoever reconciles that file, not touched here since
    # it's outside this change's actual scope (the Kidde bridge below).
    "home/presence/nate": {"home", "not_home"},
    # Added 2026-08-21 -- the real push bridge for
    # docs/ontology.yaml's trigger_kidde_co_alarm (was candidate-only:
    # a real HA entity exists, per smart_appliance_co_air_quality_kidde,
    # but nothing republished its state to MQTT). Boolean convention
    # mirrors armed_away's own ON/OFF, not the CO-alarm entity's own
    # HA state string, so cabin-backend's MqttBridgeService subscriber
    # doesn't need to know that string either.
    "cabin/kidde/co_alarm": {"ON", "OFF"},
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
