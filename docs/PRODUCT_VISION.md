# FaceoftheCabin — Product Vision & Competitive Position

> Audience: anyone deciding whether this is worth building on top of vs.
> buying something off the shelf — including the family running it.
> Deliberately implementation-agnostic: this document is about value,
> positioning, and the user's experience, not the code or infrastructure
> that produces it. For the engineering decision history behind these
> capabilities, see [`PRODUCT_NOTES.md`](PRODUCT_NOTES.md) — a different
> document, for a different reader, on purpose.

---

## The one-sentence version

A self-hosted platform that unifies a family's home security, cabin
monitoring, and household coordination under one identity and one
continuous record of what happened and why — instead of several
disconnected apps that don't talk to each other and don't let the family
own its own data.

---

## The question worth asking honestly

**Why not just buy a name-brand camera, a security system, and a family
calendar app?** That's the real competitive question, and it deserves a
real answer, not a dismissal.

For a lot of households, the off-the-shelf combination is genuinely the
right call — less setup, a company on the hook if something breaks,
someone else's problem when things need attention. This platform is not a
better choice for a family that wants zero involvement in how their own
home and property systems work.

It's the right choice for a specific, real trade: **more ownership and
more responsibility, in exchange for a system whose knowledge of your
home actually belongs to you, and that gets more capable and more
trustworthy every time something is learned about how your specific
home actually behaves** — rather than a system you can only ever operate
at the level a vendor decided to expose to you.

---

## What actually happened, and why it's the argument, not the pitch

Recently, everyday activity at the cabin — a door opening, motion near
the entry, a camera detecting something — stopped showing up anywhere in
the family's view of the system, even though the activity was genuinely
happening. Investigation traced this to two separate, quiet causes deep
in how the system was recording and relaying that activity. Both are now
fixed, and — more importantly — the second one had happened once before,
which changed the response from "fix it again" to "make sure it can't
keep happening unnoticed."

**This is the actual case for this kind of platform, not a hypothetical
one.** A closed, vendor-run system would have shown a generic "offline"
status with no way to see further, and no path to a permanent fix beyond
waiting on that vendor's own timeline. Here, the underlying cause was
findable, fixable, and — critically — the fix became a permanent part of
how the system is understood going forward, not a one-time patch that
quietly could happen again with no one the wiser. That is what "owning
the system" is actually worth: not in the abstract, but on the specific
day something breaks.

---

## Competitive landscape

| | Consumer ecosystems (Ring, Nest, SmartThings) | Professional monitoring (ADT, SimpliSafe) | Fully DIY, self-assembled | This platform |
|---|---|---|---|---|
| **Setup effort** | Low | Low | High | High |
| **Ongoing ownership** | Vendor's cloud, vendor's schedule | Vendor's cloud + monthly fee | The family, entirely | The family, entirely |
| **Data ownership** | Vendor's servers, vendor's terms | Vendor's servers | The family's | The family's |
| **Cross-device unification** | Only within one vendor's ecosystem | Security-only, no household layer | Possible, but every connection is built from scratch | Built-in — different device brands and protocols already reconciled into one coherent view |
| **Household coordination (chores, calendar, notes)** | Not offered | Not offered | Not offered, needs a separate app | Built-in, same identity and history as security and monitoring |
| **When something breaks** | A support ticket, on the vendor's timeline | A support ticket, on the vendor's timeline | The family debugs it, with full visibility | The family debugs it, with full visibility, and every past incident stays part of the system's own memory |
| **24/7 professional emergency response** | No | Yes (paid) | No | No — see "Where this platform doesn't win yet" |
| **Vendor lock-in** | High | High | None | None |
| **Cost model** | Hardware, sometimes a subscription | Hardware plus a mandatory monthly fee | Hardware plus the family's own time | Hardware plus the family's own time |

---

## Northstar goals — what's promised, cross-walked against what's actually delivered

These seven commitments have anchored the platform's direction since its
first architecture review. Each one is restated here in plain terms and
checked against reality, not just intent — including the one goal that
isn't fully delivered yet, said plainly rather than smoothed over.

| # | Northstar goal | How it's delivered today | Competitive advantage | Inherent strength |
|---|---|---|---|---|
| 1 | **One unified experience** — every property, every device, every family member's phone, under one system | A single platform spans every property and every device category the family owns, reachable from any phone, laptop, or wall display | Commercial platforms are single-property, single-vendor by design — a family managing more than one property (a cabin and a home, or a relative's house) juggles multiple disconnected apps and accounts today | Adding a new property or device extends the same system rather than starting a new vendor relationship from zero |
| 2 | **See → Think → Act on everything** — information, understanding, and action are never separated | Every feature is held to the same standard: the right information visible without digging, enough context to understand what it means, and a next action never more than a step or two away | Commercial security apps routinely show a bare notification with no context, or bury the actual action three menus deep — this platform's design standard structurally rejects both failure modes | It's a permanent contract applied to every new feature, not a one-time UI pass — the system doesn't degrade into notification noise as it grows |
| 3 | **A living ontology** — a continuously maintained map of the family's whole ecosystem | Every device, person, and piece of data the platform knows about lives in one structured, evolving map — the actual foundation the system runs on, not documentation written after the fact | A commercial platform's internal data model is proprietary and invisible to you; here, the map *is* the platform, and it's the family's | New capability gets added by extending a known structure, not by guessing at undocumented behavior |
| 4 | **FAIR data** — Findable, Accessible, Interoperable, Reusable | Every piece of data is structured to be found, understood by people and by future automation, connected to related data, and reused without being re-collected | Vendor data formats are built to keep a household inside that vendor's app — this data is structured to be genuinely useful to the family that owns it, not just to the platform | Positions the system to take advantage of AI-assisted analysis as that technology matures, without re-architecting the data later |
| 5 | **Full event history and audit trail** — camera through automation through action, end to end | Every device signal and every action taken — automated or human — becomes a permanent, traceable record | Consumer platforms typically show a short rolling window of clips, not a queryable history of what happened and why — the real incident described earlier in this document is proof this works, not a promise that it will | "What happened and why" doesn't depend on anyone remembering to write it down — normal operation produces the record |
| 6 | **Self-improving discovery** — the system surfaces what could be better, not just what broke | **Partially delivered, said plainly:** a structured way to submit, review, and act on improvement opportunities exists and is in use today, with a full record of what was considered and why. **Not yet delivered:** the fully automated process that proactively goes looking for those opportunities on its own — today, something has to point it at a candidate first | Even in its current, partial form, this beats commercial platforms outright — they only ever surface their own next product to sell; this surfaces opportunities grounded in what the family actually already owns | Every recommendation is cross-referenced against the family's real, specific devices, not a generic upsell list |
| 7 | **Radical device flexibility** — no vendor decides what's allowed to connect | Different camera brands, sensor ecosystems, and automation tools already coexist in one system, with no single vendor gatekeeping what can join | Buying into a name-brand ecosystem means living inside that vendor's rules for what connects and what doesn't — this platform assumed the opposite from day one | Every new device or protocol follows the same repeatable onboarding pattern, so flexibility doesn't get harder to maintain as the system grows |

### Strengths at a glance

**Competitive** (advantages that exist because of how the alternatives are built):
- Every property and every device category in one place, where commercial platforms fragment by vendor and by property
- A record of what happened that a cautious observer would actually trust, where consumer apps offer a short clip window at best
- Improvement suggestions grounded in the family's real, owned devices, where vendors only ever upsell their own next product
- No vendor gatekeeping which devices are allowed to join the system

**Inherent** (true regardless of what else exists in the market):
- The system's understanding of the family's home compounds over time instead of resetting with every new device or every support ticket
- Design standards are permanent contracts applied to every new feature, not one-time decisions that erode as the system grows
- The data itself is structured to remain useful — to the family, and eventually to AI-assisted analysis — without needing to be rebuilt later
- Flexibility and unification aren't add-ons bolted on afterward; they were the starting assumption

---

## User-centric capabilities — designed around real people, not device categories

The platform is built around a simple standard for every feature: the
right information should be visible without digging for it, understanding
that information shouldn't require special expertise, and the right next
action should always be one or two steps away. It's designed to serve
several different people at once from the same system — a family member
who just wants things to work and never wants to see a technical detail,
a relative managing more than one property who needs real operational
visibility, someone who cares about liability and wants a trustworthy
record of what happened and when, and a technically-inclined operator who
wants full access to how everything actually works underneath. **No
commercial product serves all of those people from one system** — a
security brand serves the security-conscious buyer, a calendar app serves
household coordination, and neither produces a record a cautious observer
would actually trust, nor lets a less technical family member stay
completely unaware of the complexity running underneath.

---

## Where this platform doesn't win yet — said plainly, not buried

- **No 24/7 professional emergency response.** If the system loses power
  or connectivity, or the family is unreachable, there is no third party
  watching on the family's behalf. Paid professional monitoring services
  offer exactly this guarantee; this platform does not have an equivalent
  today.
- **A single point of failure by design.** The cabin's entire system runs
  from one host. This is a deliberate trade-off — simplicity over
  redundancy — made and accepted knowingly, not an oversight.
- **Verification and quality processes are new and still maturing.**
  Systematic, repeatable checks on the platform's own reliability are a
  recent addition to how it's maintained, not a long-established practice.
  Confidence in any specific capability should be weighed accordingly.
- **Some alerting capability is still partial.** The most urgent
  conditions (an active leak, smoke) reliably reach a family member's
  phone today; a complete, at-a-glance view of everything else the system
  notices is still being built out — see the User Guide's Alerts &
  Notifications section for the current, honest state of what exists.
- **This requires an ongoing owner.** Every value proposition above
  assumes someone in the family is willing to occasionally read what
  happened, understand a fix, or spend time keeping the system healthy.
  That's the real cost behind everything above — not hidden, not
  absorbed into a monthly fee someone else pays on the family's behalf.

---

## The honest summary

This platform isn't trying to be a better version of a big-name security
brand with more steps. It's a bet that a family's own home and property
data — what exists, what happened, what's connected to what — is worth
owning outright, in a system that gets measurably more trustworthy every
time something goes wrong, rather than renting that same visibility back
from vendors who have no reason to make it legible in the first place.
The story above is the argument for that bet, not a hypothetical one: a
real, quiet failure, found down to its actual cause, fixed for good, and
folded into a system that remembers it. That compounding understanding —
not any single camera or sensor — is the actual product.
