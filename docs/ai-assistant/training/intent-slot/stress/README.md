# Stress sets (not gold)

`clinc150-oos-stress.jsonl` holds 100 out-of-scope utterances taken from the public CLINC150 dataset, in the same row shape as `gold.jsonl` so `evaluate.py --gold stress/clinc150-oos-stress.jsonl` can score it. It belongs to work order P-4 (E5) of [the prompt prior-art plan](../../../proposals/ask-intent-prompt-prior-art-and-improvement-plan.md). These rows are **not gold**, are never admitted to gold, and were not reviewed by a person; they exist to see what the intent model does with text that has nothing to do with the cabin.

## Source and attribution (CC BY 3.0)

The utterances come from the `oos_test` split of CLINC150 (`data/data_full.json` in the GitHub repository `clinc/oos-eval`), released under the Creative Commons Attribution 3.0 Unported licence (the repository's `LICENSE` file; a copy is `CLINC150-LICENSE.txt` here). Credit: Stefan Larson, Anish Mahendran, Joseph J. Peper, Christopher Clarke, Andrew Lee, Parker Hill, Jonathan K. Kummerfeld, Kevin Leach, Michael A. Laurenzano, Lingjia Tang and Jason Mars, "An Evaluation Dataset for Intent Classification and Out-of-Scope Prediction", EMNLP-IJCNLP 2019. The rows are unmodified apart from being placed in the gold row format; the model-facing text is the original utterance. SHA-256 of the source file: `36923c3705a59e08...` (full value in each row's provenance).

## How the 100 rows were chosen

Every tenth row of `oos_test` in file order (indices 0, 10, 20 to 990), so the choice involves no judgement and can be reproduced from the source file.

## What the target means, and what is not claimed

The target for every row is `answer_question`, because the current system prompt says that something outside home control (travel, shopping, general chat) is `answer_question` with a short answer that the request is not something this system does. That is a stated expectation, not a reviewed label: a few utterances might reasonably get `unsupported` or `clarify`. Reports on this set therefore show the whole intent distribution and the share of outputs that propose any action (`set_device_state`, `set_device_attribute`, `propose_automation`, `express_need`), and treat intent accuracy as secondary.

No score on this set is a gate result, an A1 measurement or evidence about the real household's requests. The utterances are English text written by crowd workers for other domains.
