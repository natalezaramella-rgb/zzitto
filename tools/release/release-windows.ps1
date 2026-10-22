<#
.SYNOPSIS
  Build di rilascio Windows: test, motore nativo ARM64 e x64, pacchetti MSIX per architettura, bundle .msixbundle
  per il Microsoft Store, controlli e copia in dist\.
  Spec §2.4, §7.1, §11.1, §12, §13. Aggiunto il 22/10/2026 alle 11:29.

.DESCRIPTION
  Requisiti: Windows 11, Visual Studio 2022 (carichi .NET desktop e C++ con strumenti ARM64), Windows App SDK,
  Windows SDK (makeappx.exe), CMake, Python 3.

  Il pacchetto non viene firmato: il Microsoft Store firma i pacchetti caricati in Partner Center.
  La versione del pacchetto è X.Y.Z.0 (la quarta cifra è riservata allo Store) e viene da tools\release\version.properties.

  Variabili facoltative:
    ZZ_WIN_APP_PROJECT  percorso del .csproj dell'app impacchettata (se non indicato: il .csproj accanto al
                        Package.appxmanifest sotto windows\).

.PARAMETER SkipTests     Solo per prove locali: il bundle viene marcato NON-PUBBLICABILE.
.PARAMETER SyncManifest  Scrive la versione X.Y.Z.0 nel Package.appxmanifest invece di fermarsi se è diversa.
#>
[CmdletBinding()]
param([switch]$SkipTests, [switch]$SyncManifest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Tools = Join-Path $RepoRoot 'tools'
$Archs = @('x64', 'ARM64')                      # Spec §7.1, §11.1
$StoreBundleLimit = 25GB                        # Microsoft Store: 25 GB per .msixbundle e per pacchetto (verificato il 22/10/2026)
function Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Die([string]$m) { throw "ERRORE: $m" }
function Exec { $exe = $args[0]; $a = @($args | Select-Object -Skip 1); & $exe @a; if ($LASTEXITCODE) { Die "$exe $($a -join ' ') (codice $LASTEXITCODE)" } }
$Py = @(if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3') } else { @('python') })
$PyExe = $Py[0]; $PyPre = @($Py | Select-Object -Skip 1)
function PyOut { $a = @($args); $o = & $PyExe @PyPre @a; if ($LASTEXITCODE) { Die "python $($a -join ' ')" }; $o }
function Sha([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() }

# ---- Strumenti
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { Die 'vswhere.exe non trovato: installare Visual Studio 2022' }
$MSBuild = & $vswhere -latest -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1
if (-not $MSBuild) { Die 'MSBuild non trovato' }
$hostDir = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' }
$MakeAppx = Get-ChildItem (Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin\*\*\makeappx.exe') -ErrorAction SilentlyContinue |
    Where-Object { $_.Directory.Name -in @($hostDir, 'x64') } |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
if (-not $MakeAppx) { Die 'makeappx.exe non trovato: installare il Windows SDK' }

# ---- Progetto dell'app e manifest (agente C)
if ($env:ZZ_WIN_APP_PROJECT) { $Proj = (Resolve-Path $env:ZZ_WIN_APP_PROJECT).Path }
else {
    $mf = Get-ChildItem -Recurse -File (Join-Path $RepoRoot 'windows') -Filter 'Package.appxmanifest' |
        Where-Object { $_.FullName -notmatch '\\(bin|obj)\\' } | Select-Object -First 1
    if (-not $mf) { Die 'Package.appxmanifest non trovato sotto windows\' }
    $Proj = (Get-ChildItem $mf.DirectoryName -Filter '*.csproj' | Select-Object -First 1).FullName
    if (-not $Proj) { Die "nessun .csproj accanto a $($mf.FullName)" }
}
$Manifest = Join-Path (Split-Path $Proj) 'Package.appxmanifest'
Write-Host "Progetto: $Proj"

# ---- Versione e changelog (Spec §13)
Step 'Versione e changelog'
$ver = PyOut (Join-Path $Tools 'release\version.py') appx-version
PyOut (Join-Path $Tools 'release\version.py') check | Out-Null
if ($SyncManifest) { PyOut (Join-Path $Tools 'release\version.py') sync-appx $Manifest }
PyOut (Join-Path $Tools 'release\version.py') check-appx $Manifest | Out-Null
[xml]$mx = Get-Content -Raw $Manifest
if ($mx.Package.Identity.Name -ne 'Zaramella.Zzitto') { Write-Warning "Identity Name = $($mx.Package.Identity.Name): deve coincidere con il nome riservato in Partner Center" }

# ---- Modelli (Spec §7.1)
Step 'Modelli nella cartella Models e hash nel codice'
$Lock = @{}
foreach ($line in Get-Content (Join-Path $Tools 'models\models.lock')) { if ($line -match '^([A-Z0-9_]+)="(.*)"$') { $Lock[$Matches[1]] = $Matches[2] } }
$marker = Get-ChildItem -Recurse -Force -File (Join-Path $RepoRoot 'windows') -Filter '.zz-models-dir' |
    Where-Object { $_.FullName -notmatch '\\(bin|obj)\\' } | Select-Object -First 1
$ModelsDir = if ($marker) { $marker.DirectoryName } else { Join-Path $RepoRoot 'windows\Models' }
foreach ($pair in @(@($Lock.WHISPER_FILE, $Lock.WHISPER_SHA256), @($Lock.PHI_FILE, $Lock.PHI_SHA256))) {
    $f = Join-Path $ModelsDir $pair[0]
    if (-not (Test-Path $f)) { Die "manca $f: eseguire tools\models\prepare-models.ps1" }
    if ((Sha $f) -ne $pair[1]) { Die "hash di $f diverso da models.lock" }
}
PyOut (Join-Path $Tools 'models\modeltool.py') check-manifests (Join-Path $RepoRoot 'windows') `
    --model $Lock.WHISPER_FILE $Lock.WHISPER_SHA256 $Lock.WHISPER_SIZE --model $Lock.PHI_FILE $Lock.PHI_SHA256 $Lock.PHI_SIZE

# ---- Test (Spec §12)
$Publishable = $true
if ($SkipTests) { Write-Warning 'test saltati: il bundle sarà marcato NON-PUBBLICABILE'; $Publishable = $false }
else { & (Join-Path $PSScriptRoot 'run-tests.ps1') -RealEngine }

# ---- Motore nativo per architettura (Spec §4.3, §11.1)
foreach ($arch in $Archs) {
    Step "Motore reale zz_engine.dll ($arch)"
    $b = Join-Path $RepoRoot "build\core-win-$arch"
    # I test del motore girano già in run-tests.ps1 sull'architettura della macchina; qui si compila soltanto.
    Exec cmake -S (Join-Path $RepoRoot 'core-native') -B $b -A $arch -DZZ_ENGINE_MOCK=OFF -DZZ_BUILD_TESTS=OFF
    Exec cmake --build $b --config Release
    $dll = Get-ChildItem -Recurse -File $b -Filter 'zz_engine.dll' | Select-Object -First 1
    if (-not $dll) { Die "zz_engine.dll ($arch) non trovata" }
    if ([IO.File]::ReadAllText($dll.FullName, [Text.Encoding]::ASCII).Contains('zzitto-engine MOCK')) { Die "zz_engine.dll ($arch) è il motore finto (Spec §16.1)" }
}

# ---- MSIX per architettura
$Stage = Join-Path $RepoRoot 'build\msix'
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Force -Path (Join-Path $Stage 'bundle') | Out-Null
foreach ($arch in $Archs) {
    Step "Pacchetto MSIX ($arch)"
    $out = Join-Path $Stage $arch
    Exec $MSBuild $Proj /restore /m '/p:Configuration=Release' "/p:Platform=$arch" `
        '/p:GenerateAppxPackageOnBuild=true' '/p:AppxBundle=Never' '/p:UapAppxPackageBuildMode=SideloadOnly' `
        '/p:AppxPackageSigningEnabled=false' "/p:AppxPackageDir=$out\" `
        "/p:ZzEngineDir=$(Join-Path $RepoRoot "build\core-win-$arch")"
    $msix = Get-ChildItem -Recurse -File $out -Filter '*.msix' | Where-Object { $_.Name -notmatch 'Dependencies' } | Select-Object -First 1
    if (-not $msix) { Die "MSIX $arch non trovato in $out" }
    # Controlli sul contenuto: modelli presenti e motore reale (Spec §7.1, §16.1)
    $zip = [IO.Compression.ZipFile]::OpenRead($msix.FullName)
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        foreach ($m in $Lock.WHISPER_FILE, $Lock.PHI_FILE) {
            if (-not ($names | Where-Object { $_ -like "*/$m" -or $_ -eq $m })) { Die "il pacchetto $arch non contiene $m" }
        }
        if (-not ($names | Where-Object { $_ -like '*zz_engine.dll' })) { Die "il pacchetto $arch non contiene zz_engine.dll" }
        if ($names | Where-Object { $_ -match '\.(pfx|pem|p12|jks|keystore|env)$' }) { Die "il pacchetto $arch contiene file di chiavi" }
    } finally { $zip.Dispose() }
    Copy-Item $msix.FullName (Join-Path $Stage "bundle\Zzitto_$($ver)_$arch.msix")
}

# ---- Bundle per lo Store
Step 'Bundle .msixbundle (ARM64 + x64)'
$Dist = Join-Path $RepoRoot 'dist'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
$suffix = if ($Publishable) { '' } else { '-NON-PUBBLICABILE' }
$Bundle = Join-Path $Dist "zzitto-windows-$ver$suffix.msixbundle"
Exec $MakeAppx.FullName bundle /d (Join-Path $Stage 'bundle') /p $Bundle /bv $ver /o
$size = (Get-Item $Bundle).Length
Write-Host ("Dimensione bundle: {0:N2} GB (limite Store {1:N0} GB)" -f ($size / 1GB), ($StoreBundleLimit / 1GB))
if ($size -ge $StoreBundleLimit) { Die 'il bundle supera il limite di 25 GB del Microsoft Store' }
Sha $Bundle | Set-Content -NoNewline "$Bundle.sha256"

Step "Pronto: $Bundle"
Write-Host "Versione $ver, modelli $($Lock.MODELS_VERSION). Caricare il bundle in Partner Center (docs/release-guide.md)."
