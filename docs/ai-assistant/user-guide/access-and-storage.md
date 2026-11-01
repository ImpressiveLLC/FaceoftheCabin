# Sharing access and keeping the disk healthy

Audience: administrators, plus the household-safe summary in "Who can see and change what". Behavior as of `main` on 2026-10-31, read from the app and backend source; nothing here was run on the live hub. Anything that depends on how the hub is set up is marked "host-dependent". No link, token, password or address appears here.

## Who can see and change what

| Who | How they get in | What they can do |
|---|---|---|
| Administrator | Google sign-in with an email on the instance's administrator list | Everything below, plus administrator-only screens: Guest Access (share and demo links), Managed Users, Trim old backups, Optimization Opportunities, Location Roles and confirming a platform import (households can read the import records) |
| Household member signed in with Google | Google sign-in with any other accepted account | See the app and make the changes that need a sign-in, for example workflows, notes and chores. Administrator-only screens say "Admin access required" or "Only an administrator can trim storage" |
| Managed user, "Household member (read/write)" | A sign-in link from Managed Users (copied and handed over, or emailed) | Same as a household member. Cannot manage share links or managed users |
| Managed user, "Viewer (read-only)" | A sign-in link from Managed Users (copied or emailed) | Look only. Any change is refused |
| Guest link | A share link from Guest Access | Read-only. It reaches the parts ticked when the link was made (Dashboard, Device states, Alerts, Historical readings) and whatever is open to anyone without signing in; it reaches nothing else and cannot change anything |
| Demo link | A demo link from Guest Access | The whole app, read-only. Cameras, locks, motion and door sensors, and phones show as "hidden for Demo viewers" |
| Not signed in | Opening the app without signing in | Read the areas marked **R** under Not signed in below. Everything else asks for a sign-in |
| Child, kiosk display and service roles | Defined in the code, not yet assignable | Nothing today: no one can be given these roles yet |

Signing in with Google is in Config. Workflow buttons appear only when signed in.

Evidence: `GoogleAuthInterceptor` (role derivation, managed-session gate, guest scope list `SCOPE_PATH_PREFIXES`), `WebConfig`, `ManagedUserRole.allowsWrite`, `HouseholdRole`, `DemoAccessPolicy`, each controller's own role gate (named in `RoleMatrixTest`), `HIDDEN_FOR_DEMO` and `GUEST_SCOPE_OPTIONS` in `App.jsx`.

## What each role can do, by area

Audience: household.

**By area.** One row per part of the app; one column per way of getting in. **C** create, **R** read, **U** update, **D** delete, **+** an action that is none of those (asking a question, sending a device command, firing a workflow, starting a live camera view, switching a Home Assistant switch, confirming an import, trimming backups), **-** not allowed. A letter is shown when at least one example of that operation is allowed: Rules and workflows shows C and D for creating and deleting a workflow and + for firing one.

| Area | Not signed in | Administrator | Google household member | Managed household member | Managed viewer | Guest link | Demo link |
|---|---|---|---|---|---|---|---|
| Status checks and alerts | R | CRD | CRD | CRD | R | R | R |
| Alert history (doors, motion, who silenced what) | - | R | R | R | R | - | - |
| Devices: inventory, status and commands | R | CRUD+ | CRUD+ | CRUD+ | R | R | R |
| Device type re-detect | - | + | - | - | - | - | - |
| Home Assistant switch calls | - | + | + | + | - | - | - |
| Rules and workflows | R | CRD+ | CRD+ | CRD+ | R | R | R |
| Schedules | - | CR | CR | CR | R | - | - |
| Knowledge notes behind Ask | R | CR | CR | CR | R | R | - |
| Ask: explain and diagnose | - | + | + | + | - | - | - |
| Ask: action proposals (off by default) | - | + | - | - | - | - | - |
| Places (locations) | R | CRUD | CRUD | CRUD | R | R | R |
| Family Hub notes, chores and profiles | - | CRUD | CRUD | CRUD | R | - | - |
| Cameras | - | R+ | R+ | R+ | R | - | - |
| Presence and armed state (change = manual presence override) | - | RU | RU | RU | R | - | - |
| Presence history | - | R | R | R | R | - | - |
| Historical readings | R | R | R | R | R | R | R |
| Event history | - | R | R | R | R | - | - |
| Dashboard settings | R | R | R | R | R | R | R |
| Cabin status summary | - | R | R | R | R | - | - |
| Assistant inventory | - | R | R | R | R | - | - |
| Tech ID findings (Opportunity Map data) | R | RU+ | RU+ | RU+ | R | R | - |
| Platform import: records | - | R | R | R | R | - | - |
| Platform import: confirm | - | + | - | - | - | - | - |
| Guest Access links | - | CRD | - | - | - | - | - |
| Roof Monitoring page: entries | - | CRD | CRD | CRD | R | - | - |
| Managed users | - | CRU | - | - | - | - | - |
| Location roles | - | RU | - | - | - | - | - |
| Disk status | R | R | R | R | R | R | - |
| Trim old backups | - | R+ | - | - | - | - | - |
| Platform info | - | R | - | - | - | - | - |
| Optimization Opportunities | - | RU | - | - | - | - | - |

How to read the table:
- The Administrator column is every area. Where a row says **-** for everyone else, only an administrator reaches it ("Admin access required").
- Anyone who can reach the hub can read the areas that say **R** under "Not signed in"; that is deliberate (D14), so a wall display works with no sign-in. A guest link reads those too, plus the parts ticked on it.
- A managed **Viewer** cannot change anything, but for the role-checked reads (presence history, cabin status summary, the assistant inventory, import records) any managed account counts as a household member, so a Viewer can read them. A Viewer cannot use Ask (read-only accounts are refused every non-read request).
- Ask's action proposals are off unless the hub turns them on; while off the route answers "not found" to everyone. The + on Devices never opens or closes a valve, and a lock accepts only the lock command, never unlock.
- A Roof Monitoring link (Config) reads that page and posts comments or answers there, nothing else. It is the only link that can post, and has no column here.
- Every build checks this table against the app's sign-in code (`RoleMatrixTest`). It models each screen's own role check and does not call every screen.

## Share read-only access with a link (Guest Access)

**Short answer.** To give someone without a Google account (an insurance adjuster, a contractor) a look at the cabin, go to Config > **Guest Access** > "Other share links". Type a label, tick what they may see (**Dashboard**, **Device states**, **Alerts**, **Historical readings**), set **Expires in** (days; blank means never), and press **Create link**. Copy the link straight away: "copy it now, it won't be shown again".

- The link is read-only. It can never change anything, and apart from the areas anyone can read without signing in (the **R** cells under Not signed in in the table above), it cannot open anything you did not tick.
- **Revoke** next to a link stops it at once. A stopped link stays in the list marked **Revoked** or **Expired** as a record. Revoking cannot be undone; make a new link instead.
- Each link shows when it was last opened and how many times. **View activity** lists the last 30 days: time, which part of the app, and the result.
- The Config card for the Roof Monitoring page makes a separate kind of link that can only read that page and post comments or answers there. Revoke it from the Guest Access list.

What it does not do: it does not create an account, and it does not send anything. You send the link yourself.

Evidence: `GuestAccessCard`, `AccessActivity`, `RoofSpecOwnerCard` in `App.jsx`; `CabinAccessTokensController` (`/api/access-tokens`), `CabinAccessToken.isActive`; usage trail kept 90 days by default (`cabin.access.events-retention-days`).

## Give someone a demo link

**Short answer.** For someone evaluating the product, use Config > **Guest Access** > "Demo link for product evaluation". Name it after the person and why (for example "Agent evaluation - Jane Doe"), set **Lives for** (whole days, 1 or more), press **Create demo link**, and copy the link at once; it is shown only once.

- A demo link shows the real app read-only with no sign-in. Cameras, locks, motion and door sensors, and phones are hidden, and the Config cards for Guest Access, Managed Users and Location Roles are not shown to it.
- It always has an end date. The form shows "it stops working on" a date before you create it. After that, anyone opening it sees "This link is no longer active." To end it early, press **Revoke** in the list.

Evidence: `DemoLinkSection` in `App.jsx`; `CabinAccessTokenService.create` (a demo link holds only the demo scope and defaults to 30 days if no lifetime is sent); `DemoAccessFilter` refuses every change.

## Add someone without a Google account (Managed Users)

**Short answer.** In Config > **Managed Users**, enter their email address and name, choose **Viewer (read-only)** or **Household member (read/write)**, and press **Add managed user**. Then press **Copy sign-in link** to copy a link and hand it to them yourself, or **Invite** to email it. There is no password.

- Adding and inviting are separate, so you can fix a mistyped email before anything is sent.
- The link, copied or emailed, works once and expires after 30 minutes. If it has expired or was used, press **Copy sign-in link** (or **Invite**) again. Anyone who holds a copied link can sign in as that person until it is used or expires, so hand it over privately. After they use it, they stay signed in on that browser for up to 90 days.
- **Deactivate** signs them out everywhere at once and blocks new links. **Reactivate** lets them be given a new link again. Nothing is deleted.
- **Invite** needs the hub's email service to be set up (host-dependent), and a test sender may only mail its own account. If it cannot send, Invite shows an error instead of "Invite sent." **Copy sign-in link** does not use email: use it when Invite fails.
- A managed user can never manage share links or other managed users, even as a household member.

Only administrators manage share links and managed users. Managed accounts, guest links and demo links are always refused on these screens.

Evidence: `ManagedUsersCard` in `App.jsx`; `ManagedUsersController`; `ManagedUserService` (`createSignInLink` builds the copied link; the route answers `Cache-Control: no-store`; `MAGIC_LINK_TTL` 30 minutes, `SESSION_TTL` 90 days, `validateSession` ends a deactivated user's sessions); `ResendEmailSender` refuses to send when its key is not configured; `GoogleAuthInterceptor.handleManagedSession` returns 403 for `/api/managed-users` and `/api/access-tokens`.

## Disk space warnings

**Short answer.** The hub watches two disks: the large storage disk (nightly backups, recorded video, sensor-history archives) and the system disk (the database and Docker). At **60 percent** full you get a warning; at **75 percent**, a critical alert. It appears in Rules & Alerts > Status Checks, says how full the disk is, and says what to do. It clears once the disk falls 3 points below the line, and repeats once a day while the disk stays above it. The warning itself never deletes anything.

What to do:

- **Storage disk.** Expand the alert. Its Act step is the **Trim old backups** control (next section, administrator only). After that, recorded-video retention is the next thing to review, which is a host setting.
- **System disk.** The alert says to check Docker's disk use and old images or build cache. The database lives on this disk, so never delete its volumes. This is a host task for an administrator.
- A critical storage alert, like any critical alert, can only be snoozed for 1 hour.
- **Is the critical storage alert an emergency?** It depends on automatic backup trim. If it is installed, switched on and running (host-dependent), the storage-disk alert at 75 percent says "Not a true emergency": while the disk is at or above the automatic line (70 percent by default) the host removes the oldest backup copies, down to 55 percent by default, never leaving fewer than 3 per folder, and the level corrects itself. The alert quotes the numbers the host is actually using, shows as a warning, and does not send a phone push. It becomes critical, with a push, if automatic trim is not installed, is switched off, has stopped updating, could not free enough, or the disk is still that full a day later. In those cases the alert says automatic trim is not doing the job and that you need to act, using Trim old backups.
- If the hub's automatic backup trim is installed (host-dependent), it removes the oldest backup copies as above and records an event each time it runs (a warning if it could not get the disk back down).
- Whether the warning also reaches a phone depends on the hub's notification setup (host-dependent) and is not guaranteed.

Changing the 60 and 75 percent lines in the hub's settings file has no effect today; see [Settings that never reach cabin-backend](configuration.md#settings-that-never-reach-cabin-backend-k09-slice-2).

Evidence: `StorageMonitorService` (defaults 60, 75, clear margin 3, repeat 24 hours; HDD and NVMe mounts; `actionFor` text); `application.yml` `cabin.storage.monitor`; `CRITICAL_SNOOZE_DURATIONS` in `App.jsx`; [STORAGE_ARCHITECTURE.md](../../STORAGE_ARCHITECTURE.md) "Why it filled to 99%" for the automatic trim settings.

## Trim old backups

**Short answer.** Trim old backups frees space on the storage disk by deleting older copies of the nightly backups. It is safe for your data in this sense: it never touches sensor history (telemetry), the database or recorded video, and it always keeps the newest copy of every backup folder. It is permanent: deleted backup copies cannot be brought back.

How to use it (administrator only):

1. Open **Monitoring** and find the **Storage - backups and disk space** section (administrators only; nobody else sees it), or in Rules & Alerts > Status Checks expand the storage-disk alert, where the same control is its Act step.
2. Move the slider "Keep the newest N nightly copies of each folder" toward **Trim more** or **Trim less**. Each folder row shows how many copies are kept and removed, and the summary shows about how much is freed and how full the disk would be.
3. Optional: "Need room for a big job? Free up at least N GB", then **Set the slider**. If even keeping 1 copy is not enough, it says so; the rest of the space is recorded video or a one-off system copy, which this control does not touch.
4. Press **Go**. The button reads "Trimming on the host" until the hub reports back, then the card shows "Last trim: removed N copies, freed" an amount.

Things to know:

- The app itself cannot delete files. A small service on the hub does the deleting (host-dependent). Until it is installed, the control says "Automatic backup trim is not running", shows how full the disk is, and lists the install commands for the owner to run once on the hub, with a Copy commands button. Nothing can be trimmed from the app until then, and nothing trims by itself. Once installed, the control also says whether automatic trim is on and with which numbers, and warns if the host service has stopped updating or its last automatic run could not free enough.
- Anyone who is not an administrator sees "Only an administrator can trim storage."
- How long sensor history is kept, and the monthly archive of old readings, are separate from this control; see [Backup and restore recipes per store](maintenance.md#backup-and-restore-recipes-per-store-k10-verified-from-source-only).

Evidence: `StorageTrimWidget` in `App.jsx` (labels and the note "Go permanently deletes the older backup copies, oldest first. The newest copy of each folder is never removed. Telemetry is never touched."); `StorageTrimController` (administrator only); `StorageTrimService.request` (keep at least 1; the host tool refuses telemetry and database paths); [STORAGE_ARCHITECTURE.md](../../STORAGE_ARCHITECTURE.md) "Trim control (W-62)"; backlog W-62 and W-11b "built, needs the host agent installed" (checked 2026-10-31; whether it is installed on the hub is not verified).
