import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import generate  # noqa: E402

FIXTURE = HERE / "fixture_tokens.json"


def run(argv):
    err = io.StringIO()
    with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
        code = generate.main(argv)
    return code, err.getvalue()


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.kt = self.tmp / "Tokens.kt"
        self.css = self.tmp / "tokens.css"
        self.guide = self.tmp / "GUIDE.md"
        self.guide_text = "# Guide\n\nbefore\n\n<!-- tokens:start -->\nold\n<!-- tokens:end -->\n\nafter\n"
        self.guide.write_text(self.guide_text)
        self.args = ["--tokens", str(FIXTURE), "--kotlin-out", str(self.kt), "--css-out", str(self.css), "--guide-out", str(self.guide)]

    def bad_tokens(self, mutate):
        data = json.loads(FIXTURE.read_text())
        mutate(data)
        path = self.tmp / "bad.json"
        path.write_text(json.dumps(data))
        return ["--tokens", str(path), "--kotlin-out", str(self.kt), "--css-out", str(self.css), "--guide-out", str(self.guide)]

    def test_kotlin_output_has_the_exact_color_line(self):
        self.assertEqual(run(self.args)[0], 0)
        text = self.kt.read_text()
        self.assertIn("        val rost500 = Color(0xFFE26A2E)\n", text)
        self.assertIn("package app.rustmfa.android.ui.design\n", text)
        self.assertIn("    val radiusChip: Dp = 12.dp\n", text)
        self.assertIn("    val sizeHeadline: TextUnit = 28.sp\n", text)

    def test_css_output_has_the_exact_variable_line_and_a_dark_block(self):
        run(self.args)
        text = self.css.read_text()
        self.assertIn("  --rost-500: #E26A2E;\n", text)
        self.assertIn("  --on-action: #FFFFFF;\n", text)
        self.assertIn("@media (prefers-color-scheme: dark) {\n  :root {\n    --rost-500: #FF9A66;\n", text)

    def test_missing_color_key_fails_naming_the_key_and_writes_nothing(self):
        code, err = run(self.bad_tokens(lambda d: d["colors"]["light"].pop("ink")))
        self.assertEqual(code, 1)
        self.assertIn("ink", err)
        self.assertFalse(self.kt.exists())
        self.assertFalse(self.css.exists())
        self.assertEqual(self.guide.read_text(), self.guide_text)

    def test_malformed_hex_fails_naming_the_key_and_writes_nothing(self):
        def mutate(d):
            d["colors"]["dark"]["paper"] = "#E8743"
        code, err = run(self.bad_tokens(mutate))
        self.assertEqual(code, 1)
        self.assertIn("paper", err)
        self.assertFalse(self.kt.exists())

    def test_missing_dark_theme_fails_and_writes_nothing(self):
        code, err = run(self.bad_tokens(lambda d: d["colors"].pop("dark")))
        self.assertEqual(code, 1)
        self.assertIn("dark", err)
        self.assertFalse(self.css.exists())

    def test_check_passes_when_equal_and_fails_when_different(self):
        run(self.args)
        self.assertEqual(run(self.args + ["--check"])[0], 0)
        self.kt.write_text(self.kt.read_text() + "// drift\n")
        code, err = run(self.args + ["--check"])
        self.assertEqual(code, 1)
        self.assertIn("Tokens.kt", err)

    def test_guide_table_replaces_only_the_marked_text(self):
        self.assertEqual(run(self.args)[0], 0)
        text = self.guide.read_text()
        self.assertTrue(text.startswith("# Guide\n\nbefore\n\n<!-- tokens:start -->\n"))
        self.assertTrue(text.endswith("<!-- tokens:end -->\n\nafter\n"))
        self.assertNotIn("\nold\n", text)
        self.assertIn("| `rost500` | `#E26A2E` | `#FF9A66` |", text)
        self.assertIn("| `chip` | 12 |", text)

    def test_a_guide_without_markers_fails_and_is_left_alone(self):
        self.guide.write_text("# no markers\n")
        code, err = run(self.args)
        self.assertEqual(code, 1)
        self.assertIn("tokens:start", err)
        self.assertEqual(self.guide.read_text(), "# no markers\n")
        self.assertFalse(self.kt.exists())

    def test_check_notices_a_stale_guide(self):
        run(self.args)
        self.guide.write_text(self.guide_text)
        code, err = run(self.args + ["--check"])
        self.assertEqual(code, 1)
        self.assertIn("GUIDE.md", err)

    def test_check_fails_when_the_output_is_missing(self):
        self.assertEqual(run(self.args + ["--check"])[0], 1)


if __name__ == "__main__":
    unittest.main()
