<#
.SYNOPSIS
  Prepara i modelli di Zzitto su Windows (stessi passi di prepare-models.sh).
  Spec §3, §7, §7.1, §13. Aggiunto il 22/10/2026 alle 11:29.

.DESCRIPTION
  Download dalle fonti ufficiali a revisione fissa, conversione e quantizzazione di Phi-4-mini con llama.cpp,
  SHA-256, divisione per Google Play, copia negli asset pack Android e nella cartella Models di Windows,
  aggiornamento di ModelManifest.kt / ModelManifest.cs e di tools/models/models.lock.

  Requisiti: PowerShell 7+, git, cmake, Visual Studio 2022 (strumenti C++), Python 3.9+ ("py" o "python"),
  circa 25 GB liberi. Variabile facoltativa HF_TOKEN per Hugging Face.

.EXAMPLE
  pwsh tools/models/prepare-models.ps1
  pwsh tools/models/prepare-models.ps1 -WhisperMode quantize -SkipAndroid
#>
[CmdletBinding()]
param(
    [string]$WorkDir,
    [ValidateSet('download', 'quantize')][string]$WhisperMode,
    [switch]$SkipAndroid,
    [switch]$SkipWindows,
    [switch]$KeepWork,
    [switch]$Strict
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest è molto più veloce senza barra

$ScriptDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$Tool = Join-Path $ScriptDir 'modeltool.py'

# ---- Lettura di models.conf (righe KEY="valore")
$Conf = @{}
foreach ($line in Get-Content (Join-Path $ScriptDir 'models.conf')) {
    if ($line -match '^\s*([A-Z0-9_]+)="(.*)"\s*$') { $Conf[$Matches[1]] = $Matches[2] }
}
if (-not $WorkDir) { $WorkDir = Join-Path $RepoRoot 'build\models-work' }
if (-not $WhisperMode) { $WhisperMode = $Conf.WHISPER_MODE }

function Log([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Die([string]$m) { throw "ERRORE: $m" }
function Need([string]$c) { if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { Die "manca il comando '$c'" } }

Need git; Need cmake
$Py = @(if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3') } elseif (Get-Command python -ErrorAction SilentlyContinue) { @('python') } else { Die 'manca Python 3' })
# Funzioni semplici (senza param): così argomenti come -C, -m, --quiet arrivano intatti in $args.
function Run-Py { $exe = $Py[0]; $pre = @($Py | Select-Object -Skip 1); $a = @($args); & $exe @pre @a; if ($LASTEXITCODE) { Die "python $($a -join ' ') (codice $LASTEXITCODE)" } }
function Exec { $exe = $args[0]; $a = @($args | Select-Object -Skip 1); & $exe @a; if ($LASTEXITCODE) { Die "$exe $($a -join ' ') (codice $LASTEXITCODE)" } }

$DlDir = Join-Path $WorkDir 'downloads'
$OutDir = Join-Path $WorkDir 'out'
New-Item -ItemType Directory -Force -Path $DlDir, $OutDir | Out-Null

function Get-Sha([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() }
function Get-Size([string]$p) { (Get-Item -LiteralPath $p).Length }

# Download a revisione fissa (mai "main"), con ripresa tramite curl.exe incluso in Windows 11.
function Hf-Download([string]$repo, [string]$rev, [string]$file, [string]$dest) {
    if ((Test-Path $dest) -and (Get-Size $dest) -gt 0) { Write-Host "già presente: $dest"; return }
    $url = "https://huggingface.co/$repo/resolve/$rev/$file"
    Write-Host "scarico $url"
    $partial = "$dest.partial"
    $curlArgs = @('--fail', '--location', '--retry', '5', '--retry-delay', '5', '-C', '-', '-o', $partial, $url)
    if ($env:HF_TOKEN) {
        # Il token passa da stdin per non comparire nell'elenco dei processi (Spec §10).
        "header = `"Authorization: Bearer $($env:HF_TOKEN)`"" | & curl.exe --config - @curlArgs
    } else {
        & curl.exe @curlArgs
    }
    if ($LASTEXITCODE) { Die "download fallito: $url" }
    Move-Item -Force $partial $dest
}

function Check-Sha([string]$file, [string]$expected) {
    $actual = Get-Sha $file
    if ($actual -ne $expected) {
        if ($env:ZZ_ALLOW_SOURCE_HASH_MISMATCH -eq '1') {
            Write-Warning "SHA-256 di $file diverso dall'atteso ($actual != $expected), accettato per ZZ_ALLOW_SOURCE_HASH_MISMATCH=1"
        } else {
            Die "SHA-256 di $file diverso dall'atteso: $actual (atteso $expected). Verifica la fonte su Hugging Face."
        }
    }
}

function Clone-At([string]$url, [string]$ref, [string]$dest) {
    if (-not (Test-Path (Join-Path $dest '.git'))) { Exec git clone --filter=blob:none $url $dest }
    Exec git -C $dest fetch --tags --quiet origin
    Exec git -C $dest checkout --quiet --detach $ref
}

# ------------------------------------------------------------ Whisper
Log "Whisper medium Q5_0 (modalità: $WhisperMode)"
$WhisperOut = Join-Path $OutDir $Conf.WHISPER_FILE
$WhisperToolInfo = "whisper.cpp non usato (file già quantizzato da $($Conf.WHISPER_HF_REPO)@$($Conf.WHISPER_HF_REVISION))"
if ($WhisperMode -eq 'download') {
    $src = Join-Path $DlDir $Conf.WHISPER_HF_FILE
    Hf-Download $Conf.WHISPER_HF_REPO $Conf.WHISPER_HF_REVISION $Conf.WHISPER_HF_FILE $src
    Check-Sha $src $Conf.WHISPER_SOURCE_SHA256
    Copy-Item -Force $src $WhisperOut
} else {
    $src = Join-Path $DlDir $Conf.WHISPER_HF_SOURCE_FILE_F16
    Hf-Download $Conf.WHISPER_HF_REPO $Conf.WHISPER_HF_REVISION $Conf.WHISPER_HF_SOURCE_FILE_F16 $src
    $wdir = Join-Path $WorkDir 'whisper.cpp'
    Clone-At $Conf.WHISPER_CPP_REPO $Conf.WHISPER_CPP_REF $wdir
    Exec cmake -S $wdir -B (Join-Path $wdir 'build')
    Exec cmake --build (Join-Path $wdir 'build') --config Release
    $q = Get-ChildItem -Recurse -File (Join-Path $wdir 'build') -Include 'whisper-quantize.exe', 'quantize.exe' | Select-Object -First 1
    if (-not $q) { Die 'strumento quantize di whisper.cpp non trovato' }
    Exec $q.FullName $src $WhisperOut q5_0
    $WhisperToolInfo = "whisper.cpp $($Conf.WHISPER_CPP_REF) ($(git -C $wdir rev-parse HEAD)), quantize q5_0 da $($Conf.WHISPER_HF_SOURCE_FILE_F16)"
}

# ------------------------------------------------------------ Phi-4-mini
Log "Phi-4-mini-instruct $($Conf.PHI_QUANT) (conversione GGUF e quantizzazione con llama.cpp)"
$PhiSrc = Join-Path $DlDir 'phi-4-mini-instruct'
New-Item -ItemType Directory -Force -Path $PhiSrc | Out-Null
foreach ($f in ($Conf.PHI_HF_FILES -split '\s+')) {
    Hf-Download $Conf.PHI_HF_REPO $Conf.PHI_HF_REVISION $f (Join-Path $PhiSrc $f)
}
Check-Sha (Join-Path $PhiSrc 'model-00001-of-00002.safetensors') $Conf.PHI_SOURCE_SHA256_1
Check-Sha (Join-Path $PhiSrc 'model-00002-of-00002.safetensors') $Conf.PHI_SOURCE_SHA256_2

$Vendored = Join-Path $RepoRoot 'core-native\third_party\llama.cpp'
if (Test-Path $Vendored) { $LlamaDir = $Vendored; Write-Host "uso la copia di llama.cpp del motore: $LlamaDir" }
else { $LlamaDir = Join-Path $WorkDir 'llama.cpp'; Clone-At $Conf.LLAMA_CPP_REPO $Conf.LLAMA_CPP_REF $LlamaDir }
$LlamaCommit = (git -C $LlamaDir rev-parse HEAD 2>$null); if (-not $LlamaCommit) { $LlamaCommit = 'sconosciuto' }

$Venv = Join-Path $WorkDir 'venv'
$VenvPy = Join-Path $Venv 'Scripts\python.exe'
if (-not (Test-Path $VenvPy)) { Run-Py -m venv $Venv }
$Req = Join-Path $LlamaDir 'requirements\requirements-convert_hf_to_gguf.txt'
if (-not (Test-Path $Req)) { Die "requisiti di conversione non trovati in $Req" }
Exec $VenvPy -m pip install --quiet --upgrade pip
Exec $VenvPy -m pip install --quiet -r $Req

$PhiInter = Join-Path $WorkDir "phi-4-mini-instruct-$($Conf.PHI_INTERMEDIATE_TYPE).gguf"
if (-not (Test-Path $PhiInter)) {
    Exec $VenvPy (Join-Path $LlamaDir 'convert_hf_to_gguf.py') $PhiSrc --outfile "$PhiInter.partial" --outtype $Conf.PHI_INTERMEDIATE_TYPE
    Move-Item -Force "$PhiInter.partial" $PhiInter
}
$LlamaBuild = Join-Path $WorkDir 'llama.cpp-build'
Exec cmake -S $LlamaDir -B $LlamaBuild -DLLAMA_CURL=OFF -DGGML_NATIVE=OFF
Exec cmake --build $LlamaBuild --config Release --target llama-quantize
$QLlama = Get-ChildItem -Recurse -File $LlamaBuild -Filter 'llama-quantize.exe' | Select-Object -First 1
if (-not $QLlama) { Die 'llama-quantize.exe non trovato' }
$PhiOut = Join-Path $OutDir $Conf.PHI_FILE
Exec $QLlama.FullName $PhiInter $PhiOut $Conf.PHI_QUANT

# ------------------------------------------------------------ Hash e versione
Log 'SHA-256 dei modelli finali'
$WhisperSha = Get-Sha $WhisperOut; $WhisperSize = Get-Size $WhisperOut
$PhiSha = Get-Sha $PhiOut; $PhiSize = Get-Size $PhiOut
Write-Host "$($Conf.WHISPER_FILE)  $WhisperSha  $WhisperSize byte"
Write-Host "$($Conf.PHI_FILE)  $PhiSha  $PhiSize byte"
$ModelsVersion = "whisper-medium-q5_0@$($WhisperSha.Substring(0,8))+phi-4-mini-q4_k_m@$($PhiSha.Substring(0,8))"   # Spec §13
Write-Host "Versione modelli: $ModelsVersion"

$Limit = [int64]$Conf.ANDROID_PACK_LIMIT_BYTES
$PartCount = [int]$Conf.PHI_PART_COUNT

# ------------------------------------------------------------ Android (Spec §7)
if (-not $SkipAndroid) {
    Log 'Asset pack Android'
    $AWhisper = Join-Path $RepoRoot 'android\models\src\main\assets\models'
    New-Item -ItemType Directory -Force -Path $AWhisper | Out-Null
    if ($WhisperSize -ge $Limit) { Die 'Whisper supera il limite del singolo asset pack' }
    Copy-Item -Force $WhisperOut (Join-Path $AWhisper $Conf.WHISPER_FILE)

    $SplitDir = Join-Path $WorkDir 'phi-parts'
    if (Test-Path $SplitDir) { Remove-Item -Recurse -Force $SplitDir }
    Run-Py $Tool split $PhiOut $SplitDir $PartCount | Out-Null
    $indexArgs = @('index', (Join-Path $AWhisper 'models-index.json'), '--models-version', $ModelsVersion,
        '--file', $Conf.WHISPER_FILE, $WhisperSha, "$WhisperSize",
        '--file', $Conf.PHI_FILE, $PhiSha, "$PhiSize")
    for ($i = 1; $i -le $PartCount; $i++) {
        $partName = "$($Conf.PHI_FILE).$i.part"
        $part = Join-Path $SplitDir $partName
        $psize = Get-Size $part
        if ($psize -ge $Limit) { Die "la parte $i ($psize byte) supera il limite: aumentare PHI_PART_COUNT e aggiungere un asset pack" }
        $module = Join-Path $RepoRoot "android\models\phi_$i"
        if (-not (Test-Path $module)) { Die "manca il modulo asset pack android/models/phi_$i" }
        $dest = Join-Path $module 'src\main\assets\models'
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Get-ChildItem $dest -Filter '*.part' | Remove-Item -Force
        Copy-Item -Force $part $dest
        $indexArgs += @('--part', $partName, "models_phi_$i", (Get-Sha $part), "$psize")
    }
    if (($WhisperSize + $PhiSize) -ge [int64]$Conf.ANDROID_INSTALL_TIME_LIMIT_BYTES) { Die 'i modelli superano il limite install-time di 4 GB' }
    Run-Py $Tool @indexArgs
    Write-Host "scritto $(Join-Path $AWhisper 'models-index.json')"
}

# ------------------------------------------------------------ Windows (Spec §7.1)
if (-not $SkipWindows) {
    Log 'Cartella Models del pacchetto Windows'
    $marker = Get-ChildItem -Recurse -Force -File (Join-Path $RepoRoot 'windows') -Filter '.zz-models-dir' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\(bin|obj)\\' } | Select-Object -First 1
    if ($marker) { $WDir = $marker.DirectoryName }
    else {
        $WDir = Join-Path $RepoRoot 'windows\Models'
        Write-Warning "nessun file .zz-models-dir sotto windows\: uso $WDir (vedi tools/models/README.md)"
    }
    New-Item -ItemType Directory -Force -Path $WDir | Out-Null
    Copy-Item -Force $WhisperOut (Join-Path $WDir $Conf.WHISPER_FILE)
    Copy-Item -Force $PhiOut (Join-Path $WDir $Conf.PHI_FILE)
    Write-Host "copiati in $WDir"
}

# ------------------------------------------------------------ Manifest nel codice (Spec §7)
Log 'Aggiornamento di ModelManifest.kt e ModelManifest.cs'
$umArgs = @('update-manifests', $RepoRoot, '--models-version', $ModelsVersion,
    '--model', $Conf.WHISPER_FILE, $WhisperSha, "$WhisperSize",
    '--model', $Conf.PHI_FILE, $PhiSha, "$PhiSize")
if ($Strict) { $umArgs += '--strict' }
Run-Py $Tool @umArgs

# ------------------------------------------------------------ models.lock (Spec §10, §13)
$tzId = if ($IsWindows -or $PSVersionTable.PSEdition -eq 'Desktop') { 'W. Europe Standard Time' } else { 'Europe/Rome' }
$now = [System.TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, $tzId).ToString('dd/MM/yyyy HH:mm')
$lock = @"
# Generato da tools/models/prepare-models.ps1 il $now (ora italiana). Non modificare a mano.
# Spec §7, §13: versione e integrità dei modelli inclusi nell'app.
MODELS_VERSION="$ModelsVersion"
WHISPER_FILE="$($Conf.WHISPER_FILE)"
WHISPER_SHA256="$WhisperSha"
WHISPER_SIZE="$WhisperSize"
WHISPER_SOURCE="https://huggingface.co/$($Conf.WHISPER_HF_REPO)@$($Conf.WHISPER_HF_REVISION) ($WhisperMode)"
WHISPER_TOOL="$WhisperToolInfo"
PHI_FILE="$($Conf.PHI_FILE)"
PHI_SHA256="$PhiSha"
PHI_SIZE="$PhiSize"
PHI_SOURCE="https://huggingface.co/$($Conf.PHI_HF_REPO)@$($Conf.PHI_HF_REVISION)"
PHI_TOOL="llama.cpp $LlamaCommit, convert_hf_to_gguf $($Conf.PHI_INTERMEDIATE_TYPE), llama-quantize $($Conf.PHI_QUANT)"
PHI_PART_COUNT="$PartCount"
"@
# LF e UTF-8 senza BOM, come la versione bash.
[System.IO.File]::WriteAllText((Join-Path $ScriptDir 'models.lock'), ($lock -replace "`r`n", "`n") + "`n", (New-Object System.Text.UTF8Encoding $false))
Write-Host "scritto $(Join-Path $ScriptDir 'models.lock')"

if (-not $KeepWork) {
    Remove-Item -Force -ErrorAction SilentlyContinue $PhiInter
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $WorkDir 'phi-parts')
}
Log "Fatto. Versione modelli: $ModelsVersion"
