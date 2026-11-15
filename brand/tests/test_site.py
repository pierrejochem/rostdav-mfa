import re
import unittest
from html.parser import HTMLParser
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent.parent / "site"
PAGES = {"index.html": "en", "index.de.html": "de"}
HEX = re.compile(r"#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b")


class Scan(HTMLParser):
    def __init__(self):
        super().__init__()
        self.h1 = 0
        self.lang = None
        self.imgs = []
        self.refs = []
        self.text = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.lang = a.get("lang")
        if tag == "h1":
            self.h1 += 1
        if tag == "img":
            self.imgs.append(a)
        for key in ("href", "src", "srcset"):
            if a.get(key):
                self.refs.append(a[key].split()[0])

    def handle_data(self, data):
        self.text.append(data)


def scan(name):
    s = Scan()
    s.feed((SITE / name).read_text())
    return s


class SiteTest(unittest.TestCase):
    def test_both_pages_exist(self):
        for name in PAGES:
            self.assertTrue((SITE / name).is_file(), name)

    def test_each_page_has_one_h1_and_its_language(self):
        for name, lang in PAGES.items():
            s = scan(name)
            self.assertEqual(s.h1, 1, name)
            self.assertEqual(s.lang, lang, name)

    def test_every_image_has_alt_text(self):
        for name in PAGES:
            for img in scan(name).imgs:
                self.assertTrue((img.get("alt") or "").strip(), "%s: img without alt: %s" % (name, img))

    def test_every_local_link_and_source_resolves(self):
        for name in PAGES:
            for ref in scan(name).refs:
                if re.match(r"^(https?:|mailto:|#|data:)", ref):
                    continue
                self.assertTrue((SITE / ref.split("#")[0]).exists(), "%s: broken reference %s" % (name, ref))

    def test_pages_link_the_tokens_and_the_style_sheet(self):
        for name in PAGES:
            refs = scan(name).refs
            self.assertIn("tokens.css", refs, name)
            self.assertIn("style.css", refs, name)

    def test_the_style_sheet_uses_only_token_colours(self):
        tokens = {h.upper() for h in HEX.findall((SITE / "tokens.css").read_text())}
        used = {h.upper() for h in HEX.findall((SITE / "style.css").read_text())}
        self.assertEqual(used - tokens, set(), "colour literals outside tokens.css")

    def test_no_claim_of_a_release_that_does_not_exist(self):
        for name in PAGES:
            text = " ".join(scan(name).text).lower()
            for phrase in ("play store", "app store", "download now", "jetzt herunterladen", "f-droid"):
                self.assertNotIn(phrase, text, "%s mentions %r" % (name, phrase))

    def test_the_vault_is_described_as_working(self):
        en = " ".join(scan("index.html").text)
        de = " ".join(scan("index.de.html").text)
        self.assertIn("authenticator", en.lower())
        self.assertNotIn("planned", en.lower())
        self.assertIn("Authenticator", de)
        self.assertNotIn("geplant", de.lower())


if __name__ == "__main__":
    unittest.main()
