<#
.SYNOPSIS
Builds and runs the tests of the USB helper's driver side (StarwireUsbHelper.cs).

.DESCRIPTION
Uses the C# compiler that comes with Windows, like the microphone setup, and runs the tests twice:
as a 64-bit and as a 32-bit program. The tests use a fake driver: they never open the real USB/IP
driver, and use no network. Then it builds the helper itself, StarwireUsbHelper.exe, into the
same folder, for the laptop test (USB-LAPTOP-TEST.md). Nothing is installed.

    powershell -NoProfile -ExecutionPolicy Bypass -File windows\tests\run-usb-helper-tests.ps1

Exits with 1 if anything fails.

.PARAMETER OutDir
Where the programs go. Default: a folder in TEMP.
#>
param(
    [string]$OutDir = (Join-Path $env:TEMP 'starwire-usb-helper-tests')
)

$ErrorActionPreference = 'Stop'
$tests = Split-Path -Parent $PSCommandPath
$windows = Split-Path -Parent $tests
New-Item -ItemType Directory -Force $OutDir | Out-Null

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Write-Host 'The C# compiler from .NET Framework 4 is missing.' -ForegroundColor Red
    exit 1
}

$failed = @()
foreach ($platform in 'x64', 'x86') {
    $exe = Join-Path $OutDir "StarwireUsbHelperTests-$platform.exe"
    Write-Host "== Building the tests ($platform)" -ForegroundColor Cyan
    # The helper has its own Main; the tests' one runs
    & $csc /nologo /optimize "/platform:$platform" /main:StarwireUsbHelperTests /r:System.Windows.Forms.dll /r:System.Drawing.dll `
        "/out:$exe" (Join-Path $tests 'StarwireUsbHelperTests.cs') (Join-Path $windows 'StarwireUsbHelper.cs')
    if ($LASTEXITCODE -ne 0) {
        $failed += "building the tests ($platform)"
        continue
    }
    Write-Host "== Running the tests ($platform)" -ForegroundColor Cyan
    # Only the failures and the total; every check's line is in the output file
    $log = Join-Path $OutDir "StarwireUsbHelperTests-$platform.txt"
    & $exe > $log
    $code = $LASTEXITCODE
    Get-Content $log | Where-Object { $_ -notlike 'PASS *' }
    if ($code -ne 0) {
        $failed += "the tests ($platform)"
    }
}

Write-Host '== Building the helper' -ForegroundColor Cyan
& $csc /nologo /optimize /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll `
    "/out:$(Join-Path $OutDir 'StarwireUsbHelper.exe')" (Join-Path $windows 'StarwireUsbHelper.cs')
if ($LASTEXITCODE -ne 0) {
    $failed += 'building the helper'
}
else {
    Write-Host "Built: $(Join-Path $OutDir 'StarwireUsbHelper.exe')"
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'All passed.' -ForegroundColor Green
exit 0
