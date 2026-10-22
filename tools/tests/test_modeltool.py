"""Test di tools/models/modeltool.py — Spec §7, §12. Aggiunto il 22/10/2026 alle 11:29.

Eseguire con: python3 -m unittest discover -s tools/tests -v
"""
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "models"))
import modeltool  # noqa: E402

H1 = "a" * 64
H2 = "b" * 64

KT = '''package com.zaramella.zzitto.engine

// Spec §7
object ModelManifest {
    const val WHISPER_FILE = "whisper-medium-q5_0.bin"
    // zz-model-hash: whisper-medium-q5_0.bin
    const val WHISPER_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: whisper-medium-q5_0.bin
    const val WHISPER_SIZE_BYTES = 0L
    const val LLM_FILE = "phi-4-mini-instruct-q4_k_m.gguf"
    const val LLM_SHA256 = "DA_IMPOSTARE" // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    // zz-model-size: phi-4-mini-instruct-q4_k_m.gguf
    const val LLM_SIZE_BYTES = 0L
    // zz-models-version
    const val MODELS_VERSION = "DA_IMPOSTARE"
}
'''

CS = '''namespace Zzitto.App.Engine;

// Spec §7.1
public static class ModelManifest
{
    // zz-model-hash: whisper-medium-q5_0.bin
    public const string WhisperSha256 = "DA_IMPOSTARE";

    // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    public const string LlmSha256 = "DA_IMPOSTARE";
    // zz-model-size: phi-4-mini-instruct-q4_k_m.gguf
    public const long LlmSizeBytes = 0;
}
'''

MODELS = {
    "whisper-medium-q5_0.bin": (H1, 539_000_000),
    "phi-4-mini-instruct-q4_k_m.gguf": (H2, 2_491_874_272),
}


class ManifestTest(unittest.TestCase):
    def test_kotlin_hashes_sizes_version(self):
        new, hashed, sized, ver = modeltool.update_manifest_text(KT, MODELS, "v-test")
        self.assertEqual(hashed, set(MODELS))
        self.assertEqual(sized, set(MODELS))
        self.assertTrue(ver)
        self.assertIn(f'WHISPER_SHA256 = "{H1}"', new)
        self.assertIn(f'LLM_SHA256 = "{H2}" // zz-model-hash', new)
        self.assertIn("WHISPER_SIZE_BYTES = 539000000L", new)
        self.assertIn("LLM_SIZE_BYTES = 2491874272L", new)
        self.assertIn('MODELS_VERSION = "v-test"', new)
        # i nomi dei file non devono cambiare
        self.assertIn('WHISPER_FILE = "whisper-medium-q5_0.bin"', new)
        self.assertNotIn("DA_IMPOSTARE", new)

    def test_csharp_blank_line_and_no_suffix(self):
        new, hashed, sized, _ = modeltool.update_manifest_text(CS, MODELS, None)
        self.assertEqual(hashed, set(MODELS))
        self.assertEqual(sized, {"phi-4-mini-instruct-q4_k_m.gguf"})
        self.assertIn(f'WhisperSha256 = "{H1}";', new)
        self.assertIn("LlmSizeBytes = 2491874272;", new)

    def test_idempotent_rehash(self):
        first, *_ = modeltool.update_manifest_text(KT, MODELS, "v1")
        other = {k: ("c" * 64, s) for k, (_, s) in MODELS.items()}
        second, hashed, *_ = modeltool.update_manifest_text(first, other, "v2")
        self.assertEqual(hashed, set(MODELS))
        self.assertIn('"' + "c" * 64 + '"', second)
        self.assertNotIn(H1, second)

    def test_does_not_touch_other_strings(self):
        text = '// zz-model-hash: whisper-medium-q5_0.bin\nconst val X = "non un hash"\n'
        new, hashed, *_ = modeltool.update_manifest_text(text, MODELS, None)
        self.assertEqual(new, text)
        self.assertEqual(hashed, set())

    def test_update_manifests_on_tree_strict(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "android/engine/src").mkdir(parents=True)
            (root / "windows/src").mkdir(parents=True)
            (root / "android/engine/build").mkdir(parents=True)
            (root / "android/engine/src/ModelManifest.kt").write_text(KT, encoding="utf-8")
            (root / "windows/src/ModelManifest.cs").write_text(CS, encoding="utf-8")
            # un file in build/ va ignorato
            (root / "android/engine/build/ModelManifest.kt").write_text(KT, encoding="utf-8")
            rc = modeltool.update_manifests(root, MODELS, "v", strict=True)
            self.assertEqual(rc, 0)
            self.assertIn(H1, (root / "windows/src/ModelManifest.cs").read_text(encoding="utf-8"))
            self.assertIn("DA_IMPOSTARE", (root / "android/engine/build/ModelManifest.kt").read_text(encoding="utf-8"))

    def test_strict_fails_without_manifests_or_bad_hash(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(modeltool.update_manifests(Path(d), MODELS, None, strict=True), 3)
            self.assertEqual(modeltool.update_manifests(Path(d), MODELS, None, strict=False), 0)
            self.assertEqual(modeltool.update_manifests(Path(d), {"x": ("zz", 1)}, None, strict=False), 2)


class CheckManifestsTest(unittest.TestCase):
    def test_check_before_and_after_update(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "ModelManifest.kt").write_text(KT, encoding="utf-8")
            self.assertEqual(modeltool.check_manifests(root, MODELS), 4)
            modeltool.update_manifests(root, MODELS, "v", strict=True)
            self.assertEqual(modeltool.check_manifests(root, MODELS), 0)
            other = {k: ("d" * 64, s) for k, (_, s) in MODELS.items()}
            self.assertEqual(modeltool.check_manifests(root, other), 4)
            self.assertEqual(modeltool.check_manifests(root / "vuota", MODELS), 3)


class SplitJoinTest(unittest.TestCase):
    def test_split_and_join_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / "phi-4-mini-instruct-q4_k_m.gguf"
            data = os.urandom(3 * 1024 * 1024 + 12345)
            src.write_bytes(data)
            parts = modeltool.split_file(src, d / "parts", 2)
            self.assertEqual([p.name for p in parts], [src.name + ".1.part", src.name + ".2.part"])
            self.assertEqual(parts[0].stat().st_size, 2 * 1024 * 1024)   # arrotondata a MiB
            self.assertEqual(sum(p.stat().st_size for p in parts), len(data))
            out = d / "joined.gguf"
            modeltool.join_files(out, parts)
            self.assertEqual(hashlib.sha256(out.read_bytes()).hexdigest(), hashlib.sha256(data).hexdigest())
            self.assertEqual(modeltool.sha256_file(out), hashlib.sha256(data).hexdigest())
            # hash della concatenazione delle parti = hash del file intero (verifica senza ricomporre)
            self.assertEqual(modeltool.sha256_file(*parts), hashlib.sha256(data).hexdigest())

    def test_index_json(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "models-index.json"
            rc = modeltool.main(["index", str(out), "--models-version", "v",
                                 "--file", "w.bin", H1, "10",
                                 "--file", "p.gguf", H2, "20",
                                 "--part", "p.gguf.1.part", "models_phi_1", H1, "12",
                                 "--part", "p.gguf.2.part", "models_phi_2", H2, "8"])
            self.assertEqual(rc, 0)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(data["modelsVersion"], "v")
            self.assertEqual(data["files"][0]["parts"], [])
            self.assertEqual(len(data["files"][1]["parts"]), 2)
            self.assertEqual(data["files"][1]["parts"][1]["pack"], "models_phi_2")


if __name__ == "__main__":
    unittest.main()
