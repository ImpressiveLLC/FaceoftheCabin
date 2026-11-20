# dev-sft-r1: scoring an unmerged adapter comparably with the baseline (design, 2026-11-20)

Status: **design only; NOT_RUN; authorizes nothing.** Assigned by Cowork on #244 (7035251285), for the plan draft's row "How an unmerged adapter is scored" ([dev-sft-r1 Phase 1 plan draft](dev-sft-r1-phase1-plan-draft.md), section 5). Read on main at `d5bd4b41e0f1a693afd4193ce77da441d136d188`. Hashes are content SHA-256 (`git show <commit>:<path> | sha256sum`). No code, model, host or private data is touched.

## 1. What the baseline actually measured

`evaluate.py` (`fa7111da...0846`) has two modes.

**`--model` mode** calls local Ollama (`127.0.0.1:11434/api/chat` only) once per gold row with:
- `format` = the intent-slot JSON schema, which is grammar-constrained decoding inside llama.cpp;
- `temperature` 0;
- `system.txt` as the system message;
- `request_content(text, card_target)` as the user message.

**`--predictions` mode** scores an offline JSONL of `{id, output}` with the same scorer. The scorer validates each output against the contract with the row's card, then computes intent accuracy, slot micro F1 and critical failures.

The baseline is r14 on `cabin-intent-slot-prompt-r1` (id `9d69b2ad6823`): valid-output 1.000, intent 0.837, slot micro F1 0.581.

Three things separate an unmerged HF adapter from that baseline:
1. **Weights.** `train_sft.py` trains on `unsloth/Llama-3.2-3B-Instruct` at `006f5dcd...` in fp32 on CPU (fp16 on GPU). Ollama serves a quantized GGUF build of llama3.2:3b. The trainer's own comment says the two are "NOT proven equal".
2. **Decoder.** Ollama constrains every token to the schema grammar. Plain HF `generate` does not.
3. **Prompt rendering.** Ollama renders with its served template. `sft_tokenize.py` renders from a pinned copy of it (`966de95c...6396`). Whether the served template still matches that copy is the open template-hash check.

## 2. Options

| Option | Same weights as baseline | Same decoder | Phase 1 rule it needs | New dependency | What it can claim |
|---|---|---|---|---|---|
| **A. Merge and a throwaway Ollama tag** (plan option a) | No: merged fp32 or its own quantization | Ollama `format`, at whatever Ollama version serves the tag | An exception to "no merge, no import", plus a host that can serve it | None; `evaluate.py --model` as is | Closest to the production path; still a different quantization from the baseline unless quantized the same way |
| **B. HF generation, constrained, paired** (plan option b, refined) | No, but the base is run in the same harness | Close: a JSON-schema constrained decoder in HF | None; no merge, export or import | One constrained-decoding library, pinned with hashes in the lock | **Adapter vs base in one harness** (the controlled comparison); r14 is shown descriptively, not as the comparison |
| **C. HF generation, unconstrained, paired** | As B | No | None | None | Paired intent and slot deltas; valid-output is not comparable with r14's 1.000 and is reported separately |
| **D. llama.cpp runtime LoRA over the served GGUF base** | The same base blob Ollama serves, if its provenance is pinned; the adapter was trained on the HF revision, so compatibility is a variable | Same engine family and schema; runtime, rendering and grammar versions must be pinned and are not measured equivalence | Converting the adapter to a GGUF LoRA, which is a GGUF export of the adapter (not a merge). Nate must say whether that counts as "no GGUF export" | llama.cpp (binary or Python binding) and its LoRA converter, pinned | Strongest comparability with r14. Applies an fp32-trained adapter to a quantized base, which is standard but is itself a variable |
| **E. Loss and mask statistics only** (plan option c) | n/a | n/a | None | None | No task metric in Phase 1; all scoring moves to Phase 2 |

## 3. Recommendation

**Use B for Phase 1, and keep A or D for a later phase.**

**Why B:** it needs no exception to Phase 1's rules. Its comparison is controlled: the same harness, prompt rendering, decoder, greedy settings and gold rows, with only the adapter differing. It reuses the existing scorer unchanged through `--predictions`.

**What B does not say:** that the adapter beats the served r14 model. A or D get closer to that claim, but neither proves parity automatically:
- A merges onto the HF base, and its quantization may differ from the served build.
- D needs compatible adapter and base provenance (the served GGUF blob and the HF revision the adapter was trained on), plus pinned runtime, rendering and grammar versions.

Using the same runtime family is not measured equivalence. Each would need its own comparability evidence.

**How B runs:**

1. **Generation step** (new, reviewed).
   - It loads the pinned base and renders each gold row with `sft_tokenize.py`'s pinned template from `system.txt` and `request_content(text, card_target)`, exactly as `query()` builds the messages.
   - It generates greedily (`do_sample=False`) under the schema constraint with a fixed `max_new_tokens`.
   - It writes `{id, output}` JSONL, plus a sidecar recording one `outcome` for every row: `ok`, `truncated` (hit `max_new_tokens`), `parse_error`, `decoder_error` or `transport_error`.
   - Every non-`ok` row is written with `output: null`, so the scorer counts it as a failure. There are no retries, and no row leaves the 49-row denominator.
2. **Two runs, identical except the adapter,** with the same outcome rules and the same `max_new_tokens` applied to both: `base` (no adapter) and `base+adapter`. Both are scored by `evaluate.py --predictions` and recorded with the run-capture bundle from the [run-capture design](dev-sft-r1-run-capture-design.md).
3. **Primary result:** the paired deltas (adapter minus base) in valid-output, intent accuracy, slot micro F1 and critical failures, plus the list of gold ids that flip in each direction and the outcome counts for each run.
4. **Calibration** is reported, not gated, unless Nate sets a threshold. It compares the `base` run with r14: same metrics and the same 8 failed fixtures or not. A large gap means the HF harness is not a good stand-in for the served model, and that's stated in the report.
5. **Reporting rules** (plan section 5): the dev set is contaminated by prompt use, so this is a development comparison only. It is never A1, release eligibility or an intent-SFT acceptance result. It carries the exact attribution string.

## 4. Comparability check for B, before any result is trusted

- **Rendering, local.** The HF-rendered token ids for every gold row equal those that `sft_tokenize.py`'s renderer produces from the pinned template copy (`966de95c...6396`). This is checked offline with the pinned tokenizer. It shows that the harness uses the pinned template. It does not show that it matches the served model.
- **Rendering, served: NOT_RUN.** Token parity with the served model would need the served prompt token ids. The pending template-hash yes covers only `ollama show --template` and its hash. It does not cover sending gold prompts to the server, instrumenting it or capturing token ids. That needs a named capture method and its own go. Until then, served parity is reported as NOT_RUN. If the template hash matches, the report says only that the served template equals the pinned copy.
- **Decoder conformance, separate from scoring.** A schema-conformance test runs before scoring, with a `max_new_tokens` large enough that truncation cannot occur on the fixtures. It uses synthetic inputs and every gold row, and checks that every completed (`ok`) generation is schema-valid. A completed generation that is not schema-valid means the constrained decoder is not doing what Ollama's `format` does. The harness is then judged unusable, the failed run is kept, and its scores are not interpreted.
- **Truncation and other failures in scoring.** In the scored runs, `truncated`, `parse_error` and the other non-`ok` outcomes are not decoder failures. They are scored failures with their reasons, kept in both paired runs, and reported by count. A run is never redone to remove them.
- **Determinism.** Two `base` runs give byte-identical predictions and outcome files.
- **Card handling.** Rows with `card_target` produce the same user content as `query()`. A synthetic test checks this without any model.

## 5. Decisions needed

**From Nate:**
1. **A, B, C, D or E** for Phase 1. Recommended: B.
2. **If B:** approve adding one constrained-decoding library to `requirements.lock`, pinned with hashes. The implementing PR names it and shows its schema coverage against `intent-slot.schema.json`. Unsupported schema features are a stop, not a silent relaxation.
3. **If D, now or later:** whether converting the adapter to a GGUF LoRA counts as the forbidden "GGUF export".
4. **Calibration:** whether a `base` versus r14 gap above some number blocks the report, or is only stated. Recommended: only stated in Phase 1.

**From Cowork:** confirm that B's paired delta, and not a comparison with r14, is the Phase 1 dev headline.

## 6. Work this would need, each its own reviewed PR, none authorizing a run

1. The generation script and synthetic tests, using a stub model: rendering parity with `query()`, card handling, every outcome kind written as a scored null with its reason, symmetric rules across the paired runs, and determinism.
2. The lock change for the decoder library, if B is chosen.
3. A plan-draft edit replacing the NEEDS-NATE row with the chosen option.

Running the generation itself needs the plan's other gates: approved training rows, S6, the runtime yes and the two keys.

## 7. Not claimed

- No generation has run.
- No library has been chosen or tested against the schema.
- The HF base has not been compared with the served model.
- No claim is made about which option scores better.
- Training, merge, export, import, routing and host work remain unauthorized.
