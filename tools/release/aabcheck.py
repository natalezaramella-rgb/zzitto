#!/usr/bin/env python3
"""Controlli sul bundle Android (AAB) prima del caricamento — Spec §7. Aggiunto il 22/10/2026 alle 11:29.

Uso: aabcheck.py FILE.aab

Verifica (stima prudente sulla dimensione compressa nel file AAB; Play Console ricalcola la dimensione
di download esatta al caricamento):
  - presenza degli asset pack models, models_phi_1, models_phi_2 e dei file dei modelli;
  - ogni asset pack sotto 1,5 GB e modulo base sotto 500 MB;
  - totale moduli + asset pack install-time sotto 4 GB;
  - assenza di file che non devono stare nel pacchetto (chiavi, .env);
  - assenza del motore finto (Spec §16.1): libzz_engine.so non deve contenere la firma del mock.
Limiti: https://support.google.com/googleplay/android-developer/answer/9859372 (verificati il 22/10/2026).
"""
from __future__ import annotations

import sys
import zipfile
from collections import defaultdict

PACK_LIMIT = 1_500_000_000
BASE_LIMIT = 500_000_000
TOTAL_LIMIT = 4_000_000_000
REQUIRED = {
    "models": "assets/models/whisper-medium-q5_0.bin",
    "models_phi_1": "assets/models/phi-4-mini-instruct-q4_k_m.gguf.1.part",
    "models_phi_2": "assets/models/phi-4-mini-instruct-q4_k_m.gguf.2.part",
}
FORBIDDEN_SUFFIXES = (".jks", ".keystore", ".pfx", ".pem", ".p12", ".env")
SKIP_TOP = {"BUNDLE-METADATA", "META-INF"}
MOCK_SIGNATURE = b"zzitto-engine MOCK"
ENGINE_LIB = "libzz_engine.so"


def check(path: str) -> list[str]:
    errors: list[str] = []
    sizes: dict[str, int] = defaultdict(int)
    names: dict[str, set[str]] = defaultdict(set)
    engines = 0
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            top, _, rest = info.filename.partition("/")
            if info.filename.lower().endswith(FORBIDDEN_SUFFIXES):
                errors.append(f"file vietato nel bundle: {info.filename}")
            if info.filename.endswith(ENGINE_LIB):
                engines += 1
                if MOCK_SIGNATURE in z.read(info):
                    errors.append(f"motore finto nel bundle: {info.filename} (Spec §16.1)")
            if top in SKIP_TOP or not rest:
                continue
            sizes[top] += info.compress_size
            names[top].add(rest)
    if engines == 0:
        errors.append(f"nessuna {ENGINE_LIB} nel bundle")
    for module, f in REQUIRED.items():
        if module not in sizes:
            errors.append(f"manca l'asset pack '{module}'")
        elif f not in names[module]:
            errors.append(f"manca {f} nell'asset pack '{module}'")
    for module, size in sorted(sizes.items()):
        limit = BASE_LIMIT if module == "base" else PACK_LIMIT
        print(f"{module:16s} {size / 1e9:6.3f} GB (limite {limit / 1e9:.1f} GB)")
        if size >= limit:
            errors.append(f"'{module}' supera il limite: {size} byte")
    total = sum(sizes.values())
    print(f"{'totale':16s} {total / 1e9:6.3f} GB (limite {TOTAL_LIMIT / 1e9:.1f} GB)")
    if total >= TOTAL_LIMIT:
        errors.append(f"totale oltre 4 GB: {total} byte")
    return errors


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 64
    errors = check(argv[0])
    for e in errors:
        print("ERRORE: " + e, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
