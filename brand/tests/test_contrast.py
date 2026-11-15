import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import check_contrast  # noqa: E402

FIXTURE = HERE / "fixture_tokens.json"


class ContrastTest(unittest.TestCase):
    def test_black_on_white_is_21(self):
        self.assertAlmostEqual(check_contrast.ratio("#FFFFFF", "#000000"), 21.0, places=2)

    def test_767676_on_white_passes_aa(self):
        self.assertGreaterEqual(check_contrast.ratio("#767676", "#FFFFFF"), 4.5)

    def test_the_fixture_passes(self):
        self.assertEqual(check_contrast.check(json.loads(FIXTURE.read_text())), [])

    def test_ink_equal_to_paper_fails_and_names_the_pair(self):
        data = json.loads(FIXTURE.read_text())
        data["colors"]["light"]["ink"] = data["colors"]["light"]["paper"]
        failures = check_contrast.check(data)
        self.assertTrue(any("light" in f and "ink/paper" in f for f in failures), failures)

    def test_main_exits_one_on_a_failing_pair(self):
        data = json.loads(FIXTURE.read_text())
        data["colors"]["dark"]["slate"] = data["colors"]["dark"]["paper"]
        path = Path(tempfile.mkdtemp()) / "t.json"
        path.write_text(json.dumps(data))
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(check_contrast.main(["--tokens", str(path)]), 1)


if __name__ == "__main__":
    unittest.main()
