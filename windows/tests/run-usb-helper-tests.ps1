<#
.SYNOPSIS
Builds and runs the USB helper's tests: its driver side and its side of the USB tunnel.

.DESCRIPTION
Uses the C# compiler that comes with Windows, like the setups, and runs each test program twice:
as a 64-bit and as a 32-bit program.

- StarwireUsbHelperTests: the driver side (StarwireUsbHelper.cs), against a fake driver.
- StarwireUsbTunnelTests: the tunnel (UsbTunnel*.cs, UsbRelay.cs, UsbDeviceRules.cs) against the
  shared vectors and device rules' cases in vectors\, and the whole path on 127.0.0.1 with a fake
  driver. It uses the microphone receiver's own pairing store (SecureMic.cs) for its pairings.

Neither opens the real USB/IP driver or uses the network beyond 127.0.0.1. The tunnel's tests make
TLS keys for this Windows account while they run and delete them at the end.

Then it checks setup-usb.ps1 without running its install: what it would do (-Plan) with a
non-default port and folders in -OutDir, the firewall rule's parameters against New-NetFirewallRule's
own (read only), and that it never names the microphone receiver's files. Last, it builds the
helper itself, StarwireUsbHelper.exe, into -OutDir with setup-usb.ps1 -BuildOnly, for the laptop
test (USB-LAPTOP-TEST.md), and checks it has its icon. Nothing is installed.

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
$setup = Join-Path $windows 'setup-usb.ps1'
$vectors = Join-Path $tests 'vectors'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Write-Host 'The C# compiler from .NET Framework 4 is missing.' -ForegroundColor Red
    exit 1
}

$failed = @()
function Expect($what, $ok) {
    if ($ok) {
        Write-Host "ok: $what"
    }
    else {
        Write-Host "FAIL: $what" -ForegroundColor Red
        $script:failed += $what
    }
}

# The helper's sources, as setup-usb.ps1 builds them, so the tests build the same program
$plan = & powershell -NoProfile -ExecutionPolicy Bypass -File $setup -Plan -InstallDir (Join-Path $OutDir 'install') `
    -Port 16981 -StartupFolder (Join-Path $OutDir 'startup') -DataFolder (Join-Path $OutDir 'data') | Out-String | ConvertFrom-Json
$helper = @($plan.Sources)
$refs = @('/r:System.Windows.Forms.dll', '/r:System.Drawing.dll', '/r:System.Security.dll', '/r:System.Core.dll')

foreach ($platform in 'x64', 'x86') {
    foreach ($suite in 'StarwireUsbHelperTests', 'StarwireUsbTunnelTests') {
        $exe = Join-Path $OutDir "$suite-$platform.exe"
        Write-Host "== Building $suite ($platform)" -ForegroundColor Cyan
        $files = @(Join-Path $tests "$suite.cs") + $helper
        # The tunnel's tests pair and unpair with the microphone receiver's own store
        if ($suite -eq 'StarwireUsbTunnelTests') { $files += Join-Path $windows 'SecureMic.cs' }
        # The helper has its own Main; the tests' one runs. The receiver's code warns about fields
        # only its own program sets.
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

Write-Host '== Checking setup-usb.ps1 (nothing is installed)' -ForegroundColor Cyan
foreach ($script in $setup, $PSCommandPath) {
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$errors)
    Expect "$(Split-Path -Leaf $script) parses" ($errors.Count -eq 0)
}
$installDir = Join-Path $OutDir 'install'
$installedExe = Join-Path $installDir 'StarwireUsbHelper.exe'
$rule = $plan.Firewall
Expect 'the firewall rule is for the helper''s own program' ($rule.Program -eq $installedExe)
Expect 'the firewall rule is inbound TCP on the port asked for (16981 here)' ($rule.Direction -eq 'Inbound' -and $rule.Action -eq 'Allow' -and
    $rule.Protocol -eq 'TCP' -and $rule.LocalPort -eq 16981)
Expect 'the firewall rule is on Private and Domain networks only, not Public' ((@($rule.Profile) -join ',') -eq 'Private,Domain')
$homeRanges = @('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '169.254.0.0/16', '100.64.0.0/10', 'fc00::/7', 'fe80::/10')
Expect 'the firewall rule takes local-network and private VPN addresses only' ((@($rule.RemoteAddress) -join ',') -eq ($homeRanges -join ','))
Expect 'the firewall rule has a name of its own, not the microphone receiver''s' ($rule.DisplayName -eq 'Starwire USB helper')
Expect 'the startup entry passes the port, and goes in the startup folder asked for' ($plan.Arguments -eq '--tunnel-port 16981' -and
    $plan.Startup -eq (Join-Path (Join-Path $OutDir 'startup') 'Starwire USB helper.lnk'))
$removes = @($plan.Removes)
Expect 'an uninstall removes only the helper''s files, never mic-pairings.dat or the receiver' (
    -not ($removes | Where-Object { $_ -match 'mic-pairings|StarwireMicReceiver|microphone' }) -and
    @($plan.Keeps) -contains (Join-Path (Join-Path $OutDir 'data') 'mic-pairings.dat') -and
    $removes -contains (Join-Path (Join-Path $OutDir 'data') 'usb-allowed.dat'))
Expect 'an uninstall removes the icon the setup put next to the helper' ($removes -contains (Join-Path $installDir 'starwire-usb.ico'))
$default = & powershell -NoProfile -ExecutionPolicy Bypass -File $setup -Plan -InstallDir $installDir | Out-String | ConvertFrom-Json
Expect 'by default it listens on TCP 6981' ($default.Firewall.LocalPort -eq 6981 -and $default.Arguments -eq '--tunnel-port 6981')
$text = Get-Content $setup -Raw
Expect 'setup-usb.ps1 never names the microphone receiver''s program, setup script or firewall rule' (
    $text -notmatch 'StarwireMicReceiver|setup-microphone\.ps1|Starwire microphone receiver')
# New-NetFirewallRule takes every parameter the plan gives it, with these values. Only its
# description is read: nothing runs it.
$command = Get-Command New-NetFirewallRule -ErrorAction SilentlyContinue
if (-not $command) {
    Write-Host 'skipped: New-NetFirewallRule''s parameters (no NetSecurity module here)' -ForegroundColor Yellow
}
else {
    $bad = @()
    foreach ($p in $rule.PSObject.Properties) {
        if (-not $command.Parameters.ContainsKey($p.Name)) {
            $bad += "$($p.Name) isn't one of its parameters"
            continue
        }
        try {
            [void][Management.Automation.LanguagePrimitives]::ConvertTo($p.Value, $command.Parameters[$p.Name].ParameterType)
        }
        catch {
            $bad += "$($p.Name) = $($p.Value) doesn't fit its type"
        }
    }
    Expect "New-NetFirewallRule takes the rule as setup-usb.ps1 writes it$(if ($bad) { ': ' + ($bad -join '; ') })" ($bad.Count -eq 0)
}

Write-Host '== Building the helper as the setup does' -ForegroundColor Cyan
& powershell -NoProfile -ExecutionPolicy Bypass -File $setup -BuildOnly -InstallDir $OutDir
if ($LASTEXITCODE -ne 0) {
    $failed += 'building the helper'
}
else {
    $built = Join-Path $OutDir 'StarwireUsbHelper.exe'
    # A wrong option only prints the usage: nothing opened, nothing changed
    & $built --no-such-option | Out-Null
    Expect 'the helper runs, and a wrong option gets the usage (exit 1)' ($LASTEXITCODE -eq 1)
    # Its icon, in the program and next to it, where it looks first for the clock's small sizes.
    # It had none, so the clock showed Windows' plain program icon, which was hard to find.
    $icon = Join-Path $windows 'starwire-usb.ico'
    $builtIcon = Join-Path $OutDir 'starwire-usb.ico'
    Expect 'the helper''s icon is next to it' ((Test-Path $icon) -and (Test-Path $builtIcon) -and
        (Get-FileHash $builtIcon).Hash -eq (Get-FileHash $icon).Hash)
    if (-not (Test-Path $icon)) {
        Expect 'the helper has its own icon built in (there''s no windows\starwire-usb.ico)' $false
    }
    else {
        Add-Type -AssemblyName System.Drawing
        function Get-IconPixels($i) {
            # Drawn through its handle, as Windows draws it: Icon.ToBitmap misreads an icon file's PNG images
            $bitmap = [System.Drawing.Icon]::FromHandle($i.Handle).ToBitmap()
            $pixels = New-Object int[] ($bitmap.Width * $bitmap.Height)
            for ($y = 0; $y -lt $bitmap.Height; $y++) {
                for ($x = 0; $x -lt $bitmap.Width; $x++) { $pixels[$y * $bitmap.Width + $x] = $bitmap.GetPixel($x, $y).ToArgb() }
            }
            return , $pixels
        }
        # The icon Explorer shows for the program, against the same size from starwire-usb.ico
        $shown = [System.Drawing.Icon]::ExtractAssociatedIcon($built)
        $want = Get-IconPixels (New-Object System.Drawing.Icon($icon, $shown.Width, $shown.Height))
        $got = Get-IconPixels $shown
        $different = @(0..($want.Count - 1) | Where-Object { $got[$_] -ne $want[$_] }).Count
        Expect "the helper has its own icon built in, not Windows' plain program icon ($different of $($want.Count) pixels differ)" ($different -eq 0)
    }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'All passed.' -ForegroundColor Green
exit 0
