#!/usr/bin/env bash
# Prepara i modelli di Zzitto: download dalle fonti ufficiali, quantizzazione, SHA-256,
# divisione per Google Play, copia negli asset pack Android e nella cartella Models di Windows,
# aggiornamento di ModelManifest.kt / ModelManifest.cs e di tools/models/models.lock.
# Spec §3, §7, §7.1, §13. Aggiunto il 22/10/2026 alle 11:29.
#
# Uso:
#   tools/models/prepare-models.sh [--work-dir DIR] [--whisper-mode download|quantize]
#                                  [--skip-android] [--skip-windows] [--keep-work] [--strict]
#
# Requisiti: bash, curl, git, cmake, un compilatore C/C++, python3 (3.9+) con venv e pip,
# circa 25 GB liberi nella cartella di lavoro. Variabile facoltativa HF_TOKEN per Hugging Face.
# Nessun segreto viene scritto su disco o nel repository (Spec §10).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=models.conf
source "$SCRIPT_DIR/models.conf"
TOOL="$SCRIPT_DIR/modeltool.py"

WORK_DIR="$REPO_ROOT/build/models-work"
SKIP_ANDROID=0
SKIP_WINDOWS=0
KEEP_WORK=0
STRICT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --whisper-mode) WHISPER_MODE="$2"; shift 2 ;;
    --skip-android) SKIP_ANDROID=1; shift ;;
    --skip-windows) SKIP_WINDOWS=1; shift ;;
    --keep-work) KEEP_WORK=1; shift ;;
    --strict) STRICT="--strict"; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Opzione sconosciuta: $1" >&2; exit 64 ;;
  esac
done

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERRORE: %s\n' "$*" >&2; exit 1; }

for cmd in curl git cmake python3; do
  command -v "$cmd" >/dev/null 2>&1 || die "manca il comando '$cmd'"
done
PY=python3
JOBS="$( (command -v nproc >/dev/null && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

mkdir -p "$WORK_DIR"
DL_DIR="$WORK_DIR/downloads"
OUT_DIR="$WORK_DIR/out"
mkdir -p "$DL_DIR" "$OUT_DIR"

sha256() { "$PY" "$TOOL" sha256 "$1"; }
filesize() { "$PY" -c 'import os,sys; print(os.path.getsize(sys.argv[1]))' "$1"; }

# Scarica un file da Hugging Face a una revisione fissa (mai "main": tracciabilità, Spec §10).
hf_download() {
  local repo="$1" rev="$2" file="$3" dest="$4"
  local url="https://huggingface.co/${repo}/resolve/${rev}/${file}"
  if [[ -s "$dest" ]]; then
    echo "già presente: $dest"
    return 0
  fi
  echo "scarico $url"
  local auth=()
  if [[ -n "${HF_TOKEN:-}" ]]; then
    # Il token passa da stdin per non comparire nell'elenco dei processi.
    auth=(--config -)
  fi
  if [[ ${#auth[@]} -gt 0 ]]; then
    printf 'header = "Authorization: Bearer %s"\n' "$HF_TOKEN" | \
      curl --fail --location --retry 5 --retry-delay 5 -C - "${auth[@]}" -o "$dest.partial" "$url"
  else
    curl --fail --location --retry 5 --retry-delay 5 -C - -o "$dest.partial" "$url"
  fi
  mv "$dest.partial" "$dest"
}

check_sha() {
  local file="$1" expected="$2" actual
  actual="$(sha256 "$file")"
  if [[ "$actual" != "$expected" ]]; then
    if [[ "${ZZ_ALLOW_SOURCE_HASH_MISMATCH:-0}" == "1" ]]; then
      echo "ATTENZIONE: SHA-256 di $file diverso dall'atteso ($actual != $expected), accettato per ZZ_ALLOW_SOURCE_HASH_MISMATCH=1" >&2
    else
      die "SHA-256 di $file diverso dall'atteso: $actual (atteso $expected). Verifica la fonte su Hugging Face."
    fi
  fi
}

# Clona un repository a un riferimento fisso, in modo idempotente.
clone_at() {
  local url="$1" ref="$2" dest="$3"
  if [[ ! -d "$dest/.git" ]]; then
    git clone --filter=blob:none "$url" "$dest"
  fi
  git -C "$dest" fetch --tags --quiet origin
  git -C "$dest" checkout --quiet --detach "$ref"
}

# ---------------------------------------------------------------- Whisper
log "Whisper medium Q5_0 (modalità: $WHISPER_MODE)"
WHISPER_OUT="$OUT_DIR/$WHISPER_FILE"
WHISPER_TOOL_INFO="whisper.cpp non usato (file già quantizzato da ${WHISPER_HF_REPO}@${WHISPER_HF_REVISION})"
case "$WHISPER_MODE" in
  download)
    hf_download "$WHISPER_HF_REPO" "$WHISPER_HF_REVISION" "$WHISPER_HF_FILE" "$DL_DIR/$WHISPER_HF_FILE"
    check_sha "$DL_DIR/$WHISPER_HF_FILE" "$WHISPER_SOURCE_SHA256"
    cp "$DL_DIR/$WHISPER_HF_FILE" "$WHISPER_OUT"
    ;;
  quantize)
    hf_download "$WHISPER_HF_REPO" "$WHISPER_HF_REVISION" "$WHISPER_HF_SOURCE_FILE_F16" "$DL_DIR/$WHISPER_HF_SOURCE_FILE_F16"
    clone_at "$WHISPER_CPP_REPO" "$WHISPER_CPP_REF" "$WORK_DIR/whisper.cpp"
    cmake -S "$WORK_DIR/whisper.cpp" -B "$WORK_DIR/whisper.cpp/build" -DCMAKE_BUILD_TYPE=Release
    cmake --build "$WORK_DIR/whisper.cpp/build" --config Release -j "$JOBS"
    QBIN=""
    for c in whisper-quantize quantize; do
      [[ -x "$WORK_DIR/whisper.cpp/build/bin/$c" ]] && QBIN="$WORK_DIR/whisper.cpp/build/bin/$c" && break
    done
    [[ -n "$QBIN" ]] || die "strumento quantize di whisper.cpp non trovato"
    "$QBIN" "$DL_DIR/$WHISPER_HF_SOURCE_FILE_F16" "$WHISPER_OUT" q5_0
    WHISPER_TOOL_INFO="whisper.cpp $WHISPER_CPP_REF ($(git -C "$WORK_DIR/whisper.cpp" rev-parse HEAD)), quantize q5_0 da $WHISPER_HF_SOURCE_FILE_F16"
    ;;
  *) die "WHISPER_MODE deve essere download o quantize" ;;
esac

# ---------------------------------------------------------------- Phi-4-mini
log "Phi-4-mini-instruct $PHI_QUANT (conversione GGUF e quantizzazione con llama.cpp)"
PHI_SRC="$DL_DIR/phi-4-mini-instruct"
mkdir -p "$PHI_SRC"
for f in $PHI_HF_FILES; do
  hf_download "$PHI_HF_REPO" "$PHI_HF_REVISION" "$f" "$PHI_SRC/$f"
done
check_sha "$PHI_SRC/model-00001-of-00002.safetensors" "$PHI_SOURCE_SHA256_1"
check_sha "$PHI_SRC/model-00002-of-00002.safetensors" "$PHI_SOURCE_SHA256_2"

if [[ -d "$REPO_ROOT/core-native/third_party/llama.cpp" ]]; then
  LLAMA_DIR="$REPO_ROOT/core-native/third_party/llama.cpp"
  echo "uso la copia di llama.cpp del motore: $LLAMA_DIR"
else
  LLAMA_DIR="$WORK_DIR/llama.cpp"
  clone_at "$LLAMA_CPP_REPO" "$LLAMA_CPP_REF" "$LLAMA_DIR"
fi
LLAMA_COMMIT="$(git -C "$LLAMA_DIR" rev-parse HEAD 2>/dev/null || echo sconosciuto)"

VENV="$WORK_DIR/venv"
if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
fi
REQ="$LLAMA_DIR/requirements/requirements-convert_hf_to_gguf.txt"
[[ -f "$REQ" ]] || die "requisiti di conversione non trovati in $REQ"
"$VENV/bin/python" -m pip install --quiet --upgrade pip
"$VENV/bin/python" -m pip install --quiet -r "$REQ"

PHI_INTER="$WORK_DIR/phi-4-mini-instruct-${PHI_INTERMEDIATE_TYPE}.gguf"
if [[ ! -s "$PHI_INTER" ]]; then
  "$VENV/bin/python" "$LLAMA_DIR/convert_hf_to_gguf.py" "$PHI_SRC" \
    --outfile "$PHI_INTER.partial" --outtype "$PHI_INTERMEDIATE_TYPE"
  mv "$PHI_INTER.partial" "$PHI_INTER"
fi

LLAMA_BUILD="$WORK_DIR/llama.cpp-build"
cmake -S "$LLAMA_DIR" -B "$LLAMA_BUILD" -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DGGML_NATIVE=OFF
cmake --build "$LLAMA_BUILD" --config Release --target llama-quantize -j "$JOBS"
QLLAMA="$(find "$LLAMA_BUILD" -type f -name 'llama-quantize' -perm -u+x | head -n 1)"
[[ -n "$QLLAMA" ]] || die "llama-quantize non trovato"
PHI_OUT="$OUT_DIR/$PHI_FILE"
"$QLLAMA" "$PHI_INTER" "$PHI_OUT" "$PHI_QUANT"

# ---------------------------------------------------------------- Hash e versione
log "SHA-256 dei modelli finali"
WHISPER_SHA="$(sha256 "$WHISPER_OUT")"; WHISPER_SIZE="$(filesize "$WHISPER_OUT")"
PHI_SHA="$(sha256 "$PHI_OUT")";         PHI_SIZE="$(filesize "$PHI_OUT")"
echo "$WHISPER_FILE  $WHISPER_SHA  $WHISPER_SIZE byte"
echo "$PHI_FILE  $PHI_SHA  $PHI_SIZE byte"
# Versione dei modelli riportata nel changelog (Spec §13).
MODELS_VERSION="whisper-medium-q5_0@${WHISPER_SHA:0:8}+phi-4-mini-q4_k_m@${PHI_SHA:0:8}"
echo "Versione modelli: $MODELS_VERSION"

# ---------------------------------------------------------------- Android (Spec §7)
PART_ARGS=()
if [[ "$SKIP_ANDROID" == "0" ]]; then
  log "Asset pack Android"
  A_WHISPER="$REPO_ROOT/android/models/src/main/assets/models"
  mkdir -p "$A_WHISPER"
  [[ "$WHISPER_SIZE" -lt "$ANDROID_PACK_LIMIT_BYTES" ]] || die "Whisper supera il limite del singolo asset pack"
  cp "$WHISPER_OUT" "$A_WHISPER/$WHISPER_FILE"

  SPLIT_DIR="$WORK_DIR/phi-parts"
  rm -rf "$SPLIT_DIR"
  "$PY" "$TOOL" split "$PHI_OUT" "$SPLIT_DIR" "$PHI_PART_COUNT" >/dev/null
  for ((i = 1; i <= PHI_PART_COUNT; i++)); do
    part="$SPLIT_DIR/$PHI_FILE.$i.part"
    psize="$(filesize "$part")"
    [[ "$psize" -lt "$ANDROID_PACK_LIMIT_BYTES" ]] || die "la parte $i ($psize byte) supera il limite: aumentare PHI_PART_COUNT e aggiungere un asset pack"
    dest="$REPO_ROOT/android/models/phi_$i/src/main/assets/models"
    [[ -d "$REPO_ROOT/android/models/phi_$i" ]] || die "manca il modulo asset pack android/models/phi_$i"
    mkdir -p "$dest"
    rm -f "$dest"/*.part
    cp "$part" "$dest/"
    PART_ARGS+=(--part "$PHI_FILE.$i.part" "models_phi_$i" "$(sha256 "$part")" "$psize")
  done
  TOTAL=$((WHISPER_SIZE + PHI_SIZE))
  [[ "$TOTAL" -lt "$ANDROID_INSTALL_TIME_LIMIT_BYTES" ]] || die "i modelli superano il limite install-time di 4 GB"
  "$PY" "$TOOL" index "$A_WHISPER/models-index.json" --models-version "$MODELS_VERSION" \
    --file "$WHISPER_FILE" "$WHISPER_SHA" "$WHISPER_SIZE" \
    --file "$PHI_FILE" "$PHI_SHA" "$PHI_SIZE" "${PART_ARGS[@]}"
  echo "scritto $A_WHISPER/models-index.json"
fi

# ---------------------------------------------------------------- Windows (Spec §7.1)
if [[ "$SKIP_WINDOWS" == "0" ]]; then
  log "Cartella Models del pacchetto Windows"
  MARKER="$(find "$REPO_ROOT/windows" -name '.zz-models-dir' -not -path '*/bin/*' -not -path '*/obj/*' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$MARKER" ]]; then
    W_DIR="$(dirname "$MARKER")"
  else
    W_DIR="$REPO_ROOT/windows/Models"
    echo "ATTENZIONE: nessun file .zz-models-dir sotto windows/: uso $W_DIR (vedi tools/models/README.md)" >&2
  fi
  mkdir -p "$W_DIR"
  cp "$WHISPER_OUT" "$W_DIR/$WHISPER_FILE"
  cp "$PHI_OUT" "$W_DIR/$PHI_FILE"
  echo "copiati in $W_DIR"
fi

# ---------------------------------------------------------------- Manifest nel codice (Spec §7)
log "Aggiornamento di ModelManifest.kt e ModelManifest.cs"
"$PY" "$TOOL" update-manifests "$REPO_ROOT" --models-version "$MODELS_VERSION" \
  --model "$WHISPER_FILE" "$WHISPER_SHA" "$WHISPER_SIZE" \
  --model "$PHI_FILE" "$PHI_SHA" "$PHI_SIZE" $STRICT

# ---------------------------------------------------------------- models.lock (tracciabilità, Spec §10, §13)
LOCK="$SCRIPT_DIR/models.lock"
NOW="$(TZ=Europe/Rome date '+%d/%m/%Y %H:%M')"
cat > "$LOCK" <<EOF
# Generato da tools/models/prepare-models.sh il $NOW (ora italiana). Non modificare a mano.
# Spec §7, §13: versione e integrità dei modelli inclusi nell'app.
MODELS_VERSION="$MODELS_VERSION"
WHISPER_FILE="$WHISPER_FILE"
WHISPER_SHA256="$WHISPER_SHA"
WHISPER_SIZE="$WHISPER_SIZE"
WHISPER_SOURCE="https://huggingface.co/$WHISPER_HF_REPO@$WHISPER_HF_REVISION ($WHISPER_MODE)"
WHISPER_TOOL="$WHISPER_TOOL_INFO"
PHI_FILE="$PHI_FILE"
PHI_SHA256="$PHI_SHA"
PHI_SIZE="$PHI_SIZE"
PHI_SOURCE="https://huggingface.co/$PHI_HF_REPO@$PHI_HF_REVISION"
PHI_TOOL="llama.cpp $LLAMA_COMMIT, convert_hf_to_gguf $PHI_INTERMEDIATE_TYPE, llama-quantize $PHI_QUANT"
PHI_PART_COUNT="$PHI_PART_COUNT"
EOF
echo "scritto $LOCK"

if [[ "$KEEP_WORK" == "0" ]]; then
  rm -f "$PHI_INTER"
  rm -rf "$WORK_DIR/phi-parts"
fi
log "Fatto. Versione modelli: $MODELS_VERSION"
