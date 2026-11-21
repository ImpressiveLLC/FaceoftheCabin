"""Synthetic path metadata only; never opens a private source or creates a packet."""
from dataclasses import FrozenInstanceError
from pathlib import Path
import os
import json
import subprocess
import sys
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from grading import packet_paths as pp, review_packets as rp


class PacketPaths(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("sources", "coordinator", "reviewer-a", "reviewer-b"):
            (self.root / name).mkdir()
        self.source = self.root / "sources" / "synthetic.jsonl"
        self.source.write_bytes(b"synthetic fixture, deliberately not parsed")
        self.args = dict(source_files=[self.source], source_root=self.root / "sources",
                         coordinator_root=self.root / "coordinator",
                         staging=self.root / "coordinator" / "staging",
                         destination=self.root / "coordinator" / "final",
                         recipient_roots=[self.root / "reviewer-a", self.root / "reviewer-b"])

    def check(self, **changes):
        return rp.check_blind_packet_paths_v1(**dict(self.args, **changes))

    def test_valid_layout_is_read_only_and_not_content_dependent(self):
        before = sorted(self.root.rglob("*"))
        with patch("builtins.open", side_effect=AssertionError("content read")), \
                patch.object(Path, "open", side_effect=AssertionError("content read")), \
                patch("os.mkdir", side_effect=AssertionError("directory write")):
            result = self.check()
        self.assertEqual(result.source_files, (self.source,))
        self.assertEqual(result.destination, self.args["destination"])
        self.assertEqual(sorted(self.root.rglob("*")), before)
        with self.assertRaises(FrozenInstanceError):
            result.destination = self.root

    def test_roots_must_be_existing_disjoint_directories(self):
        for changes in ({"recipient_roots": []}, {"source_files": []},
                        {"coordinator_root": self.source},
                        {"source_root": self.root / "missing"},
                        {"recipient_roots": [self.args["coordinator_root"]]},
                        {"recipient_roots": [self.root]},
                        {"recipient_roots": [self.args["recipient_roots"][0]] * 2}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.check(**changes)

    def test_paths_must_be_absolute_and_not_traverse_parents(self):
        for path in (Path("relative.jsonl"), self.source.parent / ".." / "sources" / self.source.name):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "absolute paths"):
                self.check(source_files=[path])
        with self.assertRaisesRegex(ValueError, "absolute paths"):
            self.check(destination=Path("relative"))

    def test_sources_must_be_regular_unique_and_inside_approved_root(self):
        outside = self.root / "outside.jsonl"
        outside.write_bytes(b"synthetic")
        for files in ([outside], [self.source.parent], [self.source, self.source],
                      [self.source.parent / "missing.jsonl"]):
            with self.subTest(files=files), self.assertRaises(ValueError):
                self.check(source_files=files)
        linked = self.source.parent / "alias.jsonl"
        os.link(self.source, linked)
        with self.assertRaisesRegex(ValueError, "hard-linked"):
            self.check(source_files=[self.source, linked])

    def test_outputs_are_distinct_absent_direct_children(self):
        for changes in ({"destination": self.args["staging"]},
                        {"destination": self.source},
                        {"staging": self.root / "elsewhere"},
                        {"destination": self.args["coordinator_root"] / "nested" / "final"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.check(**changes)
        for name in ("staging", "destination"):
            path = self.args[name]
            path.mkdir()
            with self.assertRaisesRegex(ValueError, "already exists"):
                self.check()
            path.rmdir()

    def test_git_directory_or_worktree_marker_refused_for_output_storage(self):
        for root in (self.args["coordinator_root"], self.args["recipient_roots"][0]):
            marker = root / ".git"
            marker.write_text("synthetic worktree marker", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "outside Git"):
                self.check()
            marker.unlink()
        marker = self.root / ".git"
        marker.mkdir()
        with self.assertRaisesRegex(ValueError, "outside Git"):
            self.check()

    def test_simulated_ancestor_reparse_and_symlink_fail_closed(self):
        original = Path.lstat
        for mode, attrs in ((stat.S_IFDIR, 0x400), (stat.S_IFLNK, 0)):
            def metadata(path, *args, **kwargs):
                if path == self.root:
                    return SimpleNamespace(st_mode=mode, st_file_attributes=attrs)
                return original(path, *args, **kwargs)
            with self.subTest(mode=mode, attrs=attrs), patch.object(Path, "lstat", metadata):
                with self.assertRaisesRegex(ValueError, "symlink or reparse"):
                    self.check()

    def test_trailing_dot_space_and_case_alias_outputs_refused(self):
        for name in ("final.", "final "):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "trailing dot or space"):
                self.check(destination=self.args["coordinator_root"] / name)
        with self.assertRaisesRegex(ValueError, "must differ"):
            self.check(staging=self.args["coordinator_root"] / "Run",
                       destination=self.args["coordinator_root"] / "run")
        with self.assertRaisesRegex(ValueError, "Git marker"):
            self.check(destination=self.args["coordinator_root"] / ".git")

    def test_existing_root_aliases_use_physical_identity(self):
        original = Path.lstat
        target = self.args["coordinator_root"]
        for name in ("COORDI~1", "case-alias"):
            alias = self.root / name
            alias.mkdir(exist_ok=True)
            def metadata(path, *args, **kwargs):
                return original(target if path == alias else path, *args, **kwargs)
            with self.subTest(name=name), patch.object(Path, "lstat", metadata):
                with self.assertRaisesRegex(ValueError, "overlap or alias"):
                    self.check(recipient_roots=[alias])

    def test_nested_root_through_aliased_ancestor_is_refused(self):
        alias = self.root / "alias-parent"
        alias.mkdir()
        nested = alias / "nested"
        nested.mkdir()
        original = Path.lstat
        def metadata(path, *args, **kwargs):
            return original(self.args["coordinator_root"] if path == alias else path, *args, **kwargs)
        with patch.object(Path, "lstat", metadata), self.assertRaisesRegex(ValueError, "overlap or alias"):
            self.check(recipient_roots=[nested])

    def test_nonroot_directory_source_and_file_ancestor_and_recipient_refused(self):
        directory = self.source.parent / "nested"
        directory.mkdir()
        with self.assertRaisesRegex(ValueError, "not a regular file"):
            self.check(source_files=[directory])
        with self.assertRaisesRegex(ValueError, "ancestor is not a directory"):
            self.check(source_files=[self.source / "child"])
        with self.assertRaisesRegex(ValueError, "root is not a directory"):
            self.check(recipient_roots=[self.source])
        with self.assertRaisesRegex(ValueError, "outside approved source root"):
            self.check(source_files=[self.args["source_root"]])

    def test_metadata_access_errors_have_value_error_contract(self):
        original = Path.lstat
        for target in (self.source, self.args["coordinator_root"] / ".git"):
            for error in (PermissionError, NotADirectoryError):
                def metadata(path, *args, **kwargs):
                    if path == target:
                        raise error("synthetic metadata refusal")
                    return original(path, *args, **kwargs)
                with self.subTest(target=target, error=error), patch.object(Path, "lstat", metadata):
                    with self.assertRaisesRegex(ValueError, "cannot inspect"):
                        self.check()

    def test_audit_hook_proves_no_open_or_filesystem_mutation(self):
        # A child process keeps the permanent audit hook out of other tests.
        script = """
import json, sys
sys.path.insert(0, sys.argv[1])
from grading.review_packets import check_blind_packet_paths_v1
args = json.loads(sys.argv[2])
def audit(event, args):
    if event == 'open' or event in {'os.mkdir', 'os.rename', 'os.remove', 'os.rmdir', 'os.link', 'os.symlink', 'os.chmod'}:
        raise AssertionError('unexpected I/O: ' + event)
# Import the lazy wrapper dependency before enforcing the I/O contract.
import grading.packet_paths
sys.addaudithook(audit)
check_blind_packet_paths_v1(**args)
print('READ_ONLY')
"""
        args = {key: [str(p) for p in value] if isinstance(value, list) else str(value)
                for key, value in self.args.items()}
        result = subprocess.run([sys.executable, "-B", "-c", script, str(Path(__file__).parent),
                                 json.dumps(args)], capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), "READ_ONLY")

    def test_real_symlink_and_broken_destination_refused(self):
        link = self.root / "source-link"
        try:
            link.symlink_to(self.source.parent, target_is_directory=True)
        except OSError as exc:
            self.skipTest(f"symlink creation unavailable: {exc.__class__.__name__}")
        with self.assertRaisesRegex(ValueError, "symlink or reparse"):
            self.check(source_root=link, source_files=[link / self.source.name])
        self.args["destination"].symlink_to(self.root / "absent", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink or reparse"):
            self.check()

    def test_new_destination_after_snapshot_is_refused_on_recheck(self):
        self.check()
        self.args["destination"].mkdir()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.check()


if __name__ == "__main__":
    unittest.main()
