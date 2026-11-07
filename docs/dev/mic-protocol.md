# The encrypted microphone: SWMC version 1

Starwire sends your microphone to a small receiver on the PC (`windows/`, or `linux/`, which
builds the same `windows/SecureMic.cs`). Up to 0.2.0 that was
plain VBAN over UDP: anyone on the same network could listen to it, or send their own sound into
the PC's microphone. From 0.2.1 each VBAN packet travels inside an envelope called SWMC ("Starwire
microphone"), version 1. This file is the contract between the two sides: the app
(`app/src/main/java/com/limelight/binding/audio/securemic/`) and the receiver
(`windows/SecureMic.cs`). Both are tested against the same vectors file, byte for byte.

## In plain words

**Pairing, once per device and PC.** On the PC you choose **Pair a device...** in the receiver's
menu. It shows a code of 16 letters and numbers (80 random bits), like `2491-650N-2RBH-G690`. You
type it into Starwire. The phone and the PC then do an elliptic-curve key exchange (ECDH on
P-256) and both prove they know the code. That gives them a 32-byte pairing key that nobody else
has. The code works once and for 5 minutes, and pairing stops after 10 wrong codes.

- Someone who only watched the network and sees the code later (in a screenshot, say) still can't
  work out the pairing key: they'd need the key exchange's secret too.
- Someone who actively sits in the middle needs the code within its 5 minutes.
- Guessing the code takes about 2^80 tries.
- If two devices use the same code, someone else saw it, so the receiver undoes that pairing and
  you start again.

**Each stream gets its own key.** When Starwire starts sending, it says HELLO with a random
number; the receiver answers with its own. Both are authenticated with the pairing key, and both
go into a new AES-256-GCM key for that session. Packets recorded from an earlier session never
check out in a new one.

**Each audio packet** is the original 540-byte VBAN packet, encrypted and authenticated, with a
counter that must always go up. The receiver plays a packet only if it decrypts under the
session's key and its counter is higher than any it has played. So nothing can be replayed, and
a late packet is dropped (playing it out of order would only garble the sound).

**What the receiver refuses:** anything it can't authenticate, devices it hasn't paired, plain
VBAN (unless the user turns on the receiver's unencrypted option), and a second device while the
first is still talking (the first keeps the microphone until it has been quiet for 3 seconds;
the second is told the PC is busy).

**What it doesn't protect against.** Nothing can stop someone on the network from flooding it or
jamming the Wi-Fi; the receiver only keeps that cheap (forged packets are dropped after one MAC
or tag check, its memory stays bounded, its warnings are rate-limited). Malware on the phone or
the PC can reach the microphone directly. A stolen phone that's still paired can send audio until
you unpair it in the receiver's menu. The packet sizes and timing are visible, as with any
encrypted stream.

**Addresses are not trusted.** A UDP source address is a field anyone can fill in. Both sides
still only talk to home-network and private VPN addresses, as an extra layer, but the security
comes from the keys. The receiver answers only a paired device's HELLO once its MAC checks out,
audio from a paired device for a session it doesn't know (a 40-byte REHELLO, at most once a
second), and pairing requests. Its answers are about the size of what asked for them, and they're
rate-limited, so it's no use for bouncing traffic at someone.

**Updating.** A 0.2.0 receiver ignores SWMC (the first four bytes aren't "VBAN"), so the new app
says the receiver isn't answering. The new receiver refuses 0.2.0's plain VBAN and says to update
and pair. Plain VBAN stays available in the app as an option labelled "Not encrypted", for
Voicemeeter and other VBAN apps.

## The bytes

### Conventions

- Numbers are little-endian, like VBAN. Byte ranges below are `start..end`, end not included.
- `HMAC` is HMAC-SHA256. `MAC16(key, data)` is its first 16 bytes. MACs are always compared in
  constant time.
- `HKDF(salt, ikm, info)` is HKDF-SHA256 (RFC 5869) giving 32 bytes. No salt means 32 zero bytes.
- Labels are ASCII with no terminator, exactly as written here. `||` joins byte strings.
- Points are uncompressed P-256 points, 65 bytes: `0x04 || X || Y`, X and Y 32 bytes big-endian.
  Both sides check the other side's point is on P-256 (and that X and Y are below the field
  prime) before using it.
- `Z` is SHA-256 of the 32-byte X coordinate of the ECDH shared point. (That's what Windows'
  `ECDiffieHellmanCng` gives with its Hash key derivation and nothing prepended or appended.)
- Names are UTF-8, at most 64 bytes, cut on a character boundary. Whoever receives a name removes
  control and formatting characters before showing it.

### The header (24 bytes, in every message)

| Bytes | Field | Value |
|---|---|---|
| 0..4 | magic | `SWMC` (53 57 4D 43) |
| 4 | version | 1 |
| 5 | type | see below |
| 6..8 | reserved | 0 |
| 8..16 | deviceId | 8 random bytes Starwire makes when it first pairs with a PC, reused if it pairs that PC again |
| 16..24 | context | depends on the type |

A receiver drops a packet whose magic, version or reserved bytes are wrong, without answering.

| Type | Name | Sent by | Context | Length |
|---|---|---|---|---|
| 1 | HELLO | Starwire | clientRandom | 40 |
| 2 | HELLO_ACK | receiver | the HELLO's clientRandom | 52 |
| 3 | AUDIO | Starwire | sessionId | 28 + VBAN packet + 16 (584 for Starwire's 540-byte packets) |
| 4 | REHELLO | receiver | the sessionId it doesn't know | 40 |
| 5 | PAIR_REQUEST | Starwire | pairNonce | 106 + name |
| 6 | PAIR_ACCEPT | receiver | the request's pairNonce | 130 + name |
| 7 | PAIR_REJECT | receiver | the request's pairNonce | 25 |

A new kind of message gets a new type number. Changing the layout or the keys of an existing
message needs a new version.

### Keys

```
K_code  = HKDF(salt = "starwire-mic/1 pairing", ikm = the code's 10 bytes, info = "code")
K_pair  = HKDF(salt = K_code, ikm = Z,
               info = "starwire-mic/1 pair" || deviceId || pairNonce || phonePoint || receiverPoint)
confirm = MAC16(K_pair, "starwire-mic/1 confirm")
K_auth  = HKDF(no salt, ikm = K_pair, info = "starwire-mic/1 auth" || deviceId)
K_audio = HKDF(salt = clientRandom || serverRandom, ikm = K_pair, info = "starwire-mic/1 audio" || deviceId)
```

K_pair is the pairing key both sides keep. K_auth signs HELLO, HELLO_ACK and REHELLO. K_audio is
the session's AES-256-GCM key.

### Pairing

**The code** is 10 random bytes written in Crockford's base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`,
5 bits per character, most significant bit first), which makes exactly 16 characters, shown in
groups of four with hyphens. Reading it back ignores case, spaces and hyphens, reads `O` as `0`
and `I` or `L` as `1`, and fails on anything else (including `U`) or on any count other than 16.

**PAIR_REQUEST** (Starwire to the receiver):

| Bytes | Field |
|---|---|
| 0..24 | header, type 5, context = pairNonce (8 random bytes, new for each pairing attempt) |
| 24..89 | phonePoint: Starwire's one-time P-256 public key |
| 89 | name length L, 0 to 64 |
| 90..90+L | the device's name |
| last 16 | MAC16(K_code, everything before) |

**PAIR_ACCEPT** (the receiver's answer when the MAC checks out):

| Bytes | Field |
|---|---|
| 0..24 | header, type 6, deviceId and context copied from the request |
| 24..89 | receiverPoint: the receiver's one-time P-256 public key |
| 89..97 | receiverId: 8 bytes the receiver made once |
| 97 | name length L, 0 to 64 |
| 98..98+L | the PC's name |
| 98+L..114+L | confirm |
| last 16 | MAC16(K_code, everything before) |

**PAIR_REJECT** (25 bytes, not authenticated): the request's header with type 7, then one byte
saying why: 1 the PC isn't pairing now, 2 wrong code, 3 the code expired, was used up by 10 wrong
codes, or was used twice.

Rules:

- Starwire sends the same PAIR_REQUEST, byte for byte, every 500 ms for 6 s, to each home-network
  address it knows for the PC (on the microphone port, 6980 by default). It stops at the first
  valid PAIR_ACCEPT, and reports no answer at 6 s.
- A PAIR_ACCEPT is valid when it's well formed, echoes Starwire's deviceId and pairNonce, its MAC
  checks out under K_code, its point is on P-256, and its confirm matches the K_pair Starwire
  worked out. A valid MAC with a wrong confirm is an error, not a pairing.
- A PAIR_REJECT counts only if it echoes the deviceId and pairNonce. Since anyone can send one,
  Starwire waits another 500 ms for a valid PAIR_ACCEPT before believing it.
- The receiver only takes pairing requests while its pairing window is open. It answers an
  identical repeat of the request that paired with the identical PAIR_ACCEPT for 60 s. A request
  with a wrong code counts once towards the 10, however often it's repeated. A different request
  with the right code, after the code was used, undoes the pairing it made and closes the window.
- The receiver saves the pairing before it answers, so a pairing it couldn't save is never
  confirmed. Pairing the same deviceId again replaces the old key.

### Sessions

**HELLO** (Starwire to the receiver):

| Bytes | Field |
|---|---|
| 0..24 | header, type 1, context = clientRandom (8 random bytes) |
| 24..40 | MAC16(K_auth, bytes 0..24) |

**HELLO_ACK** (the receiver's answer):

| Bytes | Field |
|---|---|
| 0..24 | header, type 2, context = the HELLO's clientRandom |
| 24..32 | serverRandom, which is also the sessionId |
| 32 | status: 0 ready, 1 the receiver's audio output is missing (VB-Cable), 2 busy with another device |
| 33..36 | reserved, 0 |
| 36..52 | MAC16(K_auth, bytes 0..36) |

**REHELLO** (the receiver, when a paired device sends audio for a session it doesn't know, for
example after the receiver restarted):

| Bytes | Field |
|---|---|
| 0..24 | header, type 4, context = that sessionId |
| 24..40 | MAC16(K_auth, bytes 0..24) |

Rules for Starwire:

- It sends HELLO at 0, 250, 500 ... 2000 ms, then every 2000 ms, until a valid HELLO_ACK: 52
  bytes, its own deviceId, the clientRandom of its latest HELLO, and a MAC that checks out. If none
  has come by 3000 ms it tells the user the receiver isn't answering, and keeps trying.
- Audio captured before the HELLO_ACK is dropped, not kept for later.
- Every status starts the session (the key doesn't depend on it). While the status is 1 or 2,
  Starwire sends the same HELLO again every 2000 ms to see when the PC is ready. The receiver
  answers it with the same session while it still has that session, current or pending. It
  doesn't after a restart, or once it has forgotten or replaced that session, for example a
  pending one that carried no audio for 30 s (see the rules for the receiver).
- A HELLO gets one session: the one its first valid HELLO_ACK names. A later valid HELLO_ACK for
  the same clientRandom that names another session (the receiver restarted or forgot that
  session, or someone sent an old answer again) starts no session. Starwire sends a new HELLO
  with a new clientRandom, and audio goes on in the current session until the new HELLO_ACK. So
  Starwire never goes back to an earlier session, and no key starts again at counter 0.
- A REHELLO that names the current session, with a MAC that checks out, starts a new HELLO (a new
  clientRandom), at most once every 2 s. Audio goes on in the old session until the new
  HELLO_ACK. A REHELLO for any other session is ignored, and so is every REHELLO while a new
  HELLO waits for its HELLO_ACK.
- The counter starts at 0 in each session and goes up by one per packet. It never wraps: after
  0xFFFFFFFF (265 days of audio) Starwire starts a new session.
- So Starwire never seals two packets with the same key and nonce: each HELLO has a new
  clientRandom from a secure random generator, a HELLO gets at most one session, and a session's
  counter only goes up.

Rules for the receiver:

- Per paired device it keeps one current session and at most 2 pending ones. A HELLO makes a
  pending session (or finds the one it already made for the same clientRandom); it becomes current
  on its first authentic audio, so a recorded HELLO sent again can't push the real session off.
  Pending sessions are forgotten after 30 s.
- A HELLO's MAC checks out for as long as the pairing lasts, so a recorded one could be sent
  again and again. The receiver remembers the clientRandoms a device has used. A HELLO it has seen
  before only takes a free pending place; a new one can push out a pending session made by such a
  HELLO, or else the oldest pending one once it's 2 s old. Otherwise nothing is made and the
  device's next HELLO tries again.
- At most 8 HELLO_ACKs per session per second (Starwire repeats a HELLO 4 times a second), 1
  REHELLO per device per second, and 20 pairing answers per second.
- One device plays at a time: the one playing keeps the microphone until it has been quiet for 3
  s. Another device's HELLO gets status 2 meanwhile, and its audio is checked but not played.

### Audio

| Bytes | Field |
|---|---|
| 0..24 | header, type 3, context = sessionId (the HELLO_ACK's serverRandom) |
| 24..28 | counter, u32 |
| 28..28+n | the VBAN packet (n = 1 to 1024 bytes; Starwire sends 540), encrypted with AES-256-GCM |
| 28+n..44+n | the GCM tag (16 bytes) |

- Key: K_audio. Nonce: bytes 16..28 (sessionId, then counter), 12 bytes. Additional data: bytes
  0..28. So every byte Starwire writes is authenticated.
- The receiver accepts a packet only if the tag checks out and the counter is higher than the
  highest it has accepted in that session. It moves the highest counter only after the tag has
  checked out.
- Inside is an ordinary VBAN packet (48 kHz, 16-bit mono PCM, 256 samples, a 28-byte header). The
  receiver checks its format as before but ignores its stream name: pairing says who sent it.

## Test vectors

`app/src/test/resources/swmc-v1-vectors.txt` and `windows/tests/vectors/swmc-v1-vectors.txt` are
the same file (SHA-256 `BC79B6D1E099E89BBFC93125A987AFB96A02BD5BFE8F4480CED36B369BFD810A`, LF line
endings, never changed by Git). Each line is `name=hex`. The research prototypes in Java and C#
wrote it independently from these fixed inputs and got identical bytes:

| Input | Value |
|---|---|
| pairing key (for the session vectors) | `000102...1f` (32 bytes) |
| deviceId | `a0a1a2a3a4a5a6a7` |
| clientRandom | `c0c1c2c3c4c5c6c7` |
| serverRandom (sessionId) | `5051525354555657` |
| code | `11121314151617181920`, written `2491-650N-2RBH-G690` |
| pairNonce | `7071727374757677` |
| receiverId | `b0b1b2b3b4b5b6b7` |
| phone and receiver one-time keys | RFC 5903 section 8.1 (i is the phone, r the receiver) |
| names | `Test phone` and `TEST-PC` |
| VBAN packet | frame 7, stream `Stream1`, 256 samples of `(i * 37 - 16000)` |

The `kat.` lines are known answers from the standards (GCM test case 16, HKDF RFC 5869 test case
1, ECDH RFC 5903 8.1), so both sides are also checked against something neither of them wrote.
`pkt.audio7` and `pkt.audio_last` are the VBAN packet sealed with counters 7 and 0xFFFFFFFF.
`pair.key` is K_pair from the RFC 5903 keys.

Both test suites check every line: `SwmcCodecTest` in the app (`./gradlew testNonRootDebugUnitTest`,
and on a device `SecureMicDeviceTest`), and `windows/tests/run-tests.ps1` for the receiver.
`linux/tests/run-tests.sh` runs the receiver's same tests on Linux. A change
that makes either side disagree with the file is a change to the protocol.
