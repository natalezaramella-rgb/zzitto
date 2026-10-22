<#
.SYNOPSIS
  Esegue tutti i test prima di una build di rilascio su Windows — Spec §12 ("una build con test falliti non si pubblica").
  Niente CI (decisione del titolare, Spec §13 e capitolo 14). Aggiunto il 22/10/2026 alle 11:29.

.PARAMETER RealEngine   Compila e testa anche il motore reale (obbligatorio per il rilascio).
.PARAMETER Android      Esegue anche gradlew check (test unitari e lint Android).
.PARAMETER Device       Con -Android, esegue anche connectedCheck su un dispositivo collegato.
.PARAMETER SkipWindows  Non esegue i test .NET di windows/.
#>
[CmdletBinding()]
param([switch]$RealEngine, [switch]$Android, [switch]$Device, [switch]$SkipWindows)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
function Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
# Funzione semplice: argomenti come -S, -B, --config arrivano intatti in $args.
function Exec { $exe = $args[0]; $a = @($args | Select-Object -Skip 1); & $exe @a; if ($LASTEXITCODE) { throw "ERRORE: $exe $($a -join ' ') (codice $LASTEXITCODE)" } }
$Py = @(if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3') } else { @('python') })
$PyExe = $Py[0]; $PyPre = @($Py | Select-Object -Skip 1)

Step 'Test degli strumenti di rilascio (tools/tests)'
Exec $PyExe @PyPre -m unittest discover -s (Join-Path $RepoRoot 'tools\tests')

$hostArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'ARM64' } else { 'x64' }

Step "Motore finto (core-native, $hostArch; i test girano a ogni build)"
$b = Join-Path $RepoRoot "build\core-mock-$hostArch"
Exec cmake -S (Join-Path $RepoRoot 'core-native') -B $b -A $hostArch -DZZ_ENGINE_MOCK=ON
Exec cmake --build $b --config Release

if ($RealEngine) {
    Step "Motore reale (core-native, $hostArch)"
    $b = Join-Path $RepoRoot "build\core-real-$hostArch"
    Exec cmake -S (Join-Path $RepoRoot 'core-native') -B $b -A $hostArch -DZZ_ENGINE_MOCK=OFF
    Exec cmake --build $b --config Release
}

if (-not $SkipWindows) {
    $sln = Join-Path $RepoRoot 'windows\Zzitto.sln'
    if (Test-Path $sln) {
        Step "Windows: test .NET ($hostArch)"
        Exec dotnet test $sln -c Release "-p:Platform=$hostArch"
    } else {
        throw "ERRORE: $sln non trovato"
    }
}

if ($Android) {
    Step 'Android: test unitari e lint (gradlew check)'
    Push-Location (Join-Path $RepoRoot 'android')
    try {
        Exec .\gradlew.bat --no-daemon check
        if ($Device) { Exec .\gradlew.bat --no-daemon connectedCheck }
    } finally { Pop-Location }
}

Step 'Tutti i test superati'
