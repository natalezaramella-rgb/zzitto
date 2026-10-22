<#
.SYNOPSIS
  Compila il motore Zzitto (core-native) come zz_engine.dll per Windows ed esegue i suoi test.

.DESCRIPTION
  Spec §4.3 (motore C++ condiviso, API C usata via P/Invoke), §11.1 (build native ARM64 e x64),
  §12 (test a ogni compilazione), §16.1 (motore finto finché l'agente A non consegna quello reale).
  Aggiunto il 22/10/2026 alle 11:32.

  Viene invocato automaticamente da msbuild/Zzitto.Native.targets prima della build dell'app e dei test.
  Compatibile con Windows PowerShell 5.1.

.PARAMETER Platform
  x64 oppure ARM64. Predefinita: l'architettura della macchina.
.PARAMETER Configuration
  Debug oppure Release.
.PARAMETER EngineMock
  ON (motore finto, predefinito) oppure OFF (motore reale).
.PARAMETER OutDir
  Cartella in cui copiare zz_engine.dll.
#>
[CmdletBinding()]
param(
    [ValidateSet('', 'x64', 'ARM64')]
    [string]$Platform = '',
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',
    [ValidateSet('ON', 'OFF')]
    [string]$EngineMock = 'ON',
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'

$windowsRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $windowsRoot
$engineSource = Join-Path $repoRoot 'core-native'

function Get-HostArchitecture {
    # Il registro riporta l'architettura reale anche se PowerShell gira in emulazione x64.
    try {
        $arch = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment' -Name PROCESSOR_ARCHITECTURE).PROCESSOR_ARCHITECTURE
    } catch {
        $arch = $env:PROCESSOR_ARCHITECTURE
    }
    if ($arch -eq 'ARM64') { return 'ARM64' }
    return 'x64'
}

function Find-CMake {
    $cmd = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $installPath = & $vswhere -latest -products * -property installationPath
        if ($installPath) {
            $candidate = Join-Path $installPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    throw 'CMake non trovato. Installare in Visual Studio il componente "Strumenti CMake C++ per Windows" oppure CMake 3.22+ nel PATH.'
}

function Get-VsGenerator {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path $vswhere)) { return $null }
    $line = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 Microsoft.VisualStudio.Component.VC.Tools.ARM64 -requiresAny -property catalog_productLineVersion
    switch ("$line".Trim()) {
        '2022' { return 'Visual Studio 17 2022' }
        '2026' { return 'Visual Studio 18 2026' }
        default { return $null }
    }
}

$hostArch = Get-HostArchitecture
if ([string]::IsNullOrEmpty($Platform)) { $Platform = $hostArch }

$buildDir = Join-Path $windowsRoot "build\native\$Platform"
if ([string]::IsNullOrEmpty($OutDir)) {
    $OutDir = Join-Path $windowsRoot "build\native-bin\$Platform\$Configuration"
}

# I test nativi si eseguono solo se la macchina può avviare gli eseguibili prodotti:
# ARM64 esegue anche x64 (emulazione), x64 non esegue ARM64. Spec §12.
$runTests = 'ON'
if ($hostArch -eq 'x64' -and $Platform -eq 'ARM64') { $runTests = 'OFF' }

# Più progetti (app e test) possono chiamare lo script in parallelo: serializziamo.
$mutex = New-Object System.Threading.Mutex($false, 'Local\ZzittoNativeEngineBuild')
[void]$mutex.WaitOne()
try {
    $cmake = Find-CMake
    Write-Host "Zzitto: CMake = $cmake"
    Write-Host "Zzitto: piattaforma $Platform (macchina $hostArch), configurazione $Configuration, mock $EngineMock, test $runTests"

    $configureArgs = @('-S', $engineSource, '-B', $buildDir, '-A', $Platform,
        "-DZZ_ENGINE_MOCK=$EngineMock",
        '-DZZ_BUILD_TESTS=ON',
        "-DZZ_RUN_TESTS_ON_BUILD=$runTests",
        # Runtime C++ statico (/MT): nessun runtime Visual C++ da ridistribuire nel pacchetto (richiesta agente E).
        '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded$<$<CONFIG:Debug>:Debug>',
        # DLL ed eseguibili di test nella stessa cartella, così i test trovano zz_engine.dll.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=$buildDir\bin")
    $cache = Join-Path $buildDir 'CMakeCache.txt'
    if (-not (Test-Path $cache)) {
        $generator = Get-VsGenerator
        if ($generator) { $configureArgs = @('-G', $generator) + $configureArgs }
    }

    & $cmake @configureArgs
    if ($LASTEXITCODE -ne 0) { throw "Configurazione CMake fallita ($LASTEXITCODE)" }

    & $cmake --build $buildDir --config $Configuration --parallel
    if ($LASTEXITCODE -ne 0) { throw "Compilazione o test di core-native falliti ($LASTEXITCODE)" }

    $dll = Join-Path $buildDir "bin\$Configuration\zz_engine.dll"
    if (-not (Test-Path $dll)) { throw "zz_engine.dll non trovata in $dll" }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Copy-Item -Path $dll -Destination (Join-Path $OutDir 'zz_engine.dll') -Force
    Write-Host "Zzitto: zz_engine.dll copiata in $OutDir"
}
finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
