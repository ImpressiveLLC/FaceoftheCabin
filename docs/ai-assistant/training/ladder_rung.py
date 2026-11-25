#!/usr/bin/env python3
"""One rung of the synthetic CPU length ladder (child process of ladder.py). Invented rows only.

  python ladder_rung.py --rung 512 --out DIR --model pinned|synthetic --steps 3 --budget-bytes N

Trains `steps` optimizer steps at effective batch 4 (per-device 1, accumulation 4, the train_sft.py configuration) on
rows of exactly the requested token length, fp32 on CPU, then saves the adapter and reloads it into the same model under
another adapter name and checks every tensor is equal. Writes DIR/rung.json, even on failure, before it exits.

`pinned` loads the pinned 3B base model (BASE_MODEL at BASE_REVISION). `synthetic` uses a tiny random Llama-shaped model
and token ids; it proves the mechanics only and is labelled as such in the record.

A watcher thread samples memory twice a second. If the process goes above 90 percent of the effective budget (the lowest
of --budget-bytes, --effective-budget-bytes and this cgroup's memory.max) it writes a failure
record and exits at once; it does not wait to be killed by the kernel.
"""
import argparse
import json
import math
import os
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

CGROUP = Path("/sys/fs/cgroup")
SEED = 20261123
SYNTHETIC_VOCAB = 64
SYNTHETIC_PAD = 0


def _read_int(path):
    try:
        text = Path(path).read_text().strip()
        return int(text) if text and text != "max" else None
    except (OSError, ValueError):
        return None


def oom_delta(start, end):
    """New oom plus oom_kill events between two memory.events reads, or None when either read lacks a counter (a missing
    start value is not zero) or a counter went backwards."""
    keys = ("oom", "oom_kill")
    if not all(k in start and k in end for k in keys) or any(end[k] < start[k] for k in keys):
        return None
    return sum(end[k] - start[k] for k in keys)


def _read_events(path):
    out = {}
    try:
        for line in Path(path).read_text().splitlines():
            key, _, value = line.partition(" ")
            out[key] = int(value)
    except (OSError, ValueError):
        pass
    return out


PHASE = "load"   # load -> train -> save; read by the watcher, set by PhaseCallback
WATCHER = None   # the running Watcher, so a phase change takes one sample right at the boundary


STAT_KEYS = ("anon", "file", "file_mapped", "shmem", "kernel", "slab", "sock")


def _read_stat(path):
    """memory.stat as {key: int}; empty when the file is not readable (not Linux cgroup v2)."""
    out = {}
    try:
        lines = Path(path).read_text().splitlines()
    except OSError:
        return out
    for line in lines:
        key, _, value = line.partition(" ")
        try:
            out[key] = int(value)
        except ValueError:
            continue   # a malformed line must not hide the rest
    return out


def _rss_bytes():
    try:  # Linux
        pages = int(Path("/proc/self/statm").read_text().split()[1])
        return pages * os.sysconf("SC_PAGE_SIZE")
    except (OSError, ValueError, AttributeError):
        pass
    try:
        import psutil
        return psutil.Process().memory_info().rss
    except Exception:
        return None


def _major_faults():
    try:
        return int(Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()[9])
    except (OSError, ValueError, IndexError):
        return None


class Watcher(threading.Thread):
    """Tracks the memory high-water and stops the process above the fixed ceiling."""

    def __init__(self, budget_bytes, ceiling_fraction, on_breach, ceiling_metric="original", trace_path=None):
        super().__init__(daemon=True)
        self.trace = None
        if trace_path:
            Path(trace_path).parent.mkdir(parents=True, exist_ok=True)
            self.trace = open(trace_path, "w", encoding="utf-8", buffering=1)   # line-buffered: survives a SIGKILL
            self.trace.write("epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon\n")
        self.ceiling_metric = ceiling_metric
        # one sample at a time: the watcher thread and the phase boundaries both sample, and rows written out of order
        # would look like counters going backwards
        self.lock = threading.RLock()
        # trace times are the wall clock at start plus monotonic time since: the WSL wall clock steps (seen in run 4:
        # one sample stamped 61 s early in the middle of training), which would break every interval
        self.clock_start = (time.time(), time.monotonic())
        self.metric_peak = 0
        self.metric_fallback = False   # anon_shmem asked for but memory.stat unreadable: judged on the original, stricter figure
        self.ceiling = int(budget_bytes * ceiling_fraction) if budget_bytes else None   # budget_bytes = effective budget
        self.on_breach = on_breach
        self.peak = 0
        self.swap_peak = None          # None until memory.swap.current has been read: unread is not zero
        self.swap_missing = 0
        self.kernel_peak = None        # the kernel's memory.peak: catches excursions between samples (container-wide)
        self.kernel_peak_start = _read_int(CGROUP / "memory.peak")
        self.stop = threading.Event()
        self.faults_start = _major_faults()
        # Breakdown only. It never feeds the stop rule: the ceiling is still on `peak`, the larger of memory.current and RSS.
        self.cur_peak = None
        self.rss_peak = None
        self.anon_peak = None
        self.file_peak = None
        self.at_cur_peak = None

    def set_phase(self, phase):
        """Changes phase between a closing sample of the old one and an opening sample of the new one, with no other
        sample in between."""
        global PHASE
        with self.lock:
            self.sample()
            PHASE = phase
            self.sample()

    def sample(self):
        with self.lock:
            self._sample()

    def _sample(self):
        cur, rss = _read_int(CGROUP / "memory.current"), _rss_bytes()
        stat = _read_stat(CGROUP / "memory.stat")
        values = [v for v in (cur, rss) if v is not None]
        if values:
            self.peak = max(self.peak, max(values))
        if rss is not None and (self.rss_peak is None or rss > self.rss_peak):
            self.rss_peak = rss
        if cur is not None and (self.cur_peak is None or cur >= self.cur_peak):
            self.cur_peak = cur
            self.at_cur_peak = {k: stat.get(k) for k in STAT_KEYS} if stat else None
        for key, attr in (("anon", "anon_peak"), ("file", "file_peak")):
            if key in stat and (getattr(self, attr) is None or stat[key] > getattr(self, attr)):
                setattr(self, attr, stat[key])
        swap = _read_int(CGROUP / "memory.swap.current")
        if swap is None:
            self.swap_missing += 1
        for value in (swap, _read_int(CGROUP / "memory.swap.peak")):   # swap.peak is absent on older kernels
            if value is not None:
                self.swap_peak = max(self.swap_peak or 0, value)
        metric_value = self.peak_now(cur, rss, stat)
        kernel = _read_int(CGROUP / "memory.peak")
        if kernel is not None:
            self.kernel_peak = max(self.kernel_peak or 0, kernel)
        # judged = the sample, raised to the kernel high-water when the rung is judged on the original figure
        judged = metric_value
        if self.kernel_peak is not None and (self.ceiling_metric != "anon_shmem" or self.metric_fallback):
            judged = max(judged, self.kernel_peak)
        self.metric_peak = max(self.metric_peak, judged)
        if self.trace is not None:
            cells = ([f"{self.clock_start[0] + time.monotonic() - self.clock_start[1]:.3f}"] + ["" if v is None else str(v) for v in (cur, rss, stat.get("anon"), stat.get("file"), stat.get("shmem"))]
                     + [str(metric_value), PHASE]
                     + ["" if v is None else str(v) for v in (_major_faults(), stat.get("pgmajfault"), stat.get("workingset_refault_file"), stat.get("workingset_refault_anon"))])
            self.trace.write(",".join(cells) + "\n")
        if self.ceiling and judged > self.ceiling:
            self.on_breach(judged)

    def peak_now(self, cur, rss, stat):
        """The figure the ceiling is judged on, per --ceiling-metric. `original` is the larger of memory.current and RSS.
        `anon_shmem` is cgroup anon plus shmem; if memory.stat cannot be read it falls back to the original figure (the
        stricter one) and says so, never to a looser one."""
        original = max([v for v in (cur, rss) if v is not None] or [0])
        if self.ceiling_metric != "anon_shmem":
            return original
        if "anon" in stat:
            return stat["anon"] + stat.get("shmem", 0)
        self.metric_fallback = True
        return original

    def breakdown(self):
        """Where the memory figure came from, for the record. Evidence only: it decides nothing."""
        source = None
        if self.peak:
            source = "cgroup_memory_current" if self.cur_peak == self.peak else "process_rss" if self.rss_peak == self.peak else "cgroup_memory_peak_file"
        return {"ceiling_metric": self.ceiling_metric, "ceiling_metric_peak_bytes": self.metric_peak or None,
                "ceiling_metric_fallback_to_original": self.metric_fallback, "memory_breakdown": {
            "peak_source": source, "cgroup_current_peak_bytes": self.cur_peak, "rss_peak_bytes": self.rss_peak,
            "anon_peak_bytes": self.anon_peak, "file_cache_peak_bytes": self.file_peak,
            "memory_stat_at_current_peak": self.at_cur_peak,
            "note": "anon is non-reclaimable; file is page cache and reclaimable. Not used by any stop rule."}}

    def evidence(self):
        """Kernel and swap evidence, for both a finished rung and a ceiling breach."""
        return {"kernel_memory_peak_bytes": self.kernel_peak, "kernel_memory_peak_at_start_bytes": self.kernel_peak_start,
                "swap_peak_bytes": self.swap_peak, "swap_missing_samples": self.swap_missing}

    def run(self):
        while not self.stop.is_set():
            self.sample()
            self.stop.wait(0.5)

    def finish(self):
        self.stop.set()
        with self.lock:
            self.sample()
            if self.trace is not None:
                self.trace.close()
                self.trace = None
        if self.kernel_peak is not None:
            self.peak = max(self.peak, self.kernel_peak)
        faults = _major_faults()
        return {**self.breakdown(), **self.evidence(), "memory_peak_bytes": self.peak or None,
                "major_fault_delta": (faults - self.faults_start) if None not in (faults, self.faults_start) else None}


def write_record(out: Path, record: dict):
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / "rung.json.tmp"
    tmp.write_text(json.dumps(record, indent=2, sort_keys=True, default=str) + "\n", encoding="utf-8")
    os.replace(tmp, out / "rung.json")


def synthetic_dataset(rung, rows):
    import random
    rng = random.Random(SEED + rung)
    data = []
    for _ in range(rows):
        ids = [rng.randint(1, SYNTHETIC_VOCAB - 1) for _ in range(rung)]
        split = rung // 2
        data.append({"input_ids": ids, "attention_mask": [1] * rung, "labels": [-100] * split + ids[split:]})
    return data


def pinned_dataset(rung, rows):
    import train_sft
    import sft_tokenize as st
    tokenizer = train_sft.load_pinned_tokenizer()
    encode = lambda text: tokenizer.encode(text, add_special_tokens=False).ids  # noqa: E731
    bos_id = tokenizer.token_to_id(st.BOS)
    import ladder
    messages, lengths = [], []
    for i in range(rows):
        m, n = ladder.build_row(i, rung, encode, bos_id, st.build_example)
        messages.append({"id": f"ladder-{rung}-{i:02d}", "messages": m})
        lengths.append(n)
    tokenized = train_sft.tokenize_examples(messages, tokenizer)
    return tokenized, [len(t["input_ids"]) for t in tokenized], tokenizer.token_to_id("<|finetune_right_pad_id|>")


def build_model(kind, rung):
    import torch
    from peft import LoraConfig, get_peft_model
    targets = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]
    if kind == "synthetic":
        from transformers import LlamaConfig, LlamaForCausalLM
        torch.manual_seed(SEED)
        model = LlamaForCausalLM(LlamaConfig(vocab_size=SYNTHETIC_VOCAB, hidden_size=16, intermediate_size=32,
                                             num_hidden_layers=2, num_attention_heads=2, num_key_value_heads=2,
                                             max_position_embeddings=max(64, rung + 8)))
    else:
        import train_sft
        from transformers import AutoModelForCausalLM
        model = AutoModelForCausalLM.from_pretrained(train_sft.BASE_MODEL, revision=train_sft.BASE_REVISION,
                                                     torch_dtype=torch.float32)
    return get_peft_model(model, LoraConfig(r=16 if kind == "pinned" else 4, lora_alpha=16 if kind == "pinned" else 4,
                                            lora_dropout=0.0, target_modules=targets, task_type="CAUSAL_LM"))


def adapter_tensors_equal(model, saved_name, reloaded_name):
    import torch
    state = model.state_dict()
    originals = {k: v for k, v in state.items() if f"lora_" in k and f".{saved_name}." in k}
    if not originals:
        return False, 0
    for key, value in originals.items():
        other = state.get(key.replace(f".{saved_name}.", f".{reloaded_name}."))
        if other is None or not torch.equal(value, other):
            return False, len(originals)
    return True, len(originals)


def phase_callback():
    """Marks the load/train/save boundary in the trace so paging can be judged on the training window only."""
    from transformers import TrainerCallback

    def enter(phase):
        global PHASE
        if WATCHER is not None:
            WATCHER.set_phase(phase)
        else:
            PHASE = phase

    class PhaseCallback(TrainerCallback):
        def on_train_begin(self, args, state, control, **kwargs):
            enter("train")

        def on_train_end(self, args, state, control, **kwargs):
            enter("save")
    return PhaseCallback()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rung", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", choices=("pinned", "synthetic"), default="pinned")
    parser.add_argument("--steps", type=int, default=3)
    parser.add_argument("--budget-bytes", type=int, default=0)
    parser.add_argument("--effective-budget-bytes", type=int, default=0,
                        help="the parent's ceiling denominator (usable budget lowered to the container limit)")
    parser.add_argument("--ceiling-metric", choices=("original", "anon_shmem"), default="original")
    args = parser.parse_args(argv)

    import ladder
    record = {"rung": args.rung, "model": args.model, "steps": args.steps, "completed": False, "failure": None,
              "synthetic_only": True, "budget_bytes": args.budget_bytes, "ceiling_metric": args.ceiling_metric,
              "configuration_label": ladder.CEILING_METRICS[args.ceiling_metric],
              "cgroup_memory_max": _read_int(CGROUP / "memory.max"),
              "oom_events_start": _read_events(CGROUP / "memory.events")}
    effective = ladder.effective_budget(args.budget_bytes, args.effective_budget_bytes, record["cgroup_memory_max"])
    record["effective_budget_bytes"] = effective or None
    started = time.monotonic()

    def breach(peak):
        record.update(failure=f"memory {peak} ({args.ceiling_metric}) above {int(ladder.CEILING_FRACTION * 100)} percent of budget {effective} (effective)",
                      memory_peak_bytes=peak, wall_seconds=time.monotonic() - started,
                      oom_events=oom_delta(record["oom_events_start"], _read_events(CGROUP / "memory.events")),
                      **watcher.breakdown(), **watcher.evidence())
        write_record(args.out, record)
        os._exit(3)

    watcher = Watcher(effective, ladder.CEILING_FRACTION, breach, args.ceiling_metric, args.out / "memory-trace.csv")
    watcher.start()
    globals()["WATCHER"] = watcher
    try:
        import torch
        from transformers import Trainer, TrainingArguments, set_seed
        import sft_tokenize as st
        import train_sft

        set_seed(SEED)
        rows = args.steps * 4  # effective batch 4: per-device 1 x accumulation 4
        if args.model == "synthetic":
            data = synthetic_dataset(args.rung, rows)
            lengths, pad_id = [len(d["input_ids"]) for d in data], SYNTHETIC_PAD
        else:
            data, lengths, pad_id = pinned_dataset(args.rung, rows)
        record["actual_token_lengths"] = {"min": min(lengths), "max": max(lengths), "rows": len(lengths)}
        if max(lengths) > args.rung or args.rung - min(lengths) > ladder.LENGTH_TOLERANCE:
            raise RuntimeError(f"rows are not at the rung length: {min(lengths)}..{max(lengths)} for rung {args.rung}")

        model = build_model(args.model, args.rung)

        def collate(batch):
            padded = st.collate(batch, pad_id)
            return {k: torch.tensor(v) for k, v in padded.items()}

        kwargs = train_sft.training_args_kwargs(args.out / "trainer", SEED, False, args.steps)
        kwargs["num_train_epochs"] = 1
        trainer = Trainer(model=model, train_dataset=data, data_collator=collate, args=TrainingArguments(**kwargs),
                          callbacks=[phase_callback()])
        trainer.train()
        history = trainer.state.log_history
        record["losses"] = [e["loss"] for e in history if "loss" in e]
        record["grad_norms"] = [e["grad_norm"] for e in history if "grad_norm" in e]
        record["optimizer_steps"] = trainer.state.global_step
        if trainer.state.global_step != args.steps:
            raise RuntimeError(f"expected {args.steps} optimizer steps, saw {trainer.state.global_step}")

        adapter_dir = args.out / "adapter"
        model.save_pretrained(str(adapter_dir))
        record["adapter_saved"] = (adapter_dir / "adapter_model.safetensors").exists()
        model.load_adapter(str(adapter_dir), adapter_name="reloaded")
        equal, count = adapter_tensors_equal(model, "default", "reloaded")
        record["adapter_reload_equal"], record["adapter_tensors_compared"] = equal, count
        record["completed"] = True
    except BaseException as exc:  # a failure is evidence; keep it, then exit nonzero
        record["failure"] = f"{type(exc).__name__}: {exc}"
        if isinstance(exc, MemoryError):
            record["failure"] = "MemoryError (allocation failed)"
    finally:
        record.update(watcher.finish())
        record["paging"] = ladder.paging_from_trace(args.out / "memory-trace.csv")
        # None (not readable, or reset): judge_rung treats that as missing evidence
        record["oom_events"] = oom_delta(record["oom_events_start"], _read_events(CGROUP / "memory.events"))
        record["wall_seconds"] = time.monotonic() - started
        write_record(args.out, record)
    return 0 if record["completed"] else 2


if __name__ == "__main__":
    sys.exit(main())
