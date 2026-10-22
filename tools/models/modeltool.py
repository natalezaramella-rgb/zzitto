#!/usr/bin/env python3
"""Strumenti per i modelli di Zzitto (Spec §7, §7.1, §13). Aggiunto il 22/10/2026 alle 11:29.

Usato da prepare-models.sh e prepare-models.ps1. Solo libreria standard di Python 3.9+.

Sottocomandi:
  sha256 FILE [FILE...]             stampa lo SHA-256 del file (con più file: della loro concatenazione in ordine)
  split FILE OUTDIR N               divide FILE in N parti FILE.1.part ... FILE.N.part (Spec §7)
  join OUTFILE PART...              ricompone le parti (stesso algoritmo che deve usare l'app, agente B)
  index OUT.json --models-version V --file NAME SHA SIZE [--part NAME PACK SHA SIZE ...] ...
                                    scrive l'indice models-index.json letto dall'app Android
  update-manifests ROOT --models-version V --model NAME SHA SIZE [--model ...] [--strict]
                                    aggiorna ModelManifest.kt e ModelManifest.cs (formato in README.md)
  check-manifests ROOT --model NAME SHA SIZE [--model ...]
                                    verifica, senza modificare, che ogni ModelManifest contenga questi hash (rilascio)

Nessun audio o testo dettato passa da qui: solo file dei modelli (Spec §10).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from pathlib import Path

PLACEHOLDER = "DA_IMPOSTARE"
MANIFEST_NAMES = ("ModelManifest.kt", "ModelManifest.cs")
SKIP_DIRS = {".git", "build", ".gradle", "bin", "obj", ".vs", ".idea", "node_modules"}

# Marcatori nei commenti (formato documentato in tools/models/README.md).
RE_HASH = re.compile(r"zz-model-hash:\s*(\S+)")
RE_SIZE = re.compile(r"zz-model-size:\s*(\S+)")
RE_VERSION = re.compile(r"zz-models-version\b")
# Primo letterale stringa su una riga di assegnazione.
RE_STRING = re.compile(r'"([^"\\]*)"')
# Primo letterale intero (Kotlin 123L, C# 123 o 123L) dopo il segno "=".
RE_INT = re.compile(r"(=\s*)(\d+)(L?)\b")
RE_HEX64 = re.compile(r"^[0-9a-f]{64}$")

CHUNK = 8 * 1024 * 1024


def sha256_file(*paths: Path) -> str:
    """SHA-256 di un file, o della concatenazione in ordine di più file (parti di Phi-4-mini, Spec §7)."""
    h = hashlib.sha256()
    for path in paths:
        with open(path, "rb") as f:
            while True:
                b = f.read(CHUNK)
                if not b:
                    break
                h.update(b)
    return h.hexdigest()


def split_file(src: Path, outdir: Path, parts: int) -> list[Path]:
    """Divide src in `parts` parti di dimensione quasi uguale (l'ultima può essere più corta).

    Spec §7: la dimensione di ogni parte è arrotondata a multipli di 1 MiB, così le parti
    sono uguali tranne l'ultima e la ricomposizione è una semplice concatenazione in ordine.
    """
    if parts < 1:
        raise ValueError("parts deve essere >= 1")
    size = src.stat().st_size
    mib = 1024 * 1024
    part_size = -(-size // parts)               # ceil
    part_size = -(-part_size // mib) * mib      # arrotonda a MiB
    outdir.mkdir(parents=True, exist_ok=True)
    out: list[Path] = []
    with open(src, "rb") as f:
        for i in range(1, parts + 1):
            p = outdir / f"{src.name}.{i}.part"
            remaining = part_size
            with open(p, "wb") as o:
                while remaining > 0:
                    b = f.read(min(CHUNK, remaining))
                    if not b:
                        break
                    o.write(b)
                    remaining -= len(b)
            out.append(p)
        if f.read(1):
            raise RuntimeError("divisione incompleta: dati residui")
    total = sum(p.stat().st_size for p in out)
    if total != size:
        raise RuntimeError(f"divisione errata: {total} != {size}")
    return out


def join_files(dst: Path, parts: list[Path]) -> None:
    tmp = dst.with_name(dst.name + ".tmp")
    with open(tmp, "wb") as o:
        for p in parts:
            with open(p, "rb") as f:
                while True:
                    b = f.read(CHUNK)
                    if not b:
                        break
                    o.write(b)
    os.replace(tmp, dst)


def write_index(out: Path, models_version: str, files: list[dict]) -> None:
    data = {
        "schema": 1,
        "spec": "Spec §7",
        "modelsVersion": models_version,
        "files": files,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def find_manifests(root: Path) -> list[Path]:
    found: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for n in filenames:
            if n in MANIFEST_NAMES:
                found.append(Path(dirpath) / n)
    return sorted(found)


def _target_line(lines: list[str], i: int, pattern: re.Pattern) -> int | None:
    """Riga da modificare: la stessa del marcatore se contiene l'assegnazione, altrimenti la successiva non vuota."""
    def has_assignment(s: str) -> bool:
        code = s.split("//", 1)[0]
        return "=" in code and pattern.search(code) is not None

    if has_assignment(lines[i]):
        return i
    j = i + 1
    while j < len(lines) and lines[j].strip() == "":
        j += 1
    if j < len(lines) and has_assignment(lines[j]):
        return j
    return None


def _replace_string(line: str, value: str) -> str:
    code, sep, comment = line.partition("//")
    new_code = RE_STRING.sub(lambda m: f'"{value}"', code, count=1)
    return new_code + sep + comment


def _replace_int(line: str, value: int) -> str:
    code, sep, comment = line.partition("//")
    new_code = RE_INT.sub(lambda m: f"{m.group(1)}{value}{m.group(3)}", code, count=1)
    return new_code + sep + comment


def update_manifest_text(text: str, models: dict[str, tuple[str, int]], models_version: str | None):
    """Restituisce (nuovo_testo, nomi_hash_aggiornati, nomi_size_aggiornati, versione_aggiornata)."""
    lines = text.splitlines(keepends=True)
    hashed: set[str] = set()
    sized: set[str] = set()
    version_done = False
    for i, line in enumerate(lines):
        m = RE_HASH.search(line)
        if m and m.group(1) in models:
            t = _target_line(lines, i, RE_STRING)
            if t is not None:
                cur = RE_STRING.search(lines[t].split("//", 1)[0]).group(1)
                if cur == PLACEHOLDER or RE_HEX64.match(cur):
                    lines[t] = _replace_string(lines[t], models[m.group(1)][0])
                    hashed.add(m.group(1))
            continue
        m = RE_SIZE.search(line)
        if m and m.group(1) in models:
            t = _target_line(lines, i, RE_INT)
            if t is not None:
                lines[t] = _replace_int(lines[t], models[m.group(1)][1])
                sized.add(m.group(1))
            continue
        if models_version is not None and RE_VERSION.search(line):
            t = _target_line(lines, i, RE_STRING)
            if t is not None:
                lines[t] = _replace_string(lines[t], models_version)
                version_done = True
    return "".join(lines), hashed, sized, version_done


def update_manifests(root: Path, models: dict[str, tuple[str, int]], models_version: str | None, strict: bool) -> int:
    for name, (sha, _size) in models.items():
        if not RE_HEX64.match(sha):
            print(f"ERRORE: hash non valido per {name}: {sha}", file=sys.stderr)
            return 2
    manifests = find_manifests(root)
    if not manifests:
        print("ATTENZIONE: nessun ModelManifest.kt o ModelManifest.cs trovato.", file=sys.stderr)
        return 3 if strict else 0
    rc = 0
    for path in manifests:
        text = path.read_text(encoding="utf-8")
        new, hashed, sized, ver = update_manifest_text(text, models, models_version)
        missing = sorted(set(models) - hashed)
        if new != text:
            path.write_text(new, encoding="utf-8", newline="")
        print(f"{path}: hash aggiornati {sorted(hashed)}, dimensioni {sorted(sized)}, versione {'sì' if ver else 'no'}")
        if missing:
            print(f"  ATTENZIONE: marcatore 'zz-model-hash' mancante per {missing}", file=sys.stderr)
            if strict:
                rc = 4
        if PLACEHOLDER in new:
            print(f"  ATTENZIONE: in {path} resta il segnaposto {PLACEHOLDER}", file=sys.stderr)
            if strict:
                rc = 4
    return rc


def check_manifests(root: Path, models: dict[str, tuple[str, int]]) -> int:
    """0 se ogni ModelManifest ha già esattamente questi hash e dimensioni (nessun DA_IMPOSTARE)."""
    manifests = find_manifests(root)
    if not manifests:
        print("ERRORE: nessun ModelManifest.kt o ModelManifest.cs trovato.", file=sys.stderr)
        return 3
    rc = 0
    for path in manifests:
        text = path.read_text(encoding="utf-8")
        new, hashed, _sized, _ = update_manifest_text(text, models, None)
        if hashed != set(models) or new != text or PLACEHOLDER in text:
            print(f"ERRORE: {path} non contiene gli hash dei modelli attuali (eseguire tools/models/prepare-models.*)", file=sys.stderr)
            rc = 4
        else:
            print(f"{path}: hash dei modelli corretti")
    return rc


def _parse_models(args: list[str]):
    version = None
    strict = False
    models: dict[str, tuple[str, int]] = {}
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--models-version":
            version = args[i + 1]; i += 2
        elif a == "--model":
            models[args[i + 1]] = (args[i + 2].lower(), int(args[i + 3])); i += 4
        elif a == "--strict":
            strict = True; i += 1
        else:
            raise ValueError(a)
    return models, version, strict


def _usage() -> int:
    print(__doc__, file=sys.stderr)
    return 64


def main(argv: list[str]) -> int:
    if not argv:
        return _usage()
    cmd, args = argv[0], argv[1:]
    if cmd == "sha256" and args:
        print(sha256_file(*[Path(a) for a in args]))
        return 0
    if cmd == "split" and len(args) == 3:
        for p in split_file(Path(args[0]), Path(args[1]), int(args[2])):
            print(p)
        return 0
    if cmd == "join" and len(args) >= 2:
        join_files(Path(args[0]), [Path(a) for a in args[1:]])
        return 0
    if cmd == "index" and args:
        out = Path(args[0])
        version = None
        files: list[dict] = []
        i = 1
        while i < len(args):
            a = args[i]
            if a == "--models-version":
                version = args[i + 1]; i += 2
            elif a == "--file":
                files.append({"name": args[i + 1], "sha256": args[i + 2], "size": int(args[i + 3]), "parts": []}); i += 4
            elif a == "--part":
                if not files:
                    return _usage()
                files[-1]["parts"].append({"name": args[i + 1], "pack": args[i + 2], "sha256": args[i + 3], "size": int(args[i + 4])}); i += 5
            else:
                return _usage()
        if version is None:
            return _usage()
        write_index(out, version, files)
        return 0
    if cmd in ("update-manifests", "check-manifests") and args:
        try:
            models, version, strict = _parse_models(args[1:])
        except (ValueError, IndexError):
            return _usage()
        if cmd == "check-manifests":
            return check_manifests(Path(args[0]), models)
        return update_manifests(Path(args[0]), models, version, strict)
    return _usage()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
