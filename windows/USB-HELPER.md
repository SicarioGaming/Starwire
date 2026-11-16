# Sharing a USB device from the phone: the PC's USB helper

**Experimental, and tried with a tablet.** This is the PC's side. Starwire's side, which shares
the device from the phone, is the "USB devices (Experimental)" setting. The two were tried
together on 16 November 2026, with a debug build of 0.3.0 from that day, on a RedMagic Astra
tablet (Android 16) and a Windows 11 PC with usbip-win2's driver. setup-usb.cmd installed and
started the helper. A DualSense plugged into the tablet by cable was shared, the helper attached
it, Windows showed a working DualSense Wireless Controller, and it was used on the PC. Stopping
the share gave it back to the tablet all three times. On one of those stops, the pad reset on the
tablet first, so Starwire ended that share as if it had been unplugged; it still came back and
worked. That was before the helper got its own icon. setup-usb.cmd now builds the icon into the
helper and puts it next to it. Tests check that, but the icon hasn't been seen by the clock on a
PC yet. The helper's own tests run on Windows 11 with a fake driver and a test phone over
127.0.0.1.

The helper lets a phone running Starwire lend a USB device plugged into it (a pad, a wheel, a
headset) to this PC, so Windows sees it as if it were plugged in here. It uses usbip-win2's USB/IP
driver, the one Vibepollo's DualSense haptics use. How the phone and the helper talk is in
[usb-protocol.md](../docs/dev/usb-protocol.md).

## What you need

- **The microphone receiver, with the phone paired** ([README.md](README.md)). The helper uses
  that pairing: a phone shares USB only with a PC its microphone is paired with.
- **usbip-win2's driver, version 0.9.8.1.** The helper checks the driver's version and the
  SHA-256 of its file, and won't use another.

## Set it up

The helper's files are in **Starwire-Microphone-for-Windows.zip** on the
[release page](https://github.com/SicarioGaming/Starwire/releases), from 0.3.0 on, next to the
microphone's. Unzip it, then double-click **setup-usb.cmd** in that folder. It builds the helper
with the C# compiler that comes with Windows, installs it in `Program Files\Starwire USB`, lets it
through Windows Firewall on TCP 6981 (local-network and private VPN addresses, Private and Domain
networks only, not Public), and starts it now and when you sign in. It never changes the
microphone receiver or its setup.

Then right-click the helper's icon by the clock, a USB plug on a dark tile (it may be under the ^
arrow):

1. **Let paired phones share USB devices.** Off until you turn it on. While it's off, the helper
   doesn't listen at all.
2. **Allow USB for**, then tick your phone. Each paired phone needs its own tick. A phone that
   pairs again needs it again.

While a device is shared, the menu shows it and the phone, with **Stop sharing**. Unpairing the
phone in the microphone receiver's menu, or unticking it, ends the session within 10 s and gives
the device back.

To remove it: `setup-usb.cmd -Uninstall`. It gives back anything the helper attached, then
removes the helper, its firewall rule, its startup entry, its TLS key and its own files. The
microphone receiver and its pairings stay.

## What it keeps

In `%LOCALAPPDATA%\Starwire`, next to the microphone's `mic-pairings.dat`, which it only reads:

- `usb-allowed.dat`: the phones with Allow USB ticked.
- `usb-settings.txt`: whether USB sharing is on.
- `usb-attached.txt`: what it attached, so it only ever detaches those.
- `usb-helper-log.txt`: what happened, for finding problems. No keys go in it.

Its TLS key lives in a Windows key container for your account while it runs, and is deleted when
it stops.

## What it checks

Before a device reaches Windows: that the phone holds the pairing and has Allow USB, that the
device is one the device rules allow (pads, wheels, sticks and audio; keyboards and mice only if
you said yes on the phone), and that each descriptor Windows asks for is the one the phone
described. It keeps back the requests a device could use to choose its own Windows driver.
[usb-protocol.md](../docs/dev/usb-protocol.md) says what that doesn't protect against.

## Tests

    powershell -NoProfile -ExecutionPolicy Bypass -File windows\tests\run-usb-helper-tests.ps1

They need no driver and no network beyond 127.0.0.1, and install nothing.

## Credits

The driver's commands, their layouts and its error codes are adapted from
[usbip-win2](https://github.com/vadimgrn/usbip-win2) 0.9.8.1 by vadimgrn (BSD-2-Clause; its
notice is in `StarwireUsbHelper.cs`), and the helper finds the driver and reads its list the way
usbip-win2's own tool does. How it finds the port of a device it attached before a detach, and
waits for the detaches the driver has queued, follows
[Vibepollo](https://github.com/Nonary/Vibepollo)'s `dualsense_usbip_transport.cpp` (Chase Payne,
Nonary). The rest is new in Starwire.
