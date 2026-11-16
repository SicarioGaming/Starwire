<#
.SYNOPSIS
Sets up Starwire's USB helper, so a phone running Starwire can share a USB device with this PC.

.DESCRIPTION
- Builds the USB helper (StarwireUsbHelper.cs and the Usb*.cs files next to this script) with the
  C# compiler that comes with Windows, and installs it in Program Files\Starwire USB, with its
  icon (starwire-usb.ico).
- Lets it through Windows Firewall on TCP 6981, for local-network and private VPN addresses, on
  Private and Domain networks only. Not on Public networks, like a cafe's Wi-Fi.
- Starts it now and whenever you sign in. It runs as an icon by the clock: a USB plug on a dark
  tile.

USB sharing stays off until you turn it on in the icon's menu, and each phone also needs Allow
USB ticked there. A phone can only share with a PC its microphone is paired with, so set up the
microphone first (setup-microphone.cmd) and pair the phone there. The PC also needs usbip-win2's
driver, version 0.9.8.1; the helper checks it and says what it found.

This never changes the microphone receiver, its setup, its firewall rule or its pairings.

Run it from the folder you unzipped: double-click setup-usb.cmd. It asks for administrator
rights once, for Program Files and the firewall rule.

.PARAMETER Port
The TCP port the helper listens on while USB sharing is on. Default 6981.

.PARAMETER NoStartup
Don't start the helper when you sign in.

.PARAMETER Uninstall
Gives back any device the helper attached, then removes the helper and its icon, its firewall
rule, its startup entry, its TLS key and its own files (usb-allowed.dat, usb-settings.txt,
usb-attached.txt and its log). The microphone receiver and its pairings stay.

.PARAMETER InstallDir
Where the helper goes. Default Program Files\Starwire USB.

.PARAMETER BuildOnly
Only builds the helper into -InstallDir. No administrator rights, firewall rule or startup entry.
The tests use it.

.PARAMETER Plan
Says what the setup would do, as JSON, and changes nothing. The tests use it.
#>
param(
    [ValidateRange(1, 65535)][int]$Port = 6981,
    [switch]$NoStartup,
    [switch]$Uninstall,
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'Starwire USB'),
    [switch]$BuildOnly,
    [switch]$Plan,
    # Filled in when the script restarts itself as administrator, so the startup entry and the
    # helper's files are the account's that ran it rather than the administrator's
    [string]$StartupFolder = [Environment]::GetFolderPath('Startup'),
    [string]$DataFolder = (Join-Path $env:LOCALAPPDATA 'Starwire'),
    # Set on that restart: the account's own part of an uninstall is already done
    [switch]$AdminPart
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
$exe = Join-Path $InstallDir 'StarwireUsbHelper.exe'
$shortcut = Join-Path $InstallDir 'Starwire USB helper.lnk'
$startupShortcut = Join-Path $StartupFolder 'Starwire USB helper.lnk'
$ruleName = 'Starwire USB helper'
$sourceNames = @('StarwireUsbHelper.cs', 'UsbTunnel.cs', 'UsbRelay.cs', 'UsbTunnelCodec.cs', 'UsbDeviceRules.cs')
$sources = @($sourceNames | ForEach-Object { Join-Path $here $_ })
# Its icon goes in the program and next to it, where the helper looks first for the small sizes
# the clock uses. Without it the clock showed Windows' plain program icon.
$icon = Join-Path $here 'starwire-usb.ico'
$installedIcon = Join-Path $InstallDir 'starwire-usb.ico'
# The helper's own files. Never mic-pairings.dat, the microphone receiver's: the helper only reads it.
$dataFiles = @('usb-allowed.dat', 'usb-settings.txt', 'usb-attached.txt', 'usb-helper-log.txt', 'usb-helper-log.old.txt') |
    ForEach-Object { Join-Path $DataFolder $_ }

# The firewall rule: the helper's program, TCP, its port, from local-network and private VPN
# addresses, and on Private and Domain networks only. (The microphone's rule is on every profile.)
function Get-FirewallRule($program, $tcpPort) {
    [ordered]@{
        DisplayName   = $ruleName
        Description   = 'Starwire USB helper: lets a paired phone share a USB device with this PC, over TLS. Only paired phones with Allow USB ticked can share; the address and network limits are an extra layer.'
        Direction     = 'Inbound'
        Action        = 'Allow'
        Program       = $program
        Protocol      = 'TCP'
        LocalPort     = $tcpPort
        RemoteAddress = @('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '169.254.0.0/16', '100.64.0.0/10', 'fc00::/7', 'fe80::/10')
        Profile       = @('Private', 'Domain')
    }
}

function Get-Csc {
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
    if (Test-Path $csc) { return $csc }
    return $null
}

# A Windows app rather than a console one, so it runs as a notification-area icon with no window
function Build-Helper($out) {
    foreach ($s in $sources + $icon) {
        if (-not (Test-Path $s)) {
            Write-Host "$(Split-Path -Leaf $s) must be next to this script. Unzip the whole download and run it from there." -ForegroundColor Yellow
            return $false
        }
    }
    $csc = Get-Csc
    if (-not $csc) {
        Write-Host 'The C# compiler from .NET Framework 4 is missing. Turn on ".NET Framework 4.8" in Windows Features and run this again.' -ForegroundColor Yellow
        return $false
    }
    New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null
    Copy-Item $icon (Split-Path -Parent $out) -Force
    & $csc /nologo /optimize /target:winexe "/win32icon:$icon" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll /r:System.Core.dll "/out:$out" $sources | Out-Host
    return ($LASTEXITCODE -eq 0 -and (Test-Path $out))
}

# Only the installed copy, never another program or a test build somewhere else
function Stop-InstalledHelper {
    Get-Process StarwireUsbHelper -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and ([IO.Path]::GetFullPath($_.Path) -eq [IO.Path]::GetFullPath($exe)) } |
        Stop-Process -Force
    Start-Sleep -Milliseconds 300
}

if ($Plan) {
    [ordered]@{
        Exe          = $exe
        Sources      = $sources
        Firewall     = Get-FirewallRule $exe $Port
        Startup      = $(if ($NoStartup) { $null } else { $startupShortcut })
        Arguments    = "--tunnel-port $Port"
        Removes      = @($exe, $installedIcon, $shortcut, $startupShortcut) + $dataFiles
        RemovesRule  = $ruleName
        Keeps        = @(Join-Path $DataFolder 'mic-pairings.dat')
    } | ConvertTo-Json -Depth 4
    exit 0
}

if ($BuildOnly) {
    if (Build-Helper $exe) {
        Write-Host "Built: $exe"
        exit 0
    }
    exit 1
}

function Say($text, $color) { if ($color) { Write-Host "   $text" -ForegroundColor $color } else { Write-Host "   $text" } }
function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Done($text) { Say $text Green }
function Note($text) { Say $text Yellow }
function Finish($code) {
    Write-Host ''
    Read-Host 'Press Enter to close'
    exit $code
}

trap {
    Write-Host ''
    Write-Host "Setup stopped: $($_.Exception.Message)" -ForegroundColor Red
    Note 'Fix that and run the setup again. Nothing after this step was changed.'
    Finish 1
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
$admin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

# --- The account's own part of an uninstall, as that account: the helper, its devices, its key
# and its files are this account's, not the administrator's ---
if ($Uninstall -and -not $AdminPart) {
    Write-Host 'Starwire USB helper: uninstall' -ForegroundColor White
    Step 'Giving back the devices it attached, and removing its files'
    Stop-InstalledHelper
    if (Test-Path $exe) {
        & $exe --debug-detach | Out-Host
        & $exe --forget-key | Out-Host
    }
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
    $attached = Join-Path $DataFolder 'usb-attached.txt'
    foreach ($f in $dataFiles) {
        # A list that still names an attached device stays, so a later run can still detach it
        if ($f -eq $attached -and (Test-Path $f) -and ((Get-Content $f) | Where-Object { $_ -and -not $_.StartsWith('#') })) {
            Note "$f still lists a device the helper attached, so it stays. Unplug the device, or detach it in Device Manager."
            continue
        }
        Remove-Item $f -ErrorAction SilentlyContinue
    }
    if ((Test-Path $DataFolder) -and -not (Get-ChildItem $DataFolder -Force)) {
        Remove-Item $DataFolder -ErrorAction SilentlyContinue
    }
    Done 'Done. The microphone receiver and its pairings are untouched.'
}

# --- Administrator rights (Program Files and the firewall rule need them) ---
if (-not $admin) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
        '-Port', $Port, '-InstallDir', "`"$InstallDir`"", '-StartupFolder', "`"$StartupFolder`"", '-DataFolder', "`"$DataFolder`"")
    if ($NoStartup) { $arguments += '-NoStartup' }
    if ($Uninstall) { $arguments += @('-Uninstall', '-AdminPart') }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments
    exit
}

if ($Uninstall) {
    Step 'Removing the Starwire USB helper'
    Stop-InstalledHelper
    Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    # Only what the setup put there, then the folder if that leaves it empty
    Remove-Item $exe, $installedIcon, $shortcut -ErrorAction SilentlyContinue
    if ((Test-Path $InstallDir) -and -not (Get-ChildItem $InstallDir -Force)) {
        Remove-Item $InstallDir -ErrorAction SilentlyContinue
    }
    Done 'Removed, with its firewall rule. USB/IP drivers and the microphone receiver are untouched.'
    Finish 0
}

Write-Host 'Starwire USB helper setup' -ForegroundColor White
Stop-InstalledHelper

# --- Smart App Control blocks programs built on this PC, like the helper ---
$smartAppControl = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState -ErrorAction SilentlyContinue).VerifiedAndReputablePolicyState
if ($smartAppControl -eq 1) {
    Step 'Smart App Control is on'
    Note "Windows' Smart App Control only runs signed programs, and Starwire's USB helper is built on this PC, so Windows would block it."
    Finish 1
}

# --- Build and install ---
Step 'Installing the Starwire USB helper'
if (-not (Build-Helper $exe)) {
    Note 'Building the helper failed (see above).'
    Finish 1
}
Done "Installed $exe"

Step 'Checking the USB/IP driver'
& $exe --probe | Out-Host
if ($LASTEXITCODE -ne 0) {
    Note "The helper won't share devices until this PC has usbip-win2's driver, version 0.9.8.1. Install it from github.com/vadimgrn/usbip-win2/releases, then use Check the driver again in the helper's menu."
}

# --- Firewall ---
Step 'Letting the helper through Windows Firewall (Private and Domain networks, local addresses only)'
Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
$rule = Get-FirewallRule $exe $Port
New-NetFirewallRule @rule | Out-Null
Done "TCP port $Port, from local-network and private VPN addresses, on Private and Domain networks. Not on Public ones."

# --- Start it now and when you sign in ---
Step 'Starting the helper'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.Arguments = "--tunnel-port $Port"
$link.WorkingDirectory = $InstallDir
$link.Description = 'Lets a paired phone share a USB device with this PC'
$link.Save()
if ($NoStartup) {
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
}
else {
    Copy-Item $shortcut $startupShortcut -Force
    Done 'It starts by itself when you sign in.'
}
# Started through Explorer so it runs as you, not as administrator
Start-Process explorer.exe -ArgumentList "`"$shortcut`""
Done 'Running, with no window: its icon, a USB plug on a dark tile, is in the notification area by the clock. It may be under the ^ arrow.'

Step 'Next'
Say '1. Pair the phone with the microphone receiver first, if you haven''t: its icon > Pair a device.'
Say '2. Right-click the USB helper''s icon (the USB plug) > Let paired phones share USB devices.'
Say '3. In the same menu, Allow USB for > tick your phone.'
if ($Port -ne 6981) { Note "The helper listens on TCP $Port. Starwire connects to 6981." }
Finish 0
