# Presence generator (W-38 slice 3, D24)

Generates the per-person Home Assistant presence blocks and the publish allow-list entries from a roster, so a second
primary-role person is one roster line and not a hand-copied block.

```bash
node generate-presence.mjs           # write ../cabin_security_presence.yaml and ../presence_people_allowlist.json
node generate-presence.mjs --check   # exit 1 if either differs from what the roster generates
node --test generate-presence.test.mjs
python -m unittest test_cabin_security_mqtt_publish    # from the parent folder
```

## What goes in the roster

`presence-people.json` lists **primary-role people only**. Each entry is an opaque person id, a location (`cabin` or `home`),
the zone, and the Home Assistant entity ids of the phone's GPS tracker and battery sensor. The repository is public, so:

- no MAC address, phone number or real name, ever. The phone's MAC and the LAN to sweep stay in the uncommitted
  `authorized_user_phones.conf`;
- a child or other family member gets an opaque id (`child_1`), never a name (D24 R-LR-10, D11 data minimization).

## What it refuses

The generator reads `scripts/host/w38-register-primary-presence.sql` and **refuses any roster person who is not PRIMARY at
that location there**. A maintenance-only person therefore never gets a phone check or a publish permission (D24 R-LR-4).
It also refuses a bad id (lowercase letters, digits, underscore, at most 32), a location outside `cabin` and `home`,
unknown keys, duplicates and more than 32 people.

## The limit to know about

The seed SQL is a file in git. **The generator cannot see the live backend registry.** A person an administrator assigns in
Config (Location Roles) is not generated until they are added to the seed SQL and the roster, the output is reviewed, and
Nate applies it. Until then the hub still counts them (an unregistered person is counted and flagged); only the Home
Assistant side, which produces the phone signal, is missing.

## The allow-list

`presence_people_allowlist.json` names people and locations only, never a topic. `cabin_security_mqtt_publish.py` builds the
two exact topics itself (`<location>/presence/<id>` with `home` or `not_home`, and `<location>/presence/<id>/last_seen`
with a UTC time). The hand-reviewed dict in that script stays the base; the JSON can only add. It fails closed: a missing,
unreadable, oversized (over 8 KiB) or malformed file adds nothing, and one bad entry rejects the whole file.

## Applying it

Nothing here is applied by an agent. After review: copy `cabin_security_presence.yaml`, `cabin_security_mqtt_publish.py`
and `presence_people_allowlist.json` to the Home Assistant config as described in `../PRESENCE.md`, reload automations. The
roster `{nate}` regenerates today's file and allow-list byte for byte (a required test), so applying this change alone
changes nothing live.

## Deferred

The family-profile link (a person's profile in the registry) is not here: nothing consumes it yet (Cowork ruling,
2026-10-30). Revisit with the Family Hub profile work.
