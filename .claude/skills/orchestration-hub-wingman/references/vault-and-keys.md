# Vault and keys

Two long-lived pieces of custody work that Nate asked Wingman to carry on 2026-10-27: the **Home Vault** (W-120, an encrypted second-site copy of what cannot be regenerated) and the **security-key set** (hardware second factors for the accounts and hosts he uses). This file is the design and the rules. It holds no prices, no model numbers, no stock figures and no secrets; a dated purchase record carries the live cart facts (`carts-and-purchases.md`). Owner: Cowork. Decisions that need a person: Nate. Agents do not buy, install, plug in, or touch the router.

Check marks in tables: `R` recovered from a vendor or project page by a research pass on 2026-10-27 and not re-fetched since, `U` unknown or conflicting, ask before relying on it. Nothing here is `V` (verified live by hand) unless a row says so.

## 0. Custody classes (apply to both parts)

Every secret has one live custody class and an escrow in K4. Unattended automation never depends on K0 or K1.

| Class | Holds | Examples | Where it lives | What an agent may see |
|---|---|---|---|---|
| K0 Memory | what only Nate knows | the password manager's master passphrase; the passphrase of any repository that holds private evidence | Nate's head. No copy in git, chat, notes or any agent-visible place | nothing |
| K1 Hardware | something Nate has | the security keys (primary, secondary, backup); the offline NVMe copy | keychain, home desk, a third place that is not the cabin; never all in one site | nothing; at most "a key is registered", never serial numbers or PINs |
| K2 Password manager | everyday logins, the note of which key is registered where (service names only) | the manager Nate uses (U which one, if any) | the manager's own vault, protected by K0 and K1 | nothing; agents never open it |
| K3 Host-held | unattended automation secrets | the Ansible Vault password file and live `.env` on the M920q, the restic repository password the timer uses, deploy and CI tokens | on the host only, restricted file mode; Ansible Vault ciphertext is in git, the password never is | presence, length, hashes; never values (`cabin-credential-lifecycle`) |
| K4 Paper and sealed | recovery material | recovery codes, an escrow of the K3 passwords, the PINs and PUKs of the backup key | written or printed, sealed, stored away from the cabin and away from the key it recovers | nothing |

A lost K1 item is revoked from every service the same day (section B1). A K3 secret that leaves its host is rotated, not "kept an eye on" (`cabin-credential-lifecycle`).

# Part A. Home Vault (W-120)

## A1. Purpose and threat model

The cabin has already flooded and frozen, and every backup today sits on the same cabin disk as its source (`docs/STORAGE_ARCHITECTURE.md`). The vault must survive: loss of the cabin site (flood, freeze, fire, theft); compromise of or a mistake on the M920q (ransomware, a bad prune, an agent error); silent media corruption; and a lost key. It is **not** a platform node (D23: Home stays a collector), not a replacement for the leak, freeze and siren automations that stay in Home Assistant, not a home for recorded video or other disposable data, and never a reason to delete anything at the cabin (the W-49 dump-warning rule for BI telemetry is unchanged).

## A2. Architecture

```
M920q (cabin, the head)
  systemd timer -> restic backup     client-side encryption; repository password is K3, escrow K4
        |
        |  restic REST protocol over the tailnet (WireGuard); one port, one source; no router port forward
        v
Vault node (Home, wired LAN)         rest-server --append-only --private-repos; stores ciphertext only
  mirrored storage, UPS signalling a clean shutdown, SMART monitoring, scrub
        |
        |  by hand, from a Home LAN machine, on a schedule Nate sets
        v
Offline NVMe copy (K1)               a second repository, seeded by hand, rotated away from Home
```

- **Client-side encryption.** restic encrypts before upload, so the vault node and the disks hold ciphertext. A stolen node or disk leaks nothing without the repository password. The node never needs the password, and it should not have it.
- **Append-only.** The cabin credential can add snapshots but cannot delete or overwrite them. `forget` and `prune` therefore do not work from the cabin by design. Pruning is a rare, owner-run job (quarterly, or when the repository crosses a size line) done on the Home side with a credential the cabin host never holds, after a dry run is read. This matches the standing rule that permanent deletions are run by Nate, not by an agent session.
- **Transport.** Tailscale carries it: outbound-initiated, no inbound port on the Home router, one ACL rule allowing the M920q to reach the vault's one port and nothing else. Do not publish the vault with `tailscale cert` or `serve`: those publish the node's name to a public certificate log, and WireGuard plus restic already encrypt. (R)
- **Offline copy.** The first full upload from the cabin is impractical on its uplink, so the first copy of the repository is seeded by hand, and a second repository on a spare NVMe SSD in a USB enclosure is refreshed from a Home LAN machine (restic `copy` between repositories, or copying the repository directory; the runbook picks one and records why). Unpowered flash loses data over time, so the offline copy is powered, read and verified at least quarterly, and rotated to a place that is not the vault's site. The NVMe Nate already owns is the planned medium; its capacity and model are U until he reads the label, and if it is smaller than the repository the offline copy holds the irreplaceable set only.
- **3-2-1-1-0.** Three copies (live data, vault, offline copy); two media types (disk and flash); one off-site (Home, in Minneapolis); one offline or immutable (the offline copy, and the append-only repository); zero errors (the checks in A6 pass and are logged).

## A3. What lives where

| Source | Class | In the vault | Notes |
|---|---|---|---|
| Postgres dumps (events, notes, chores, profiles, findings) | irreplaceable | yes | dump files, not the live volume |
| BI telemetry archives (`/storage/archives/cabin_event/`) | irreplaceable | yes | the vault copy never justifies deleting the source (W-49) |
| Service configs under `/storage/services/` (Home Assistant, Zigbee2MQTT, Frigate config, Node-RED, mosquitto, uptime-kuma) | rebuildable only by hand | yes | exclude regenerable caches and large recorder databases by name; the runbook lists them (U) |
| Ansible Vault ciphertext and env templates | rebuildable only by hand | yes | ciphertext and templates only; never the password, never a live `.env` |
| Git bundle of `main` | regenerable from the org repo | yes | small; survives loss of the account or org |
| Home collector configs (Termux Zigbee2MQTT and agents) | rebuildable only by hand | later | only after they are pushed to the cabin |
| Private evidence folders (claim, grading) | irreplaceable | only if Nate rules them in scope | a separate repository encrypted with a key only Nate holds (K0 with K4 escrow); no agent ever reads it |
| Recorded video, nightly rsync snapshots, `poc1-work`, build caches, the GitHub runner | disposable or regenerable | no | `cabin-storage-tiers` |

## A4. Sizing method

Size the vault from the irreplaceable set, not from the cabin disk. As of the 2026-10-10 numbers in `docs/STORAGE_ARCHITECTURE.md`: telemetry grows about 2.5 GB per year, `/storage/archives` held under 1 GB, `/storage/services` under 1 GB, and the Postgres hot window about half a GB. The 640 GB of nightly backups on that disk was mostly copies of `/home` (about 577 GB, dominated by regenerable work trees) and is disposable. The irreplaceable set is therefore tens of gigabytes after years of growth, not terabytes.

```
usable capacity  >=  2 x (irreplaceable set today + 5 years of growth)
                    + the private evidence set, if Nate rules it in scope (measure it; little dedupes)
                    + 30 percent free space
```

Measure the set with `du` on the real directories, one level at a time (a full `du` of `/storage/backups` takes minutes; run it in the background), and measure restic's deduplication ratio after the first seed (U until then). Capacity beyond the formula is headroom Nate may choose to buy ("overkill is fine if it is sustainable and future proof"); say so in the cart report instead of implying the data needs it. Reliability matters more than volume: pick for failure tolerance, warranty and a firmware update path.

## A5. Hardware classes (no models here)

- **Vault node:** a small always-on Linux machine with two 3.5-inch bays (or two SATA plus M.2 slots), wired gigabit or 2.5 GbE, x86-64 or well-supported ARM64, a warranty of two years or more, and a normal Linux or container runtime so it can run rest-server and Tailscale. ECC memory is nice, not required: restic verifies content hashes end to end.
- **Storage:** two CMR drives (not SMR) rated for 24x7 NAS use, from two vendors or at least two production batches to reduce correlated failure, mirrored with a checksumming layer (ZFS, btrfs) or md RAID1 plus restic's own checks. A mirror is availability, not backup: the offline copy is the backup of the backup.
- **UPS:** a small line-interactive or standby unit with a data port so NUT can shut the node down cleanly. It buys minutes, not hours.
- **Offline medium:** the spare NVMe SSD in a USB enclosure whose bridge chip passes SMART through (check the chip, not the brand; smartmontools needs a recent release for NVMe over USB bridges). A short, properly rated USB cable.
- **Network:** a short Cat6 patch cable to a router LAN port. The vault is wired, never Wi-Fi.
- **Pi-class boards** remain viable for the rest-server role only if the data disks attach by SATA or PCIe, not USB. Decide at purchase.

## A6. Checks and schedule

| What | Where it runs | Cadence | Evidence | Failure shows as |
|---|---|---|---|---|
| Backup run | M920q systemd timer | nightly for the small set; weekly for large sets (set in the runbook) | run log | a Status Check in the app when a run is missed or fails (acceptance criterion of W-120) |
| restic `check` (structure) | M920q over the tailnet | weekly | log line with the result | Status Check |
| restic `check --read-data-subset` | a Home LAN machine, or the M920q with a small subset | monthly, rotating subsets so every pack is read within a year | log line | Status Check |
| One-file restore test | a machine that is not the M920q | weekly | hash of the restored file compared with the source, logged | Status Check |
| Full restore drill of the irreplaceable set to a scratch directory | Nate's Windows PC | quarterly | hashes compared, result recorded | a backlog row if it fails |
| SMART (short weekly, long monthly) and scrub or md check of the mirror (monthly) | vault node | as stated | `smartd` and scrub results | alert through the existing path (ntfy, Home Assistant push) |
| Append-only proof | M920q | once at install, again after any server change | a delete attempt from the cabin credential fails | blocks go-live |
| Offline copy refresh, power-on and verify | Home LAN machine | at least quarterly | verify result | Nate's calendar |

Where the repository password lives decides where a data-reading check can run. Never put the password on the vault node. Until W-51 sets the backend's ntfy topic, failures from the backend do not reach a phone, so a monthly by-hand look at the logs is part of the routine (U whether Home Assistant push covers the vault).

## A7. Network placement

Wired to a LAN port on the Home router (the Linksys network). Router settings (DHCP reservation, anything else) are Nate's; agents never touch the router, and router credentials never go in git. No port forward, no UPnP, no router USB share, no Wi-Fi. Tailscale on the node with a tag and a one-rule ACL. IP and MAC addresses stay out of git and out of comments. The Linksys model is U and needed only if Nate wants the router-USB variant, which A8 rejects.

## A8. Options considered and not chosen

| Option | Verdict | Reason |
|---|---|---|
| Galaxy Note 5 as the node | rejected | Android 7.0 is its ceiling, the Tailscale Android app needs Android 8 or newer, so no tailnet path; storage would be micro-USB 2.0 with no microSD slot. Fine as a spare phone, not as a server |
| Disk on the Linksys router's USB port | rejected | the router share supports NTFS, HFS+, FAT32 and Samba only: no restic rest-server, no Linux filesystem, no checksums, no append-only, and the data would sit behind consumer router firmware |
| A USB-attached primary disk on any host | avoid | USB bridges drop, power-manage and hide SMART. Acceptable only for the offline copy, after SMART passthrough is shown to work |
| Copying the whole cabin disk | rejected | the disk is mostly disposable (A4); it would pay to protect video and regenerable trees |

## A9. Boundaries and Nate's gates

- Agents: Code stages the timer, the backup script, the exclude list and the runbook in git and runs the checks; Cowork writes the decision record (a new D-number allocated by grepping `main` and open PRs at that time) and the runbook text; Codex reviews. The permission layer refuses remote host writes from automated sessions (2026-10-04): Nate runs the install.
- Deletion: pruning vault history, trimming old backups and any permanent delete are run by Nate from a dry-run-first script (`cabin-storage-tiers`). BI telemetry is never deleted; video and old backups are disposable.
- Nate decides: what to buy from the cart; where the node sits and the cabling; whether private evidence is in scope; the offline rotation cadence and place; where the K4 escrow lives.

# Part B. Security keys

## B1. Principles and lockout rules

1. **Two keys on every service before anything is turned off.** Register primary and secondary (and the backup where the service allows it) before enabling "require a security key" or removing SMS, TOTP or recovery options. A service that accepts only one key gets the primary and a recovery path in K4.
2. **Recovery codes at enrollment.** Download or print them the same hour, store them in K4 away from the key. GitHub issues 16.
3. **Test each key on each service right after registering it,** and again after a phone change. An untested key is not a backup.
4. **A lost key is revoked from every service the same day.** The service list in the section B4 matrix is the revocation checklist; K2 holds the per-key register (service names only).
5. **Set a FIDO2 PIN on every key.** PIN and retry limits differ per applet (FIDO2, PIV, OpenPGP, OATH) and this pass did not read the vendor page that states them, so the numbers are U: read the vendor's current PIN page before setting them and record only that they were set (the values go to K4). A FIDO2 reset erases that key's passkeys and PIN and leaves the other applets alone (R).
6. **TOTP seeds cannot be read back out of a key.** For services that only offer TOTP, scan the same QR code into primary, secondary and backup in one sitting (or keep the setup secret in K4). Otherwise the backup key cannot generate the code.
7. **Do the strict modes last.** Google Advanced Protection and Apple's two-key requirement come after every other service is stable and every integration is listed.
8. **No key stays inserted in a cabin host.** A key left plugged into a headless machine proves nothing about the person and invites theft with the machine.

## B2. Roles and where each lives

| Role | Class | Lives | Used for |
|---|---|---|---|
| Primary | multi-protocol, USB-C plus NFC | Nate's keychain, carried daily | phone by NFC, laptops and the Windows PC by USB-C, SSH sk keys, TOTP in the key's OATH slots |
| Secondary | same capability as the primary, registered on every service | home desk, plugged in only when needed | any day the primary is out of reach; the first fallback |
| Backup | same capability, registered on every service | sealed, away from both the keychain and the home desk, never at the cabin (the event that takes the cabin must not take the backup) | recovery only. Its place is Nate's choice (U) |
| USB-A variant (optional) | multi-protocol, USB-A plus NFC | with the secondary | hosts that have no USB-C port. Whether one is needed depends on the ports of the machines Nate uses (U for the Windows PC) |
| Second-vendor key (optional) | FIDO2 only is enough | with the backup | guards against a firmware flaw in one vendor's line; not required |

## B3. What the key must offer

- **Multi-protocol for the pair that matters.** FIDO2 and passkeys, PIV, OpenPGP, OATH (TOTP and HOTP) and OTP. The FIDO-only line covers passkeys and security-key logins only: no OATH, PIV or OpenPGP, so it cannot hold TOTP seeds for the services in B4 that offer nothing else. (R)
- **Firmware 5.8** is current on the YubiKey 5 series (announced July 2026: CTAP 2.3, larger enterprise-attestation list); the vendor store's product pages showed v5.8 on 2026-10-27. Firmware is fixed at manufacture and cannot be upgraded. FIPS variants run older firmware (5.4 and 5.7.4 lines) and are not needed for a household (U if Nate disagrees). (R)
- **Retail stock may be older.** The vendor's support says it cannot identify firmware before purchase at a reseller and recommends its own store for 5.8. Buy from the vendor store, or accept the uncertainty at a reseller. (R)
- **EUCLEAK (YSA-2024-03, CVE-2024-45678).** YubiKey 5 firmware below 5.7 uses the affected Infineon library; 5.7 and later use the vendor's own library and are not affected. Check the firmware on arrival with the vendor's manager app and record the version only; a unit below 5.7 goes back. The status of other vendors' older keys is U. (R)
- **Buy new, sealed, from the vendor or an authorized dealer.** No used units and no marketplace sellers: a key's value is its provenance. Confirm the return window at the time (the vendor store advertised 45 days on 2026-10-27).
- **Form factor.** USB-C plus NFC is the primary shape, so a phone taps it. The fingerprint (Bio) models have no NFC, so they would not tap a phone, and their multi-protocol edition is sold only through an enterprise service. (R)
- Capacities worth knowing: 100 passkeys, 64 OATH slots, 24 PIV certificates, two OTP slots, IP68, no battery. (R)

## B4. Service matrix

The Check column is `R` unless stated. "Plan" is what to do, in order; none of it has been done by an agent.

| Service | Hardware-key support and limits | Plan | Caveat or U |
|---|---|---|---|
| GitHub | passkeys and security keys; a key requires TOTP or SMS to be enabled first; a passkey satisfies password and second factor; sudo mode accepts a passkey or key (lasts 2 hours); 16 recovery codes; an organization can require secure 2FA | enable TOTP in the key's OATH slots, register primary and secondary as security keys, store the codes (K4). Each GitHub account Nate signs in with registers its own credential on the same keys | SSH commit signing needs Git 2.34 or newer; whether sk key types work for signing is U (not stated on the pages read). Agent tokens are K3 software and are unaffected |
| Google account (Gmail, Drive, Calendar, the Android phone, "Continue with Google" for Claude.ai) | passkeys and security keys. Advanced Protection requires a passkey or key to sign in, recommends the primary plus at least one backup, revokes app passwords, and blocks most third-party OAuth apps (Home Assistant's Gmail integration and many others fail with "policy_enforced" or "not approved by Advanced Protection"); unenrolling is the only fix; a locked-out recovery takes days | register primary, secondary and backup as passkeys or security keys now. Enrol in Advanced Protection only after listing every OAuth integration and getting Nate's explicit decision | whether the Claude connectors (Gmail, Calendar, Drive) stay allowed under Advanced Protection is U: check Google's allowed-app list before enrolling |
| Cloudflare | account 2FA by TOTP and U2F security keys; Access supports independent MFA with several security keys, AAGUID restriction (April 23, 2026) and `amr` matching | register primary and secondary on the account. If Access protects the public tunnel's admin paths, require a hardware key there only after both keys are tested | the account-level 2FA page was not fetchable in the research pass: U to re-check |
| Tailscale | passkey login in beta (YubiKey listed); tailnets created by passkey carry an `@passkey` domain; a dedicated passkey admin account is recommended | the keys protect the sign-in at the identity provider the tailnet uses (U which one) | ACLs and node ownership follow user identities, so test any identity change on a spare tailnet first (inference, U); beta |
| Vaultwarden or Bitwarden | Bitwarden: up to 5 FIDO2 keys for two-step login. Vaultwarden: WebAuthn needs HTTPS and the exact `DOMAIN` setting; passkey login and unlock (PR 7784) was not merged on 2026-10-27; PRF vault unlock is a Chromium web-vault beta; the CLI cannot do FIDO2 | register primary and secondary as two-step keys; scripts use the personal API key (`BW_CLIENTID`, `BW_CLIENTSECRET`) plus unlock | U whether Nate uses either, and which |
| Microsoft account (personal) | FIDO2 hardware keys as passkeys, up to 10; Edge or Chrome 109 and later, Safari 16 and later | register primary and secondary | Windows 11's passkey plugin model (November 2025 update) lets third-party managers hold passkeys; whether the hardware-key choice stays in that dialog is U, and the Windows sign-in guide for the key could not be read (U) |
| Apple Account | at least two keys required, up to six; iOS 16.3 or macOS 13.2 and later | register primary and secondary (the backup too); keep Apple's recovery option in K4 | do it after the other services |
| Amazon | SMS, call or authenticator-app TOTP; passkeys on a device lock screen; hardware-key support not stated | TOTP in the OATH slots on all keys; passkey on the phone | no hardware FIDO key (U) |
| Samsung account | SMS, TOTP and backup codes only; no security key | TOTP in the OATH slots; backup codes in K4 | |
| Starlink | passkeys launched October 2026, including hardware keys, up to 10 | register primary and secondary | new feature; test both keys |
| Home Assistant | built-in MFA is TOTP and notify only; the community integration `ha-webauthn-mfa` (HACS) adds WebAuthn and is reported unstable | TOTP in the OATH slots for each user; no custom auth integration on the head without a decision | Home Assistant is the safety path (leak, freeze, siren): do not risk a lockout of the head with an unstable plugin. It is reached over Tailscale |
| Uptime Kuma | TOTP only | TOTP in the OATH slots | |
| Claude.ai | sign-in by email link or code, or Google; no 2FA, passkey or security-key setting is documented | protect through the Google account (Continue with Google): the keys on Google protect it | if Nate signs in by emailed link, the mailbox is the factor: it must be the Google account with keys |
| Porkbun | TOTP and U2F | register two keys if Nate has an account there | U whether it is the registrar of record |
| Newegg | SMS, email and TOTP only | TOTP in the OATH slots | |
| Ring | SMS and TOTP only (a FIDO2 request is open) | TOTP in the OATH slots | |
| AWS | security keys (FIDO and U2F), TOTP apps and hardware TOTP tokens | root user and any administrator: two keys | U whether an account exists |
| Reolink | TOTP or SMS only (per the research summary) | TOTP in the OATH slots | U |

Not researched (U, look before relying): Blink, Linksys router admin, ntfy, the insurer's portal, Grafana, Node-RED. Their entries get a row when someone reads their current docs.

## B5. SSH, PAM and disk unlock

- **SSH sk keys.** OpenSSH 8.2 added FIDO/U2F key types; 8.4 added `verify-required` and the resident-key (credProtect) handling. The server must be 8.2 or newer: Ubuntu 24.04 ships 9.6p1, and a newer release ships newer. If `PubkeyAcceptedAlgorithms` is restricted, add `sk-ssh-ed25519@openssh.com`. Generate with `ssh-keygen -t ed25519-sk` (fallback `ecdsa-sk`); set the FIDO2 PIN first and consider `-O verify-required` so a stolen key cannot sign without the PIN. An sk private-key file is a handle, not a secret, but keep it with the key's record in K2. (R)
- **Windows.** The inbox OpenSSH (8.6p1 on 22H2 and 23H2; 9.5p1 is reported for 24H2 and is unverified) is not relied on for FIDO2. Use the Win32-OpenSSH preview at 8.9.1 or newer, installed from winget (`Microsoft.OpenSSH.Preview` or the Beta package), placed first in `PATH`, with ssh-agent set to Automatic; every Win32-OpenSSH release in the list read was a preview, none marked stable. One source says key generation needs an elevated shell: U, try without it first. Agent forwarding of a PIN-protected sk key needs `ssh-askpass`, and ssh-agent refuses keys whose application string does not begin with `ssh:`. (R)
- **Break-glass.** Keep one passphrase-protected software SSH key (K2 or K4) until the sk keys are proven on each host. Remove it only after both keys have logged in to that host.
- **pam-u2f** (`pamu2fcfg`, `auth sufficient pam_u2f.so authfile=... cue`; Ubuntu 24.04 packages 1.1.0). The key must be attached to the machine that runs PAM, so it suits a console where Nate sits, not a remote headless host. Keep a root shell open while testing, use `sufficient` (never `required`) at first, and keep the password fallback. `pam_rssh` (sudo over a forwarded agent) exists, but its default key file is user-writable, so any program the user runs could escalate: use a root-owned `auth_key_file` and a review before adopting (U). (R)
- **systemd-cryptenroll `--fido2-device`.** Works. **Never require a key at boot on a headless host:** the M920q must come back alone after a power loss. Keep a passphrase or recovery-key slot enrolled; enrolling any key requires unlocking with one of those first. With several enrolled tokens that need a PIN, each is tried in turn and prompts repeatedly. (R)
- **Unattended keys stay software.** CI, deploy and rotate-secrets, the restic timer, Home Assistant and agent tokens use scoped software credentials rotated under `cabin-credential-lifecycle`. A hardware key is for a person.

## B6. Questions for Nate (each is a `U` above)

1. Which password manager, if any (Vaultwarden, Bitwarden, other)?
2. Which ports do the machines you use have (USB-C, USB-A), and which Windows release is the PC on?
3. Where does the backup key live (not the cabin, not the keychain, not the desk)?
4. Do you want Google Advanced Protection, knowing it blocks most third-party OAuth apps? Say yes only after the integration list is reviewed.
5. Which GitHub accounts, and which identity provider does the tailnet use?
6. M920q OS release (24.04 in the repo, 26.04 in your notes): `lsb_release -a` settles it.

## Verified on and sources

Date: 2026-10-27. Method: research subagents in the Cowork session read the pages below and summarised them; this file's author did not re-fetch them. Some fetches were blocked (the Cloudflare 2FA page, the Bitwarden help pages, MDN, Yubico's SSH guide and the OpenSSH release notes returned a permission request that was not answered in time), so their facts come from search snippets and are marked `R` or `U`. Re-check a row before acting on it.

Primary pages and projects named: the vendor store's YubiKey 5 series pages (https://www.yubico.com/product/yubikey-5-series/yubikey-5c-nfc/), the vendor's YubiKey 5 series technical specification, the vendor security advisory YSA-2024-03 (https://yubico.com/support/security-advisories/ysa-2024-03), GitHub Docs on two-factor authentication, security keys and SSH, Google Account Help on Advanced Protection, Cloudflare Access documentation on independent MFA, Tailscale documentation on passkeys, Bitwarden and Vaultwarden documentation (including the open Vaultwarden pull request 7784), Microsoft and Apple support pages on security keys, the OpenSSH 8.2 and 8.4 release notes, Ubuntu package pages for openssh-server and libpam-u2f (https://ubuntuupdates.org/package/core/noble/main/base/openssh-server), the systemd-cryptenroll manual, `pam_rssh` (https://github.com/z4yx/pam_rssh), Win32-OpenSSH releases (https://github.com/PowerShell/Win32-OpenSSH/releases) and its inbox-version thread (https://github.com/PowerShell/Win32-OpenSSH/issues/2053), and the restic and rest-server documentation.
