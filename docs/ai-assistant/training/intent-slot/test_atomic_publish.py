"""Synthetic directory publication only; no packet or operational data."""
import ctypes
import errno
from pathlib import Path
import os
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
from grading import atomic_publish as ap


class BackendContract(unittest.TestCase):
    def test_unsupported_platform_never_touches_paths(self):
        with patch.object(ap.sys, "platform", "win32"), patch.object(ap, "_absolute", side_effect=AssertionError):
            with self.assertRaises(NotImplementedError):
                ap.publish_directory("unused", "unused")

    def test_missing_syscall_has_no_fallback(self):
        with patch.object(ap.ctypes, "CDLL", return_value=SimpleNamespace()):
            with self.assertRaises(NotImplementedError):
                ap._rename_noreplace(7, "a", "b")

    def test_syscall_uses_same_parent_and_noreplace_and_propagates_errors(self):
        calls = []
        def rename(*args):
            calls.append(args)
            return 0
        with patch.object(ap.ctypes, "CDLL", return_value=SimpleNamespace(renameat2=rename)):
            ap._rename_noreplace(7, "a", "b")
        self.assertEqual(calls, [(7, b"a", 7, b"b", 1)])
        for code in (errno.EEXIST, errno.ENOSYS, errno.EINVAL, errno.EXDEV):
            def failed(*args):
                ctypes.set_errno(code)
                return -1
            with self.subTest(code=code), patch.object(ap.ctypes, "CDLL", return_value=SimpleNamespace(renameat2=failed)):
                with self.assertRaises(OSError) as caught:
                    ap._rename_noreplace(7, "a", "b")
                self.assertEqual(caught.exception.errno, code)


@unittest.skipUnless(sys.platform.startswith("linux"), "actual publication is Linux-only; exercised in Linux CI")
class LinuxPublication(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stage = self.root / ("a" * 32)
        self.stage.mkdir(mode=0o700)
        (self.stage / "synthetic.txt").write_bytes(b"invented fixture")
        self.dest = self.root / ("b" * 32)

    def test_success_moves_complete_directory_without_changing_bytes(self):
        self.assertEqual(ap.publish_directory(self.stage, self.dest), self.dest)
        self.assertFalse(self.stage.exists())
        self.assertEqual((self.dest / "synthetic.txt").read_bytes(), b"invented fixture")

    def test_existing_empty_or_populated_destination_is_never_replaced(self):
        self.dest.mkdir(mode=0o700)
        for populated in (False, True):
            if populated:
                (self.dest / "winner.txt").write_bytes(b"earlier evidence")
            with self.assertRaises(FileExistsError):
                ap.publish_directory(self.stage, self.dest)
            self.assertEqual((self.stage / "synthetic.txt").read_bytes(), b"invented fixture")
            if populated:
                self.assertEqual((self.dest / "winner.txt").read_bytes(), b"earlier evidence")

    def test_two_publishers_have_one_winner_and_keep_losing_staging(self):
        second = self.root / ("c" * 32)
        second.mkdir(mode=0o700)
        (second / "synthetic.txt").write_bytes(b"second fixture")
        def publish(stage):
            try:
                ap.publish_directory(stage, self.dest)
                return stage
            except FileExistsError:
                return None
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(publish, (self.stage, second)))
        self.assertEqual(sum(r is not None for r in results), 1)
        winner = next(r for r in results if r is not None)
        loser = second if winner == self.stage else self.stage
        self.assertTrue(loser.is_dir())
        self.assertFalse(winner.exists())

    def test_failures_leave_staging_and_no_destination(self):
        with patch.object(ap, "_rename_noreplace", side_effect=OSError(errno.EIO, "synthetic failure")):
            with self.assertRaises(OSError):
                ap.publish_directory(self.stage, self.dest)
        self.assertTrue(self.stage.is_dir())
        self.assertFalse(self.dest.exists())

    def test_private_storage_names_and_links_are_checked(self):
        for mode in (0o750, 0o707):
            self.stage.chmod(mode)
            with self.assertRaisesRegex(ValueError, "no group/other access"):
                ap.publish_directory(self.stage, self.dest)
        self.stage.chmod(0o700)
        with self.assertRaisesRegex(ValueError, "opaque"):
            ap.publish_directory(self.stage, self.root / "human-name")
        link = self.root / ("d" * 32)
        link.symlink_to(self.stage, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink or reparse"):
            ap.publish_directory(link, self.dest)
        self.dest.symlink_to(self.root / "missing", target_is_directory=True)
        with self.assertRaises(FileExistsError):
            ap.publish_directory(self.stage, self.dest)


if __name__ == "__main__":
    unittest.main()
