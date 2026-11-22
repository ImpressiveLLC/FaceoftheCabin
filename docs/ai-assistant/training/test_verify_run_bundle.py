"""Stage 1 tests for verify_run_bundle.py (#543 section 4): bundles made by run_capture.py with a stub trainer."""
import hashlib
import json
import stat
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import verify_run_bundle as vb  # noqa: E402
from test_run_capture import FREEZE_OK, _unseal, env, incoming, run  # noqa: E402,F401  (env is a fixture)


def make(env, run_id="run-a", **kw):
    incoming(env, run_id, **{k: kw.pop(k) for k in ("repo", "image") if k in kw})
    run_dir, bundle, manifest = run(env, run_id, **kw)
    return run_dir, bundle, manifest


def writable(path):
    for p in [path, *path.rglob("*")]:
        p.chmod(p.stat().st_mode | stat.S_IWUSR)


def reseal(run_dir):
    """Rewrites bundle.sha256 for the current files, the way a careful forger would; returns the new hash."""
    lines = []
    for p in sorted(p for p in run_dir.rglob("*") if p.is_file()):
        rel = p.relative_to(run_dir).as_posix()
        if rel != vb.BUNDLE:
            lines.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {rel}\n")
    (run_dir / vb.BUNDLE).write_text("".join(lines), encoding="utf-8", newline="\n")
    return hashlib.sha256((run_dir / vb.BUNDLE).read_bytes()).hexdigest()


def edit_manifest(run_dir, change):
    path = run_dir / vb.MANIFEST
    manifest = json.loads(path.read_text(encoding="utf-8"))
    change(manifest)
    path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")


def test_clean_completed_bundle_verifies(env):
    run_dir, bundle, _ = make(env)
    assert vb.verify(run_dir, bundle, env["lock"]) == "completed"


def test_cli_reports_ok_and_failure(env, capsys):
    run_dir, bundle, _ = make(env)
    assert vb.main([str(run_dir), bundle, "--lock", str(env["lock"])]) == 0
    assert capsys.readouterr().out.startswith("INTEGRITY_OK status=completed")
    assert vb.main([str(run_dir), "0" * 64, "--lock", str(env["lock"])]) == 1
    assert "INTEGRITY_FAIL" in capsys.readouterr().out


@pytest.mark.parametrize("tamper", ["edit", "add", "delete", "edit_bundle"])
def test_tampering_is_detected(env, tamper):
    run_dir, bundle, _ = make(env)
    writable(run_dir)
    if tamper == "edit":
        (run_dir / "train.stdout").write_bytes(b"edited")
    elif tamper == "add":
        (run_dir / "extra.txt").write_text("x", encoding="utf-8")
    elif tamper == "delete":
        (run_dir / "loss.jsonl").unlink()
    else:
        with open(run_dir / vb.BUNDLE, "a", encoding="utf-8", newline="\n") as f:
            f.write("0" * 64 + "  ghost.txt\n")
    with pytest.raises(vb.IntegrityError):
        vb.verify(run_dir, bundle, env["lock"])


def test_a_wrong_posted_hash_fails_even_when_files_are_intact(env):
    run_dir, _, _ = make(env)
    with pytest.raises(vb.IntegrityError, match="posted hash"):
        vb.verify(run_dir, "f" * 64, env["lock"])


def test_resealed_forgery_still_fails_against_the_original_posted_hash(env):
    run_dir, bundle, _ = make(env)
    writable(run_dir)
    (run_dir / "train.stdout").write_bytes(b"forged")
    with pytest.raises(vb.IntegrityError, match="train.stdout hash does not match"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])  # a half-done forgery trips the manifest cross-check
    forged = hashlib.sha256(b"forged").hexdigest()
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"train.stdout": {"sha256": forged}}))
    new = reseal(run_dir)
    with pytest.raises(vb.IntegrityError, match="posted hash"):
        vb.verify(run_dir, bundle, env["lock"])
    assert vb.verify(run_dir, new, env["lock"]) == "completed"  # only the posted anchor tells them apart


def test_bundle_does_not_list_itself_and_malformed_lines_fail(env):
    run_dir, bundle, _ = make(env)
    assert vb.BUNDLE not in (run_dir / vb.BUNDLE).read_text(encoding="utf-8")
    writable(run_dir)
    raw = (run_dir / vb.BUNDLE).read_text(encoding="utf-8")
    (run_dir / vb.BUNDLE).write_text(raw.replace("  ", " ", 1), encoding="utf-8", newline="\n")
    with pytest.raises(vb.IntegrityError, match="malformed"):
        vb.verify(run_dir, hashlib.sha256((run_dir / vb.BUNDLE).read_bytes()).hexdigest(), env["lock"])


def test_failure_bundles_verify_as_integrity_ok_but_never_completed(env):
    for run_id, kw in (("run-img", {"image": False}), ("run-dirty", {"repo": {"commit": "c", "status_porcelain": " M x"}}),
                       ("run-lock", {"freeze": FREEZE_OK.replace("torch==2.7.0+cpu", "torch==2.8.0")}),
                       ("run-FAIL", {})):
        run_dir, bundle, manifest = make(env, run_id, **kw)
        status = vb.verify(run_dir, bundle, env["lock"])
        assert status == manifest["status"] and status != "completed", run_id


def test_completed_with_missing_image_facts_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    (run_dir / "image-facts.json").unlink()
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"image-facts.json": {"status": "NOT_PRODUCED", "reason": "x"}}))
    with pytest.raises(vb.IntegrityError, match="completed run lacks image-facts.json"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_completed_with_a_recorded_lock_mismatch_fails(env):
    run_dir, _, _ = make(env, freeze=FREEZE_OK.replace("torch==2.7.0+cpu", "torch==2.8.0"))
    writable(run_dir)
    edit_manifest(run_dir, lambda m: m.update(status="completed", stage="post_capture", exit_code=0))
    with pytest.raises(vb.IntegrityError, match="completed run lacks|lock check is not a pass"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_completed_run_with_every_artifact_but_a_consistent_lock_mismatch_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    freeze = FREEZE_OK.replace("torch==2.7.0+cpu", "torch==2.8.0")
    (run_dir / "pip-freeze.txt").write_text(freeze, encoding="utf-8", newline="\n")
    check = vb.lock_check(env["lock"].read_text(encoding="utf-8"), freeze)
    (run_dir / "lock-check.json").write_text(json.dumps(check, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")
    digests = {n: hashlib.sha256((run_dir / n).read_bytes()).hexdigest() for n in ("pip-freeze.txt", "lock-check.json")}
    edit_manifest(run_dir, lambda m: m["artifacts"].update({n: {"sha256": d} for n, d in digests.items()}))
    with pytest.raises(vb.IntegrityError, match="lock check is not a pass"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_lock_check_that_disagrees_with_the_recompute_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    path = run_dir / "lock-check.json"
    check = json.loads(path.read_text(encoding="utf-8"))
    check["extra_installed"] = []
    path.write_text(json.dumps(check), encoding="utf-8", newline="\n")
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"lock-check.json": {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}}))
    with pytest.raises(vb.IntegrityError, match="disagrees with the recomputed"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_not_produced_without_a_reason_or_while_present_fails(env):
    run_dir, _, _ = make(env, "run-FAIL")
    writable(run_dir)
    edit_manifest(run_dir, lambda m: m["artifacts"]["adapter"].update(reason=" "))
    with pytest.raises(vb.IntegrityError, match="without a reason"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"loss.jsonl": {"status": "NOT_PRODUCED", "reason": "x"}}))
    edit_manifest(run_dir, lambda m: m["artifacts"]["adapter"].update(reason="crashed"))
    with pytest.raises(vb.IntegrityError, match="NOT_PRODUCED but is present"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_wrong_attribution_or_unknown_status_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    edit_manifest(run_dir, lambda m: m.update(attribution="human audit"))
    with pytest.raises(vb.IntegrityError, match="attribution"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])
    edit_manifest(run_dir, lambda m: m.update(attribution=vb.ATTRIBUTION, status="success"))
    with pytest.raises(vb.IntegrityError, match="not a known value"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


# --- Codex HOLD 7060663663 probes: incomplete evidence must never verify as completed. ---

def test_null_or_malformed_artifact_hash_never_counts_as_produced(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    (run_dir / "loss.jsonl").unlink()
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"loss.jsonl": {"sha256": None}}))
    with pytest.raises(vb.IntegrityError, match="malformed sha256"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"loss.jsonl": {"sha256": "0" * 64}}))
    with pytest.raises(vb.IntegrityError, match="loss.jsonl hash does not match"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


@pytest.mark.parametrize("repo", [{}, [], {"commit": "c", "plan_id": "p", "plan_sha256": "ab" * 32},
                                  {"commit": "c", "status_porcelain": 0, "plan_id": "p", "plan_sha256": "ab" * 32}])
def test_completed_run_with_malformed_repo_facts_fails_cleanly(env, repo):
    run_dir, _, _ = make(env)
    writable(run_dir)
    path = run_dir / "repo.json"
    path.write_text(json.dumps(repo), encoding="utf-8", newline="\n")
    edit_manifest(run_dir, lambda m: m["artifacts"].update(
        {"repo.json": {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}}))
    with pytest.raises(vb.IntegrityError, match="break the capture contract"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_completed_run_needs_the_apt_inventory(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    (run_dir / "dpkg.txt").unlink()
    edit_manifest(run_dir, lambda m: m["artifacts"].pop("dpkg.txt"))
    with pytest.raises(vb.IntegrityError, match="completed run lacks dpkg.txt"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])
    edit_manifest(run_dir, lambda m: m["artifacts"].update({"dpkg.txt": {"status": "NOT_PRODUCED", "reason": "no dpkg"}}))
    with pytest.raises(vb.IntegrityError, match="completed run lacks dpkg.txt"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_required_artifacts_are_the_capture_list():
    assert set(vb.REQUIRED_FOR_COMPLETED) == set(vb.TRAINER_ARTIFACTS + vb.CAPTURE_ARTIFACTS)
    assert "dpkg.txt" in vb.REQUIRED_FOR_COMPLETED


def test_status_and_stage_must_agree_and_timestamps_must_be_strings(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    edit_manifest(run_dir, lambda m: m.update(stage="preflight"))
    with pytest.raises(vb.IntegrityError, match="inconsistent with status"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])
    edit_manifest(run_dir, lambda m: m.update(stage="post_capture", started_at=["2026"]))
    with pytest.raises(vb.IntegrityError, match="started_at is empty or not a string"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_a_non_object_manifest_fails_cleanly(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    (run_dir / vb.MANIFEST).write_text("[]\n", encoding="utf-8", newline="\n")
    with pytest.raises(vb.IntegrityError, match="not a JSON object"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_completed_run_with_a_dirty_tree_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    path = run_dir / "repo.json"
    repo = json.loads(path.read_text(encoding="utf-8"))
    repo["status_porcelain"] = " M train_sft.py"
    path.write_text(json.dumps(repo), encoding="utf-8", newline="\n")
    edit_manifest(run_dir, lambda m: m["artifacts"].update(
        {"repo.json": {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}}))
    with pytest.raises(vb.IntegrityError, match="dirty tree"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])



# --- Codex HOLD on 8336d336: real capture failure bundles with no lock check must verify as failure evidence. ---

@pytest.mark.parametrize("lock_text", [None, "", "# no pins\n", "untracked-package>=99\n"])
def test_capture_bundles_with_an_unusable_lock_verify_as_aborted_evidence(env, lock_text):
    incoming(env, "run-a")
    if lock_text is None:
        env["lock"].unlink()
    else:
        env["lock"].write_text(lock_text, encoding="utf-8")
    run_dir, bundle, manifest = run(env, "run-a")
    assert manifest["status"] == "aborted_preflight"
    assert manifest["artifacts"]["lock-check.json"]["status"] == "NOT_PRODUCED"
    assert "sha256" in manifest["artifacts"]["pip-freeze.txt"]
    assert vb.verify(run_dir, bundle, env["lock"]) == "aborted_preflight"


def test_a_partly_pinned_lock_bundle_verifies_as_aborted(env):
    from test_run_capture import LOCK
    incoming(env, "run-a")
    env["lock"].write_text(LOCK + "untracked-package>=99\n", encoding="utf-8")
    run_dir, bundle, manifest = run(env, "run-a")
    assert manifest["status"] == "aborted_preflight"
    assert vb.verify(run_dir, bundle, env["lock"]) == "aborted_preflight"


def test_a_stream_failure_bundle_verifies_as_failed_never_completed(env, monkeypatch):
    import run_capture as rc
    incoming(env, "run-a")

    def failing(sink, chunk):
        raise OSError("synthetic write failure")

    monkeypatch.setattr(rc, "_write_chunk", failing)
    run_dir, bundle, manifest = run(env, "run-a")
    assert manifest["status"] == "failed"
    assert vb.verify(run_dir, bundle, env["lock"]) == "failed"


def test_a_completed_claim_without_a_lock_check_fails(env):
    incoming(env, "run-a")
    env["lock"].write_text("", encoding="utf-8")
    run_dir, _, _ = run(env, "run-a")
    writable(run_dir)
    edit_manifest(run_dir, lambda m: m.update(status="completed", stage="post_capture", exit_code=0))
    with pytest.raises(vb.IntegrityError, match="completed run"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_a_produced_lock_check_still_needs_a_usable_lock_to_recompute(env):
    run_dir, bundle, _ = make(env)
    missing = env["tmp"] / "absent.lock"
    with pytest.raises(vb.IntegrityError, match="is missing"):
        vb.verify(run_dir, bundle, missing)
    broken = env["tmp"] / "broken.lock"
    broken.write_text("untracked-package>=99\n", encoding="utf-8")
    with pytest.raises(vb.IntegrityError, match="not a usable pinned lock"):
        vb.verify(run_dir, bundle, broken)


def test_a_lock_check_without_its_freeze_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    (run_dir / "pip-freeze.txt").unlink()
    edit_manifest(run_dir, lambda m: m["artifacts"].update(
        {"pip-freeze.txt": {"status": "NOT_PRODUCED", "reason": "x"}}))
    with pytest.raises(vb.IntegrityError, match="pip-freeze.txt is not"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])



# --- Cowork 7063667626 (non-blocking): the bundle-line checks each need a test that kills them. ---

def _rewrite_bundle(run_dir, lines):
    (run_dir / vb.BUNDLE).write_text("".join(line + "\n" for line in lines), encoding="utf-8", newline="\n")
    return hashlib.sha256((run_dir / vb.BUNDLE).read_bytes()).hexdigest()


def test_an_unsorted_bundle_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    lines = bundle_text_lines(run_dir)
    posted = _rewrite_bundle(run_dir, [lines[1], lines[0]] + lines[2:])
    with pytest.raises(vb.IntegrityError, match="not sorted"):
        vb.verify(run_dir, posted, env["lock"])


def test_a_duplicate_bundle_path_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    lines = bundle_text_lines(run_dir)
    posted = _rewrite_bundle(run_dir, [lines[0], lines[0]] + lines[1:])
    with pytest.raises(vb.IntegrityError, match="twice"):
        vb.verify(run_dir, posted, env["lock"])


@pytest.mark.parametrize("bad", ["/etc/passwd", "../outside.txt", "adapter/../../x", "C:/x.txt", "a\\b.txt",
                                 "bundle.sha256"])
def test_unsafe_or_self_bundle_paths_fail(env, bad):
    run_dir, _, _ = make(env)
    writable(run_dir)
    lines = bundle_text_lines(run_dir)
    posted = _rewrite_bundle(run_dir, sorted(lines + ["0" * 64 + "  " + bad], key=lambda l: l.split("  ", 1)[1]))
    with pytest.raises(vb.IntegrityError, match="invalid path"):
        vb.verify(run_dir, posted, env["lock"])


def test_a_symlink_inside_the_run_fails_even_when_resealed(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    outside = env["tmp"] / "outside.txt"
    outside.write_text("outside", encoding="utf-8")
    try:
        (run_dir / "link.txt").symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("this platform does not allow creating symlinks here")
    with pytest.raises(vb.IntegrityError, match="symlink"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def bundle_text_lines(run_dir):
    return (run_dir / vb.BUNDLE).read_text(encoding="utf-8").split("\n")[:-1]



# --- CODEX-PROXY P3 on #563 (2): the directory, run-dir and bundle-file symlink cases, not only a file symlink. ---

def _symlink_or_skip(link, target, *, directory=False):
    try:
        link.symlink_to(target, target_is_directory=directory)
    except (OSError, NotImplementedError):
        pytest.skip("this platform does not allow creating symlinks here")


def test_a_directory_symlink_inside_the_run_fails(env):
    run_dir, _, _ = make(env)
    writable(run_dir)
    outside = env["tmp"] / "outside-dir"
    outside.mkdir()
    (outside / "weights.bin").write_bytes(b"outside")
    _symlink_or_skip(run_dir / "adapter" / "extra", outside, directory=True)
    with pytest.raises(vb.IntegrityError, match="symlink"):
        vb.verify(run_dir, reseal(run_dir), env["lock"])


def test_a_symlinked_run_directory_fails(env):
    run_dir, bundle, _ = make(env)
    alias = env["tmp"] / "alias-run"
    _symlink_or_skip(alias, run_dir, directory=True)
    with pytest.raises(vb.IntegrityError, match="symlink"):
        vb.verify(alias, bundle, env["lock"])


def test_a_symlinked_bundle_file_fails_even_with_the_right_posted_hash(env):
    run_dir, bundle, _ = make(env)
    writable(run_dir)
    real = env["tmp"] / "bundle-copy.sha256"
    real.write_bytes((run_dir / vb.BUNDLE).read_bytes())
    (run_dir / vb.BUNDLE).unlink()
    _symlink_or_skip(run_dir / vb.BUNDLE, real)
    with pytest.raises(vb.IntegrityError, match="symlink"):
        vb.verify(run_dir, bundle, env["lock"])
