"""Test di tools/release/version.py — Spec §13, §12. Aggiunto il 22/10/2026 alle 11:29."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import version  # noqa: E402

CL = """# Changelog

## [Non rilasciato]

### Aggiunto

- Prima funzione.

## [0.1.0] – 01/10/2026 10:00

- Codice versione Android: 1; versione pacchetto Windows: 0.1.0.0
- Modelli inclusi: `m-old`
"""


class VersionTest(unittest.TestCase):
    def test_next_version(self):
        self.assertEqual(version.next_version("0.1.0", "patch"), "0.1.1")
        self.assertEqual(version.next_version("0.1.9", "minor"), "0.2.0")
        self.assertEqual(version.next_version("1.2.3", "major"), "2.0.0")
        self.assertEqual(version.next_version("1.2.3", "1.10.0"), "1.10.0")
        with self.assertRaises(ValueError):
            version.next_version("1.2.3", "1.2.3")
        with self.assertRaises(ValueError):
            version.next_version("1.2.3", "01.2.4")

    def test_changelog_release_and_check(self):
        out = version.changelog_release(CL, "0.2.0", 2, "m-new", "22/10/2026 11:30")
        self.assertIn("## [Non rilasciato]\n\n## [0.2.0] – 22/10/2026 11:30", out)
        self.assertIn("Modelli inclusi: `m-new`", out)
        self.assertIn("- Prima funzione.", out.split("## [0.1.0]")[0])
        self.assertEqual(version.changelog_check(out, "0.2.0", "m-new"), [])
        self.assertTrue(version.changelog_check(out, "0.2.0", "m-diverso"))
        self.assertTrue(version.changelog_check(out, "0.3.0", "m-new"))
        self.assertTrue(version.changelog_check(out, "0.2.0", "DA_IMPOSTARE"))
        with self.assertRaises(ValueError):
            version.changelog_release(out, "0.2.0", 3, "m", "x")

    def test_changelog_release_empty_unreleased(self):
        out = version.changelog_release("# C\n\n## [Non rilasciato]\n", "1.0.0", 5, "m", "t")
        self.assertIn("Nessuna modifica descritta", out)

    def test_props_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "version.properties"
            p.write_text("# c\nVERSION_NAME=0.1.0\nVERSION_CODE=1\n", encoding="utf-8")
            version.write_props("0.2.0", 2, p)
            self.assertEqual(version.read_props(p), {"VERSION_NAME": "0.2.0", "VERSION_CODE": "2"})

    def test_models_version_from_lock(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "models.lock"
            self.assertEqual(version.models_version(p), "DA_IMPOSTARE")
            p.write_text('# x\nMODELS_VERSION="w@1+p@2"\n', encoding="utf-8")
            self.assertEqual(version.models_version(p), "w@1+p@2")

    def test_identity_regex(self):
        xml = '<Package><Identity Name="Zaramella.Zzitto"\n  Publisher="CN=X" Version="1.0.0.0" /></Package>'
        new = version.RE_IDENTITY_VERSION.sub(lambda m: m.group(1) + "0.2.0.0" + m.group(3), xml, count=1)
        self.assertIn('Version="0.2.0.0"', new)
        self.assertIn('Name="Zaramella.Zzitto"', new)


if __name__ == "__main__":
    unittest.main()
