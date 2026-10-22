#!/usr/bin/env python3
"""Versionamento semantico e changelog di Zzitto — Spec §13. Aggiunto il 22/10/2026 alle 11:29.

Fonte unica della versione: tools/release/version.properties (VERSION_NAME=X.Y.Z, VERSION_CODE=n).
Android (agente B) legge questo file in app/build.gradle.kts; Windows usa X.Y.Z.0 nel Package.appxmanifest
(la quarta cifra è riservata al Microsoft Store e deve essere 0).

Sottocomandi:
  show                          stampa VERSION_NAME e VERSION_CODE
  appx-version                  stampa X.Y.Z.0
  bump major|minor|patch|X.Y.Z  nuova versione: VERSION_CODE+1, sezione nel CHANGELOG con data, ora e versione dei modelli
  check                         verifica che CHANGELOG.md abbia la sezione della versione corrente con la versione dei
                                modelli uguale a tools/models/models.lock (usato prima di ogni build di rilascio)
  sync-appx MANIFEST            scrive X.Y.Z.0 nell'attributo Version di <Identity> del Package.appxmanifest
  check-appx MANIFEST           verifica che il Package.appxmanifest abbia la versione X.Y.Z.0
"""
from __future__ import annotations

import re
import sys
from datetime import datetime
from pathlib import Path

try:
    from zoneinfo import ZoneInfo
    _TZ = ZoneInfo("Europe/Rome")
except Exception:  # Windows senza tzdata: si usa l'ora locale della macchina (in Italia è la stessa)
    _TZ = None

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PROPS = HERE / "version.properties"
CHANGELOG = ROOT / "CHANGELOG.md"
LOCK = ROOT / "tools" / "models" / "models.lock"

UNRELEASED = "## [Non rilasciato]"
RE_SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
RE_IDENTITY_VERSION = re.compile(r'(<Identity\b[^>]*?\bVersion=")([^"]*)(")', re.S)


def now_it() -> str:
    d = datetime.now(_TZ) if _TZ else datetime.now()
    return d.strftime("%d/%m/%Y %H:%M")


def read_props(path: Path = PROPS) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def write_props(name: str, code: int, path: Path = PROPS) -> None:
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^VERSION_NAME=.*$", f"VERSION_NAME={name}", text)
    text = re.sub(r"(?m)^VERSION_CODE=.*$", f"VERSION_CODE={code}", text)
    path.write_text(text, encoding="utf-8", newline="\n")


def models_version(lock: Path = LOCK) -> str:
    if not lock.exists():
        return "DA_IMPOSTARE"
    m = re.search(r'(?m)^MODELS_VERSION="([^"]*)"', lock.read_text(encoding="utf-8"))
    return m.group(1) if m else "DA_IMPOSTARE"


def next_version(current: str, kind: str) -> str:
    if RE_SEMVER.match(kind):
        new = kind
    else:
        m = RE_SEMVER.match(current)
        if not m:
            raise ValueError(f"versione corrente non valida: {current}")
        major, minor, patch = (int(x) for x in m.groups())
        if kind == "major":
            new = f"{major + 1}.0.0"
        elif kind == "minor":
            new = f"{major}.{minor + 1}.0"
        elif kind == "patch":
            new = f"{major}.{minor}.{patch + 1}"
        else:
            raise ValueError("usare major, minor, patch o X.Y.Z")
    if tuple(int(x) for x in new.split(".")) <= tuple(int(x) for x in current.split(".")):
        raise ValueError(f"la nuova versione {new} deve essere maggiore di {current}")
    return new


def changelog_release(text: str, version: str, code: int, models: str, when: str) -> str:
    """Sposta le voci di [Non rilasciato] in una nuova sezione di versione (Spec §13)."""
    if UNRELEASED not in text:
        raise ValueError(f"CHANGELOG.md senza la sezione '{UNRELEASED}'")
    if re.search(rf"(?m)^## \[{re.escape(version)}\]", text):
        raise ValueError(f"CHANGELOG.md contiene già la versione {version}")
    head, rest = text.split(UNRELEASED, 1)
    m = re.search(r"(?m)^## \[", rest)
    body, tail = (rest[: m.start()], rest[m.start():]) if m else (rest, "")
    body = body.strip("\n")
    if not body.strip():
        body = "- Nessuna modifica descritta."
    section = (
        f"## [{version}] – {when}\n\n"
        f"- Codice versione Android: {code}; versione pacchetto Windows: {version}.0\n"
        f"- Modelli inclusi: `{models}`\n\n"
        f"{body}\n\n"
    )
    return f"{head}{UNRELEASED}\n\n{section}{tail}".rstrip("\n") + "\n"


def changelog_check(text: str, version: str, models: str) -> list[str]:
    errors = []
    m = re.search(rf"(?ms)^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|\Z)", text)
    if not m:
        errors.append(f"CHANGELOG.md: manca la sezione ## [{version}]")
    elif f"`{models}`" not in m.group(1):
        errors.append(f"CHANGELOG.md: la sezione {version} non riporta i modelli `{models}` (Spec §13)")
    if "DA_IMPOSTARE" in models:
        errors.append("tools/models/models.lock: versione dei modelli non impostata (eseguire tools/models/prepare-models.*)")
    return errors


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__, file=sys.stderr)
        return 64
    cmd = argv[0]
    props = read_props()
    name, code = props["VERSION_NAME"], int(props["VERSION_CODE"])
    if cmd == "show":
        print(f"VERSION_NAME={name}\nVERSION_CODE={code}")
        return 0
    if cmd == "appx-version":
        print(f"{name}.0")
        return 0
    if cmd == "bump" and len(argv) == 2:
        new = next_version(name, argv[1])
        models = models_version()
        text = changelog_release(CHANGELOG.read_text(encoding="utf-8"), new, code + 1, models, now_it())
        CHANGELOG.write_text(text, encoding="utf-8", newline="\n")
        write_props(new, code + 1)
        print(f"Versione {name} ({code}) -> {new} ({code + 1}); modelli {models}")
        return 0
    if cmd == "check":
        errors = changelog_check(CHANGELOG.read_text(encoding="utf-8"), name, models_version())
        for e in errors:
            print("ERRORE: " + e, file=sys.stderr)
        return 1 if errors else 0
    if cmd in ("sync-appx", "check-appx") and len(argv) == 2:
        path = Path(argv[1])
        text = path.read_text(encoding="utf-8-sig")
        m = RE_IDENTITY_VERSION.search(text)
        if not m:
            print(f"ERRORE: <Identity Version=...> non trovato in {path}", file=sys.stderr)
            return 1
        want = f"{name}.0"
        if cmd == "check-appx":
            if m.group(2) != want:
                print(f"ERRORE: {path} ha Version={m.group(2)}, attesa {want} (eseguire version.py sync-appx)", file=sys.stderr)
                return 1
            return 0
        path.write_text(RE_IDENTITY_VERSION.sub(lambda x: x.group(1) + want + x.group(3), text, count=1), encoding="utf-8")
        print(f"{path}: Version={want}")
        return 0
    print(__doc__, file=sys.stderr)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
