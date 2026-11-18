import json
import os
import sys
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import ask_notice_rules as r  # noqa: E402

VECTORS = os.path.join(ROOT, "docs", "ai-assistant", "notice-vectors", "vectors.json")
FIXTURES = [os.path.join(ROOT, "cabin-orchestration-platform", "backend", "src", "main",
                         "resources", "rag", name) for name in
            ("context-fixtures-r1.json", "context-fixtures-r2.json")]
CRITERION = os.path.join(ROOT, "docs", "ai-assistant", "proposals", "ask-notice-criterion.md")


class SharedVectors(unittest.TestCase):
    def test_every_vector_matches(self):
        self.assertEqual([], r.run_vectors(VECTORS))

    def test_vector_file_is_lf_only_and_ascii(self):
        with open(VECTORS, "rb") as handle:
            raw = handle.read()
        self.assertNotIn(b"\r", raw)
        raw.decode("ascii")

    def test_long_inputs_are_bounded_time(self):
        with open(VECTORS, "rb") as handle:
            data = json.loads(handle.read().decode("ascii"))
        for vec in data["vectors"]:
            if "answerRepeat" not in vec:
                continue
            rep = vec["answerRepeat"]
            answer = rep["prefix"] + rep["unit"] * rep["count"] + rep.get("suffix", "")
            started = time.perf_counter()
            r.notice_code(answer, vec["supplied"], vec["contextKind"])
            self.assertLess(time.perf_counter() - started, 2.0, vec["id"])


class Rules(unittest.TestCase):
    def test_normalization_is_idempotent_and_terminates(self):
        alphabet = ["doc:", "DOC:", "(", ")", ".", ":", "`", "a", "#", " ", "…", "K"]
        import itertools
        for n in range(1, 5):
            for combo in itertools.product(alphabet, repeat=n):
                text = "".join(combo)
                once = r.normalize(text)
                self.assertEqual(once, r.normalize(once), text)

    def test_ascii_fold_only(self):
        self.assertEqual("k", r.fold("K"))
        self.assertEqual("K", r.fold("K"))
        self.assertEqual("ſ", r.fold("ſ"))

    def test_supplied_label_matches_itself(self):
        for label in ["a.md#b", "x/y.md#z.", "(odd).md#q)", "Doc:foo#bar"]:
            self.assertIsNone(r.notice_code("[source: " + label + "]", [label], "WITH_DOCS"))

    def test_notice_text_is_fixed_and_does_not_echo(self):
        for code, text in r.NOTICE_TEXT.items():
            self.assertNotIn("#", text)
            self.assertNotIn(".md", text)

    def test_unknown_context_kind_raises(self):
        with self.assertRaises(ValueError):
            r.notice_code("x", [], "SOMETHING")


class RealLabels(unittest.TestCase):
    def labels(self):
        out = []
        for path in FIXTURES:
            with open(path, "rb") as handle:
                data = json.loads(handle.read().decode("utf-8"))
            for cat in data.get("categories", []):
                for doc in cat.get("contextDocuments", []):
                    out.append(doc["path"] + "#" + doc["section"])
        return out

    def test_real_supplied_labels_round_trip(self):
        labels = self.labels()
        self.assertTrue(labels)
        for label in labels:
            for ch in "],;":
                self.assertNotIn(ch, label)
            self.assertEqual(r.normalize(label), r.normalize(r.normalize(label)))
            self.assertIsNone(r.notice_code("[source: " + label + "]", [label], "WITH_DOCS"), label)


class CitedHashes(unittest.TestCase):
    """Once the criterion PR exists, the vector file and this script must still be the cited bytes."""

    @unittest.skipUnless(os.path.exists(CRITERION), "criterion file not merged yet (PR B)")
    def test_hashes_match_the_criterion(self):
        text = open(CRITERION, encoding="utf-8").read()
        cited = {}
        for line in text.splitlines():
            for key in ("vectors-sha256:", "script-sha256:"):
                if line.startswith(key):
                    cited[key] = line.split(":", 1)[1].strip()
        self.assertEqual(r.file_sha256(VECTORS), cited.get("vectors-sha256:"))
        self.assertEqual(r.file_sha256(os.path.join(HERE, "ask_notice_rules.py")),
                         cited.get("script-sha256:"))


if __name__ == "__main__":
    unittest.main()
