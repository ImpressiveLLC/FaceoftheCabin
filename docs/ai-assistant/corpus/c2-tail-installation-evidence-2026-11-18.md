# C2 tail installation phrasings, part 1 (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Code's tail group per Cowork on #244 (6981947683, 6983140307); the installation rows in [the C2 tail plan](../../governance/proposals/2026-11-18-c2-tail-plan.md).

## What changes and what does not

| Row | Question | Before (main d4296aec) | This change |
|---|---|---|---|
| install_gap_3 | How do I recreate the instance on a new machine? | no category, empty context | `recreation_instance_plan`: the installation-plan section is delivered whole (2393 chars) |
| install_gap_4 | What are the acceptance checks for recreating the instance? | `device_import_lifecycle` (device onboarding, 4367 chars) | `recreation_instance_acceptance`: the acceptance-record section is delivered whole (1486 chars) |
| install_gap_1 | What is the installation plan for a clean machine? | `installation` only | **unchanged, known gap: needs a budget ruling** |
| install_gap_2 | What goes in the installation acceptance record when I install on a blank machine? | `installation` only | **unchanged, known gap: needs a budget ruling** |

- **Two zero-boost categories**, each reusing an existing administrator section of `independent-installation.md`:
  - `recreation_instance_plan`, phrase "recreate the instance";
  - `recreation_instance_acceptance`, phrase "checks for recreating".
- No guide, boost, matcher or budget change.
- **Why three words each:** a three-word phrase beats the generic matches here ("acceptance" scores 1; the first phrasing matched nothing). It only ties credential password, token and secret wording (1 + boost 2), so `credential_redaction` stays first and delivers credentials-and-access whole. The first draft of the acceptance phrase, "acceptance checks for recreating" (four words), outscored credential wording and was dropped.

## Why install_gap_1 and install_gap_2 are left for a ruling

Both questions say "clean machine" or "blank machine" and "install", so the existing `installation` category scores 3. Its two sections are `minimum-inputs` (2130 chars) and `verified-clean-host-limitations` (2430), 4560 together, and they carry the setup limits the plan says must not be dropped.

- **Plan question:** the plan section (2393 chars) does not fit beside them: 6953 > 6000. If it tied, the matcher would skip it. If it won outright, the limits would be lost.
- **Acceptance question:** the acceptance record (1486 chars) misses by 46 characters: 6046 > 6000.

This is the same budget-and-tie question as M04. **Cowork ruled on #244 ([6984259185](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6984259185)):** both stay measured known gaps. There is no global budget raise, no dropping of the setup-limits sections and no score workaround. They are parked as C1 retrieval-design candidates (a reviewed split or condensation with preservation evidence), with the 46-character acceptance gap first. The ruling is recorded in the tail plan by #508.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main 608b1101 (after #500, #502 to #506) | This change, integrated |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 93 of 98 | **95 of 98** |
| household, delivered whole | 4 of 5 | 5 of 5 | 5 of 5 |

Measured on the integrated head: main 608b1101 merged into this branch, with main's fixture kept byte for byte and only these two categories and their note appended. The pre-integration measurement against d4296aec gave the same two-row delta (91 to 93). After this change, the only known gaps left in the development set are `cov_M04`, `install_gap_1` and `install_gap_2`, all budget-limited.

- **Rows changed against main 608b1101:** only install_gap_3 and install_gap_4. Their knownGap markers are cleared. install_gap_1 and 2 change only their marker text, which now records the budget reason.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 phrasings still have 16 with no category, with the same ids.
- **All roles:** in a throwaway probe I compared 253 questions under all six roles against `origin/main` 608b1101, on the integrated head. Only these two questions differ. Delivered context changes for the administrator only (2 pairs). For the other roles only the selected set differs, and their context stays empty.

## Tests

New `AskRecreateInstanceContextTest` (3 tests):
- Both questions deliver their section with exact text equality, within budget, to an administrator only.
- Ordinary device-import acceptance and clean-machine install questions keep their categories.
- Credential questions that use the same wording keep credentials-and-access whole.

All Ask test classes on the integrated head: 108 passed, 0 failed.
