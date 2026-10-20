# HO-2026-10-19 — Cowork → Code: humid solution vectors and the recheck schedule (Nate rulings)

Both rulings are Nate's, 2026-10-19. Nothing here enables an action: asks are server-owned options a resolver may offer, never model output.

## 1. `humid` is one concept with several solution vectors
- "Stuffy", "stale air" and "needs air" stay aliases of `humid` (no separate ventilation concept). A stuffy complaint may point at heat, air flow or moisture, so the **clarification** may ask which of those the person means; it is not a reason to split the concept.
- A `humid` need may propose **several devices in several states** to correct the condition, by device class (brand-agnostic) and by area, with more than one device per class where assigned:
  1. air circulation: a fan (today the S6 Cloudline);
  2. lower temperature: the thermostat (or several thermostats assigned to the area);
  3. dehumidify: one or more dehumidifiers, or a mini-split in dehumidify mode (**not installed yet; planned for now**).
- `concepts.json` now lists thermostat-lower and mini-split-dehumidify asks beside the existing dehumidifier and fan asks. The two new asks use `set_device_attribute`; `supported_needs` still only matches `set_device_state`, so reviewer context is unchanged. Each option still needs an exactly-one, authorized, fresh, online match before it can be confirmable (D27 R-OC-6, R-OC-11); the mini-split ask resolves to nothing until one is installed, and says so rather than inventing a device.
- Sealed test row test-053 ("Stuffy in here, the air is stale") stays `humid`; the reviewer's `clarify` suggestion is not adopted.

## 2. Recurring schedules: local wall time, rechecked at least daily
- A recurring rule runs on the **site's local clock** (America/Chicago for the cabin); the ISO offset only anchors the start. A skipped spring time runs at the next valid minute; a repeated autumn time runs once, on the first pass.
- The scheduler recomputes each rule's next occurrence **at the start of every system check or a similar event**, or on a heartbeat of its own choosing, **not on a system restart** (a restart can coincide with a clock change or a temporary clock lag). The only requirement is an interval nearer daily than weekly or monthly.
- Code to: put the policy in the W-71 design note; add DST test fixtures (repeated and nonexistent wall times) to the scheduler tests; keep restart recovery as a separate test.

## Effect on the frozen test
None for the 170 authored rows (test-053 unchanged in label). The concept change alters the grading journal's fingerprint, so the next grading session starts a fresh journal automatically.
