-- W-38 slice 1 (D24): register the people who ALREADY publish presence as primary, so deploying the role
-- registry changes nothing about who is recognized. Staged in git; applying it on the host is Nate's step.
--
-- Run once AFTER the backend with the registry has started (it creates the table on boot), e.g.:
--   docker exec -i cabin-postgres psql -U cabin -d cabin < scripts/host/w38-register-primary-presence.sql
--
-- Until this runs nothing breaks: a signal from a person who is not registered is still COUNTED (the siren gate fails
-- safe) and is listed as "unregistered, counted" by GET /api/presence/roles and as unregisteredCounted in GET /api/presence.
-- Exit rule: once the people below are seeded, an unregistered signal is still counted but raises that flag, so a new
-- phone or a mistyped person id is visible instead of silent.
--
-- person_id is the id the publisher uses: the {person} segment of cabin/presence/{person} (MQTT), or the Home Assistant
-- entity id when the backend discovers a person/device_tracker entity itself. Add one line per primary-role person at
-- each location. A maintenance-only person (a contractor, the person who maintains the location) is registered with
-- role 'MAINTENANCE' instead and is then a guest for presence: not counted, arrival recorded as MAINTENANCE_VISIT.

INSERT INTO tracked_person_location_role (person_id, location, role) VALUES
    ('nate', 'cabin', 'PRIMARY')
ON CONFLICT DO NOTHING;
