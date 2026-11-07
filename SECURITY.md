# Security

## Reporting a security problem

Please don't put the details in a public issue or discussion. Report it privately instead:

1. Go to the repo's [Security tab](https://github.com/SicarioGaming/Starwire/security) and choose
   **Report a vulnerability** (or use [this link](https://github.com/SicarioGaming/Starwire/security/advisories/new)).
2. Say which part of Starwire it's in (the Android app, the Windows microphone receiver or its
   setup), what you found and how to reproduce it. Only you and I can see the report.

I'll reply there. If you can't use GitHub's form, open an issue that only says you've found a
security problem, without the details, and I'll get in touch.

Fixes go into new releases, so please check the problem is still there in the
[latest release](https://github.com/SicarioGaming/Starwire/releases/latest).

## The microphone

This is about **Settings → Sound & vibration → Send my microphone** and the receiver that
`setup-microphone.cmd` installs on the PC.

From 0.2.1, with **Send my microphone to** set to **Starwire's receiver on the PC (encrypted)**,
which is the default, the audio is encrypted and authenticated, and the receiver only plays it
from devices you've paired with that PC. You pair each device once, with a 16-character code the
receiver shows. The code authenticates a key exchange, each stream gets its own AES-256-GCM key,
and every packet carries a counter that only goes up. The receiver refuses anything it can't
check: devices it hasn't paired, packets recorded and sent again, forged packets, and plain VBAN.
Both sides still only talk to private addresses (local network or VPN), but that's an extra
filter, not what keeps it safe: a UDP packet's sender address is easy to fake. The format, and
how it's tested, is in [docs/dev/mic-protocol.md](docs/dev/mic-protocol.md).

Before 0.2.1 shipped, the encryption had two separate security reviews. The first found three
medium problems. The second found one more: if the receiver restarted mid-stream, someone who had
recorded an earlier reply could send it again and make the app reuse an encryption key. All four
were fixed before the release, each with a test that fails without the fix.

What it doesn't protect against:

- Someone who sees the pairing code before you've typed it could pair instead of you, so don't
  show it while your screen is shared. If the receiver's window says **Paired with** a device
  before you've typed the code, or one that isn't yours, choose **Unpair it** there.
- A lost or stolen device that's still paired can send audio until you unpair it: right-click the
  receiver's icon by the clock, then **Paired devices**, the device and **Unpair**. On Linux:
  `starwire-mic unpair <id or name>`.
- The paired devices' keys are kept on the PC. On Windows, DPAPI encrypts them for your Windows
  account. Linux has nothing like DPAPI, so there they're kept in a file only your user can read
  (`~/.local/share/Starwire/mic-pairings.dat`, mode `0600`), not encrypted: anyone who can read
  your files, root included, can read the keys and pretend to be your device.
- Nothing can stop someone on the network from flooding it or jamming the Wi-Fi. The receiver only
  keeps that cheap.
- Malware on the Android device or the PC can reach the microphone directly.
- The packet sizes and timing are visible, as with any encrypted stream.

**Not encrypted**, another choice under **Send my microphone to** (for Voicemeeter and other
VBAN apps), sends plain VBAN, so anyone on the same network can listen to the microphone.

Starwire's receiver only plays plain VBAN if you set it up with `-AllowUnencrypted`. Then, as with
Voicemeeter or VBAN-Receptor, anyone on the network can send their own audio into the PC's virtual
mic so Discord or the game hears it as you, whichever option your device uses. The receiver's
firewall rule applies on every type of network Windows knows about, public Wi-Fi included. Use
the "Not encrypted" option and `-AllowUnencrypted` only on a network you trust.

Starwire 0.2.0 sent the microphone as plain VBAN, with no encryption or authentication. If you
still use 0.2.0, update both the app and the PC setup, then pair once. The
[microphone guide](windows/README.md#updating-from-starwire-020) has the steps.

## The custom Vulkan driver option

**Settings → Advanced → Custom Vulkan driver** is off by default. It's experimental, and it
hasn't been tried with a real Turnip driver yet.

When you turn it on and pick a driver file (`.so`, `.zip` or `.adpkg`) with Android's file
picker, Starwire copies that file into its own private storage. On 64-bit ARM devices with
Android 10 or newer, PyroWave streams then load it in place of the device's own Vulkan driver.
Other codecs keep using the system driver. Starwire never downloads a driver itself.

To load it, Starwire uses libadrenotools, whose hook libraries change how Android loads the
Vulkan driver inside Starwire's own process. Those libraries are in every 64-bit ARM APK, but
they're only loaded when the option is on and a driver has been picked.

A driver is native code. It runs inside Starwire with all of Starwire's permissions, including
the network, the microphone and the keys for your paired PCs. So only use a driver from a source
you trust. Starwire refuses files that aren't 64-bit ARM libraries, and goes back to the system
driver when a driver can't load or can't run PyroWave. A driver that loads and then crashes will
end the stream.

## Checking that a download is really Starwire

Every Starwire APK is signed with the same certificate. Its SHA-256 fingerprint is:

```
B6:04:1D:43:9F:28:4F:17:16:64:6F:C0:F9:80:F1:9A:76:17:8F:A7:04:D5:6F:50:34:D4:06:2A:D3:C1:3F:97
```

For [AppVerifier](https://github.com/soupslurpr/AppVerifier), the package name is `com.starwire`.
With the Android SDK's build tools, `apksigner verify --print-certs Starwire-0.2.0.apk` should show
`certificate SHA-256 digest: b6041d439f284f1716646fc0f980f19a76178fa704d56f5034d4062ad3c13f97`.
The [README](README.md#more-details) has more on this, including the Windows zip.

Android won't install an update over Starwire if it was signed with a different certificate, so
if it refuses, don't force it by uninstalling first. Check where the file came from.
