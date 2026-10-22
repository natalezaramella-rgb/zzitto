#!/usr/bin/env bash
# Esegue tutti i test prima di una build di rilascio — Spec §12: "una build con test falliti non si pubblica".
# Niente CI (decisione del titolare, Spec §13 e capitolo 14): questo script è il cancello locale.
# Aggiunto il 22/10/2026 alle 11:29.
#
# Uso: tools/release/run-tests.sh [--real-engine] [--device] [--skip-android]
#   --real-engine  compila e testa anche il motore reale (obbligatorio per il rilascio)
#   --device       esegue anche i test strumentali Android (connectedCheck) su un dispositivo collegato
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REAL=0; DEVICE=0; SKIP_ANDROID=0
for a in "$@"; do
  case "$a" in
    --real-engine) REAL=1 ;;
    --device) DEVICE=1 ;;
    --skip-android) SKIP_ANDROID=1 ;;
    *) echo "Opzione sconosciuta: $a" >&2; exit 64 ;;
  esac
done
JOBS="$( (command -v nproc >/dev/null && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
step() { printf '\n==> %s\n' "$*"; }

step "Test degli strumenti di rilascio (tools/tests)"
python3 -m unittest discover -s "$REPO_ROOT/tools/tests"
for s in "$REPO_ROOT"/tools/models/*.sh "$REPO_ROOT"/tools/release/*.sh; do bash -n "$s"; done

step "Motore finto (core-native, i test girano a ogni build)"
cmake -S "$REPO_ROOT/core-native" -B "$REPO_ROOT/build/core-mock" -DZZ_ENGINE_MOCK=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$REPO_ROOT/build/core-mock" -j "$JOBS"

if [[ "$REAL" == "1" ]]; then
  step "Motore reale (core-native)"
  cmake -S "$REPO_ROOT/core-native" -B "$REPO_ROOT/build/core-real" -DZZ_ENGINE_MOCK=OFF -DCMAKE_BUILD_TYPE=Release
  cmake --build "$REPO_ROOT/build/core-real" -j "$JOBS"
fi

if [[ "$SKIP_ANDROID" == "0" ]]; then
  step "Android: test unitari e lint (gradlew check)"
  (cd "$REPO_ROOT/android" && ./gradlew --no-daemon check)
  if [[ "$DEVICE" == "1" ]]; then
    step "Android: test strumentali (connectedCheck)"
    (cd "$REPO_ROOT/android" && ./gradlew --no-daemon connectedCheck)
  fi
fi

step "Tutti i test superati"
