"""Test di tools/release/aabcheck.py — Spec §7, §12. Aggiunto il 22/10/2026 alle 11:29."""
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import aabcheck  # noqa: E402


def make_aab(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
        for name, data in entries.items():
            z.writestr(name, data)


GOOD = {
    "BundleConfig.pb": b"x",
    "base/manifest/AndroidManifest.xml": b"m",
    "base/dex/classes.dex": b"d" * 100,
    "base/lib/arm64-v8a/libzz_engine.so": b"ELF zzitto-engine api=1",
    "models/manifest/AndroidManifest.xml": b"m",
    "models/assets/models/whisper-medium-q5_0.bin": b"w" * 10,
    "models/assets/models/models-index.json": b"{}",
    "models_phi_1/assets/models/phi-4-mini-instruct-q4_k_m.gguf.1.part": b"p",
    "models_phi_2/assets/models/phi-4-mini-instruct-q4_k_m.gguf.2.part": b"q",
    "META-INF/UPLOAD.SF": b"s",
}


class AabCheckTest(unittest.TestCase):
    def test_good_bundle(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.aab"
            make_aab(p, GOOD)
            self.assertEqual(aabcheck.check(str(p)), [])

    def test_missing_pack_and_forbidden_file(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.aab"
            bad = {k: v for k, v in GOOD.items() if not k.startswith("models_phi_2/")}
            bad["base/assets/upload.jks"] = b"k"
            make_aab(p, bad)
            errors = aabcheck.check(str(p))
            self.assertTrue(any("models_phi_2" in e for e in errors))
            self.assertTrue(any("upload.jks" in e for e in errors))

    def test_mock_engine_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.aab"
            bad = dict(GOOD)
            bad["base/lib/arm64-v8a/libzz_engine.so"] = b"ELF zzitto-engine MOCK api=1"
            make_aab(p, bad)
            self.assertTrue(any("motore finto" in e for e in aabcheck.check(str(p))))
            del bad["base/lib/arm64-v8a/libzz_engine.so"]
            make_aab(p, bad)
            self.assertTrue(any("nessuna libzz_engine.so" in e for e in aabcheck.check(str(p))))

    def test_pack_over_limit(self):
        old = aabcheck.PACK_LIMIT
        aabcheck.PACK_LIMIT = 5
        try:
            with tempfile.TemporaryDirectory() as d:
                p = Path(d) / "a.aab"
                make_aab(p, GOOD)
                self.assertTrue(any("'models'" in e for e in aabcheck.check(str(p))))
        finally:
            aabcheck.PACK_LIMIT = old


if __name__ == "__main__":
    unittest.main()
