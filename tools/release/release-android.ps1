<#
.SYNOPSIS
  Build di rilascio Android su Windows: test, AAB release, firma con la chiave di upload, controlli, copia in dist\.
  Stessi passi di release-android.sh. Spec §7, §12, §13. Aggiunto il 22/10/2026 alle 11:29.

.DESCRIPTION
  Chiave di upload (Play App Signing), MAI nel repository. Variabili d'ambiente:
    ZZ_UPLOAD_KEYSTORE, ZZ_UPLOAD_KEY_ALIAS, ZZ_UPLOAD_STORE_PASSWORD, ZZ_UPLOAD_KEY_PASSWORD (facoltativa)
  oppure ZZ_SIGNING_PROPERTIES = percorso di un file KEY=valore fuori dal repository con le stesse chiavi.
  Facoltativa: BUNDLETOOL_JAR per validare il bundle e il versionCode.

.PARAMETER Device     Esegue anche i test strumentali su un dispositivo collegato.
.PARAMETER SkipTests  Solo per prove locali: l'AAB viene marcato NON-PUBBLICABILE.
#>
[CmdletBinding()]
param([switch]$Device, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Tools = Join-Path $RepoRoot 'tools'
function Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Die([string]$m) { throw "ERRORE: $m" }
function Exec { $exe = $args[0]; $a = @($args | Select-Object -Skip 1); & $exe @a; if ($LASTEXITCODE) { Die "$exe $($a -join ' ') (codice $LASTEXITCODE)" } }
$Py = @(if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3') } else { @('python') })
$PyExe = $Py[0]; $PyPre = @($Py | Select-Object -Skip 1)
function PyOut { $a = @($args); $o = & $PyExe @PyPre @a; if ($LASTEXITCODE) { Die "python $($a -join ' ')" }; $o }

# ---- Credenziali di firma (Spec §13; Spec §10: nessun segreto nel repository)
if ($env:ZZ_SIGNING_PROPERTIES) {
    if (-not (Test-Path $env:ZZ_SIGNING_PROPERTIES)) { Die 'ZZ_SIGNING_PROPERTIES non trovato' }
    foreach ($line in Get-Content $env:ZZ_SIGNING_PROPERTIES) {
        if ($line -match '^(ZZ_UPLOAD_KEYSTORE|ZZ_UPLOAD_KEY_ALIAS|ZZ_UPLOAD_STORE_PASSWORD|ZZ_UPLOAD_KEY_PASSWORD)=(.*)$') {
            Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2]
        }
    }
}
foreach ($v in 'ZZ_UPLOAD_KEYSTORE', 'ZZ_UPLOAD_KEY_ALIAS', 'ZZ_UPLOAD_STORE_PASSWORD') {
    if (-not (Get-Item "Env:$v" -ErrorAction SilentlyContinue)) { Die "variabile $v non impostata" }
}
if (-not $env:ZZ_UPLOAD_KEY_PASSWORD) { $env:ZZ_UPLOAD_KEY_PASSWORD = $env:ZZ_UPLOAD_STORE_PASSWORD }
$Ks = (Resolve-Path $env:ZZ_UPLOAD_KEYSTORE).Path
if ($Ks.StartsWith($RepoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { Die "il keystore è dentro il repository: spostalo fuori ($Ks)" }
if (-not (Get-Command jarsigner -ErrorAction SilentlyContinue)) { Die 'jarsigner non trovato (JDK 17 nel PATH)' }

# ---- Versione e changelog (Spec §13)
Step 'Versione e changelog'
$show = PyOut (Join-Path $Tools 'release\version.py') show
$VersionName = ($show | Where-Object { $_ -like 'VERSION_NAME=*' }) -replace '^VERSION_NAME=', ''
$VersionCode = ($show | Where-Object { $_ -like 'VERSION_CODE=*' }) -replace '^VERSION_CODE=', ''
PyOut (Join-Path $Tools 'release\version.py') check | Out-Null

# ---- Modelli e hash nel codice (Spec §7)
Step 'Modelli negli asset pack e hash nel codice'
$Lock = @{}
foreach ($line in Get-Content (Join-Path $Tools 'models\models.lock')) { if ($line -match '^([A-Z0-9_]+)="(.*)"$') { $Lock[$Matches[1]] = $Matches[2] } }
$A = Join-Path $RepoRoot 'android\models'
$W = Join-Path $A "src\main\assets\models\$($Lock.WHISPER_FILE)"
if (-not (Test-Path $W)) { Die "manca $W: eseguire tools\models\prepare-models.ps1" }
$Parts = @()
for ($i = 1; $i -le [int]$Lock.PHI_PART_COUNT; $i++) {
    $p = Join-Path $A "phi_$i\src\main\assets\models\$($Lock.PHI_FILE).$i.part"
    if (-not (Test-Path $p)) { Die "manca $p: eseguire tools\models\prepare-models.ps1" }
    $Parts += $p
}
$tool = Join-Path $Tools 'models\modeltool.py'
if ((PyOut $tool sha256 $W) -ne $Lock.WHISPER_SHA256) { Die 'hash di Whisper diverso da models.lock' }
if ((PyOut $tool sha256 @Parts) -ne $Lock.PHI_SHA256) { Die 'hash delle parti di Phi-4-mini diverso da models.lock' }
PyOut $tool check-manifests (Join-Path $RepoRoot 'android') --model $Lock.WHISPER_FILE $Lock.WHISPER_SHA256 $Lock.WHISPER_SIZE --model $Lock.PHI_FILE $Lock.PHI_SHA256 $Lock.PHI_SIZE

# ---- Test (Spec §12)
$Publishable = $true
if ($SkipTests) {
    Write-Warning "test saltati: l'AAB sarà marcato NON-PUBBLICABILE"
    $Publishable = $false
} else {
    & (Join-Path $PSScriptRoot 'run-tests.ps1') -RealEngine -Android -Device:$Device -SkipWindows
}

# ---- Build AAB (senza firma Gradle: la chiave non passa da Gradle)
Step 'Build AAB release'
Push-Location (Join-Path $RepoRoot 'android')
try { Exec .\gradlew.bat --no-daemon clean :app:bundleRelease } finally { Pop-Location }
$Unsigned = Get-ChildItem -Recurse (Join-Path $RepoRoot 'android\app\build\outputs\bundle\release') -Filter '*.aab' | Select-Object -First 1
if (-not $Unsigned) { Die 'AAB non trovato' }
if ((& jarsigner -verify $Unsigned.FullName 2>&1) -match 'jar verified') {
    Die "l'AAB è già firmato da Gradle: rimuovere il signingConfig release da app/build.gradle.kts (docs/release-guide.md)"
}

Step 'Firma con la chiave di upload'
$Dist = Join-Path $RepoRoot 'dist'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
$suffix = if ($Publishable) { '' } else { '-NON-PUBBLICABILE' }
$Out = Join-Path $Dist "zzitto-android-$VersionName-$VersionCode$suffix.aab"
Exec jarsigner -keystore $Ks '-storepass:env' ZZ_UPLOAD_STORE_PASSWORD '-keypass:env' ZZ_UPLOAD_KEY_PASSWORD `
    -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $Out $Unsigned.FullName $env:ZZ_UPLOAD_KEY_ALIAS
if (-not ((& jarsigner -verify $Out 2>&1) -match 'jar verified')) { Die 'verifica della firma fallita' }

Step 'Controlli del bundle (asset pack, limiti Google Play, motore reale)'
PyOut (Join-Path $Tools 'release\aabcheck.py') $Out
if ($env:BUNDLETOOL_JAR) {
    Exec java -jar $env:BUNDLETOOL_JAR validate --bundle $Out
    $vc = (& java -jar $env:BUNDLETOOL_JAR dump manifest --bundle $Out --xpath '/manifest/@android:versionCode').Trim()
    if ($vc -ne $VersionCode) { Die "versionCode nell'AAB ($vc) diverso da version.properties ($VersionCode)" }
}
(Get-FileHash -Algorithm SHA256 $Out).Hash.ToLowerInvariant() | Set-Content -NoNewline "$Out.sha256"
Step "Pronto: $Out"
Write-Host "Versione $VersionName ($VersionCode), modelli $($Lock.MODELS_VERSION)"
