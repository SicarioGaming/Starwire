// The USB tunnel's frames for the PC's USB helper (GPL-3.0, part of Starwire): SWUT version 1,
// exactly as docs/dev/usb-protocol.md describes it. The key that comes from the microphone's
// pairing, the BIND and BIND_ACK MACs that tie the TLS session's two certificates to that pairing,
// and the layouts of every frame. The app's SwutCodec.java is the same, and both are tested
// against windows/tests/vectors/swut-v1-vectors.txt.
//
// No TLS, sockets or USB/IP here. Frames are whole byte arrays, header included, since the MACs
// cover whole frames. Parsing never throws on what arrives from the network: it returns -1, false
// or null instead.
//
// Built with the C# compiler that comes with Windows, like the rest of the helper, so it's C# 5.

using System;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;

static class Swut
{
    public const int Version = 1;
    // The versions this side speaks
    public const int LowestVersion = 1;
    public const int HighestVersion = 1;

    public const int THello = 1, TBind = 2, TBindAck = 3, TRefuse = 4, TShare = 5, TShareAck = 6, TData = 7, TKeepalive = 8, TEnd = 9;

    // Type 1, reserved 1, payload length u16
    public const int FrameHeader = 4;
    public const int MaxPayload = 16384;
    public const int MaxFrame = FrameHeader + MaxPayload;
    public const int IdLength = 8;
    public const int NonceLength = 16;
    public const int MacLength = 16;
    public const int KeyLength = 32;
    public const int FingerprintLength = 32;
    public const int HelloLength = FrameHeader + 24;                                         // 28
    public const int BindLength = FrameHeader + 4 + IdLength + NonceLength + MacLength;       // 48
    public const int BindAckLength = FrameHeader + MacLength;                                // 20
    public const int ShortLength = FrameHeader + 4;                                          // REFUSE, SHARE_ACK, END
    public const int KeepaliveLength = FrameHeader;

    // Where fields are, in whole frames
    const int HelloLowestAt = 8;
    const int HelloHighestAt = 9;
    const int HelloNonceAt = 12;
    const int BindVersionAt = 4;
    const int BindDeviceIdAt = 8;
    const int BindNonceAt = 16;
    const int BindMacAt = 32;

    public const int RefuseNotPaired = 1;
    public const int RefuseNoVersion = 2;
    public const int RefuseMalformed = 3;
    // The MAC checked out, but Allow USB isn't ticked for this phone in the helper's menu
    public const int RefuseNotAllowed = 4;

    public const int ShareAttaching = 0;
    public const int ShareBusy = 1;
    public const int ShareRefused = 2;
    public const int ShareNoDriver = 3;

    public const int EndStopped = 1;
    public const int EndUnplugged = 2;
    public const int EndStreamEnded = 3;
    public const int EndUsbOff = 4;
    public const int EndUnpaired = 5;
    public const int EndRules = 6;
    public const int EndAttachFailed = 7;
    public const int EndClosing = 8;
    public const int EndProtocol = 9;

    // The device rules (docs/dev/usb-protocol.md), as SHARE_ACK's and END's detail
    public const int RuleHub = 1;
    public const int RuleStorage = 2;
    public const int RuleNetwork = 3;
    public const int RuleUnlistedVendor = 4;
    public const int RuleUnconfirmedInput = 5;
    public const int RuleConfigurations = 6;
    public const int RuleUnreadable = 7;
    public const int RuleOtherClass = 8;
    public const int RuleMismatch = 9;
    public const int RuleTooFast = 10;

    // SHARE
    public const int FlagConfirmedInput = 1;
    public const int MaxBusId = 31;
    public const int MaxNameBytes = 64;
    public const int DeviceDescriptor = 18;
    public const int MinConfiguration = 9;
    public const int MaxConfiguration = 4096;
    public const int MaxReports = 16;
    public const int MaxReport = 4096;

    static readonly byte[] Magic = Ascii("SWUT");
    static readonly byte[] LabelAuth = Ascii("starwire-usb/1 auth");
    static readonly byte[] LabelBind = Ascii("starwire-usb/1 bind");
    static readonly byte[] LabelBindAck = Ascii("starwire-usb/1 bind ack");

    // ---------------------------------------------------------------- bytes
    public static byte[] Ascii(string s) { return Encoding.ASCII.GetBytes(s); }

    public static byte[] Hex(string s)
    {
        if (s == null || s.Length % 2 != 0) {
            throw new FormatException("not hex");
        }
        byte[] b = new byte[s.Length / 2];
        for (int i = 0; i < b.Length; i++) {
            b[i] = byte.Parse(s.Substring(2 * i, 2), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
        }
        return b;
    }

    public static string Hex(byte[] b) { return Hex(b, 0, b.Length); }

    public static string Hex(byte[] b, int at, int length)
    {
        return BitConverter.ToString(b, at, length).Replace("-", "").ToLowerInvariant();
    }

    public static byte[] Concat(params byte[][] parts)
    {
        MemoryStream m = new MemoryStream();
        foreach (byte[] p in parts) {
            m.Write(p, 0, p.Length);
        }
        return m.ToArray();
    }

    public static byte[] Slice(byte[] b, int at, int length)
    {
        byte[] r = new byte[length];
        Array.Copy(b, at, r, 0, length);
        return r;
    }

    public static int GetU16(byte[] b, int at)
    {
        return b[at] | b[at + 1] << 8;
    }

    static void PutU16(byte[] b, int at, int v)
    {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    static byte[] Frame(int type, int payloadLength)
    {
        byte[] f = new byte[FrameHeader + payloadLength];
        f[0] = (byte) type;
        PutU16(f, 2, payloadLength);
        return f;
    }

    static bool Zero(byte[] b, int from, int to)
    {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public static bool ConstantTimeEquals(byte[] a, int aAt, byte[] b, int bAt, int length)
    {
        int diff = 0;
        for (int i = 0; i < length; i++) {
            diff |= a[aAt + i] ^ b[bAt + i];
        }
        return diff == 0;
    }

    // Overwrites a key or anything else secret, so it doesn't stay in memory longer than needed
    public static void Wipe(byte[] b)
    {
        if (b != null) {
            Array.Clear(b, 0, b.Length);
        }
    }

    // ---------------------------------------------------------------- HMAC and HKDF, as in mic-protocol.md
    public static byte[] Hmac(byte[] key, byte[] data, int at, int length)
    {
        using (HMACSHA256 h = new HMACSHA256(key)) {
            return h.ComputeHash(data, at, length);
        }
    }

    // HMAC-SHA256 cut to its first 16 bytes
    public static byte[] Mac16(byte[] key, byte[] data, int at, int length)
    {
        byte[] full = Hmac(key, data, at, length);
        byte[] mac = Slice(full, 0, MacLength);
        Wipe(full);
        return mac;
    }

    // RFC 5869 with HMAC-SHA256. No salt means 32 zero bytes, as the RFC says.
    public static byte[] Hkdf(byte[] salt, byte[] ikm, byte[] info, int length)
    {
        byte[] prk = Hmac(salt == null || salt.Length == 0 ? new byte[32] : salt, ikm, 0, ikm.Length);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            byte[] input = Concat(t, info, new byte[] { (byte) i });
            Wipe(t);
            t = Hmac(prk, input, 0, input.Length);
            Wipe(input);
            int n = Math.Min(t.Length, length - done);
            Array.Copy(t, 0, okm, done, n);
            done += n;
        }
        Wipe(t);
        Wipe(prk);
        return okm;
    }

    // ---------------------------------------------------------------- keys
    // K_usb = HKDF(no salt, K_pair, "starwire-usb/1 auth" || deviceId). It isn't the mic's K_auth.
    public static byte[] UsbKey(byte[] pairKey, byte[] deviceId)
    {
        if (pairKey == null || pairKey.Length != KeyLength || deviceId == null || deviceId.Length != IdLength) {
            throw new ArgumentException("a 32-byte pairing key and an 8-byte device id");
        }
        return Hkdf(null, pairKey, Concat(LabelAuth, deviceId), KeyLength);
    }

    // A certificate's fingerprint: SHA-256 of its DER bytes, as TLS sent them
    public static byte[] Fingerprint(byte[] certificate)
    {
        using (SHA256 sha = SHA256.Create()) {
            return sha.ComputeHash(certificate);
        }
    }

    // ---------------------------------------------------------------- frames
    // The frame's type if its header is a version 1 frame header that matches the frame's length
    // (a known type, a zero reserved byte, a payload of at most 16384 bytes), else -1
    public static int TypeOf(byte[] f)
    {
        if (f == null || f.Length < FrameHeader) {
            return -1;
        }
        return FrameLength(f, 0) == f.Length ? f[0] : -1;
    }

    // The length of the whole frame that starts with these 4 header bytes, for reading frames from
    // a stream, or -1 if they can't start a version 1 frame. The payload's own checks come later.
    public static int FrameLength(byte[] header, int at)
    {
        if (header == null || at < 0 || header.Length - at < FrameHeader) {
            return -1;
        }
        int type = header[at];
        int length = GetU16(header, at + 2);
        if (type < THello || type > TEnd || header[at + 1] != 0 || length > MaxPayload) {
            return -1;
        }
        return FrameHeader + length;
    }

    // ---------------------------------------------------------------- HELLO
    public static byte[] Hello(byte[] helperNonce, int lowest, int highest)
    {
        if (helperNonce == null || helperNonce.Length != NonceLength || lowest < 1 || lowest > highest || highest > 255) {
            throw new ArgumentException("a 16-byte nonce and versions 1 to 255, lowest first");
        }
        byte[] f = Frame(THello, HelloLength - FrameHeader);
        Array.Copy(Magic, 0, f, FrameHeader, Magic.Length);
        f[HelloLowestAt] = (byte) lowest;
        f[HelloHighestAt] = (byte) highest;
        Array.Copy(helperNonce, 0, f, HelloNonceAt, NonceLength);
        return f;
    }

    // Whether this is a well-formed HELLO: 28 bytes, the magic, versions 1 to 255 lowest first
    public static bool IsHello(byte[] f)
    {
        return TypeOf(f) == THello && f.Length == HelloLength
            && ConstantTimeEquals(f, FrameHeader, Magic, 0, Magic.Length)
            && f[HelloLowestAt] != 0 && f[HelloLowestAt] <= f[HelloHighestAt]
            && Zero(f, HelloHighestAt + 1, HelloNonceAt);
    }

    // The highest version both this side and the HELLO's sender speak; 0 if they share none, -1 if
    // it isn't a well-formed HELLO. Starwire's choice; the helper's tests act as Starwire with it.
    public static int ChooseVersion(byte[] hello)
    {
        if (!IsHello(hello)) {
            return -1;
        }
        int highest = Math.Min(HighestVersion, (int) hello[HelloHighestAt]);
        return highest >= Math.Max(LowestVersion, (int) hello[HelloLowestAt]) ? highest : 0;
    }

    static bool Offers(byte[] hello, int version)
    {
        return version >= hello[HelloLowestAt] && version <= hello[HelloHighestAt];
    }

    // ---------------------------------------------------------------- BIND and BIND_ACK
    static bool FingerprintsOk(byte[] phoneFingerprint, byte[] helperFingerprint)
    {
        return phoneFingerprint != null && phoneFingerprint.Length == FingerprintLength
            && helperFingerprint != null && helperFingerprint.Length == FingerprintLength;
    }

    static byte[] BindMac(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint, byte[] bind)
    {
        byte[] input = Concat(LabelBind, hello, phoneFingerprint, helperFingerprint, Slice(bind, 0, BindMacAt));
        return Mac16(usbKey, input, 0, input.Length);
    }

    static byte[] AckMac(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint, byte[] bind, byte[] ack)
    {
        byte[] input = Concat(LabelBindAck, hello, phoneFingerprint, helperFingerprint, bind, Slice(ack, 0, FrameHeader));
        return Mac16(usbKey, input, 0, input.Length);
    }

    // Starwire's BIND for this HELLO and these two certificates (each fingerprint as that side saw
    // it in its finished TLS handshake: Starwire's own first, then the helper's). The helper never
    // sends one; its tests do, acting as Starwire.
    public static byte[] Bind(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
        int version, byte[] deviceId, byte[] phoneNonce)
    {
        if (!IsHello(hello) || !Offers(hello, version) || !FingerprintsOk(phoneFingerprint, helperFingerprint)
                || deviceId == null || deviceId.Length != IdLength || phoneNonce == null || phoneNonce.Length != NonceLength) {
            throw new ArgumentException("a HELLO that offers the version, 32-byte fingerprints, an 8-byte id, a 16-byte nonce");
        }
        byte[] f = Frame(TBind, BindLength - FrameHeader);
        f[BindVersionAt] = (byte) version;
        Array.Copy(deviceId, 0, f, BindDeviceIdAt, IdLength);
        Array.Copy(phoneNonce, 0, f, BindNonceAt, NonceLength);
        Array.Copy(BindMac(usbKey, hello, phoneFingerprint, helperFingerprint, f), 0, f, BindMacAt, MacLength);
        return f;
    }

    static bool IsBind(byte[] f)
    {
        return TypeOf(f) == TBind && f.Length == BindLength && f[BindVersionAt] != 0
            && Zero(f, BindVersionAt + 1, BindDeviceIdAt);
    }

    // The version a BIND frame asks for: its byte 4, where every version keeps it, whatever the
    // rest of the frame looks like. -1 if it isn't a BIND frame with a payload.
    public static int BindVersion(byte[] bind)
    {
        return TypeOf(bind) == TBind && bind.Length > BindVersionAt ? bind[BindVersionAt] : -1;
    }

    // The helper's answer to the frame that came where BIND should, before it looks for a pairing:
    // RefuseNoVersion if it's a BIND for a version this HELLO didn't offer, whatever its layout;
    // else RefuseMalformed if it isn't a well-formed BIND (another type of frame included); else 0,
    // and the pairing and the MAC come next.
    public static int BindRefusal(byte[] hello, byte[] frame)
    {
        if (!IsHello(hello)) {
            throw new ArgumentException("the helper's own HELLO");
        }
        int version = BindVersion(frame);
        if (version < 0) {
            return RefuseMalformed;
        }
        if (!Offers(hello, version)) {
            return RefuseNoVersion;
        }
        return IsBind(frame) ? 0 : RefuseMalformed;
    }

    // The device id in a well-formed BIND, to find its pairing, or null
    public static byte[] BindDeviceId(byte[] bind)
    {
        return IsBind(bind) ? Slice(bind, BindDeviceIdAt, IdLength) : null;
    }

    // Whether a BIND is well formed, asks for a version this HELLO offered, and carries the MAC that
    // K_usb gives over this HELLO and these two fingerprints. The helper's check.
    public static bool BindChecksOut(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint, byte[] bind)
    {
        if (!IsBind(bind) || !IsHello(hello) || !Offers(hello, bind[BindVersionAt])
                || !FingerprintsOk(phoneFingerprint, helperFingerprint) || usbKey == null || usbKey.Length != KeyLength) {
            return false;
        }
        byte[] mac = BindMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind);
        return ConstantTimeEquals(mac, 0, bind, BindMacAt, MacLength);
    }

    // The helper's BIND_ACK for a BIND that checked out
    public static byte[] BindAck(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint, byte[] bind)
    {
        if (!IsBind(bind) || !IsHello(hello) || !FingerprintsOk(phoneFingerprint, helperFingerprint)) {
            throw new ArgumentException("a well-formed HELLO and BIND and 32-byte fingerprints");
        }
        byte[] f = Frame(TBindAck, MacLength);
        Array.Copy(AckMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind, f), 0, f, FrameHeader, MacLength);
        return f;
    }

    // Whether a BIND_ACK answers exactly this BIND, in this session, from someone who has K_usb.
    // Starwire's check; the helper's tests use it, acting as Starwire.
    public static bool BindAckChecksOut(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
        byte[] bind, byte[] ack)
    {
        if (TypeOf(ack) != TBindAck || ack.Length != BindAckLength || !IsBind(bind) || !IsHello(hello)
                || !FingerprintsOk(phoneFingerprint, helperFingerprint)) {
            return false;
        }
        byte[] mac = AckMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind, ack);
        return ConstantTimeEquals(mac, 0, ack, FrameHeader, MacLength);
    }

    // ---------------------------------------------------------------- REFUSE, SHARE_ACK, END, KEEPALIVE, DATA
    static byte[] ShortFrame(int type, int code, int detail)
    {
        if (code < 0 || code > 255 || detail < 0 || detail > 255) {
            throw new ArgumentException("a code and a detail of one byte each");
        }
        byte[] f = Frame(type, ShortLength - FrameHeader);
        f[FrameHeader] = (byte) code;
        f[FrameHeader + 1] = (byte) detail;
        return f;
    }

    public static byte[] Refuse(int reason) { return ShortFrame(TRefuse, reason, 0); }

    public static byte[] ShareAck(int status, int detail) { return ShortFrame(TShareAck, status, detail); }

    public static byte[] End(int reason, int detail) { return ShortFrame(TEnd, reason, detail); }

    // The reason or status in a well-formed REFUSE, SHARE_ACK or END of this type, or -1
    public static int Code(byte[] f, int type)
    {
        return IsShort(f, type) ? f[FrameHeader] : -1;
    }

    // The detail (the rule, for a refused device) in a well-formed SHARE_ACK or END of this type, or -1
    public static int Detail(byte[] f, int type)
    {
        return IsShort(f, type) ? f[FrameHeader + 1] : -1;
    }

    static bool IsShort(byte[] f, int type)
    {
        return (type == TRefuse || type == TShareAck || type == TEnd) && TypeOf(f) == type
            && f.Length == ShortLength && Zero(f, FrameHeader + 2, ShortLength)
            && (type != TRefuse || f[FrameHeader + 1] == 0);
    }

    public static byte[] Keepalive() { return Frame(TKeepalive, 0); }

    public static bool IsKeepalive(byte[] f) { return TypeOf(f) == TKeepalive && f.Length == KeepaliveLength; }

    // A DATA frame carrying the next 1 to 16384 bytes of the USB/IP stream
    public static byte[] Data(byte[] b, int at, int length)
    {
        if (length < 1 || length > MaxPayload) {
            throw new ArgumentException("1 to 16384 bytes");
        }
        byte[] f = Frame(TData, length);
        Array.Copy(b, at, f, FrameHeader, length);
        return f;
    }

    // ---------------------------------------------------------------- SHARE
    // Digits, letters and . _ : - only, as in usbipdcpp's 1-1.2 or 1-3:1. The bus id comes from a
    // phone that may be hostile, and the helper hands it to the driver and may show it.
    public static bool BusIdOk(byte[] b, int at, int length)
    {
        if (length < 1 || length > MaxBusId) {
            return false;
        }
        for (int i = at; i < at + length; i++) {
            int c = b[i];
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '.' || c == '_' || c == ':' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    public static bool BusIdOk(string busId)
    {
        if (busId == null) {
            return false;
        }
        foreach (char c in busId) {
            if (c > 127) {
                return false;
            }
        }
        byte[] b = Encoding.ASCII.GetBytes(busId);
        return BusIdOk(b, 0, b.Length);
    }

    static bool DescriptorsOk(byte[] device, int deviceAt, byte[] config, int configAt, int configLength)
    {
        return device[deviceAt] == DeviceDescriptor && device[deviceAt + 1] == 1
            && configLength >= MinConfiguration && configLength <= MaxConfiguration
            && config[configAt] == MinConfiguration && config[configAt + 1] == 2
            && GetU16(config, configAt + 2) == configLength;
    }

    // UTF-8, cut to at most 64 bytes without splitting a character
    public static byte[] NameBytes(string name)
    {
        byte[] all = new UTF8Encoding(false).GetBytes(name ?? "");
        if (all.Length <= MaxNameBytes) {
            return all;
        }
        int n = MaxNameBytes;
        while (n > 0 && (all[n] & 0xC0) == 0x80) {
            n--;
        }
        return Slice(all, 0, n);
    }

    // Without control and formatting characters, before it's shown; runs of white space become one
    // space. The fallback if nothing is left.
    public static string CleanName(byte[] utf8, string fallback)
    {
        string raw = new UTF8Encoding(false, false).GetString(utf8 ?? new byte[0]);
        StringBuilder sb = new StringBuilder();
        bool space = false;
        foreach (char c in raw) {
            UnicodeCategory category = char.GetUnicodeCategory(c);
            if (char.IsControl(c) || char.IsWhiteSpace(c) || category == UnicodeCategory.Format
                    || category == UnicodeCategory.LineSeparator || category == UnicodeCategory.ParagraphSeparator
                    || category == UnicodeCategory.Surrogate || category == UnicodeCategory.OtherNotAssigned
                    || category == UnicodeCategory.PrivateUse) {
                space = sb.Length > 0;
                continue;
            }
            if (space) {
                sb.Append(' ');
                space = false;
            }
            sb.Append(c);
        }
        return sb.Length == 0 ? fallback : sb.ToString();
    }

    // Starwire's SHARE. The helper never sends one; its tests do, acting as Starwire.
    public static byte[] Share(int flags, string busId, string name, byte[] deviceDescriptor, byte[] configuration,
        int[] reportInterfaces, byte[][] reports)
    {
        byte[] bus = busId == null ? new byte[0] : Encoding.ASCII.GetBytes(busId);
        byte[] nameBytes = NameBytes(name);
        if ((flags & ~FlagConfirmedInput) != 0 || !BusIdOk(busId) || deviceDescriptor.Length != DeviceDescriptor
                || reportInterfaces.Length != reports.Length || reports.Length > MaxReports
                || !DescriptorsOk(deviceDescriptor, 0, configuration, 0, configuration.Length)) {
            throw new ArgumentException("not a device SHARE can describe");
        }
        int length = 2 + bus.Length + 1 + nameBytes.Length + DeviceDescriptor + 2 + configuration.Length + 1;
        for (int i = 0; i < reports.Length; i++) {
            if (reportInterfaces[i] < 0 || reportInterfaces[i] > 255 || reports[i].Length < 1 || reports[i].Length > MaxReport) {
                throw new ArgumentException("a report descriptor of 1 to 4096 bytes for an interface 0 to 255");
            }
            for (int j = 0; j < i; j++) {
                if (reportInterfaces[j] == reportInterfaces[i]) {
                    throw new ArgumentException("one report descriptor per interface");
                }
            }
            length += 3 + reports[i].Length;
        }
        if (length > MaxPayload) {
            throw new ArgumentException("the descriptors don't fit in one SHARE");
        }
        byte[] f = Frame(TShare, length);
        int at = FrameHeader;
        f[at++] = (byte) flags;
        f[at++] = (byte) bus.Length;
        Array.Copy(bus, 0, f, at, bus.Length);
        at += bus.Length;
        f[at++] = (byte) nameBytes.Length;
        Array.Copy(nameBytes, 0, f, at, nameBytes.Length);
        at += nameBytes.Length;
        Array.Copy(deviceDescriptor, 0, f, at, DeviceDescriptor);
        at += DeviceDescriptor;
        PutU16(f, at, configuration.Length);
        at += 2;
        Array.Copy(configuration, 0, f, at, configuration.Length);
        at += configuration.Length;
        f[at++] = (byte) reports.Length;
        for (int i = 0; i < reports.Length; i++) {
            f[at++] = (byte) reportInterfaces[i];
            PutU16(f, at, reports[i].Length);
            at += 2;
            Array.Copy(reports[i], 0, f, at, reports[i].Length);
            at += reports[i].Length;
        }
        return f;
    }

    // A well-formed SHARE, or null: every length within its limits and adding up to exactly the
    // frame, a bus id of digits, letters and . _ : - only, an 18-byte device descriptor, a
    // configuration descriptor whose wTotalLength is its length, and at most one report descriptor
    // per interface. Whether the device is allowed is the device rules' job (UsbDeviceRules).
    public static SwutShare ParseShare(byte[] f)
    {
        if (TypeOf(f) != TShare) {
            return null;
        }
        int end = f.Length;
        int at = FrameHeader;
        if (end - at < 2) {
            return null;
        }
        int flags = f[at++];
        int busLength = f[at++];
        if ((flags & ~FlagConfirmedInput) != 0 || end - at < busLength + 1 || !BusIdOk(f, at, busLength)) {
            return null;
        }
        string busId = Encoding.ASCII.GetString(f, at, busLength);
        at += busLength;
        int nameLength = f[at++];
        if (nameLength > MaxNameBytes || end - at < nameLength + DeviceDescriptor + 2) {
            return null;
        }
        byte[] name = Slice(f, at, nameLength);
        at += nameLength;
        int deviceAt = at;
        at += DeviceDescriptor;
        int configLength = GetU16(f, at);
        at += 2;
        if (end - at < configLength + 1 || configLength < MinConfiguration || !DescriptorsOk(f, deviceAt, f, at, configLength)) {
            return null;
        }
        byte[] configuration = Slice(f, at, configLength);
        at += configLength;
        int count = f[at++];
        if (count > MaxReports) {
            return null;
        }
        int[] interfaces = new int[count];
        byte[][] reports = new byte[count][];
        for (int i = 0; i < count; i++) {
            if (end - at < 3) {
                return null;
            }
            interfaces[i] = f[at++];
            int length = GetU16(f, at);
            at += 2;
            if (length < 1 || length > MaxReport || end - at < length) {
                return null;
            }
            for (int j = 0; j < i; j++) {
                if (interfaces[j] == interfaces[i]) {
                    return null;
                }
            }
            reports[i] = Slice(f, at, length);
            at += length;
        }
        if (at != end) {
            return null;
        }
        return new SwutShare(flags, busId, name, Slice(f, deviceAt, DeviceDescriptor), configuration, interfaces, reports);
    }
}

// What SHARE says about the device Starwire wants to share
sealed class SwutShare
{
    public readonly int Flags;
    public readonly string BusId;
    public readonly byte[] Name;               // UTF-8, at most 64 bytes; clean it before showing it
    public readonly byte[] DeviceDescriptor;   // 18 bytes
    public readonly byte[] Configuration;      // the whole configuration descriptor
    public readonly int[] ReportInterfaces;    // the interface each HID report descriptor is for
    public readonly byte[][] Reports;

    public SwutShare(int flags, string busId, byte[] name, byte[] deviceDescriptor, byte[] configuration,
        int[] reportInterfaces, byte[][] reports)
    {
        Flags = flags;
        BusId = busId;
        Name = name;
        DeviceDescriptor = deviceDescriptor;
        Configuration = configuration;
        ReportInterfaces = reportInterfaces;
        Reports = reports;
    }

    public bool Confirmed { get { return (Flags & Swut.FlagConfirmedInput) != 0; } }

    public int VendorId { get { return Swut.GetU16(DeviceDescriptor, 8); } }

    public int ProductId { get { return Swut.GetU16(DeviceDescriptor, 10); } }

    // The report descriptor SHARE gave for this interface, or null
    public byte[] ReportFor(int interfaceNumber)
    {
        for (int i = 0; i < ReportInterfaces.Length; i++) {
            if (ReportInterfaces[i] == interfaceNumber) {
                return Reports[i];
            }
        }
        return null;
    }

    // For the menu: the name without control characters, or the vendor and product IDs
    public string DisplayName()
    {
        return Swut.CleanName(Name, "USB device " + VendorId.ToString("x4") + ":" + ProductId.ToString("x4"));
    }
}
