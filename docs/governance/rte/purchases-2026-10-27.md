# Purchase record 2026-10-27: Home Vault parts and security keys

Carts built by Cowork on 2026-10-27 at Nate's request. **Nothing was purchased; checkout is Nate's.** The Micro Center cart was built twice: in the built-in browser at 17:00 CDT, where all six lines and prices below were read, and again in Nate's own Chrome between 17:15 and 17:20 CDT, because the built-in browser cannot finish a checkout. A read-only look at that Chrome cart between 18:35 and 18:38 CDT found the cable present at quantity 2 (section 1). Nate built his own Yubico cart and plans to remove the Nano from it (section 2). Anything marked `U` was not read live for this record. A later session re-verifies prices (see `.claude/skills/wingman/references/carts-and-purchases.md`, section 3) before reusing any figure here.

No order numbers, addresses, account details, payment data or cart links appear in this file, and none were entered anywhere.

Serves [W-120](../backlog.md) (Home Vault, parts for the vault node, UPS and offline copy) and the security-key set described in `.claude/skills/wingman/references/vault-and-keys.md` Part B.

## 1. Home Vault parts: Micro Center, pickup (St. Louis Park, MN)

| Line | Qty | Unit | Line total | Stock at read time | Note |
|---|---:|---:|---:|---|---|
| NASync DXP2800 2-bay NAS, 2 M.2 slots, diskless (SKU 753525) | 1 | $369.99 | $369.99 | 4 in stock | The vault node (A5 class: two 3.5-inch bays, wired Ethernet). Remaining specifications `U`: read the vendor page, and confirm it can run rest-server and Tailscale (a standard Linux or container runtime) before install. Code verifies at install |
| WD Red Plus 8TB 5640 RPM SATA III CMR NAS drive (SKU 756486) | 1 | $374.99 | $374.99 | 10+ in stock (13 on the second build) | Mirror member one. CMR, NAS-rated |
| Seagate IronWolf 8TB 7200 RPM SATA III CMR NAS drive (SKU 066878) | 1 | $329.99 | $329.99 | 3 in stock | Mirror member two, a different vendor from the first (A5: two vendors or two batches). CMR, NAS-rated |
| UPS, 600 VA, 120 V, 7 outlets, model BE600M1 (SKU 375451) | 1 | $79.99 | $79.99 | 3 in stock | 3-year warranty on the listing and the manufacturer page. The Micro Center listing says 360 W; the manufacturer page and manual say 330 W, so plan on 330 W. The manual describes a USB data port for PowerChute in addition to the one USB charging port, so NUT has a port to read (A5). Whether the box holds a USB cable, and its type, is `U`: check at pickup. Listing runtime: 11.8 minutes at half load, 3.2 minutes at full load. Prove a working `upsc` reading at install. Replacement battery model APCRBC154 |
| PCIe M.2 NVMe SSD to USB-A and USB-C dual-connector enclosure (SKU 640441) | 1 | $29.99 | $29.99 | 10+ in stock | Offline copy medium for the NVMe Nate already owns. Bridge chip RTL9210B (read from the product page earlier on 2026-10-27, read time `U`): use `smartctl -d sntrealtek` and a recent smartmontools, and prove SMART passthrough before relying on it (A8) |
| 10 ft Cat 6 thin Ethernet cable (SKU 638866) | 1 (cart shows 2) | $9.99 | $9.99 | 8 in stock | Wired link from the node to a router LAN port (A7). Not in Nate's Chrome cart at 17:20 CDT: he interrupted at this line. Present at quantity 2 in the 18:35 to 18:38 CDT read; Cowork did not add it and who did is not known. One is enough for the node; any 10 ft Cat 6 cable works. Check the quantity at checkout |

Built-in browser cart total (six lines): **$1,194.94** before tax and fees; the six line totals sum to that figure.

Second build, Nate's own Chrome, store St. Louis Park: five lines (everything except the cable), every price matched the table. The five lines sum to $1,184.95, computed from the line prices; the page total was not read after the fifth line.

Later read of the same Chrome cart (page reloaded, quantities and plan choices read from the page, nothing clicked or changed, 18:35 to 18:38 CDT): six lines, every price unchanged; the five parts at quantity 1 with no protection plan and pickup selected; the cable at quantity 2. The page header reads 7 items, $1,204.93 before tax and fees, which is the five parts ($1,184.95) plus two cables ($19.98). With one cable the total is $1,194.94.

Pickup only: orders are held 3 days and payment is collected at pickup. Protection plans are declined on every line. The cart page also advertises a store credit-card discount; it was not applied and no card application was started.

Headroom, not need: the irreplaceable set is tens of gigabytes (A4), so a mirrored pair of 8 TB drives is headroom Nate chose to buy ("overkill is fine if it is sustainable and future proof"). The parts that carry real value are the mirror, the UPS signalling, the append-only repository and the offline copy.

### High and low checked

Checked on 2026-10-27 across Micro Center, Amazon, Newegg and eBay. The per-line table was not preserved in this record, so the seller type and read time of each point are `U`:

- Micro Center was equal to or cheaper than the other sources for the NAS, the UPS and the enclosure.
- WD Red Plus 8TB: $374.99 at Micro Center against $354.99 on Amazon, a $20 difference. Micro Center was kept for local pickup and cart-price confirmation; swap this one line if the $20 matters more.
- High end seen: an Amazon listing for a WD Red 8TB at $570, and Newegg marketplace listings for 8TB NAS drives between $617.99 and $648.40.
- Lowest eBay prices were for used or unwarranted drives. They carry warranty and wear risk and were not considered for a backup medium.

## 2. Security keys: Yubico direct

Nate built his own cart. As he sent it at 17:26 CDT:

| Line | Qty | Unit | Line total |
|---|---:|---:|---:|
| YubiKey 5C NFC | 2 | $58 | $116 |
| YubiKey 5 NFC (USB-A) | 1 | $58 | $58 |
| YubiKey 5C Nano | 1 | $68 | $68 |

That is $242 before import fees. **Nate's decision, 17:26 CDT: remove the Nano.** Three keys remain, $174 before import fees, and free shipping on US orders of $100 or more still applies. Cowork's own cart in the built-in browser (3x 5C NFC and 1x 5 NFC USB-A, $232) is superseded and stays in that browser only.

The cart pages state that Yubico does not collect tax and that estimated import fees appear only after a shipping address is entered, so the final amount can be higher; buying the same keys from a Yubico verified dealer in the US avoids import fees (`U`: dealer price not read for this record). The store advertises 45-day consumer returns.

Roles (Part B2): the two 5C NFC keys are primary (carried) and secondary (home desk); the USB-A 5 NFC is the backup, sealed offsite and never at the cabin. The NFC tap works on a phone with either connector. One check before paying: which ports `ilikethelights` has. If it has only USB-A ports, swap one 5C NFC for a second 5 NFC (same $58). Firmware 5.8 was shown on the vendor pages on 2026-10-27; keys below 5.7 are affected by the EUCLEAK advisory (YSA-2024-03). Read the firmware on arrival and record the version only.

Carrying (Nate asked at 17:31 CDT; no keyring): per Yubico's product page the 5C NFC is 18 x 45 x 3.7 mm and 4.1 g, rated IP68 (water and dust resistant) and crush resistant, so no case is needed. The $5 YubiStyle cover is a vinyl scuff sticker, not a case, and was not added. The lanyard ($3) and key organizer ($25) were not added either. Only the primary key travels. General NFC behavior, not verified on this key: an RFID-blocking wallet also blocks the NFC tap (USB still works), and a key next to a tap-to-pay card can make a tap fail, so it belongs in its own slot.

## 3. Caveats

- Yubico: import fees and any tax show only after the address step; compare with a US verified dealer before paying.
- Google Advanced Protection blocks most third-party OAuth apps. Decide after the integration list is reviewed (open question 4 in Part B6).
- Claude.ai has no security-key sign-in of its own; protect it through the Google account.
- Samsung, Ring, Uptime Kuma, Newegg and Reolink accept TOTP or SMS only, not a key; Home Assistant needs a custom integration for keys; Vaultwarden WebAuthn needs HTTPS (Part B4).
- Register primary and secondary (and the backup) on every service before turning anything off, keep recovery codes sealed, never require a key at boot on a headless host, and keep CI and deploy credentials as software keys (Part B1 and B5).
- Micro Center items are pickup only and held for 3 days; check each line and quantity at pickup, and look for a USB cable in the UPS box.

## 4. Alternatives not chosen

- A lean vault: two 4 TB drives mirrored instead of two 8 TB, about $320 cheaper (figure read earlier on 2026-10-27, `U` until re-read). Capacity is far above the data either way.
- A cheaper NAS enclosure or an ARM board: rejected for the node unless the data disks attach by SATA or PCIe rather than USB (A5). Specific cheaper models `U`.
- Fewer keys: two ($116, no offsite recovery key, not advised). Four, with the Nano as a parked key in a PC: not chosen (Nate, 17:26 CDT).
- The Galaxy Note 5 as the node and a disk on the router's USB port: rejected in A8.

## 5. What Nate does

1. Yubico: in your own cart remove the Nano, check which ports `ilikethelights` has, enter the shipping address, read the import-fee line, pay (or buy from a verified US dealer instead).
2. Micro Center: your Chrome cart holds the cable at quantity 2 (one is enough; remove one or both if you want none), then check out for pickup at St. Louis Park and pay at pickup within 3 days, declining protection plans. At pickup check each line and quantity.
3. On arrival: keys are registered following Part B; vault hardware goes to Nate's install with Code's staged files (Part A).
4. After checkout, add only the date to this record.

## 6. What agents did not do

No checkout, no sign-in, no account creation, no promo codes, no payment, address or account entry, and no protection plans. Cowork did not touch Nate's own Yubico cart, and stopped acting in Nate's Chrome when he interrupted at 17:20 CDT. The 18:35 to 18:38 CDT look reloaded the cart page and read it; nothing was clicked or changed.

## 7. Sources read on 2026-10-27

- Yubico: [YubiKey 5C NFC](https://www.yubico.com/in/product/yubikey-5c-nfc/) and [YubiStyle cover](https://www.yubico.com/product/yubistyle-cover-green-a/) product pages. The 5C NFC page read was the store's `/in/` locale path; the size and rating figures in section 2 come from it, and the US store page was not read (`U`).
- APC: a copy of the BE600M1 user manual at manuals.plus, and the manufacturer product page on the Schneider Electric marketplace (330 W, 3-year warranty, one USB charging port).
- Micro Center listings, read live in both cart builds.
