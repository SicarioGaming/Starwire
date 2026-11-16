# The USB tunnel: SWUT version 1

Starwire is to share a wired USB device plugged into the phone with the PC it streams from, so
Windows sees the real pad, wheel or headset as if it were plugged in there. The device's traffic
goes from a USB/IP exporter inside the app, through an encrypted tunnel called SWUT ("Starwire USB
tunnel"), version 1, to a USB helper on the PC, which hands it to the usbip-win2 driver. This file
is the contract between the two sides: the app's tunnel
(`app/src/main/java/com/limelight/binding/usb/tunnel/`) and the helper
(`windows/StarwireUsbHelper.cs`). Both are to be tested against the same vectors file, byte for
byte.

**Status.** Written before the code. It has had one security review round on the contract (Rv-1),
whose findings are fixed here, as well as separate reviews of the tunnel, the exporter and the
helper. The maintainer signed off on this design on 16 November 2026 for 0.3.0, where USB devices
ships as Experimental, and it can still change in later versions. The frames, keys and MACs exist (`SwutCodec.java`), and so does Starwire's end of the
tunnel (`UsbTunnel.java` and `SwutTls.java`: TLS, BIND, SHARE, the relay to the exporter, and
each way a session ends). Starwire's copy of the device rules (`UsbDeviceRules.java`) decides
every one of their cases, and the exporter (`UsbExporter.java`) applies them before it exports
anything. The app's JVM tests check all of this against the vectors and the rules' cases, and
Starwire's end of the tunnel against a fake helper and a fake exporter on 127.0.0.1. The stream
menu's USB devices page drives the two together (`RealUsbShareBackends.java`), and
`UsbShareWiredTest` runs that whole path, HELLO to END, against a fake helper and a stand-in for
the exporter's native part on 127.0.0.1. The helper's side is in code too (`windows/`): the TLS
server, BIND against the receiver's pairings, the per-phone Allow USB list, the device rules, the
relay and the USB/IP checks, and each way a session ends. Its tests run on Windows against the
shared vectors and device rules' cases, and drive the whole path over 127.0.0.1 with a fake driver
and a test phone. On 16 November 2026, the maintainer tried the app and the real helper together
for the first time: a USB device plugged into a RedMagic Astra tablet (Android 16) was shared
with a Windows 11 PC through the real usbip-win2 driver, worked there, and was given back when it
was selected again. That run used a test build from before the fix for a device unplugged at the
moment sharing starts (END 2, below). The helper's branch had drifted from `feature/usb-tunnel`'s copy of this
contract (the waiting-room rule and the split of the pairing-file reads below); the two are to be
reconciled on `feature/usb-tunnel`, which holds the canonical contract.

## In plain words

**Sharing a device.** In a stream you plug a device into the phone and choose it in Starwire's
menu. Starwire hands it to the exporter, the tunnel carries its traffic to the helper, and the
helper attaches it with the usbip-win2 driver through a connection that never leaves the PC.
Choosing Stop, unplugging it or ending the stream gives it back to the phone. One device is shared
at a time.

**No new pairing.** The tunnel uses the microphone's pairing ([mic-protocol.md](mic-protocol.md)).
A phone paired with the PC's microphone receiver can share USB devices with that PC once USB is
turned on in the helper's menu and **Allow USB** is ticked for that phone there. Both are off
until you turn them on. Each time Starwire connects, the helper reads the receiver's pairings
file again, works out a USB key from that device's pairing key, and wipes the pairing key from
memory. It never writes the file. So the microphone's pairing counts for much more than before:
with USB allowed, a paired phone can plug devices into the PC, and Windows runs their drivers in
its kernel. Unpairing it in the receiver's menu takes both away.

**Each connection** is TLS with a fresh key exchange (ECDHE), so recorded traffic stays secret
even if a pairing key leaks later. That matters because a shared keyboard carries whatever you
type. Both sides show a certificate they made themselves. Neither certificate means anything on
its own: what makes them count is the BIND. Starwire proves it knows the USB key with a MAC over
both certificates' fingerprints and a random number the helper just sent, and the helper proves
the same back. Someone sitting in the middle has to show each side a certificate of their own, so
the fingerprints differ between the two sides, and without the key they can't make a MAC that
fits.

**What can be shared.** Pads, wheels, sticks and audio devices. Keyboards and mice only if you say
yes on the phone, each time. Devices that say they're storage, network adapters or hubs, and
vendor-specific devices from vendors the rules don't list, are refused, with the reason. Starwire
checks before it asks the PC. The helper checks again, and compares each descriptor Windows asks
for with the one Starwire described, before Windows gets it. It also keeps back the Microsoft
descriptors and vendor requests a device could use to choose its own Windows driver. What the
checks can't catch is a device that lies about its vendor and product ID, since Windows ties some
drivers to those IDs, a few storage drivers among them.

**What it doesn't protect against.**

- A stolen phone that's still paired and unlocked can share what the rules allow, keyboards and
  mice included (it can say yes itself), until you unpair it in the receiver's menu. It could
  already stream and type on the PC through its pairing with the host, so unpair it there too.
- Code that can read the phone's pairing key: root malware, or a custom Vulkan driver, which runs
  inside Starwire with its keys. It doesn't need Starwire's checks or your yes, and anything the
  helper lets through reaches drivers that run in Windows' kernel. The helper's own checks are
  all that stands in its way (see "Code that holds the pairing").
- A hostile USB device. The rules narrow what reaches Windows, but Windows still reads parts of a
  shared device the helper doesn't check (see "USB/IP inside the tunnel"), and the drivers that
  load read whatever it sends. And Windows picks some drivers by vendor and product ID, so a
  device that lies about its IDs can reach another device's driver, a storage one included (see
  "What the rules can't see").
- Malware on the PC, which can reach USB devices directly.
- Someone on the network can block, slow or flood the tunnel, and can see the sizes and timing of
  its packets, as with any encrypted connection. With a keyboard shared, the timing can hint at
  when keys were pressed.
- A device's firmware being updated by its vendor's own commands. Starwire refuses devices with a
  standard firmware update interface (DFU), but a vendor's own update commands look like any
  other traffic.

**Addresses are not trusted.** Starwire only connects to the PC's home-network or private VPN
address, the helper only answers those, and the helper's firewall rule covers private and domain
networks only, not public Wi-Fi (unlike the microphone's rule). That's an extra filter. The
security comes from the keys. With USB off in its menu, the helper doesn't listen at all.

## How it fits together

```
phone:  USB device -- exporter (127.0.0.1) -- Starwire
                                                 |  TLS over TCP, port 6981
PC:                                            helper -- relay (127.0.0.1) -- usbip-win2 -- Windows
```

- **The exporter** is usbipdcpp inside the app. It listens on 127.0.0.1 only, and that isn't enough
  on its own, since every app on the phone can reach 127.0.0.1. So it takes only the connection
  Starwire authorised: Starwire binds its socket to a free port first, tells the exporter that
  exact source port, then connects. The exporter uses the authorisation once and closes any other
  connection. This is how Moonlight V+'s USB backend does it (qiin2333/moonlight-vplus,
  `usbip-backend`).
- **The helper** listens on TCP 6981 by default (the microphone uses UDP 6980), and only while USB
  is on in its menu.
- **The relay** is part of the helper. For each attach it listens on a new port on 127.0.0.1 and
  takes one connection, only while its own attach is in progress. It also checks that the
  connection belongs to the System process (process 4), since the driver connects from the
  kernel. That last check is still to be confirmed on a real attach.
- **Inside the tunnel** is plain USB/IP, as the Linux kernel's USB/IP protocol document describes
  it, so the exporter and the driver talk as if they were connected directly. The relay reads it
  on the way and enforces the checks below.

## The bytes

### Conventions

- Starwire's own numbers are little-endian, as in the microphone. USB/IP's own messages, carried
  in DATA frames, keep their big-endian numbers and pass through unchanged.
- `HMAC`, `MAC16`, `HKDF`, labels and `||` are as in [mic-protocol.md](mic-protocol.md)
  ("Conventions"): HMAC-SHA256, its first 16 bytes, HKDF-SHA256 giving 32 bytes, ASCII labels
  with no terminator. MACs are always compared in constant time.
- A certificate's **fingerprint** is SHA-256 of its DER bytes, exactly as TLS sent them.
- Byte ranges are `start..end`, end not included, counted from the start of the frame, header
  included.

### TLS

- Starwire connects. The helper is the TLS server and asks for Starwire's certificate.
- TLS 1.3, or TLS 1.2 with ECDHE_ECDSA and AES-GCM or ChaCha20-Poly1305. Nothing older, no RSA key
  exchange, no CBC, no pre-shared keys, no compression.
- Each side sends exactly one certificate: X.509, self-signed, a P-256 key, signed with ECDSA and
  SHA-256, subject and issuer `CN=Starwire` and nothing that names the phone or the PC, and a
  random serial number. Starwire makes a new one for each connection. The helper makes one when it
  starts. Neither is saved with the pairing. SslStream may only take a private key that's in a
  saved Windows key container (a known limit of .NET on Windows, not checked here yet). If so,
  the helper makes a new container for its own Windows account when it starts, and deletes it
  when it exits and again at its next start, in case it crashed.
- Neither side checks a chain, names or dates, so a wrong clock doesn't matter. Each checks that
  the other's certificate has a P-256 key. The TLS handshake proves the other side holds that
  key's private half, and BIND proves the certificate belongs to someone with the pairing.
- No session resumption: Starwire makes a new TLS context for each connection, so it never offers
  an earlier session.
- No renegotiation. Neither side asks for it. Starwire refuses it. The helper refuses it too if
  SslStream lets it, which is to be checked when the helper is written. TLS 1.3 has none. In TLS
  1.2 only the other end of the session can start one, and BIND has already proved that end holds
  the pairing. If one happens anyway, both sides allow only the secure kind (RFC 5746), which ties
  the new handshake to the first, so a third party can't use it to get in.
- TLS with a pre-shared key would be simpler on paper, but .NET's SslStream, which the helper is to
  use, doesn't offer it. Certificates plus BIND work with the standard TLS on both sides.

### Frames

Everything inside TLS is a frame:

| Bytes | Field |
|---|---|
| 0 | type |
| 1 | reserved, 0 |
| 2..4 | payload length n, u16, 0 to 16384 |
| 4..4+n | payload |

| Type | Name | Sent by | Length |
|---|---|---|---|
| 1 | HELLO | helper | 28 |
| 2 | BIND | Starwire | 48 |
| 3 | BIND_ACK | helper | 20 |
| 4 | REFUSE | helper | 8 |
| 5 | SHARE | Starwire | 28 + B + N + C + (3 + L) for each report descriptor |
| 6 | SHARE_ACK | helper | 8 |
| 7 | DATA | both | 5 to 16388 |
| 8 | KEEPALIVE | both | 4 |
| 9 | END | both | 8 |

The order is fixed. The helper sends HELLO as soon as TLS is up. Starwire answers with BIND. The
helper answers with BIND_ACK, or with REFUSE and closes. Starwire sends SHARE, and the helper
answers with SHARE_ACK. From a SHARE_ACK with status 0, DATA and KEEPALIVE go both ways. Either
side may send END at any time after BIND_ACK. A frame with an unknown type, a reserved byte that
isn't 0, a payload that's too long, a layout that doesn't add up or that comes out of order ends
the session, with END 9 after BIND_ACK. Before BIND_ACK the connection is just closed, except that
the helper answers whatever frame comes where BIND should as "Rules for the helper" says.

### Keys

```
K_usb   = HKDF(no salt, ikm = K_pair, info = "starwire-usb/1 auth" || deviceId)
T       = HELLO || phoneFp || helperFp
bindMac = MAC16(K_usb, "starwire-usb/1 bind" || T || BIND bytes 0..32)
ackMac  = MAC16(K_usb, "starwire-usb/1 bind ack" || T || BIND || BIND_ACK bytes 0..4)
```

K_pair is the microphone's pairing key for deviceId ([mic-protocol.md](mic-protocol.md), "Keys").
K_usb has its own label, so it's a different key from the microphone's K_auth, and nothing signed
for the microphone checks out here. HELLO and BIND are the whole frames, headers included.
phoneFp is the fingerprint of Starwire's certificate and helperFp the helper's, in that order on
both sides, each as that side saw it in its own finished handshake.

### The handshake

**HELLO** (the helper, first). Its layout never changes, in any version:

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 1, length 24 |
| 4..8 | magic `SWUT` (53 57 55 54) |
| 8 | the lowest version the helper speaks, 1 to 255 |
| 9 | the highest, at least the lowest |
| 10..12 | reserved, 0 |
| 12..28 | helperNonce: 16 random bytes, new for each connection |

**BIND** (Starwire). Byte 4 is the version in every version, so the helper reads it before it
knows the rest of BIND's layout:

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 2, length 44 |
| 4 | version: the highest both sides speak |
| 5..8 | reserved, 0 |
| 8..16 | deviceId, as in the microphone's pairing with this PC |
| 16..32 | phoneNonce: 16 random bytes, new for each connection |
| 32..48 | bindMac |

**BIND_ACK** (the helper, when the BIND checks out):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 3, length 16 |
| 4..20 | ackMac |

**REFUSE** (the helper, not authenticated). Its layout and its reasons are the same in every
version, so any Starwire can read why any helper refused it, "no version in common" included:

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 4, length 4 |
| 4 | reason: 1 not paired (no pairing for this deviceId, or a MAC that doesn't check out), 2 no version in common, 3 a malformed BIND, 4 paired, but USB isn't allowed for this phone |
| 5..8 | reserved, 0 |

Rules for the helper:

- **Waiting connections.** At most 4 connections that haven't finished their BIND, and at most 1
  from each address. Each address may start a new waiting connection at most once every 2 s (its
  grace time): one that comes sooner is closed at once, so a single host can't churn the waiting
  room or burn pairing-file reads by reconnecting over and over. When 4 are waiting, a new
  connection from a fresh address pushes out the oldest, and only the oldest, once it has waited
  its grace time, as the microphone's receiver does with its pending sessions; otherwise the new
  one is closed at once. No address pushes out another address's connection any other way. So one
  host can't keep the real phone out by holding every place. Each connection gets 10 s from its
  TCP connection to a valid BIND.
- **The answer to BIND.** It answers the frame that comes where BIND should in this order, and
  the first that applies wins:
  1. A header that doesn't read (see "Frames"): it closes the connection.
  2. A BIND whose version, byte 4, isn't in the HELLO's range, whatever the rest of the frame
     looks like: REFUSE 2.
  3. Anything that isn't a well-formed BIND of that version, a frame of another type included:
     REFUSE 3.
  4. No pairing for the deviceId, or a MAC that doesn't check out: REFUSE 1.
  5. A phone that isn't allowed USB ("Allow USB, per phone"): REFUSE 4. Only a phone that has just
     proved it holds the pairing learns this.
  6. Otherwise BIND_ACK.
- **Reading the pairings file.** At every BIND it reads the pairings file again, never a copy from
  earlier, as the Windows account the receiver runs under (the keys in it are encrypted for that
  account). It opens the file for reading, shared for reading, writing and deleting
  (`FileShare.ReadWrite | FileShare.Delete`), reads it whole and closes it at once. It never
  writes, moves or locks the file, and lines it can't read are skipped. It doesn't use the
  receiver's `MicPairingStore.Load`, or `File.ReadAllText` as that does: ReadAllText shares the
  file for reading only, and while it's open the receiver's save (`File.Replace`, or `File.Copy`
  on a volume that can't swap files) fails, so pairing and unpairing fail. The helper's tests
  check that the receiver can still pair and unpair while the helper reads the file over and
  over.
- **At most 4 reads a second**, in all, and BINDs get 3 of them. The 4th is kept for the
  sharing phone's pairing check and the helper's menu, so connections that keep sending BINDs
  can't hold up an unpair. A BIND that comes when its 3 are used up waits its turn, within its
  10 s, and stops waiting if its connection is closed. So a host that sends BINDs back to back
  can't keep the file open, or make the helper decrypt keys, as often as it likes.
- **Only the one key.** It finds deviceId's line in constant time and decrypts only that line's
  key (`MicPairingStore.Load` decrypts every line's). With no such line, it decrypts a dummy key
  instead, one it encrypted itself at start, and checks the MAC against a random key, so an
  unknown deviceId and a wrong MAC get the same REFUSE 1 after the same work. It works out K_usb
  and wipes every key it decrypted, K_pair or the dummy, at once.
- **What it keeps.** After BIND_ACK it wipes K_usb too, and from then on TLS protects the session.
  To notice later that the pairing changed, it keeps that line's key field as the file has it: the
  base64 text of the encrypted key, which the receiver's saves write back unchanged and which a
  new pairing replaces. It never decrypts it again.

Rules for Starwire:

- It waits up to 10 s for HELLO, 10 s from BIND for BIND_ACK, and 10 s from SHARE for SHARE_ACK.
  With no HELLO or BIND_ACK in time it just closes. With no SHARE_ACK in time it sends END 9.
- It picks the highest version both sides speak. If there's none, it closes and says which side
  needs updating.
- It reads K_pair from its own pairing store for this PC, works out K_usb and wipes K_pair. It
  never writes the store. With no pairing, it says to pair the microphone with this PC first.
- Only a BIND_ACK that checks out counts. A REFUSE, a wrong MAC or a closed connection means no
  device is shared and the exporter isn't started. Anyone in the middle can send a REFUSE, so its
  reason is shown as a hint only. For REFUSE 4 the hint is to tick **Allow USB** for this phone
  in the helper's menu on the PC.
- If the phone's exporter can't take the device after BIND_ACK, Starwire can't connect to it
  after SHARE_ACK 0, or its connection closes, Starwire sends END 2, as when the device is
  unplugged.

### Allow USB, per phone

Turning USB on in the helper's menu isn't enough on its own. The menu also lists the paired
phones, each with **Allow USB**, off until you tick it. Otherwise every phone ever paired with the
microphone would get USB, a pairing made by someone who saw the pairing code included
([SECURITY.md](../../SECURITY.md)).

The helper keeps its own list, `usb-allowed.dat` in the same folder as the pairings file, and
never writes the pairings file. Each line is a deviceId and the SHA-256 of that pairing's key
field (the base64 text, as the pairings file has it). A phone that pairs again, or another device
that pairs with its deviceId, has a new key field, so it needs Allow USB again. A line for a phone
that was unpaired matches nothing, and the helper drops it the next time it saves its list.

### SHARE and SHARE_ACK

**SHARE** (Starwire, right after BIND_ACK):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 5 |
| 4 | flags: bit 0 set means you confirmed sharing this keyboard or mouse just now. Other bits 0 |
| 5 | bus id length B, 1 to 31 |
| 6..6+B | the bus id the exporter gives the device: digits, letters, `.`, `_`, `:` and `-` only, like `1-1.2` or `1-3:1` |
| next 1 | name length N, 0 to 64 |
| next N | the device's name as Android reports it, UTF-8 |
| next 18 | the device descriptor |
| next 2 | the configuration descriptor's length C, u16, 9 to 4096 |
| next C | the configuration descriptor, with everything in it: interfaces, alternate settings, class and endpoint descriptors. Its wTotalLength is C |
| next 1 | R, the number of HID report descriptors, 0 to 16 |
| R times | an interface number (1 byte), a length L (u16, 1 to 4096), then that interface's HID report descriptor (L bytes) |

The lengths must add up to the frame exactly, and the payload is at most 16384 bytes. At most one
report descriptor per interface. Whoever receives a name removes control and formatting
characters before showing it. The bus id is held to so few characters because the helper may
pass it to a command, where characters like `"`, `&`, `|`, `%`, `^`, `<` and `>` mean something.

**SHARE_ACK** (the helper):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 6, length 4 |
| 4 | status: 0 attaching, 1 busy (another device is shared with this PC), 2 refused by the device rules, 3 the PC's USB driver is missing or isn't a version the helper knows |
| 5 | for status 2, the rule that refused it (see "The device rules"); otherwise 0 |
| 6..8 | reserved, 0 |

The helper waits up to 10 s for SHARE after BIND_ACK. With status 0 it attaches the device and
the relay starts. With any other status it closes the session.

### DATA, KEEPALIVE and END

**DATA** (both): the frame header, type 7, then the next 1 to 16384 bytes of the USB/IP stream in
that direction. Frames don't have to line up with USB/IP's messages.

**KEEPALIVE** (both): the frame header, type 8, length 0. From SHARE_ACK on, each side sends one
when it has sent nothing for 1 s, Starwire also while it connects to its exporter. A side that
has heard nothing for 8 s closes the connection without an END, since the other side isn't
answering, and the device goes back as below. Starwire then offers to share it again. Before
SHARE_ACK neither side sends one, and one that comes earlier is out of order (see "Frames").

**END** (both):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 9, length 4 |
| 4 | reason: 1 stopped by you, on either side; 2 the device was unplugged from the phone; 3 the stream ended; 4 USB was turned off in the helper; 5 the pairing was removed or changed, or Allow USB was unticked for this phone; 6 the device broke the rules; 7 Windows couldn't attach the device, or removed it; 8 the app or the helper is closing; 9 a protocol error |
| 5 | for reason 6, the rule; otherwise 0 |
| 6..8 | reserved, 0 |

After sending END, a side closes TLS properly and then the connection.

### Giving the device back

- At the end of a session, however it ends, the helper detaches its own port: the one it
  attached, after checking that the port still holds its device (the relay's address and port,
  the bus id, the serial). Never port 0, which means every port, and never a port it didn't
  attach: other programs, Vibepollo's virtual pad among them, use the same driver. Then it closes
  the relay and the tunnel.
- The helper never asks the driver to reattach by itself, so a lost connection unplugs the device
  in Windows instead of leaving the driver to retry later.
- Starwire closes the exporter's connection, and Android gets the device back.
- If the helper stops or crashes, the driver loses the relay's connection and Windows removes the
  device. If Starwire stops, the helper notices within 8 s.

### When the pairing is removed or changed

- In the receiver's menu (**Unpair**): the next BIND from that phone gets REFUSE 1. While a device
  is shared, the helper also reads the pairings file (as BIND does, within the same 4 reads a
  second) and its own list at least every 10 s and whenever either changes. If that phone's line
  has gone, its key field isn't the one the helper kept at BIND (it paired again), or Allow USB
  was unticked for it, the helper sends END 5 and gives the device back. Comparing the key field
  means it never has to decrypt the key again.
- On the phone: without a pairing for this PC, Starwire doesn't connect at all. After pairing
  again, the old K_usb no longer works, so a session still running ends as above.

### USB/IP inside the tunnel

The relay checks the USB/IP stream in both directions (message formats as in the Linux kernel's
USB/IP protocol document):

1. **The import.** The first message from the driver must be OP_REQ_IMPORT for SHARE's bus id. The
   first from Starwire must be OP_REP_IMPORT with status 0, the same bus id, the device
   descriptor's idVendor, idProduct, bcdDevice, class, subclass, protocol and bNumConfigurations,
   the configuration's bNumInterfaces, and a bConfigurationValue of 0 or the configuration's own.
   A reply that doesn't match ends the session (END 6, rule 9), and so does a wrong request from
   the driver (END 9). Its speed must be low, full or high (USB/IP's 1, 2 or 3): a faster device
   ends the session (END 6, rule 10), since Windows reads more descriptors from a USB 3 device
   (the BOS descriptor, below). Starwire checks the speed too, before it sends SHARE.
2. **The transfers.** After that, the driver sends CMD_SUBMIT and CMD_UNLINK, Starwire RET_SUBMIT
   and RET_UNLINK. The relay works out each message's length from its header, and remembers each
   transfer the driver asked for, since a RET_SUBMIT's data length depends on that transfer's
   direction. It ends the session (END 9) on a command it doesn't know, a reply to a transfer it
   doesn't know, a transfer over 1 MiB, more than 1024 isochronous packets, more than 4096
   transfers waiting, or a reply with more data than was asked for.
3. **The descriptors**, before the driver gets them. For GET_DESCRIPTOR on endpoint 0: a request for
   the device descriptor must get back the start of SHARE's device descriptor; for configuration
   index 0, the start of SHARE's configuration descriptor; for any other index, no data. A
   request for a HID report descriptor (request type 0x81, descriptor type 0x22, wIndex the
   interface) must get back the start of SHARE's report descriptor for that interface, or no data
   if SHARE gave none. Anything else ends the session (END 6, rule 9) before the reply reaches
   the driver.
4. **What the relay answers itself.** These requests on endpoint 0 never reach the device. The
   relay answers each one with a stall, as a device without them would: a RET_SUBMIT with status
   -32 (EPIPE) and no data, for the same seqnum, sent to the driver between two whole messages
   from Starwire. A CMD_UNLINK for one of them gets RET_UNLINK with status 0 from the relay too.
   - GET_DESCRIPTOR for string descriptor 0xEE, where Windows looks for Microsoft OS 1.0
     descriptors.
   - GET_DESCRIPTOR for the BOS descriptor (type 0x0F), which Windows reads from a device with a
     bcdUSB of 0x0201 or more, and which can hold Microsoft OS 2.0 descriptors.
   - GET_DESCRIPTOR for an other-speed configuration (type 0x07).
   - Every vendor request (bits 5 and 6 of bmRequestType are 2), and every request of the
     reserved type 3.

   Microsoft OS descriptors let a device give Windows a compatible ID, and so a driver, that its
   class codes don't: the compatible ID `RNDIS` with `5162001` loads Windows' own RNDIS network
   driver (`rndiscmp.inf` lists `USB\MS_COMP_RNDIS&MS_SUBCOMP_5162001`). Without them, Windows
   goes by the class codes the device rules checked. Whether Windows still sets up a device with a
   bcdUSB of 0x0201 or more whose BOS request stalls hasn't been tried. A device that needs vendor
   requests for some of its features (some headsets' extra controls) loses those features in
   version 1.
5. **Not checked in version 1:** other string descriptors, the device qualifier, class requests,
   SET_CONFIGURATION and SET_INTERFACE, and every transfer on the other endpoints. Windows reads
   some of these, and the class drivers that load read whatever the device sends, so a hostile
   device can still reach the code that reads them.

### The device rules, version 1

Starwire applies them before it sends SHARE, and the helper applies them again to SHARE. Both
read the descriptors exactly as below, so they reach the same decision, and both are tested
against the same cases (`swut-v1-policy.txt`, see "Test vectors"). The rules:

| Rule | Refused because it's |
|---|---|
| 1 | a hub |
| 2 | storage: mass storage, or still image (cameras, phones) |
| 3 | a network adapter, modem or radio |
| 4 | vendor-specific, and its vendor and product IDs aren't on the list below |
| 5 | a keyboard or mouse you didn't confirm (the helper only) |
| 6 | a device with more than one configuration |
| 7 | descriptors that don't read, or don't fit together |
| 8 | a kind of device Starwire doesn't share |
| 9 | sending Windows something SHARE didn't describe (the relay) |
| 10 | faster than high speed, so USB 3 (the relay, and Starwire before SHARE) |

The checks run in the order below. The first one that refuses names the rule. An "ask" doesn't
stop them.

**1. Reading the descriptors.** Rule 7 if any of these fails:

- The device descriptor has a bLength of 18 and type 1.
- The configuration descriptor is read descriptor by descriptor from its start. Each one has a
  bLength of at least 2 and ends inside wTotalLength, and the last one ends exactly there. The
  first is the configuration itself (bLength 9, type 2). No device or configuration descriptor
  comes after it.
- An interface descriptor (type 4) is 9 bytes. An endpoint descriptor (type 5) is 7 bytes, or 9
  in an audio interface. An interface association descriptor (IAD, type 0x0B) is 8 bytes. Other
  descriptors, class-specific ones among them, are only checked for length.
- Each pair of interface number and alternate setting comes once, and bNumInterfaces is the number
  of different interface numbers. Between an interface descriptor and the next interface
  descriptor or IAD there are exactly bNumEndpoints endpoint descriptors. None is for endpoint 0,
  and none comes twice in one alternate setting.
- Each IAD names one or more interfaces that are there (bFirstInterface, bInterfaceCount), and no
  interface is named by two IADs.
- Every alternate setting of a HID interface (class 0x03) has a HID descriptor (type 0x21), whose
  first class descriptor is the report descriptor (type 0x22). SHARE has that interface's report
  descriptor, exactly that long. SHARE has no report descriptor for an interface that isn't HID or
  isn't there.

**2. One configuration.** bNumConfigurations is 1, or rule 6.

**3. The device's own class codes.** bDeviceClass, bDeviceSubClass and bDeviceProtocol must be
00/00/00 (each interface gives its class) or EF/02/01 (some interfaces are grouped by IADs). For
any other codes, Windows takes the compatible IDs, and so the driver, for the whole device from
them. So other codes are refused with the rule the class table below gives that class (a hub 1,
storage 2, a network adapter 3, vendor-specific 4), or rule 8 for any other class, audio and HID
included. A device on the vendor list may also have class 0xFF here.

**4. Each IAD, in order.** Windows gives the function an IAD groups its compatible IDs from the
IAD, so its function class goes through the class table as an interface's would. Then it must be
the class of every interface it names, or rule 7.

**5. Each interface and alternate setting, in order.** Its class gives a decision from this table,
and then its endpoints must fit that class, or rule 7:

| Interface class | Decision | Endpoints |
|---|---|---|
| 0x01 audio, subclass 1 (control) | allowed | at most one, interrupt IN |
| 0x01 audio, subclass 2 (streaming) | allowed | isochronous only |
| 0x01 audio, subclass 3 (MIDI) | allowed | bulk only |
| 0x03 HID | by its report descriptor (step 6). Subclass 1 with protocol 1 or 2 (a boot keyboard or mouse) asks, whatever the report descriptor says | interrupt only, at most one IN and one OUT |
| 0xFF vendor-specific | allowed only if the device's vendor and product IDs are on the list below; otherwise rule 4 | as the list says |
| 0x08 mass storage, 0x06 still image | rule 2 | |
| 0x09 hub | rule 1 | |
| 0x02 communications, 0x0A CDC data, 0xE0 wireless controller (Bluetooth, RNDIS), 0xEF with subclass 0x04 (RNDIS) | rule 3 | |
| anything else: audio with another subclass, video, 0xFE (firmware update) | rule 8 | |

An IAD's audio function (class 0x01) may have any subclass, since an IAD's function subclass for
audio is 0.

**6. Each HID report descriptor, in the order of its interface.** It's read item by item as HID
1.11 section 6.2.2 describes, with these limits so that every parser finds the same top-level
collections with the same usages. Rule 7 if any fails:

- A short item's data is 0, 1, 2 or 4 bytes (a size of 3 means 4), little-endian, and no item
  runs past the end. Long items (prefix 0xFE) are refused.
- Only the tags HID 1.11 defines, and not all of them. Main: Input, Output, Feature, Collection
  and End Collection. Global: Usage Page, Logical and Physical Minimum and Maximum, Unit Exponent,
  Unit, Report Size, Report ID and Report Count. Local: Usage, Usage Minimum and Maximum, and the
  Designator and String Index, Minimum and Maximum. Push, Pop and Delimiter are refused, since a
  parser that gets them wrong reads another page or usage.
- Collections nest at most 16 deep. Each one has its End Collection, and no End Collection comes
  without one. Input, Output and Feature items come only inside a collection.
- A collection that isn't inside another is a top-level collection. It must be an Application
  collection (data 1). Exactly one Usage item comes before it, after the last main item, and no
  Usage Minimum or Maximum.
- That Usage's page: when its data is 4 bytes (an extended usage), its upper 16 bits are the page
  and the lower 16 the usage, whatever Usage Page is in effect. Otherwise it's the Usage Page in
  effect, and no Usage Page item may come between the Usage and the Collection, since parsers
  differ on which page applies then. A Usage Page above 0xFFFF is refused.
- There's at least one top-level collection.

Windows makes one HID device for each top-level collection and picks its driver by that
collection's usage, so only the top-level collections decide:

| Page and usage | Decision |
|---|---|
| Generic Desktop (0x01) Joystick 0x04, Game Pad 0x05 or Multi-axis Controller 0x08; any usage on the Simulation Controls (0x02) or Game Controls (0x05) pages | a pad: allowed |
| Generic Desktop Pointer 0x01, Mouse 0x02, Keyboard 0x06, Keypad 0x07 or System Control 0x80; any usage on the Digitizers page (0x0D) | a keyboard or mouse: ask |
| any usage on the Consumer (0x0C) or Telephony (0x0B) pages | allowed on a device that also has an audio interface (a headset's buttons); otherwise ask |
| vendor-defined pages, 0xFF00 to 0xFFFF | allowed |
| anything else | rule 8 |

**7. The decision.** The device gets the strictest decision of all its parts: refused if anything
is refused, else ask if anything asks, else allowed. "Ask" means Starwire asks you every time and
sets SHARE's flag only after a yes. The helper refuses an "ask" device without the flag (rule 5).
That protects against a mistake, not against a stolen phone, which can say yes itself.

The vendor list is empty in version 1, so every vendor-specific interface is refused, Xbox pads by
cable among them (Starwire's own Xbox driver still handles those on the phone). A device goes on
the list by its vendor and product IDs, after a test with that device and a review. Listing a
vendor without its product IDs would let a device with another product's ID reach that product's
driver.

**What the rules can't see.** They look at the class codes and usages Windows picks its class
drivers by, and the relay makes sure those are what Windows gets. But Windows matches a device's
vendor and product ID before its class codes, and a device can lie about its IDs. On a Windows 11
PC checked on 26 Oct 2026, Windows' own storage driver (`usbstor.inf`) lists 67 devices by vendor
and product ID, so a device that says it's one of them gets the storage driver, whatever its
class codes say. The same goes for any driver another program installed for a device's IDs. So
storage and network adapters are refused by what they say they are, not by what they are. A
later version could ask Windows, before attaching, whether any installed driver is tied to the
device's IDs. That hasn't been tried.

Changing the rules doesn't change the bytes, so it needs no new version. Both sides change
together, with the policy cases, and SHARE_ACK and END name the rule, so Starwire can explain a
refusal its own copy of the rules didn't expect.

## Attacks, and what stops them

**Someone in the middle** (a hostile access point, ARP spoofing). They can end TLS on both sides,
but then each side sees a different certificate, so the fingerprints Starwire puts in bindMac
aren't the ones the helper checks, and making a new bindMac or ackMac needs K_usb. Starwire
starts the exporter only after a BIND_ACK that checks out, so nothing about the device leaves the
phone before both sides have proved the pairing. What they do see is the HELLO, a deviceId and a
MAC, nothing more.

**Replay.** A recorded BIND doesn't check out again: bindMac covers the helper's new helperNonce
and Starwire's new certificate. A recorded BIND_ACK doesn't either: ackMac covers the whole BIND,
phoneNonce included. Everything later is inside TLS, which numbers its records, so frames can't be
replayed, dropped quietly or reordered. Nothing from the microphone replays here: other keys,
other labels, another port.

**Downgrade.** There is no unencrypted mode to fall back to. TLS versions below 1.2 and weak cipher
suites aren't enabled on either side, and TLS 1.3 has its own check against being pushed back to
1.2. The HELLO, with its version range, is inside bindMac, so someone who changes the range breaks
the MAC instead of forcing an older SWUT. Starwire never picks a version below its own lowest.

**A hostile network.** Anyone can connect to the helper's port. Before a valid BIND they get a TLS
handshake and at most a REFUSE: the helper keeps at most 4 such connections, 1 for each address,
for 10 s each, and does no work beyond TLS, a fresh read of the pairings file (at most 4 a
second), one key to decrypt and one MAC. One host can't hold every place, since a newer
connection pushes out one that has waited 2 s. The frames' lengths are bounded, so memory stays
bounded. They can't read or change the tunnel (TLS), can't pretend to be either side (that needs
K_usb), and can't follow a phone around by its certificate, which is new each time and names no
person, phone or PC. The deviceId travels inside TLS, unlike the microphone's. They can tell
that a Starwire USB helper is listening, with USB on, since the HELLO's `SWUT` and the
`CN=Starwire` certificate say so.

**A stolen phone.** It holds the pairing key (encrypted with a key that stays in Android's
Keystore), so while it's still paired, whoever can unlock it can do what you could: share devices
the rules allow, and confirm keyboards and mice. It could already stream and type on the PC
through its pairing with the host. Unpair it in the receiver's menu (or untick Allow USB for it),
which ends a running session within 10 s, and in the host. While a device is shared, the helper's
menu shows the phone's name (from the pairing) and the device's name (from SHARE), with Stop.

**Code that holds the pairing.** Root malware on the phone, or a custom Vulkan driver, which runs
inside Starwire with the keys for your paired PCs ([SECURITY.md](../../SECURITY.md), "The custom
Vulkan driver option"), can read the pairing key whenever it likes. The Keystore key that
protects it on the phone doesn't ask for an unlocked screen (`MicPairingStore.java`). With the
pairing key it doesn't need Starwire at all: it can make its own BIND and SHARE, say yes for its
own keyboard, and send any USB/IP it likes. Whatever gets past the helper's rules and the relay
goes to usbip-win2's driver and Windows' USB and HID drivers, which run in the kernel. So the
helper's checks are the only filter between such code and the PC's kernel, and the pairing now
allows a kernel-level attach where before it could only send audio. That's why USB is off until
you turn it on, needs Allow USB for each phone, and stops when a phone is unpaired. Making the
Keystore key need an unlocked phone would narrow this, but that's a change to the microphone's
pairing, so it isn't part of this contract.

**A hostile PC**, or a helper someone replaced, can send any request to the shared device. That's
why Starwire refuses devices with a firmware update interface. Everything else a device accepts,
a PC with the pairing can ask it to do.

**A hostile device** plugged into the phone: see "What it doesn't protect against". The relay's
checks make sure the class codes and usages Windows picks its class drivers by are the ones the
rules looked at, and the relay keeps back the Microsoft OS descriptors and vendor requests that
would let a device pick another driver. They can't stop Windows picking a driver by vendor and
product ID ("What the rules can't see"), and they don't cover every part of a device.

## Versioning

- These never change, in any version: the frame header, HELLO's layout, BIND's byte 4 (the
  version), and REFUSE's layout and reasons. So a helper reads the version a BIND asks for before
  it knows the rest of BIND's layout, and any Starwire can read any helper's REFUSE. Everything
  else follows the version Starwire picks in BIND.
- A version is 1 to 255. A new frame type, a new field, or a changed layout, key or MAC input
  needs a new version, and its labels change with it (`starwire-usb/2 ...`).
- Starwire picks the highest version both sides speak. A helper offers every version it speaks.
- Each release works with the one before it, both ways: a new helper still offers version 1 and a
  new app still speaks it. A release that stops is a breaking change
  ([RELEASING.md](RELEASING.md), "What counts as breaking"), and says so.
- If the microphone's pairing changes (a new SWMC version with a different K_pair), the tunnel
  gets a new version too, since K_usb comes from K_pair.

## Checking the inner layer early

Plain USB/IP, as usbipd-win shares a device from another Windows PC such as a laptop, is exactly
what the tunnel carries. So sharing a cabled pad that way, straight to the PC's usbip-win2
driver, tests the driver, how Windows sees the device, and whether its audio and haptics survive
Wi-Fi, before the tunnel exists. It doesn't test the tunnel, BIND, the device rules, the relay or
the phone's exporter. And it isn't encrypted or authenticated:

- Do it only on a network you trust. While the pad is shared, any host on that network can watch
  its traffic, or attach it before the PC does.
- usbipd-win's `usbipd bind --busid <id>` goes on sharing the device after a reboot. When you're
  done, run `usbipd unbind --busid <id>` on the laptop. Closing the port isn't enough.
- To attach, the PC needs usbip-win2's `usbip.exe`, from the release that matches its installed
  driver. A PC that got the driver from Vibepollo may not have it.
- On a PC where another program uses the same driver (Vibepollo's virtual pad does), detach only
  the port you attached, never every port.

## Test vectors

`app/src/test/resources/swut-v1-vectors.txt` and `windows/tests/vectors/swut-v1-vectors.txt` are
the same file (SHA-256 `B955594038A2A423525024796861A3F43748799C8E10E4260DEA9F6EAE60A085`, LF line
endings, never changed by Git). Each line is `name=hex`.
`.github/tools/vectors/make-swut-v1-vectors.sh` wrote it with openssl (3.2 or later, for
deterministic ECDSA) and xxd from the fixed inputs below, apart from the app's code, and the
app's codec reproduces every line. `check-tools.sh` runs it, and the device rules' script below,
and compares what they write with both copies of each file. The inputs:

| Input | Value |
|---|---|
| pairing key K_pair | `000102...1f` (32 bytes), as in the microphone's vectors |
| deviceId | `a0a1a2a3a4a5a6a7`, as in the microphone's vectors |
| helperNonce | `d0d1...df` (16 bytes) |
| phoneNonce | `e0e1...ef` (16 bytes); `f0f1...ff` for the other BIND |
| certificate keys | RFC 5903 section 8.1 (i is Starwire, r the helper) |
| certificates | self-signed, `CN=Starwire`, serial 1 (Starwire) and 2 (the helper), valid 1 Jan 2026 to 1 Jan 2036, no extensions, deterministic ECDSA (RFC 6979) |
| SHARE's device | a pad with one HID interface, 1209:0001, bus id `1-1`, named `Test pad`, with a 40-byte report descriptor (16 buttons, 2 axes) |

The `kat.` lines are known answers from the standards (HKDF RFC 5869 test case 1, HMAC-SHA256
truncated to 128 bits RFC 4231 test case 5). `key.usb` is K_usb. `cert.` and `fp.` are the two
certificates and their fingerprints. The `frame.` lines are one of each frame, with `frame.bind`
and `frame.bind_ack` the handshake for these inputs and `frame.data_import` a DATA frame carrying
OP_REQ_IMPORT for bus id `1-1`. The `refuse.` lines must not check out: a BIND made with the
microphone's K_auth, one with the fingerprints the other way round, one made over a HELLO that
offered versions 1 to 2, and a BIND_ACK for the other BIND.

`SwutCodecTest` checks every line in the app (`./gradlew testNonRootDebugUnitTest`). The helper's
tests are to check the same file. A change that makes either side disagree with the file is a
change to the protocol.

### The device rules' cases

`app/src/test/resources/swut-v1-policy.txt` and `windows/tests/vectors/swut-v1-policy.txt` are
the same file too (SHA-256 `2EC55B710F7FB46D2BE00B38103DC25F118B4C213CA14F710D68D42DE4CADB4B`,
LF line endings, never changed by Git). Each case is two lines: `<case>.share=` a whole SHARE
frame in hex, and `<case>.want=` the decision the device rules reach for it: `allow`, `ask`, or
`refuse` and the rule. `.github/tools/vectors/make-swut-v1-policy.sh` wrote it from descriptors
written out by hand, each case changing one thing in the same test pad (its `pad` case is the
vectors' `frame.share`, byte for byte). The cases cover each class code check, IADs, endpoints,
the ways descriptors can fail to read, and the report descriptors that parsers can read
differently: an extended usage, a Usage Page after the Usage, two Usages, Delimiter, Push and
Pop, long items and nesting. The cases after `report_deepest` came from a review of Starwire's
rules on 27 Oct 2026, one for each check it found no case for: mice, pointers and keypads, the
other pads and pages, Telephony, the endpoints each class may have, the descriptors' lengths and
places, and reserved items and tags.

`SwutPolicyCasesTest` checks that both copies are the same, that every SHARE in it is well formed,
and that every rule a SHARE can break has a case. `UsbDeviceRulesTest` checks that Starwire's
device rules reach every decision. The helper's rules are to reach every one too, in its own tests.
