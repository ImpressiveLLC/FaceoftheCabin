# dev-sft-r1: run manifest, log capture and base-image pin (design, 2026-11-20)

Status: **design only; NOT_RUN; authorizes nothing.** Assigned by Cowork on #244 (7033531858) for the PENDING-EVIDENCE gap in the [dev-sft-r1 Phase 1 plan draft](dev-sft-r1-phase1-plan-draft.md), section 7 and the Container row. Read on main at `d5bd4b41e0f1a693afd4193ce77da441d136d188`. No code is changed, nothing is built or run, and no host, sealed file, private row or credential is touched.

## 1. What exists today, and the gap

These are Git-blob SHA-256 values at the commit above:

- `train_sft.py` (`42291422...898610`):
  - Trains with a `TrainingArguments` built inline. Its `output_dir` is the fixed path `data/adapter/`, and `save_strategy="no"`.
  - Saves the adapter and `tokenizer.json` to that same path, and prints progress.
  - Writes no manifest, no resolved-arguments dump, no host facts, no `pip freeze` and no log bundle.
  - A second run overwrites the first run's adapter.
- `Dockerfile` (`3393638e...737aad`):
  - Starts `FROM python:3.11-slim`, which is a tag, not a digest.
  - Installs `requirements.lock` (`d0b537ed...e5c957`, `torch==2.7.0+cpu`, `transformers==5.23.0`, `peft==0.21.3`) with `--require-hashes`.
  - Also runs an unpinned `apt-get install build-essential git`.
  - The image has never been built from this lock.
- `docker-compose.yml` mounts `./data` read-write at `/app/data`. Whatever is written under `data/` survives the container.

The plan's section 7 lists what a run manifest must hold. Nothing produces it yet.

## 2. Recommended shape: a small trainer change plus a wrapper

Two values exist only inside the training process: the resolved `TrainingArguments` (transformers fills in the defaults the plan's section 4 leaves unset, such as optimizer, scheduler, warmup and weight decay) and the trainer's own loss history. A pure outside wrapper cannot read either. So the design has two parts.

**A. Reviewed trainer change (`train_sft.py`), minimal:**

1. **`--run-dir PATH` (required for a real run).** The adapter and tokenizer go to `PATH/adapter/`, and `TrainingArguments.output_dir` is `PATH/trainer/`. Without `--run-dir` the script refuses a real run. `--check-only` stays as it is. The fixed `data/adapter/` path is no longer written by a real run.
2. **Before `trainer.train()`:** write `PATH/resolved-training-args.json` from `trainer.args.to_dict()`, plus the seed, the base model id and revision, the tokenizer SHA-256 it verified, the dataset path and its SHA-256, and the example, token and trainable-token counts it already computes.
3. **After `trainer.train()`:** write `PATH/loss.jsonl` from `trainer.state.log_history`, one record per logged step.
4. **Nothing else changes.** Hyperparameters, mask, template, seed handling and dtype choice stay as they are. Any change to those is a separate reviewed change.

**B. Wrapper (`run_capture.py`, new, in `docs/ai-assistant/training/`)**

The wrapper is the only supported way to start a real run. It runs inside the training container:

1. **Run id and directory.** The run id is UTC time, the short repository commit and 8 random hex characters, for example `20261120T0830Z-d5bd4b41-3fa9c2e1`. The directory is created with an exclusive `os.mkdir` under `data/runs/`. If it already exists, or `data/runs/` is a symlink, the wrapper refuses and writes nothing. Earlier run directories are never opened for writing.
2. **Pre-run capture,** written before training starts:
   - `host-facts.json`, automatic: Python version and platform; CPU model, core count and memory from `/proc/cpuinfo` and `/proc/meminfo`; the torch version; `torch.cuda.is_available()`; a dtype capability test (a two-by-two matmul in fp32, and in fp16/bf16 only where the trainer would use them), recording pass or fail and the exception text.
   - `pip-freeze.txt` from `python -m pip freeze --all`, with its SHA-256. The wrapper compares every `name==version` line with the pins in `requirements.lock`. Any difference refuses the run.
   - `dpkg.txt` from `dpkg-query -W`, because the apt layer is not pinned (section 4).
   - `repo.json`: the commit, `git status --porcelain` (a dirty tree refuses a real run), and the Git-blob SHA-256 of every PINNED file in the plan's section 2, read on the host.
   - **`image-facts.json`, supplied by the operator and not by the wrapper.** It holds the built image id and the `python` base digest. It comes from section 3's commands run on the docker host, because a container cannot see its own image id reliably. The wrapper refuses to start without it. It records the file as given and labels it operator-attested.
3. **Run.** The wrapper calls `train_sft.py --run-dir <dir> --seed <seed> --data <path>` as a subprocess. Standard output and standard error are copied unchanged to `train.log`, with a UTC timestamp per line. The exit code is recorded.
4. **Post-run, written once training has finished:**
   - `run-manifest.json` holds:
     - the plan id and the plan file's blob hash;
     - the repository commit;
     - every pre-run file's SHA-256;
     - the resolved-arguments file's SHA-256;
     - the seed actually used;
     - the data manifest id and hashes (placeholders refuse a real run until S7 exists);
     - the row, token and trainable-token counts;
     - `loss.jsonl`'s SHA-256;
     - the adapter files' SHA-256s;
     - the exit code;
     - start and end times;
     - the exact section 5 attribution string.
   - `bundle.sha256` lists the SHA-256 of every file in the run directory, sorted by path, `run-manifest.json` included. A non-zero exit still writes the manifest and the bundle, so failures are evidence too.
5. **Seal.** The wrapper makes every file and directory in the run read-only and prints the SHA-256 of `bundle.sha256`. Read-only bits stop accidents, not a determined edit. **The tamper evidence is that one hash, posted on #244 at the time of the run.** A later verifier recomputes everything from it.

**Verifier (`verify_run_bundle.py`, new).** Given a run directory and the posted bundle hash, it checks four things:
- the bundle file matches the posted hash;
- every listed file matches its line;
- no unlisted file exists;
- every manifest field the plan's section 7 requires is present and non-empty.

It also recomputes the pip-freeze-versus-lock comparison. It reads nothing outside the run directory and the repository.

## 3. Base-image digest pin

1. **Pin.** Change `FROM python:3.11-slim` to `FROM python:3.11-slim@sha256:<index digest>`. Pin the multi-arch index digest, not one platform's manifest, and record the `linux/amd64` manifest digest beside it in a comment.
2. **Getting the digest.** Use `docker buildx imagetools inspect python:3.11-slim`. That is a registry read only, with no build, pull into a running service, or host change, and it can run on ilikethelights. Code proposes the digest in the PR. Codex re-runs the same command independently and must get the same index digest. A different result means the tag moved between reads, and Code re-pins.
3. **Updates.** A digest change only ever happens through a reviewed PR that changes `FROM` and the comment together. There is no automatic update.
4. **The apt layer.** It stays unpinned in this design. The `dpkg.txt` capture records what was installed. Pinning it is a separate decision, either Debian snapshot pins or dropping `build-essential` if every locked package ships a wheel. I have not checked whether every locked package has a CPU wheel for Python 3.11, so I make no claim either way.
5. **Operator facts at build.** Run on the docker host after `docker compose build trainer`:
   - `docker image inspect --format '{{.Id}}'` for the built image;
   - `docker image inspect --format '{{index .RepoDigests 0}}' python:3.11-slim@sha256:<digest>`.

   Both go into `image-facts.json`. This is a read on the host and is only done after Nate's runtime yes for that host.

## 4. Dry-run verification (what makes the gap closable)

**Stage 1: no host and no training, in CI on synthetic data only.**
- Unit tests for the wrapper and the verifier:
  - an existing run directory is refused;
  - a missing `image-facts.json` is refused;
  - a dirty tree is refused for a real run;
  - a pip-freeze line differing from the lock is refused;
  - the bundle detects an edited file, an added file and a deleted file;
  - a second run gets a new directory and leaves the first byte-identical;
  - the manifest field list is complete.
- The trainer is replaced by a stub that writes a fake adapter and log history. No model is downloaded.

**Stage 2: on the chosen host, only after Nate's runtime yes and the plan's other gates.**
- One run on invented rows, the same kind `test_sft_tokenize.py` uses, with a step cap. Its bundle hash is posted on #244 and the verifier is run on it.
- This stage also supplies the plan's other host evidence: the dtype capability test, the measured wall time, and the real-tokenizer tests with `SFT_TOKENIZER_JSON` set.
- It is not training on project data, and it produces nothing that is served.

## 5. Changes this design would need, each its own reviewed PR

1. The `train_sft.py` change in section 2A, with tests.
2. `run_capture.py`, `verify_run_bundle.py` and their Stage 1 tests, wired into the existing `sft-tokenize-tests.yml` path filter or a sibling workflow.
3. The Dockerfile `FROM` digest pin.
4. A plan-draft edit that turns the section 7 status and the Container row from PENDING-EVIDENCE into a reference to the merged PRs. They become PINNED only after Stage 2.

Every one is reviewed by Codex on the exact head and by Cowork. None of them authorizes a run.

## 6. Not claimed

- No digest has been read and no image has been built.
- Nothing has run on any host, and no wall-time or dtype result exists.
- Read-only file modes are not a security boundary.
- The operator-supplied image facts are attested, not measured by the wrapper.
- The apt layer is not reproducible under this design.
- Training, export, quantization, routing and host configuration remain unauthorized.
