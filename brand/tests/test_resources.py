import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RES = ROOT / "android/app/src/main/res"
TOKENS = json.loads((ROOT / "brand/tokens.json").read_text())


def paper(path):
    return re.search(r'<color name="paper">(#[0-9A-Fa-f]{6})</color>', path.read_text()).group(1).upper()


class ResourcesMatchTokens(unittest.TestCase):
    def test_light_paper_resource_is_the_light_paper_token(self):
        self.assertEqual(paper(RES / "values/colors.xml"), TOKENS["colors"]["light"]["paper"].upper())

    def test_night_paper_resource_is_the_dark_paper_token(self):
        self.assertEqual(paper(RES / "values-night/colors.xml"), TOKENS["colors"]["dark"]["paper"].upper())

    def test_the_app_theme_paints_the_window_with_paper(self):
        text = (RES / "values/themes.xml").read_text()
        style = re.search(r'<style name="Theme.RostdavMfa" .*?</style>|<style name="Theme.RostdavMfa"[^>]*/>', text, re.S).group(0)
        self.assertIn('android:windowBackground">@color/paper', style)


if __name__ == "__main__":
    unittest.main()
