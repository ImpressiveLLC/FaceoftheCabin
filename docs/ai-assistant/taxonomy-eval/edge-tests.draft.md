# Edge tests (draft for the D16 amendment)

**Status: draft hypotheses, not ratified.** Plan section 5.3 says these belong in the D16 amendment and nowhere else.
Until W-92 writes that amendment (held until gate G4), this file is their single home, the harness reads the tags from
it, and **W-92 moves the table into `ontology-decisions.md` and deletes this file**. Do not copy these rules anywhere
else in the meantime.

Each edge is a binary question that two graders answer the same way, even when the answer feels arbitrary. A test that
two careful people answer differently is a defective test: sharpen it, do not defend it.

| Tag | Edge | Discriminating test | Result |
|---|---|---|---|
| `th-aq` | Temp & Humidity vs Air Quality | Does it describe how the space feels (heat, cold, moisture) or what is in the air (gases, particles)? | Humidity stays in Temp & Humidity even though it feeds a stale-air judgment. CO2, particles, VOC and an air-quality index are Air Quality. |
| `conn-dh` | Connectivity vs Device Health | If this fails, does more than one device or household function lose something? | Yes: Connectivity (uplink, Wi-Fi, VPN, the Zigbee network as a whole). No, only this device: Device Health. |
| `dh-energy` | Device Health vs Energy | Is the action to charge or replace a device, or to change how the building uses power? | A device's own battery or check-in: Device Health. Building load or mains: Energy. A house or backup-unit battery is Device Health (ruled by Nate, 2026-10-17, R3). |
| `presence-conn` | Security & Presence vs Connectivity | Does it answer "is a person here" or "is the path up"? | A person (motion, a phone as a sign someone is home): Security & Presence. A path (can this phone or hub be reached): Connectivity. One phone appears in both, through different measurements. |
| `life-safety` | Life-safety signal (CO, smoke, leak) | Is it an alarm state that should interrupt someone? | Yes: it stays a device alarm flag, surfaces through alerts, and never feeds a comfort or air badge. Ruled by Nate, 2026-10-17 (R4, option B): the reading or state stays with its measurement's topic (CO in Air Quality, a leak in Security & Presence); the alarm state surfaces only through alerts. |
| `derived-state` | Derived state | Which question does the state answer? | Its home is that question's topic, whatever its inputs (a stale-air state belongs with the air, not with each input). |
| `view-topic` | Computed view vs topic | Is it a reading from one source, or a summary across time or across topics? | A reading: its topic. A summary across time or topics: the view (Alert History for what went wrong, Occupancy for how busy). Views own no readings. |
| `candidate-access-perimeter` | Candidate grouping: Access & Perimeter | Is it about whether something is secured (a lock, a gate, a door held shut) rather than whether a person is present? | Does not exist as a topic. If locks and gates keep drawing `cannot_tell_apart_from` against presence elements, the probe has found a missing topic; if they place cleanly under Security & Presence, drop the candidate. |
| `candidate-energy-wording` | Candidate wording: capacity questions in Energy | Is the person asking whether a load fits right now ("can I run X") rather than what was used? | Placed under Energy for now. If it keeps drawing `belongs_in` another topic, the Energy description needs capacity wording. |
| `candidate-dim-lights-view` | Candidate computed view: "why did something just change" | Is it asking why a state changed a moment ago, across several possible causes? | Does not exist as a view. If it keeps landing on Energy or Alert History with `cannot_tell_apart_from`, the probe has found a missing view; if it lands cleanly on one, drop the candidate. |

## How a test is used

- A labeler (W-90) uses the discriminating test and the topic descriptions in `topics.json`, nothing else.
- The probe (W-91) never sees this file. Its asserted `cannot_tell_apart_from` verdicts are how a weak edge shows itself.
- When a neighbor pair draws disagreement or abandonment, the fix is to sharpen the row for that pair here, then relabel only the affected elements, blind.
