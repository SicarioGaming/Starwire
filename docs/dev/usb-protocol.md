# The USB tunnel: SWUT version 1

Starwire is to share a wired USB device plugged into the phone with the PC it streams from, so
Windows sees the real pad, wheel or headset as if it were plugged in there. The device's traffic
goes from a USB/IP exporter inside the app, through an encrypted tunnel called SWUT ("Starwire USB
tunnel"), version 1, to a USB helper on the PC, which hands it to the usbip-win2 driver. This file
is the contract between the two sides: the app's tunnel
(`app/src/main/java/com/limelight/binding/usb/tunnel/`) and the helper
(`windows/StarwireUsbHelper.cs`). Both are to be tested against the same vectors file, byte for
byte.

**Status.** Written before the code. Only the frames, keys and MACs exist so far
(`SwutCodec.java`), checked by the app's JVM tests against the vectors. There's no tunnel, no
helper and no device rules in code yet, and no device has gone through any of it.

## In plain words

**Sharing a device.** In a stream you plug a device into the phone and choose it in Starwire's
menu. Starwire hands it to the exporter, the tunnel carries its traffic to the helper, and the
helper attaches it with the usbip-win2 driver through a connection that never leaves the PC.
Choosing Stop, unplugging it or ending the stream gives it back to the phone. One device is shared
at a time.

**No new pairing.** The tunnel uses the microphone's pairing ([mic-protocol.md](mic-protocol.md)).
A phone paired with the PC's microphone receiver can share USB devices with that PC once USB is
turned on in the helper's menu, which is off until you turn it on. Each time Starwire connects,
the helper reads the receiver's pairings file again, works out a USB key from that device's
pairing key, and wipes the pairing key from memory. It never writes the file. So the microphone's
pairing now counts for more than before: a paired phone can also plug devices into the PC.
Unpairing it in the receiver's menu takes both away.

**Each connection** is TLS with a fresh key exchange (ECDHE), so recorded traffic stays secret
even if a pairing key leaks later. That matters because a shared keyboard carries whatever you
type. Both sides show a certificate they made themselves. Neither certificate means anything on
its own: what makes them count is the BIND. Starwire proves it knows the USB key with a MAC over
both certificates' fingerprints and a random number the helper just sent, and the helper proves
the same back. Someone sitting in the middle has to show each side a certificate of their own, so
the fingerprints differ between the two sides, and without the key they can't make a MAC that
fits.

**What can be shared.** Pads, wheels, sticks and audio devices. Keyboards and mice only if you say
yes on the phone, each time. Storage, network adapters, hubs, and vendor-specific devices from
vendors the rules don't list are refused, with the reason. Starwire checks before it asks the PC.
The helper checks again, and compares each descriptor Windows asks for with the one Starwire
described, before Windows gets it.

**What it doesn't protect against.**

- A stolen phone that's still paired and unlocked can share what the rules allow, keyboards and
  mice included (it can say yes itself), until you unpair it in the receiver's menu. It could
  already stream and type on the PC through its pairing with the host, so unpair it there too.
- A hostile USB device. The rules narrow what reaches Windows, but Windows still reads parts of a
  shared device the helper doesn't check (see "USB/IP inside the tunnel"). And Windows picks
  drivers by vendor and product ID, so a device that lies about its IDs can reach another
  device's driver.
- Malware on the phone or the PC, which can reach USB devices directly.
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
  starts. Neither is saved with the pairing.
- Neither side checks a chain, names or dates, so a wrong clock doesn't matter. Each checks that
  the other's certificate has a P-256 key. The TLS handshake proves the other side holds that
  key's private half, and BIND proves the certificate belongs to someone with the pairing.
- No session resumption: Starwire makes a new TLS context for each connection, so it never offers
  an earlier session. No renegotiation: Starwire never asks for it and refuses it.
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
the session, with END 9 after BIND_ACK. Before BIND_ACK the connection is just closed.

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

**BIND** (Starwire):

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

**REFUSE** (the helper, not authenticated):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 4, length 4 |
| 4 | reason: 1 not paired (no pairing for this deviceId, or a MAC that doesn't check out), 2 no version in common, 3 a malformed BIND |
| 5..8 | reserved, 0 |

Rules for the helper:

- At most 4 connections that haven't finished their BIND at once; another one is closed at once.
  Each gets 10 s from the TCP connection to a valid BIND.
- At every BIND it reads the pairings file again, never a copy from earlier, as the Windows
  account the receiver runs under (the keys in it are encrypted for that account). It opens the
  file read-only and lets the receiver go on saving it. Lines it can't read are skipped. It never
  writes, moves or locks the file.
- It finds deviceId in constant time, works out K_usb, and wipes K_pair from memory at once. An
  unknown deviceId and a wrong MAC get the same REFUSE 1 after the same work: with no pairing, it
  checks the MAC against a random key.
- A BIND that asks for a version outside the HELLO's range gets REFUSE 2, a malformed one
  REFUSE 3.
- After BIND_ACK it wipes K_usb too. From then on TLS protects the session.

Rules for Starwire:

- It waits up to 10 s for HELLO and 10 s for BIND_ACK.
- It picks the highest version both sides speak. If there's none, it closes and says which side
  needs updating.
- It reads K_pair from its own pairing store for this PC, works out K_usb and wipes K_pair. It
  never writes the store. With no pairing, it says to pair the microphone with this PC first.
- Only a BIND_ACK that checks out counts. A REFUSE, a wrong MAC or a closed connection means no
  device is shared and the exporter isn't started. Anyone in the middle can send a REFUSE, so its
  reason is shown as a hint only.

### SHARE and SHARE_ACK

**SHARE** (Starwire, right after BIND_ACK):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 5 |
| 4 | flags: bit 0 set means you confirmed sharing this keyboard or mouse just now. Other bits 0 |
| 5 | bus id length B, 1 to 31 |
| 6..6+B | the bus id the exporter gives the device, ASCII 0x21 to 0x7E |
| next 1 | name length N, 0 to 64 |
| next N | the device's name as Android reports it, UTF-8 |
| next 18 | the device descriptor |
| next 2 | the configuration descriptor's length C, u16, 9 to 4096 |
| next C | the configuration descriptor, with everything in it: interfaces, alternate settings, class and endpoint descriptors. Its wTotalLength is C |
| next 1 | R, the number of HID report descriptors, 0 to 16 |
| R times | an interface number (1 byte), a length L (u16, 1 to 4096), then that interface's HID report descriptor (L bytes) |

The lengths must add up to the frame exactly, and the payload is at most 16384 bytes. At most one
report descriptor per interface. Whoever receives a name removes control and formatting
characters before showing it.

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
when it has sent nothing for 1 s. A side that has heard nothing for 8 s closes the connection
without an END, since the other side isn't answering, and the device goes back as below. Starwire
then offers to share it again.

**END** (both):

| Bytes | Field |
|---|---|
| 0..4 | frame header, type 9, length 4 |
| 4 | reason: 1 stopped by you, on either side; 2 the device was unplugged from the phone; 3 the stream ended; 4 USB was turned off in the helper; 5 the pairing was removed or changed; 6 the device broke the rules; 7 Windows couldn't attach the device, or removed it; 8 the app or the helper is closing; 9 a protocol error |
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
  is shared, the helper also checks the pairings file at least every 10 s and whenever it
  changes. If that phone's pairing has gone, or has a different key because it paired again, it
  sends END 5 and gives the device back.
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
   the driver (END 9).
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
4. **Not checked in version 1:** string descriptors, BOS and device qualifier descriptors,
   other-speed configurations, class and vendor requests (Microsoft OS descriptors among them),
   SET_CONFIGURATION, and every transfer that isn't one of the descriptor requests above. Windows
   reads some of these, so a hostile device can still reach the code that reads them.

### The device rules, version 1

Starwire applies them before it sends SHARE, and the helper applies them again to SHARE. Both use
this table.

The device as a whole:

| Check | Rule |
|---|---|
| bNumConfigurations isn't 1 | 6, more than one configuration |
| descriptors that don't read: lengths that don't add up, a HID interface with no report descriptor in SHARE, a report descriptor with no top-level application collection | 7, can't be read |
| bDeviceClass 0x09 | 1, a hub |

Then every interface and alternate setting in the configuration:

| Interface class | Decision |
|---|---|
| 0x01 audio | allowed |
| 0x03 HID | by its report descriptor, below |
| 0xFF vendor-specific | allowed only if the device's vendor and product IDs are on the list below; otherwise rule 4 |
| 0x08 mass storage, 0x06 still image (cameras, phones) | rule 2, storage |
| 0x09 hub | rule 1, a hub |
| 0x02 communications, 0x0A CDC data, 0xE0 wireless controller (Bluetooth, RNDIS), 0xEF with subclass 0x04 (RNDIS) | rule 3, a network adapter, modem or radio |
| anything else, 0xFE (firmware update) included | rule 8, a kind of device Starwire doesn't share |

A HID interface is decided by the top-level application collections in its report descriptor:

| Usage | Decision |
|---|---|
| Generic Desktop (page 0x01) Joystick 0x04, Game Pad 0x05 or Multi-axis Controller 0x08; anything on the Simulation Controls (0x02) or Game Controls (0x05) pages | a pad: allowed |
| Generic Desktop Pointer 0x01, Mouse 0x02, Keyboard 0x06, Keypad 0x07 or System Control 0x80; anything on the Digitizers page (0x0D) | a keyboard or mouse: ask |
| anything on the Consumer (0x0C) or Telephony (0x0B) pages | allowed on a device that also has an audio interface (a headset's buttons); otherwise ask |
| vendor-defined pages, 0xFF00 to 0xFFFF | allowed |
| anything else | rule 8 |

The device gets the strictest decision of all its parts: refused if anything is refused, else ask
if anything asks, else allowed. "Ask" means Starwire asks you every time and sets SHARE's flag
only after a yes. The helper refuses an "ask" device without the flag (rule 5, a keyboard or
mouse that wasn't confirmed). That protects against a mistake, not against a stolen phone, which
can say yes itself.

The vendor list is empty in version 1, so every vendor-specific interface is refused, Xbox pads by
cable among them (Starwire's own Xbox driver still handles those on the phone). A device goes on
the list by its vendor and product IDs, after a test with that device and a review. Listing a
vendor without its product IDs would let a device with another product's ID reach that product's
driver.

Changing the rules doesn't change the bytes, so it needs no new version. Both sides change
together, and SHARE_ACK and END name the rule, so Starwire can explain a refusal its own copy of
the rules didn't expect.

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
handshake and at most a REFUSE: the helper keeps at most 4 such connections, for 10 s each, and
does no work beyond TLS, a fresh read of the pairings file and one MAC. The frames' lengths are
bounded, so memory stays bounded. They can't read or change the tunnel (TLS), can't pretend to be
either side (that needs K_usb), and can't follow a phone around by its certificate, which is new
each time and names nobody. The deviceId travels inside TLS, unlike the microphone's.

**A stolen phone.** It holds the pairing key (encrypted with a key that stays in Android's
Keystore), so while it's still paired, whoever can unlock it can do what you could: share devices
the rules allow, and confirm keyboards and mice. It could already stream and type on the PC
through its pairing with the host. Unpair it in the receiver's menu, which ends a running session
within 10 s, and in the host. While a device is shared, the helper's menu shows the phone's name
(from the pairing) and the device's name (from SHARE), with Stop.

**A hostile PC**, or a helper someone replaced, can send any request to the shared device. That's
why Starwire refuses devices with a firmware update interface. Everything else a device accepts,
a PC with the pairing can ask it to do.

**A hostile device** plugged into the phone: see "What it doesn't protect against". The relay's
checks make sure the descriptors Windows gets are the ones the rules looked at. They don't cover
every part of a device.

## Versioning

- The frame header and HELLO's layout never change, in any version. Everything after HELLO
  follows the version Starwire picks in BIND.
- A version is 1 to 255. A new frame type, a new field, or a changed layout, key or MAC input
  needs a new version, and its labels change with it (`starwire-usb/2 ...`).
- Starwire picks the highest version both sides speak. A helper offers every version it speaks.
- Each release works with the one before it, both ways: a new helper still offers version 1 and a
  new app still speaks it. A release that stops is a breaking change
  ([RELEASING.md](RELEASING.md), "What counts as breaking"), and says so.
- If the microphone's pairing changes (a new SWMC version with a different K_pair), the tunnel
  gets a new version too, since K_usb comes from K_pair.

## Checking the inner layer early

Plain USB/IP, as usbipd-win shares a device from another Windows PC, is exactly what the tunnel
carries. So sharing a cabled pad that way, straight to the PC's usbip-win2 driver, tests the
driver, how Windows sees the device, and whether its audio and haptics survive Wi-Fi, before the
tunnel exists. It doesn't test the tunnel, BIND, the device rules, the relay or the phone's
exporter. And it isn't encrypted or authenticated: anyone on that network can watch it or connect
to the shared device. Do it only on a network you trust, close the port afterwards, and on a PC
where another program uses the same driver, detach only the port you attached.

## Test vectors

`app/src/test/resources/swut-v1-vectors.txt` and `windows/tests/vectors/swut-v1-vectors.txt` are
the same file (SHA-256 `B955594038A2A423525024796861A3F43748799C8E10E4260DEA9F6EAE60A085`, LF line
endings, never changed by Git). Each line is `name=hex`. An openssl script wrote it from these
fixed inputs, apart from the app's code, and the app's codec reproduces every line:

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
