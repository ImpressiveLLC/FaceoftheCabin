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
    # Added 2026-09-05 -- replaces the phone-side MacroDroid listener
    # (adware-driven, required watching ads to keep working) that used to
    # call BlinkMotionWebhookController's HTTP endpoint directly. Payload
    # is the camera name (BlinkLiveviewService's own blinkCameraMap key,
    # not the Blink app's own device name) -- see
    # cabin_security_publish_blink_motion in cabin_security.yaml for the
    # HA automation that resolves a Last-Notification-sensor hit into one
    # of these two exact strings. Allow-list is a real safety gate here,
    # same as everywhere else in this file: the automation's own template
    # logic could have a bug, but it can never publish a camera name this
    # script doesn't already know is real.
    "cabin/blink/motion": {"driveway", "home_aldrich_front"},
}

# A motion notification is a one-off EVENT, not ongoing state, unlike every
# other topic above (armed/presence/Kidde's alarm, which genuinely persist
# until something changes them and are retained so a reconnecting
# subscriber gets the current value immediately). Retaining an event
# topic would mean cabin-backend's own MqttBridgeService restart/resubscribe
# replays "motion happened" as if it just did -- exactly the retained-
# message mistake this project's own Zigbee2MqttAdapter made before it
# started checking message.isRetained(). Anything not listed here defaults
# to the original, unchanged retain=True behavior.
NOT_RETAINED = {"cabin/blink/motion"}


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
        retain=topic not in NOT_RETAINED,
        hostname="127.0.0.1",
        port=1883,
        client_id="homeassistant-cabin-security",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
