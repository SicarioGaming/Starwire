<#
.SYNOPSIS
Builds and runs the USB helper's tests: its driver side and its side of the USB tunnel.

.DESCRIPTION
Uses the C# compiler that comes with Windows, like the microphone setup, and runs each test program
twice: as a 64-bit and as a 32-bit program.

- StarwireUsbHelperTests: the driver side (StarwireUsbHelper.cs), against a fake driver.
- StarwireUsbTunnelTests: the tunnel, against the shared vectors and device rules' cases in
  vectors\. Also the pairings file's reads (with the
  microphone receiver's own MicPairingStore, SecureMic.cs), Allow USB, the waiting room, and
  the whole path on 127.0.0.1 with a fake driver. It makes TLS keys for this Windows account
  while it runs, and deletes them at the end.

Neither opens the real USB/IP driver or uses the network beyond 127.0.0.1. Then it builds the
helper itself, StarwireUsbHelper.exe, into the same folder, for the laptop test
(USB-LAPTOP-TEST.md). Nothing is installed.

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
$vectors = Join-Path $tests 'vectors'
New-Item -ItemType Directory -Force $OutDir | Out-Null

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Write-Host 'The C# compiler from .NET Framework 4 is missing.' -ForegroundColor Red
    exit 1
}

# What each program is built from
$helper = @('StarwireUsbHelper.cs', 'UsbTunnelCodec.cs') | ForEach-Object { Join-Path $windows $_ }
$tunnel = @('StarwireUsbHelper.cs', 'UsbTunnel.cs', 'UsbTunnelCodec.cs', 'UsbDeviceRules.cs', 'UsbRelay.cs', 'SecureMic.cs') | ForEach-Object { Join-Path $windows $_ }
$refs = @('/r:System.Windows.Forms.dll', '/r:System.Drawing.dll', '/r:System.Security.dll', '/r:System.Core.dll')

$failed = @()
foreach ($platform in 'x64', 'x86') {
    foreach ($suite in 'StarwireUsbHelperTests', 'StarwireUsbTunnelTests') {
        $exe = Join-Path $OutDir "$suite-$platform.exe"
        Write-Host "== Building $suite ($platform)" -ForegroundColor Cyan
        $files = @(Join-Path $tests "$suite.cs") + $(if ($suite -eq 'StarwireUsbHelperTests') { $helper } else { $tunnel })
        # The helper has its own Main; the tests' one runs. The microphone receiver's code warns
        # about fields only its own program sets.
        & $csc /nologo /optimize "/platform:$platform" "/main:$suite" /nowarn:649 $refs "/out:$exe" $files
        if ($LASTEXITCODE -ne 0) {
            $failed += "building $suite ($platform)"
            continue
        }
        Write-Host "== Running $suite ($platform)" -ForegroundColor Cyan
        # Only the failures and the total; every check's line is in the output file
        $log = Join-Path $OutDir "$suite-$platform.txt"
        & $exe $vectors > $log
        $code = $LASTEXITCODE
        Get-Content $log | Where-Object { $_ -notlike 'PASS *' }
        if ($code -ne 0) {
            $failed += "$suite ($platform)"
        }
    }
}

Write-Host '== Building the helper' -ForegroundColor Cyan
& $csc /nologo /optimize /target:winexe $refs "/out:$(Join-Path $OutDir 'StarwireUsbHelper.exe')" $helper
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
