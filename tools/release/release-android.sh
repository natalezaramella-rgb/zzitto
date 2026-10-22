#!/usr/bin/env bash
# Build di rilascio Android: test, AAB release, firma con la chiave di upload, controlli, copia in dist/.
# Spec §7 (asset pack), §12 (test obbligatori), §13 (Play App Signing, versionamento, changelog).
# Aggiunto il 22/10/2026 alle 11:29.
#
# Uso: tools/release/release-android.sh [--device] [--skip-tests]
#   --skip-tests è consentito solo per prove locali: l'AAB prodotto viene marcato NON-PUBBLICABILE.
#
# Chiave di upload (Play App Signing: Google conserva la chiave di firma dell'app, noi solo quella di upload).
# MAI nel repository. Due modi:
#   1) variabili d'ambiente:
#        ZZ_UPLOAD_KEYSTORE        percorso del keystore (.jks o .p12) FUORI dal repository
#        ZZ_UPLOAD_KEY_ALIAS       alias della chiave
#        ZZ_UPLOAD_STORE_PASSWORD  password del keystore
#        ZZ_UPLOAD_KEY_PASSWORD    password della chiave (facoltativa: se manca vale quella del keystore)
#   2) ZZ_SIGNING_PROPERTIES=/percorso/fuori/dal/repo/zzitto-signing.properties con le stesse chiavi
#      (righe KEY=valore). Il file deve essere leggibile solo dal titolare (chmod 600).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS="$REPO_ROOT/tools"
DEVICE_FLAG=""; SKIP_TESTS=0
for a in "$@"; do
  case "$a" in
    --device) DEVICE_FLAG="--device" ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "Opzione sconosciuta: $a" >&2; exit 64 ;;
  esac
done
die() { printf 'ERRORE: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

# ---- Credenziali di firma (Spec §13, §10: nessun segreto nel repository)
if [[ -n "${ZZ_SIGNING_PROPERTIES:-}" ]]; then
  [[ -f "$ZZ_SIGNING_PROPERTIES" ]] || die "ZZ_SIGNING_PROPERTIES non trovato"
  while IFS='=' read -r k v; do
    [[ -z "$k" || "$k" == \#* ]] && continue
    case "$k" in
      ZZ_UPLOAD_KEYSTORE|ZZ_UPLOAD_KEY_ALIAS|ZZ_UPLOAD_STORE_PASSWORD|ZZ_UPLOAD_KEY_PASSWORD) export "$k=$v" ;;
    esac
  done < "$ZZ_SIGNING_PROPERTIES"
fi
for v in ZZ_UPLOAD_KEYSTORE ZZ_UPLOAD_KEY_ALIAS ZZ_UPLOAD_STORE_PASSWORD; do
  [[ -n "${!v:-}" ]] || die "variabile $v non impostata (vedi l'intestazione dello script)"
done
export ZZ_UPLOAD_KEY_PASSWORD="${ZZ_UPLOAD_KEY_PASSWORD:-$ZZ_UPLOAD_STORE_PASSWORD}"
KS="$(cd "$(dirname "$ZZ_UPLOAD_KEYSTORE")" && pwd)/$(basename "$ZZ_UPLOAD_KEYSTORE")"
[[ -f "$KS" ]] || die "keystore non trovato: $KS"
case "$KS/" in "$REPO_ROOT"/*) die "il keystore è dentro il repository: spostalo fuori ($KS)" ;; esac
command -v jarsigner >/dev/null || die "jarsigner non trovato (JDK 17)"

# ---- Versione e changelog (Spec §13)
step "Versione e changelog"
python3 "$TOOLS/release/version.py" show
python3 "$TOOLS/release/version.py" check || die "aggiornare CHANGELOG.md con version.py bump"
VERSION_NAME="$(python3 "$TOOLS/release/version.py" show | sed -n 's/^VERSION_NAME=//p')"
VERSION_CODE="$(python3 "$TOOLS/release/version.py" show | sed -n 's/^VERSION_CODE=//p')"

# ---- Modelli e hash nel codice (Spec §7)
step "Modelli negli asset pack e hash nel codice"
# shellcheck disable=SC1091
source "$TOOLS/models/models.lock"
A="$REPO_ROOT/android/models"
W="$A/src/main/assets/models/$WHISPER_FILE"
[[ -f "$W" ]] || die "manca $W: eseguire tools/models/prepare-models.sh"
PARTS=()
for ((i = 1; i <= PHI_PART_COUNT; i++)); do
  p="$A/phi_$i/src/main/assets/models/$PHI_FILE.$i.part"
  [[ -f "$p" ]] || die "manca $p: eseguire tools/models/prepare-models.sh"
  PARTS+=("$p")
done
[[ "$(python3 "$TOOLS/models/modeltool.py" sha256 "$W")" == "$WHISPER_SHA256" ]] || die "hash di Whisper diverso da models.lock"
[[ "$(python3 "$TOOLS/models/modeltool.py" sha256 "${PARTS[@]}")" == "$PHI_SHA256" ]] || die "hash delle parti di Phi-4-mini diverso da models.lock"
python3 "$TOOLS/models/modeltool.py" check-manifests "$REPO_ROOT/android" \
  --model "$WHISPER_FILE" "$WHISPER_SHA256" "$WHISPER_SIZE" \
  --model "$PHI_FILE" "$PHI_SHA256" "$PHI_SIZE" || die "ModelManifest.kt non aggiornato"

# ---- Test (Spec §12)
PUBLISHABLE=1
if [[ "$SKIP_TESTS" == "1" ]]; then
  echo "ATTENZIONE: test saltati, l'AAB sarà marcato NON-PUBBLICABILE" >&2
  PUBLISHABLE=0
else
  "$TOOLS/release/run-tests.sh" --real-engine $DEVICE_FLAG
fi

# ---- Build AAB (senza firma: la firma la fa questo script, così la chiave non passa da Gradle)
step "Build AAB release"
(cd "$REPO_ROOT/android" && ./gradlew --no-daemon clean :app:bundleRelease)
UNSIGNED="$(find "$REPO_ROOT/android/app/build/outputs/bundle/release" -name '*.aab' | head -n 1)"
[[ -n "$UNSIGNED" ]] || die "AAB non trovato"
if jarsigner -verify "$UNSIGNED" 2>&1 | grep -q 'jar verified'; then
  die "l'AAB è già firmato da Gradle: rimuovere il signingConfig release da app/build.gradle.kts (vedi docs/release-guide.md)"
fi

step "Firma con la chiave di upload"
DIST="$REPO_ROOT/dist"
mkdir -p "$DIST"
SUFFIX=""; [[ "$PUBLISHABLE" == "1" ]] || SUFFIX="-NON-PUBBLICABILE"
OUT="$DIST/zzitto-android-$VERSION_NAME-$VERSION_CODE$SUFFIX.aab"
jarsigner -keystore "$KS" -storepass:env ZZ_UPLOAD_STORE_PASSWORD -keypass:env ZZ_UPLOAD_KEY_PASSWORD \
  -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$OUT" "$UNSIGNED" "$ZZ_UPLOAD_KEY_ALIAS"
jarsigner -verify "$OUT" 2>&1 | grep -q 'jar verified' || die "verifica della firma fallita"

step "Controlli del bundle (asset pack, limiti Google Play, motore reale)"
python3 "$TOOLS/release/aabcheck.py" "$OUT"
if [[ -n "${BUNDLETOOL_JAR:-}" ]]; then
  java -jar "$BUNDLETOOL_JAR" validate --bundle "$OUT"
  VC="$(java -jar "$BUNDLETOOL_JAR" dump manifest --bundle "$OUT" --xpath /manifest/@android:versionCode)"
  [[ "$VC" == "$VERSION_CODE" ]] || die "versionCode nell'AAB ($VC) diverso da version.properties ($VERSION_CODE)"
else
  echo "Suggerimento: impostare BUNDLETOOL_JAR per validare il bundle e il versionCode." >&2
fi

python3 "$TOOLS/models/modeltool.py" sha256 "$OUT" > "$OUT.sha256"
step "Pronto: $OUT"
echo "Versione $VERSION_NAME ($VERSION_CODE), modelli $MODELS_VERSION"
[[ "$PUBLISHABLE" == "1" ]] && echo "Caricare l'AAB nella traccia di test interno della Play Console (docs/release-guide.md)."
