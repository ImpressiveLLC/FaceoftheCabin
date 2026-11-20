# dev-sft-r1: run manifest, log capture and base-image pin (design, 2026-11-20)

Status: **design only; NOT_RUN; authorizes nothing.** Assigned by Cowork on #244 (7033531858) for the PENDING-EVIDENCE gap in the [dev-sft-r1 Phase 1 plan draft](dev-sft-r1-phase1-plan-draft.md), section 7 and the Container row. Read on main at `d5bd4b41e0f1a693afd4193ce77da441d136d188`. Repaired for Cowork's review 7034339416 and Codex's review 7034406415. No code is changed, nothing is built or run, and no host, sealed file, private row or credential is touched.

**Hash label used throughout:** "content SHA-256" means the SHA-256 of a file's committed bytes, `git show <commit>:<path> | sha256sum`. It is not a Git object id and not a header-prefixed object digest. This is the same byte source as the plan draft's pins and #539.

## 1. What exists today, and the gap

These are content SHA-256 values at the commit above:

- `train_sft.py` (`42291422...898610`):
  - Trains with a `TrainingArguments` built inline. Its `output_dir` is the fixed path `data/adapter/`, and `save_strategy="no"`.
  - Has no step cap.
  - Saves the adapter and `tokenizer.json` to that same path, and prints progress.
  - Writes no manifest, no resolved-arguments dump, no host facts, no `pip freeze` and no log bundle.
  - A second run overwrites the first run's adapter.
- `Dockerfile` (`3393638e...737aad`):
  - Starts `FROM python:3.11-slim`, which is a tag, not a digest.
  - Installs `requirements.lock` (`d0b537ed...e5c957`, `torch==2.7.0+cpu`, `transformers==5.23.0`, `peft==0.21.3`) with `--require-hashes`.
  - Runs an unpinned `apt-get install build-essential git`.
  - COPYs only `export_grades_to_sft.py`, `train_sft.py`, `sft_tokenize.py` and `merge_and_convert.py` into `/app`. The image has no `.git` and has never been built from this lock.
- `docker-compose.yml` mounts `./data` read-write at `/app/data`. Whatever is written under `data/` survives the container.

The plan's section 7 lists what a run manifest must hold. Nothing produces it yet.

## 2. Shape: a small trainer change, a container capture step and a host step

**A. Reviewed trainer change (`train_sft.py`), minimal:**

1. **`--run-dir PATH` (required for a real run).** The adapter and tokenizer go to `PATH/adapter/`, and `TrainingArguments.output_dir` is `PATH/trainer/`. Without `--run-dir` the script refuses a real run. `--check-only` stays as it is. The fixed `data/adapter/` path is no longer written by a real run.
2. **`--max-steps N` (optional).** It is passed to `TrainingArguments.max_steps`. When absent, it is unset and the existing `num_train_epochs=3` applies. The value used is recorded with the resolved arguments. This is the step cap Stage 2 uses.
3. **Before `trainer.train()`:** write `PATH/resolved-training-args.json` from `trainer.args.to_dict()`, plus the seed, the base model id and revision, the tokenizer SHA-256 it verified, the dataset path and its content SHA-256, and the example, token and trainable-token counts it already computes.
4. **After `trainer.train()`:** write `PATH/loss.jsonl` from `trainer.state.log_history`.
5. **Nothing else changes.** Hyperparameters, mask, template, seed handling and dtype stay as they are.

The resolved arguments and the loss history exist only inside the training process, so no outside wrapper can capture them.

**B. Where each step runs**

The container has no repository and no view of its own image. Git facts and image facts therefore come from the host, and training facts come from the container.

| Step | Runs on | Writes |
|---|---|---|
| H1. Freshness and repo facts | Host, in the clean clone the image was built from | `repo.json`: commit, `git status --porcelain` (non-empty refuses a real run), content SHA-256 of every PINNED file in the plan's section 2 |
| H2. Image facts | Host, after `docker compose build trainer` | `image-facts.json`: built image id (`docker image inspect --format '{{.Id}}'`), base digest (section 3). Operator-attested |
| H3. Start | Host | `docker compose run trainer python run_capture.py --run-id <id> --host-facts-dir /app/data/runs-incoming/<id>/` |
| C1 to C5 | Container (`run_capture.py`) | Everything below |
| V. Verify | Host or any machine with the run directory | `verify_run_bundle.py` output, posted with the bundle hash |

H1 and H2 write into `data/runs-incoming/<id>/`. The container only reads that folder, then copies it into the sealed run folder (C1), so the files are hashed with everything else. The Dockerfile change in section 5 COPYs `run_capture.py` and `verify_run_bundle.py` into the image.

**C. Container capture (`run_capture.py`, new, in `docs/ai-assistant/training/`)**

- **C1. Run directory.** Create `data/runs/<run_id>/` with an exclusive `os.mkdir`. If it exists, or `data/runs/` is a symlink, refuse and write nothing. Copy `repo.json` and `image-facts.json` in from `runs-incoming`. If either is missing, the run aborts at preflight, but it still leaves evidence. The missing file is marked NOT_PRODUCED in the manifest with the reason, and C4 and C5 still write the manifest and the bundle. A `repo.json` showing a dirty tree aborts the same way, with the tree status kept as evidence. Only an existing directory or a symlinked `data/runs/` leaves nothing behind. Earlier run directories are never opened for writing.
- **C2. Pre-run facts:**
  - `host-facts.json`: Python version and platform; CPU model, core count and memory from `/proc/cpuinfo` and `/proc/meminfo`; the torch version; `torch.cuda.is_available()`; and a dtype capability test. The test is a two-by-two matmul in fp32, and in fp16/bf16 only where the trainer would use them, recording pass or fail and the exception text.
  - `pip-freeze.txt` from `python -m pip freeze --all`, with its content SHA-256.
  - **Lock comparison, lock to freeze only.** Every `name==version` pinned in `requirements.lock` must appear in the freeze with that exact version. Otherwise the run aborts at preflight, and the mismatch (package, locked version, found version) is written to `lock-check.json` as evidence. Extra freeze lines, such as `pip`, `setuptools` and `wheel`, are recorded and do not fail the run.
  - `dpkg.txt` from `dpkg-query -W`, because the apt layer is not pinned (section 3).
- **C3. Run.** Call `train_sft.py --run-dir <dir> --seed <seed> --data <path> [--max-steps N]` as a subprocess.
  - Its standard output and standard error go **byte for byte unchanged** to `train.stdout` and `train.stderr`.
  - A separate `events.jsonl` records the UTC time of process start, each chunk read (offset and length per stream), and exit.
  - Nothing is inserted into the raw streams.
- **C4. Manifest.** `run-manifest.json` always gets written, including after a failure. It holds:
  - `status`: `completed`, `failed` or `aborted_preflight`.
  - `stage`: the last stage reached, one of `preflight`, `trainer_started`, `trainer_exited` or `post_capture`.
  - The trainer exit code, or null if it never started.
  - Start and end times.
  - The plan id and the plan file's content SHA-256, and the commit from `repo.json`.
  - The seed actually used, and the max-steps value or null.
  - The data manifest id and hashes. Placeholders refuse a real run until S7 exists.
  - Counts.
  - The exact section 5 attribution string.
  - An `artifacts` map listing every expected artifact: `resolved-training-args.json`, `loss.jsonl`, the adapter files, `train.stdout`, `train.stderr`, `events.jsonl`, `host-facts.json`, `pip-freeze.txt`, `lock-check.json`, `dpkg.txt`, `repo.json` and `image-facts.json`. Each entry is either `{"sha256": ...}` or `{"status": "NOT_PRODUCED", "reason": ...}`. A preflight abort marks the trainer outputs NOT_PRODUCED with the refusal reason. A mid-training failure marks the adapter and possibly `loss.jsonl` NOT_PRODUCED.
- **C5. Bundle and seal.**
  - Write `bundle.sha256` listing the content SHA-256 of **every file in the run directory except `bundle.sha256` itself**, one `<sha256>  <relative path>` line per file, sorted by path, LF line endings. `run-manifest.json` is included.
  - Make every file and directory read-only, and print the content SHA-256 of `bundle.sha256`.
  - Read-only modes stop accidents, not a determined edit. **The tamper anchor is that one hash, posted on #244 at the time of the run.**

**D. Verifier (`verify_run_bundle.py`, new).** It takes a run directory and the posted bundle hash, and fails on the first mismatch, naming it. It checks:

1. The content SHA-256 of `bundle.sha256` equals the posted hash.
2. Every listed file exists and matches its line.
3. No file other than `bundle.sha256` exists unlisted.
4. `run-manifest.json` is listed, and every `artifacts` entry is either listed with a matching hash or marked NOT_PRODUCED with a non-empty reason.
5. **Fields every status needs:** status, stage, times and attribution are present. `repo.json`, `image-facts.json`, `pip-freeze.txt` and `lock-check.json` are each either produced or NOT_PRODUCED with a reason. A missing fact with a recorded reason is valid failure evidence, not corruption.
6. **Only for `status: completed`:** every artifact the plan's section 7 requires is produced, `repo.json` shows a clean tree, and `lock-check.json` records a pass.

It reports `INTEGRITY_OK` with the status separately. **A failed or aborted run can be `INTEGRITY_OK`, and that is never reported as successful training.**

The verifier recomputes the lock-to-freeze comparison from `pip-freeze.txt` whenever that file was produced, and checks that the result equals `lock-check.json`. It does this for every status. A recorded mismatch in an `aborted_preflight` run is consistent evidence. The same mismatch in a run marked `completed`, or a recomputed result that disagrees with `lock-check.json`, fails integrity. It reads nothing outside the run directory and the repository.

## 3. Base-image digest pin

1. **Pin.** Change `FROM python:3.11-slim` to `FROM python:3.11-slim@sha256:<index digest>`. Pin the multi-arch index digest, and record the `linux/amd64` manifest digest beside it in a comment.
2. **Getting the digest.** Use `docker buildx imagetools inspect python:3.11-slim`. That is a registry read only: no build, no pull into a running service, no host change, and it can run on ilikethelights. Code proposes the digest in the PR. Codex re-runs the command independently and must get the same index digest. A difference means the tag moved, and Code re-pins.
3. **Updates.** A digest only changes through a reviewed PR that changes `FROM` and the comment together.
4. **The apt layer.** It stays unpinned; `dpkg.txt` records what was installed. Pinning it, through Debian snapshot pins or by dropping `build-essential` if every locked package ships a wheel, is a separate decision. I have not checked wheel coverage.
5. **H2's base-digest read.** `docker image inspect --format '{{index .RepoDigests 0}}' python:3.11-slim@sha256:<digest>` on the docker host. This is only done after Nate's runtime yes for that host.

## 4. Dry-run verification

**Stage 1: no host and no training, in CI on synthetic data.** Unit tests for `run_capture.py` and `verify_run_bundle.py`, with the trainer replaced by a stub that writes a fake adapter and log history. No model is downloaded. The tests cover:

- **Clean bundles.** A completed run verifies `INTEGRITY_OK`/`completed`, including the case where `bundle.sha256` is excluded from its own list.
- **Tamper cases fail:** an edited listed file, an added unlisted file, a deleted listed file, an edited `bundle.sha256`, and a posted hash that does not match.
- **Failure bundles:**
  - A missing `image-facts.json` gives `INTEGRITY_OK`/`aborted_preflight`, with `image-facts.json` and the trainer outputs marked NOT_PRODUCED with reasons.
  - A lock-to-freeze mismatch gives `INTEGRITY_OK`/`aborted_preflight`, with `lock-check.json` recording the mismatch and the verifier's recomputation agreeing with it.
  - A dirty tree gives `INTEGRITY_OK`/`aborted_preflight`.
  - None of these is ever reported as training.
  - A stub that exits non-zero mid-training gives `INTEGRITY_OK`/`failed`, with the adapter marked NOT_PRODUCED.
  - An artifact marked NOT_PRODUCED without a reason fails.
  - A manifest that says `completed` while `image-facts.json` is NOT_PRODUCED, or while `lock-check.json` records a mismatch, fails integrity.
  - A `lock-check.json` that disagrees with the verifier's recomputation fails integrity.
- **Raw logs.** `train.stdout` equals the stub's bytes exactly, with timestamps only in `events.jsonl`.
- **Isolation.** An existing run directory is refused, and a second run leaves the first byte-identical.
- **`--max-steps`.** It is passed through and recorded.
- **Lock comparison.** Extra freeze lines such as `pip` and `setuptools` pass; a changed pinned version fails.

**Stage 2: on the chosen host, only after Nate's runtime yes and the plan's other gates.**
- One run on invented rows, the kind `test_sft_tokenize.py` uses, with `--max-steps` set to a small number. The run follows H1 to H3 and V, and its bundle hash is posted on #244.
- It also supplies the plan's other host evidence: the dtype capability test, the measured wall time, and the real-tokenizer tests with `SFT_TOKENIZER_JSON` set.
- It is not training on project data and serves nothing.

## 5. Changes this design would need, each its own reviewed PR

1. The `train_sft.py` change in section 2A (`--run-dir`, `--max-steps`, resolved-arguments and loss files), with tests.
2. `run_capture.py`, `verify_run_bundle.py` and their Stage 1 tests, wired into the existing `sft-tokenize-tests.yml` path filter or a sibling workflow.
3. **Dockerfile:**
   - the `FROM` digest pin;
   - a `COPY` of `run_capture.py` and `verify_run_bundle.py` into `/app`.

   They are one PR, because both change the image.
4. A plan-draft edit that turns the section 7 status and the Container row into a reference to the merged PRs. They become PINNED only after Stage 2.

Each one is reviewed by Codex on the exact head and by Cowork. None of them authorizes a run.

## 6. Not claimed

- No digest has been read and no image has been built.
- Nothing has run on any host, and no wall-time or dtype result exists.
- Read-only file modes are not a security boundary.
- Image facts are operator-attested, not measured by the container.
- The apt layer is not reproducible under this design.
- Training, export, quantization, routing and host configuration remain unauthorized.
