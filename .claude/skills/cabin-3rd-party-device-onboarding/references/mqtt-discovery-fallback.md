# Fallback path: hand-rolled MQTT discovery bridge

Only reach for this when **no maintained Home Assistant integration
exists at all** for the vendor -- check HACS and HA core first (see the
main SKILL.md's Step 0). This path means something else (a script, a
small service, a scheduled job) has to poll the vendor's own API/webhook
and republish the result as MQTT, with Home Assistant's own MQTT
discovery convention describing the entity. It's real, ongoing
maintenance burden a proper HA integration doesn't have -- you now own
keeping the bridge running, not just consuming someone else's
already-maintained one.

**Cautionary precedent from this exact project**: an earlier, incomplete
attempt at bridging Kidde this way left a single retained MQTT message on
a state topic, manually published once and never updated again. It sat
in Home Assistant looking like a live, configured device -- three real
entities, plausible values -- for weeks, discovered only when someone
went looking for why the numbers never changed. A `mosquitto_sub -t '#'
-C <n> -v` sweep (or your broker's equivalent) is the fast way to confirm
something is *actually* still publishing before trusting an existing
entity's data. When a real HA integration later replaced it, the dead
entities had to be found and disabled explicitly so they didn't confuse
a future "why are there two devices for this" search. If you build a
hand-rolled bridge, make very sure whatever is meant to keep republishing
actually keeps running -- a one-time manual `mosquitto_pub` is a test
step, never the finished bridge.

## Mechanics checklist

- **Discovery topic must be exact**:
  `homeassistant/<component>/<node_id>/<object_id>/config` (e.g.
  `homeassistant/sensor/kidde_iaq_01/temperature/config`). A wrong
  segment fails silently -- HA just never creates the entity, with
  nothing in its log pointing at why.
- **Config messages must be published with `retain` set**, or Home
  Assistant only ever sees them if it happens to already be connected at
  the exact moment of publish. Retained config is what lets HA rediscover
  the entity on its own restart.
- **State messages need `retain` too** if you want the last known value
  to survive an HA reconnect/restart instead of the entity showing
  "unavailable" until the next real update.
- **Verify HA's MQTT integration is actually pointed at the broker you're
  publishing to.** `docker ps` / `ss -tlnp` to see what's really bound to
  the port before assuming a publish failed on HA's end -- a stray second
  broker (an accidental `snap install mosquitto` alongside a Dockerized
  one, for instance) will silently eat every publish with no error on
  either side.
- **`network_mode: host` on the Home Assistant container means it cannot
  resolve other containers by Docker service-name DNS.** Point its MQTT
  integration at `localhost`/`127.0.0.1`, not a service name like
  `mosquitto` -- that hostname simply won't resolve from inside a
  host-networked container.
- **MQTT wildcards are `+` (single level) and `#` (multi-level, trailing
  position only)** -- there is no `*` glob. Using `*` just matches
  nothing, silently.
- **Verify the retained message actually landed broker-side** before
  assuming HA is misconfigured:
  `mosquitto_sub -t "homeassistant/#" -v -C <n> | grep <device>`. If
  nothing comes back, the publish itself failed (wrong broker, wrong
  topic, auth) -- that's a different bug than an HA-side discovery
  problem, and worth ruling out first since the symptom ("entity never
  appears") looks identical either way.
- **After confirming discovery, check HA's Entities tab, not just the
  Devices list view.** The Devices list can lag or cache stale
  immediately after an area assignment or a fresh discovery -- a missing
  device there isn't necessarily a real registration failure.
- If a tooling side-quest costs you your CLI clients (e.g. removing a
  stray second broker package also removes its bundled
  `mosquitto_pub`/`mosquitto_sub`), reinstalling them is normal cleanup,
  not a sign something deeper broke -- just remember your shell may have
  cached the old binary path (`hash -r` in bash) after reinstalling.

## Once entities exist in HA

Everything from here on is identical to the HA-integration path in the
main SKILL.md -- `inferType()`, semantic field normalization, frontend
parity, the already-configured-device trap, ontology + tests. The
hand-rolled bridge only changes *how the entity gets into HA*, not
anything about how this app consumes it afterward.
