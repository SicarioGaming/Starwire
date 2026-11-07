# Your microphone on a Linux PC, with any host

Starwire can send your Android device's microphone to the PC, so you can talk in Discord or
in-game voice chat while you stream. This works with **Vibepollo, Sunshine and Apollo**, with no
change to your streaming host. Starwire sends the microphone as VBAN, wrapped and encrypted, to a
small receiver on the PC, which plays it into a microphone called **Starwire microphone** that
every app can use.

This is the Linux version of the [Windows receiver](../windows/README.md). It speaks the same
encrypted format, from the same code (`../windows/SecureMic.cs`), so nothing changes in the app.
On Linux there's no driver to install: PipeWire lets the receiver be a microphone itself.

## Please read first

- **It's new.** It's been tried on one PC: CachyOS with KDE Plasma 6.8 and PipeWire 1.6.10, with
  a Samsung Galaxy Z Fold7 over the home network (see [What's been tested](#whats-been-tested)).
  It hasn't been tried on other distributions yet.
- **Don't show the pairing code while your screen is shared.** Anyone who sees it before you've
  typed it could pair instead of you.
- **Not encrypted**, one of the choices under **Send my microphone to** in Starwire, is for
  Voicemeeter and other VBAN apps. Starwire's receiver refuses it unless you set it up with
  `--allow-unencrypted` (see **Unencrypted VBAN** below).

[SECURITY.md](../SECURITY.md) says what the encryption protects against and what it doesn't.

## What you need

- **PipeWire.** KDE Plasma 6, GNOME and most current desktops use it. Check with
  `pw-cli info 0`: it prints PipeWire's version if it's running.
- **The .NET SDK, version 10 or later**, to build the receiver. On CachyOS or Arch:
  `sudo pacman -S dotnet-sdk`. On Fedora: `sudo dnf install dotnet-sdk-10.0`. Others:
  [Microsoft's guide](https://learn.microsoft.com/dotnet/core/install/linux).
- **Starwire's microphone receiver and its app**, in this folder. They're open source (GPL-3.0)
  and built on your PC from the `.cs` files here and `../windows/SecureMic.cs`. The app's window
  and tray icon are made with [Avalonia](https://avaloniaui.net/), which the setup downloads from
  NuGet the first time (about 30 MB), checked against `app/packages.lock.json`.

## Set up the PC (once)

1. Get Starwire's source. There's no Linux download on the release page yet, so clone it:

   ```bash
   git clone https://github.com/SicarioGaming/Starwire.git
   ```

2. Run the setup as yourself, not with sudo:

   ```bash
   ./Starwire/linux/setup-microphone.sh
   ```

   In this order, it:
   - stops the receiver if it's already running, so it's safe to run the setup again;
   - checks for the .NET SDK and PipeWire, and says how to get the SDK if it's missing;
   - builds the receiver and installs it in `~/.local/lib/starwire-mic`, with the command
     `starwire-mic` in `~/.local/bin`;
   - makes it a systemd user service that starts whenever you sign in, and starts it now. It has
     no window. Its microphone, **Starwire microphone**, is there from then on;
   - if ufw or firewalld is on, lets the receiver through on UDP port 6980, for local-network and
     private VPN (like Tailscale) addresses only. This asks for your password;
   - plays a test tone into the microphone and records it back, to check it works;
   - builds and installs the **Starwire microphone** app: it's in the app menu, its icon is in the
     system tray from then on, and it starts there whenever you sign in. It opens now;
   - shows the next steps and this PC's IP addresses.
3. In Discord or the game, choose **Starwire microphone** as the microphone. It's in KDE's System
   Settings > Sound too.

## Pair Starwire with the PC (once)

The receiver only plays a device's microphone once you've paired that device with the PC.

1. On the PC, open the **Starwire microphone** app (or click its tray icon), choose **Pair a
   device...**, then **Show the code**. The window shows a code of 16 letters and numbers, like
   `2491-650N-2RBH-G690`. (In a terminal, `starwire-mic pair` does the same.)
2. In Starwire on your Android device: **Settings → Sound & vibration → Send my microphone →
   Pair with the PC**. Pick the PC and type the code. Capitals, spaces and dashes don't matter.
3. The window says **Paired with** your device, and the address it paired from. That's it: the
   device stays paired.

The code works once, for 5 minutes. After 10 wrong codes it stops working; choose **Pair a
device...** again for a new one. Closing the window (or pressing Ctrl+C in the terminal) before
you've typed it stops it working too. Pair each device
you use (a phone and a tablet, say) the same way. If two paired devices send at once, the first one
keeps the microphone until it has been quiet for 3 seconds.

Anyone who sees the code before you've typed it could pair instead of you, so don't show it while
your screen is shared. If the window says **Paired with** a device before you've typed the code, or
one that isn't yours, choose **Unpair it** there. And if a second device uses the same code, the PC
undoes the pairing and a notification says the code was used twice; pair again for a new code.

To unpair a device (if it's lost, say): choose **Unpair** next to it in the app's window, or in the
tray icon's menu under **Paired devices**. In a terminal, `starwire-mic devices` lists them and
`starwire-mic unpair <id or name>` unpairs one. It can't send its microphone to the PC again until
you pair it again, and the receiver's log says it was unpaired.

## Set up Starwire

**Settings → Sound & vibration**:

1. Turn on **Send my microphone**.
2. Check **Send my microphone to** says **Starwire's receiver on the PC (encrypted)**. It's the
   default.
3. Pair with the PC, as above, if you haven't yet.

The microphone starts with each stream. The port (`6980`) only needs changing if you changed it
on the PC too.

## The app

The **Starwire microphone** app shows what the receiver is doing (receiving from your device,
waiting, or not running), whether the microphone is ready, and your paired devices. Its buttons, and
its tray icon's menu, pair a device, unpair one, check the microphone, play a test tone, and show
the receiver's log. Closing the window leaves the microphone working; **Quit** in the tray's menu
closes the app too, and the receiver still runs. **Stop the receiver...** stops the microphone until
you start it again or sign in again.

The app only talks to the receiver, the way the commands below do: without it (setup with
`--no-app`), everything still works from a terminal.

## The commands

| Command | What it does | On Windows, in the icon's menu |
| --- | --- | --- |
| `starwire-mic pair` | Shows a pairing code and waits for a device to use it | Pair a device... |
| `starwire-mic devices` | Lists the paired devices, when each was paired and last used | Paired devices |
| `starwire-mic unpair <id or name>` | Unpairs a device | Paired devices > the device > Unpair |
| `starwire-mic status` | What the receiver is doing, its counts and its audio buffer | Hovering over the icon |
| `starwire-mic check` | Plays a tone into the microphone and records it back | Check the virtual microphone |
| `starwire-mic tone [seconds]` | Plays a tone into the microphone, for Discord's mic test | Play a test tone |
| `systemctl --user stop starwire-mic` | Stops it until you next sign in | Quit |

Problems show up as desktop notifications, like audio from a device that isn't paired, or
unencrypted audio from Starwire 0.2.0. Each kind shows at most once every 10 minutes, saying how
many other addresses it came from in between. Everything the receiver does is in its log:
`journalctl --user -u starwire-mic -f`.

## Details and fixes

- **Encrypted, and only from paired devices.** As on Windows: each packet is encrypted and signed
  with a key made fresh for each stream from the key the device and the PC agreed on when they
  paired. The receiver plays nothing it can't check, refuses packets that are recorded and sent
  again, and never trusts an address on its own. As an extra layer it only listens to
  local-network and private VPN addresses, and so do its firewall rules.
- **Where the paired devices are kept:** `~/.local/share/Starwire/mic-pairings.dat`. Linux has no
  equivalent of Windows' DPAPI, so the keys aren't encrypted in that file. Instead only your user
  can read it (the file is `0600`, its folder `0700`), the way SSH keys are kept. Anyone who can
  read your files, root included, can read the keys. If you think someone copied that file, unpair
  your devices and pair them again.
- **The commands talk to the receiver over a socket** in `$XDG_RUNTIME_DIR/starwire-mic`, a folder
  only your user can open, and the receiver checks that whoever connects is your user.
- **Unencrypted VBAN, if you really need it.** Starwire's receiver doesn't play plain VBAN (from
  Starwire 0.2.0, Voicemeeter or other VBAN apps) unless you run the setup with
  `--allow-unencrypted`. That isn't safe: then anyone on the same network can send sound into the
  PC's microphone, and anyone who can see the network's traffic can listen to it. Run the setup
  again without it to turn it off.
- **Turning it off.** `systemctl --user stop starwire-mic` stops it until you next sign in. To stop
  it starting at sign-in, run the setup again with `--no-startup`. `--uninstall` removes it
  completely, with its firewall rules and the list of paired devices.
- **Troubleshooting:** `starwire-mic status` says what it's doing, and
  `journalctl --user -u starwire-mic -f` shows its log as it goes. To run it in a terminal instead,
  stop the service and run `starwire-mic --stats`, which prints the audio buffer's state every few
  seconds. Add `--pair` to have it print a pairing code too.
- **Delay and gaps:** the receiver keeps the Windows receiver's jitter buffer rules, measured on
  top of PipeWire's quantum (the audio PipeWire takes at once, usually 1024 samples, 21 ms): it
  builds up 40 ms more than a quantum before playing, never holds more than 120 ms more, and trims
  the delay back when the phone's clock runs fast. That rides out about 64 ms with no packets, for
  about a quantum more delay than on Windows. PipeWire only takes audio while an app records from
  the microphone, so while none does, the receiver keeps only the newest 40 ms or so: an app that
  starts recording mid-stream (joining a Discord call, say) hears you as you are now. Each gap is in
  the log with how long no packet came, and `starwire-mic status` counts every wait for packets of
  20, 40, 60 and 100 ms or more. The delay on Linux hasn't been measured yet.
- **Port already in use?** Another VBAN app may be using port 6980. The receiver says so in its log
  and in a notification, and stops. Close the other app, or run the setup with `--port 6990` and
  set the same port in Starwire.
- **PipeWire's own VBAN module isn't needed.** It can only take the unencrypted stream, and this
  receiver takes the encrypted one.
- **Using logabell's Apollo build?** Then you don't need any of this: choose **The PC's streaming
  app** instead, and the microphone goes over the stream itself.

## What's been tested

`linux/tests/run-tests.sh` builds everything and runs:

- **The Windows receiver's own tests of the format** (`windows/tests/SecureMicTests.cs`, unchanged
  apart from the Windows-only recording helper it leaves out), built for Linux: 214 checks,
  including every line of the vectors file the Android app is tested against, byte for byte.
- **The Linux parts' tests** (`tests/LinuxTests.cs`): the format the receiver asks PipeWire for
  matches what PipeWire's own C builder makes, byte for byte; the jitter buffer's rules with a fake
  clock (a phone 0.1% fast, a late packet with PipeWire's longer quanta, a burst); AES-GCM leaving
  nothing behind on a forged packet; and the paired devices file's permissions.
- **End to end:** a real receiver on this PC only (its own port, pairings file and microphone name),
  and a test phone (`tests/FakePhone.cs`) that pairs with the code it prints and streams 4 seconds
  of a tone in real time. The receiver must play every packet, the audio it plays must match what
  was sent byte for byte, and it must refuse a replayed packet, a wrong code and a device that isn't
  paired. The tone is recorded back from the PipeWire microphone, and must come out at the level it
  was sent at.
- **The app:** what it shows for each answer the receiver gives (pairing, status, devices) is tested
  without a screen, and the end-to-end test watches the receiver the way the app does, seeing the
  device pair and its audio come. The app's build must match `app/packages.lock.json`. On the CachyOS PC
  below, the Galaxy Z Fold7 paired through the app's pairing window and then sent its microphone.

On CachyOS (KDE Plasma 6.8, PipeWire 1.6.10, .NET 10) all of that passes.

**With a real phone:** on that PC, with ufw on, the setup installed and started the receiver, a
Samsung Galaxy Z Fold7 (SM-F966U1) paired with `starwire-mic pair`, and its microphone played for
about 96 seconds (18,078 packets, encrypted). The receiver refused nothing and nothing was forged.
In that time the audio buffer ran dry 7 times with PipeWire's 1024-sample quantum: 3 of them were
streams ending, and 4 were gaps. A second stream of about 56 seconds, with the receiver counting
how long each wait for packets was, showed what they were. The phone's packets came in clumps
(1,806 waits of 20 to 40 ms, none between 40 ms and 2 seconds), and there was one 2-second outage,
which no buffer covers. The rest came from Discord not recording from the microphone for a while:
the buffer filled with old audio, the newer packets were dropped (6,578 of them), and once Discord
recorded, the receiver trimmed the old audio back 10 ms at a time, 5 skips in a row. Now only the
newest audio is kept while no app records, and the buffer holds a quantum more, since the clumps
came close to the 40 ms it rode out before.

A third stream, of about 31 seconds, joined Discord partway through: about 16 seconds with no app
recording, then Discord. It had no gaps and no trims. The longest wait for packets was 45 ms,
which the old buffer (about 40 ms) wouldn't have ridden out and this one did. 30 packets were
dropped in the moment after Discord stopped recording, while the phone still sent; nobody was
recording them.

Not tried yet: firewalld's rules, other distributions, and measuring the delay.
