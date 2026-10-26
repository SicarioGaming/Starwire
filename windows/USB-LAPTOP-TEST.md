# Test: a DualSense shared from a laptop over USB/IP

This test checks two things at once:

- that the Starwire USB helper can attach a device shared over the network, through the USB/IP
  driver this PC already has (usbip-win2, installed by Vibepollo), and detach it again;
- whether a DualSense's HD haptics still work when its USB connection travels over the network.

The DualSense is plugged into the laptop by cable. The laptop shares it with usbipd, this PC
attaches it, and Windows here sees a cabled DualSense. You play a game on this PC, streamed to the
laptop with Moonlight.

What has been tried so far: the helper's tests with a fake driver, and `--probe` on this PC,
which read the driver's version and its list of devices. The helper has never attached or
detached a real device. This test is the first time.

> **usbipd has no password.** While the pad is shared, anyone on the laptop's network who can
> reach TCP port 3240 can take it. Do this only on your home network, and unbind it as soon as
> you're done (step 13).

The driver runs inside Windows. usbip-win2 has had blue-screen bugs in older versions (issues
#172 and #181, fixed upstream). Save your work on this PC before step 7.

## You need

- The laptop, with Windows 10 or 11, on the same home network as this PC.
- The DualSense and a USB cable that carries data.
- Moonlight on the laptop, paired with this PC.
- A game whose PC version does HD haptics with a cabled DualSense, for example Ratchet & Clank:
  Rift Apart or Returnal.

## On the laptop

Use a terminal opened as administrator (right-click Terminal, Run as administrator).

1. Install usbipd-win:

       winget install usbipd

2. Plug the DualSense into the laptop by cable. List the USB devices:

       usbipd list

   Find the line for the pad (VID:PID `054c:0ce6`, usually named "Wireless Controller"). Its
   BUSID, like `2-1`, is the bus ID. Write it down. It changes if you use another USB port.

3. Check the laptop's network counts as Private:

       Get-NetConnectionProfile

   If NetworkCategory says Public, change it in Settings > Network & internet > your Wi-Fi or
   Ethernet > Network profile type > Private network.

4. Allow usbipd through the firewall on the Private network only. Its installer adds a rule named
   usbipd for TCP 3240 on every network profile, from the local subnet (from usbipd-win's
   Installer/Server.wxs). Limit it to Private, and check it:

       Set-NetFirewallRule -DisplayName usbipd -Profile Private
       Get-NetFirewallRule -DisplayName usbipd | Format-List DisplayName, Enabled, Profile, Direction, Action

   If there's no such rule, add one:

       New-NetFirewallRule -DisplayName usbipd -Direction Inbound -Protocol TCP -LocalPort 3240 -Profile Private -RemoteAddress LocalSubnet -Action Allow

5. Share the pad:

       usbipd bind --busid <bus-id>

   `usbipd list` now says Shared for it. The pad keeps working on the laptop until this PC
   attaches it.

6. Find the laptop's address: run `ipconfig` and note the IPv4 Address of the Wi-Fi or Ethernet
   adapter.

## On this PC

Use a normal PowerShell window (not as administrator) in the Starwire folder, on the branch
`feature/usb-pc-helper`.

7. Build the helper and run its tests:

       powershell -NoProfile -ExecutionPolicy Bypass -File windows\tests\run-usb-helper-tests.ps1

   It should end with "All passed." and put `StarwireUsbHelper.exe` in
   `%TEMP%\starwire-usb-helper-tests`. Nothing is installed.

8. Check the driver. This only reads; it changes nothing:

       cd $env:TEMP\starwire-usb-helper-tests
       .\StarwireUsbHelper.exe --probe | Out-Host

   It should say "That's usbip-win2 0.9.8.1, which the helper knows." If it names another
   version, stop here and send me the output. Don't update or reinstall the driver: Vibepollo
   uses it.

9. Attach the pad:

       .\StarwireUsbHelper.exe --debug-attach <laptop-ip> <bus-id> | Out-Host

   It prints each step. It should end with "Attached on port" and name the Sony DualSense. With
   a wrong address the driver can take a while to give up. On the laptop, `usbipd list` should
   now say Attached, and the pad stops working there.

10. Check Windows sees it here: the controller shows up in Device Manager under Human Interface
    Devices, and Sound settings should list the pad's own output (usually named Wireless
    Controller). Steam or the game should see a PS5 controller.

## The haptics test

11. On the laptop, open Moonlight and start a stream from this PC. The laptop no longer sees the
    pad, so Moonlight there won't send it a second time.

12. Start the game and play for a few minutes. Note:

    - whether buttons and sticks work, and how the delay feels;
    - whether you feel the HD haptics (the fine, textured buzz, not just rumble), and whether
      they stutter, lag behind the picture or cut out;
    - whether the adaptive triggers push back;
    - Wi-Fi or Ethernet on each side, and 5 GHz or 6 GHz Wi-Fi;
    - anything odd: crackling from the pad's speaker, the pad dropping out, Windows messages.

    If the haptics stutter, try the driver's other receive mode: detach (step 13, this PC's half
    only), then attach again with `--low-latency` added to step 9, and compare.

## Give it back

13. On this PC:

        .\StarwireUsbHelper.exe --debug-detach | Out-Host

    It should end with "Done: the helper has nothing attached now." It only detaches what the
    helper attached. If Vibepollo's virtual pad is attached, it's listed as another program's
    and left alone.

    Then on the laptop, as administrator:

        usbipd unbind --busid <bus-id>

    `usbipd list` says Not shared. If the pad doesn't work on the laptop again, unplug it and
    plug it back in.

14. If you won't use usbipd again, remove it with `winget uninstall usbipd`, or at least turn its
    firewall rule off: `Disable-NetFirewallRule -DisplayName usbipd`.

Send me the output of steps 8, 9 and 13 and your notes from step 12. The output shows the
laptop's local address and a serial number the helper made up, nothing else about you.

## If something goes wrong

- **"refused the connection"**: usbipd isn't running on the laptop (`Get-Service usbipd`), or
  its firewall blocks TCP 3240 (step 4).
- **"no answer ... in time"**: a wrong address, the two on different networks, or the laptop's
  network set to Public (step 3).
- **"shares no device with bus ID"**: bind it again (step 5). The bus ID changes if the pad
  moves to another USB port.
- **"isn't a home-network address"**: the helper only connects to private, link-local, VPN and
  loopback addresses. Use the laptop's local address from `ipconfig`.
- **You unbound on the laptop before detaching here**: this PC's driver keeps trying to
  reconnect the pad. Run step 13's detach anyway; it stops that.
- **This PC restarted while the pad was attached**: a restart detaches it. Step 13's detach then
  says it isn't attached any more and clears the helper's list.

The helper installs nothing. While something is attached it keeps a short list in
`%LOCALAPPDATA%\Starwire\usb-attached.txt`, and removes the file when nothing is.
