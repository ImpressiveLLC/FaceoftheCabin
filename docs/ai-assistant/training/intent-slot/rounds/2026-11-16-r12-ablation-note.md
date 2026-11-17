# Round r12: leave-one-example-out ablation (2026-11-16)

Work order from #244 (Cowork, 2026-11-15), design in section 7a of [the prompt prior-art plan](../../../proposals/ask-intent-prompt-prior-art-and-improvement-plan.md) (matched control). Run at Nate's word in chat on 2026-11-16 (about 10:17 CST, "Approve both"); Tailscale SSH to the M920q approved by Nate at about 16:00 CST. Prompt-only, the existing evaluator (package files byte-identical to r11's), dev gold only, tag `cabin-intent-slot-poc2-r9-b` unchanged, M920q Ollama loopback, `system.txt` unchanged, recorded with `round_ledger.py`. Not training, not a deploy. A1 stays NOT_MEASURED, `release_eligible` stays false, overall offline acceptance gates were not passed.

## Design

One revised control prompt was fixed first and hashed. Every deletion arm is that control minus exactly one of the eight invented contrast examples; arms are compared only with the control, never with the unrevised baseline. The control differs from the current `system.txt` (sha256 `15a94e07...`) in two places only:

1. The quoted gold-23 line `"I am cold"` is replaced by the invented `"I am shivering"` (same concept, `cold`). It is applied once, in the control, so it is not a difference between arms.
2. The example delimiter is explicit: the header reads `Contrast examples (invented, for pattern only; each example is one line that starts with "- " and the list ends at the line "--- end of contrast examples ---"):` and that end line follows the last example.

Other gold texts still appear in the prompt (for example "it is too humid", "it feels muggy", "it is dark in here", "it needs to be brighter" in the need paragraph); only the gold-23 line was replaced, as ordered. The overlap is inherited from the baseline.

| Arm | Example removed | Prompt sha256 (first 16) |
|---|---|---|
| ctrl | none | `ade252bfb80dc070` |
| del1 | "Would you mind powering off the nursery lamp?" (polite on/off command) | `0c453b736c2916e2` |
| del2 | "Dim the porch lamp to 30 percent" (attribute with unit) | `28f9b360b261c16c` |
| del3 | "Raise it a bit" (pronoun clarify) | `b089a444177acd58` |
| del4 | "Set the bedroom thermostat to 21" (number without unit) | `b7bfbeaacf7ffc39` |
| del5 | "When the garage door opens, switch on the hallway light" (condition) | `b08b4b2e09dd3aea` |
| del6 | "Never switch off the freezer outlet" (negation) | `126a956cd715f0c8` |
| del7 | "Order me a pizza" (outside home control) | `d3f95b06b0a1645b` |
| del8 | "Is the pantry sensor battery low?" (state question) | `069a89bf195f92cb` |

Gold sha256 `1d33f622...` and schema sha256 `59f63b92...` are unchanged. Each arm ran three times, run 1 of every arm first, then run 2, then run 3, one after another (27 runs, 16:00 to 19:00 CST, nothing else running). Round files: `2026-11-16-r12-<arm>.json` (run 1) and `-rep2`, `-rep3`. Raw reports are on the M920q under `~/eval-results/intent-slot/2026-11-16-r12/` (not in git). Prompt files sit beside the round files.

## Result

Intent accuracy and critical failures were identical in all three runs of every arm, and so was the failed-fixture set. Slot F1 varied between runs only for del2 (0.5946, 0.6126, 0.6126) and del3 (0.5789, 0.5965, 0.5965).

| Arm | Intent | Slot micro F1 (run 1) | Critical | Failed | Against control: fixed | Against control: broke |
|---|---|---|---|---|---|---|
| ctrl | 0.857 | 0.615 | 12 | 7 | n/a | n/a |
| del1 | 0.837 | 0.564 | 13 | 8 | none | gold-08 |
| del2 | 0.857 | 0.595 | 12 | 7 | gold-14 | gold-48 |
| del3 | 0.837 | 0.579 | 12 | 8 | none | gold-48 |
| del4 | 0.816 | 0.576 | 13 | 9 | none | gold-08, gold-38 |
| del5 | 0.837 | 0.596 | 12 | 8 | none | gold-48 |
| del6 | 0.816 | 0.602 | 15 | 9 | none | gold-16, gold-38 |
| del7 | 0.857 | 0.632 | 13 | 7 | gold-07 | gold-16 |
| del8 | 0.837 | 0.615 | 13 | 8 | none | gold-16 |

For reference, the unrevised baseline in r10 scored intent 0.837, F1 0.569, critical 13, 8 failed (gold-07, 13, 14, 15, 37, 38, 40, 47). Against it the control fixes gold-38 and breaks nothing: the two edits alone (invented line, explicit delimiter) moved one fixture. That is a prompt-sensitivity observation, not an improvement claim, and it is not a candidate.

## Reading (scoped)

- No deletion arm beats the control on intent accuracy; two tie it (del2, del7) with a different failed set.
- Every deletion changes at least one fixture, so no example is demonstrably inert on this set. Fixtures that move with deletions: gold-16 (del6, del7, del8), gold-48 (del2, del3, del5), gold-08 (del1, del4) and gold-38 (del4, del6).
- Deleting an example never cleanly helps: del2 and del7 each fix one fixture and break another; the rest only break fixtures.
- These are associations on 49 rows with a 3 B model, one deletion at a time. They support the plan's lesson that examples act as binding precedent (the surface of the nearest example can carry over to a fixture), but they do not show why, and the near-miss pairs in the E3 set are the right test for it. Verbatim copying was not checked.
- Temperature 0 did not make the runs bit-identical: slot F1 differed between run 1 and runs 2 and 3 for del2 and del3 while their failed sets, intent and critical counts did not, so the process is not shown to be fully deterministic and no host-stability claim is made. The three runs are repeats of the same fixtures, not independent samples. They show that the intent and failed-fixture verdicts repeated here; they do not show that the effects would hold on other fixtures.

## Not claimed

No candidate is adopted, and nothing here is an acceptance of any prompt: these are offline development observations on the 49 dev rows only. Nothing here is a gate result, an A1 measurement or evidence about the served model beyond this tag and this set. No E4 or E5 result is implied.
