// Starwire microphone receiver: the encrypted format, "SWMC" version 1 (GPL-3.0, part of
// Starwire).
//
// Starwire wraps each VBAN packet it sends in SWMC, so only devices paired with this PC can send
// audio into its microphone, and nobody else on the network can listen in:
// - Pairing: the receiver shows a one-time code (16 characters, 80 bits) that the user types into
//   Starwire. The code authenticates an ECDH P-256 exchange, which gives the device and this PC a
//   32-byte pairing key. A code works once, for 5 minutes, and pairing stops after 10 wrong codes.
//   If a second device uses the same code, someone else saw it, so the pairing it made is undone.
// - Each time the device starts sending, a HELLO / HELLO_ACK exchange (authenticated with the
//   pairing key) makes a fresh AES-256-GCM key for that session.
// - Each audio packet is the original VBAN packet, encrypted and authenticated, with a counter
//   that must always go up, so nothing can be replayed.
// docs/dev/mic-protocol.md describes the format; tests/vectors/swmc-v1-vectors.txt has the known
// answers that this file and Starwire's own code must both give.
//
// AES-GCM comes from Windows (CNG, bcrypt.dll), ECDH from ECDiffieHellmanCng, and the stored keys
// are encrypted with DPAPI. HKDF and the truncated HMACs are written out here because .NET
// Framework has no HKDF; the tests check them against RFC 5869.
//
// No Windows Forms in this file: tests/SecureMicTests.cs builds it on its own. Written for C# 5,
// like the receiver.
//
// The Linux receiver (linux/) builds this same file with STARWIRE_LINUX defined. That leaves out
// the three parts that only Windows has (CNG's AES-GCM, CNG's ECDH and DPAPI), and
// linux/Platform.cs gives the same classes and methods on .NET's own cryptography instead.
// Everything else, the format, the pairing and the endpoint's rules, is shared.

#if STARWIRE_LINUX
using EcdhKey = System.Security.Cryptography.ECDiffieHellman;
#else
using EcdhKey = System.Security.Cryptography.ECDiffieHellmanCng;
#endif
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

#if !STARWIRE_LINUX
// AES-256-GCM through Windows' CNG. One instance per key, used by one thread at a time. The
// buffers CNG reads and writes are unmanaged memory allocated once, so nothing is pinned or
// allocated per packet.
sealed class CngAesGcm : IDisposable
{
    const int STATUS_AUTH_TAG_MISMATCH = unchecked((int) 0xC000A002);
    public const int MaxAad = 80, MaxData = 2048;
    const int NonceAt = 0, AadAt = 16, TagAt = 96, InAt = 128, OutAt = InAt + MaxData, Size = OutAt + MaxData;

    // BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO; the tests run on both 32-bit and 64-bit builds
    [StructLayout(LayoutKind.Sequential)]
    struct AuthInfo
    {
        public int cbSize;
        public int dwInfoVersion;
        public IntPtr pbNonce;
        public int cbNonce;
        public IntPtr pbAuthData;
        public int cbAuthData;
        public IntPtr pbTag;
        public int cbTag;
        public IntPtr pbMacContext;
        public int cbMacContext;
        public int cbAAD;
        public long cbData;
        public int dwFlags;
    }

    [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
    static extern int BCryptOpenAlgorithmProvider(out IntPtr algorithm, string algId, string implementation, int flags);
    [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
    static extern int BCryptSetProperty(IntPtr handle, string property, string value, int valueBytes, int flags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptGenerateSymmetricKey(IntPtr algorithm, out IntPtr key, IntPtr keyObject, int keyObjectBytes, byte[] secret, int secretBytes, int flags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptEncrypt(IntPtr key, IntPtr input, int inputBytes, ref AuthInfo info, IntPtr iv, int ivBytes, IntPtr output, int outputBytes, out int result, int flags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptDecrypt(IntPtr key, IntPtr input, int inputBytes, ref AuthInfo info, IntPtr iv, int ivBytes, IntPtr output, int outputBytes, out int result, int flags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptDestroyKey(IntPtr key);

    static IntPtr algorithm;
    static readonly object algorithmLock = new object();

    IntPtr key;
    readonly IntPtr mem;

    static IntPtr Algorithm()
    {
        lock (algorithmLock) {
            if (algorithm == IntPtr.Zero) {
                IntPtr a;
                Check(BCryptOpenAlgorithmProvider(out a, "AES", null, 0), "BCryptOpenAlgorithmProvider");
                const string gcm = "ChainingModeGCM";
                Check(BCryptSetProperty(a, "ChainingMode", gcm, (gcm.Length + 1) * 2, 0), "BCryptSetProperty");
                algorithm = a;   // kept for the life of the process
            }
            return algorithm;
        }
    }

    static void Check(int status, string what)
    {
        if (status != 0) {
            throw new CryptographicException(what + " failed (0x" + status.ToString("X8") + ")");
        }
    }

    public CngAesGcm(byte[] key32)
    {
        if (key32 == null || key32.Length != 32) {
            throw new ArgumentException("AES-256 needs a 32-byte key");
        }
        Check(BCryptGenerateSymmetricKey(Algorithm(), out key, IntPtr.Zero, 0, key32, 32, 0), "BCryptGenerateSymmetricKey");
        mem = Marshal.AllocHGlobal(Size);
    }

    AuthInfo Info(int aadLength)
    {
        AuthInfo info = new AuthInfo();
        info.cbSize = Marshal.SizeOf(typeof(AuthInfo));
        info.dwInfoVersion = 1;
        info.pbNonce = mem + NonceAt;
        info.cbNonce = 12;
        info.pbAuthData = mem + AadAt;
        info.cbAuthData = aadLength;
        info.pbTag = mem + TagAt;
        info.cbTag = 16;
        return info;
    }

    // Encrypts input into output (the ciphertext, then the 16-byte tag); returns the bytes written
    public int Seal(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength, byte[] input, int inAt, int inLength, byte[] output, int outAt)
    {
        if (key == IntPtr.Zero) {
            throw new ObjectDisposedException("CngAesGcm");
        }
        if (aadLength > MaxAad || inLength > MaxData) {
            throw new ArgumentException("too long to encrypt");
        }
        Marshal.Copy(nonce, nonceAt, mem + NonceAt, 12);
        Marshal.Copy(aad, aadAt, mem + AadAt, aadLength);
        Marshal.Copy(input, inAt, mem + InAt, inLength);
        AuthInfo info = Info(aadLength);
        int written;
        Check(BCryptEncrypt(key, mem + InAt, inLength, ref info, IntPtr.Zero, 0, mem + OutAt, inLength, out written, 0), "BCryptEncrypt");
        Marshal.Copy(mem + OutAt, output, outAt, written);
        Marshal.Copy(mem + TagAt, output, outAt + written, 16);
        return written + 16;
    }

    // Decrypts the ciphertext and tag into output; returns the plaintext's length, or -1 if it
    // isn't authentic (output is then left as it was)
    public int Open(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength, byte[] input, int inAt, int inLength, byte[] output, int outAt)
    {
        int dataLength = inLength - 16;
        if (key == IntPtr.Zero || dataLength < 0 || aadLength > MaxAad || dataLength > MaxData) {
            return -1;
        }
        Marshal.Copy(nonce, nonceAt, mem + NonceAt, 12);
        Marshal.Copy(aad, aadAt, mem + AadAt, aadLength);
        Marshal.Copy(input, inAt, mem + InAt, dataLength);
        Marshal.Copy(input, inAt + dataLength, mem + TagAt, 16);
        AuthInfo info = Info(aadLength);
        int written;
        int status = BCryptDecrypt(key, mem + InAt, dataLength, ref info, IntPtr.Zero, 0, mem + OutAt, dataLength, out written, 0);
        if (status == STATUS_AUTH_TAG_MISMATCH) {
            return -1;
        }
        Check(status, "BCryptDecrypt");
        Marshal.Copy(mem + OutAt, output, outAt, written);
        return written;
    }

    public void Dispose()
    {
        if (key != IntPtr.Zero) {
            BCryptDestroyKey(key);
            key = IntPtr.Zero;
            // Don't leave audio behind in the scratch memory
            Marshal.Copy(new byte[Size], 0, mem, Size);
            Marshal.FreeHGlobal(mem);
        }
    }
}

// The pairing keys as the pairings file keeps them: encrypted with DPAPI for this Windows account,
// so the file is no use on another account or PC
static class KeyProtection
{
    public static byte[] Protect(byte[] key, byte[] entropy)
    {
        return ProtectedData.Protect(key, entropy, DataProtectionScope.CurrentUser);
    }

    // Throws CryptographicException for a key this account can't decrypt
    public static byte[] Unprotect(byte[] stored, byte[] entropy)
    {
        return ProtectedData.Unprotect(stored, entropy, DataProtectionScope.CurrentUser);
    }

    public static void CreateFolder(string path)
    {
        Directory.CreateDirectory(path);
    }

    public static void WriteFile(string path, string text)
    {
        File.WriteAllText(path, text, new UTF8Encoding(false));
    }
}
#endif

// The SWMC v1 packets. Every field is little-endian like VBAN's, and every byte the sender writes
// is authenticated. The 24-byte header, in the clear: "SWMC", version 1, the type, 2 reserved
// zero bytes, the device's 8-byte id, and 8 bytes of context (the device's random number, the
// session id or the pairing nonce, depending on the type).
static partial class Swmc
{
    public static readonly byte[] Magic = { (byte) 'S', (byte) 'W', (byte) 'M', (byte) 'C' };
    public const int Version = 1;
    public const int THello = 1, THelloAck = 2, TAudio = 3, TRehello = 4, TPairRequest = 5, TPairAccept = 6, TPairReject = 7;
    public const int Header = 24, MacLength = 16, TagLength = 16, CounterAt = 24, AudioAad = 28;
    public const int HelloLength = Header + MacLength;                 // 40, REHELLO too
    public const int AckLength = Header + 8 + 1 + 3 + MacLength;       // 52
    public const int RejectLength = Header + 1;                        // 25
    public const int PointLength = 65;                                 // 0x04, X, Y
    public const int MaxNameBytes = 64;
    public const int PairRequestLength = Header + PointLength + 1 + MacLength;                  // 106 + the name
    public const int PairAcceptLength = Header + PointLength + 8 + 1 + MacLength + MacLength;   // 130 + the name
    public const int MaxAudioPlaintext = 1024;   // the VBAN packet inside (Starwire sends 540 bytes)
    public const int StatusReady = 0, StatusNoOutput = 1, StatusBusy = 2;
    public const int RejectNotPairing = 1, RejectWrongCode = 2, RejectExpired = 3;

    public static readonly byte[] LabelAuth = Ascii("starwire-mic/1 auth");
    public static readonly byte[] LabelAudio = Ascii("starwire-mic/1 audio");
    public static readonly byte[] LabelPairingSalt = Ascii("starwire-mic/1 pairing");
    public static readonly byte[] LabelCode = Ascii("code");
    public static readonly byte[] LabelPair = Ascii("starwire-mic/1 pair");
    public static readonly byte[] LabelConfirm = Ascii("starwire-mic/1 confirm");

    public static byte[] Ascii(string s) { return Encoding.ASCII.GetBytes(s); }

    public static byte[] Hex(string s)
    {
        byte[] b = new byte[s.Length / 2];
        for (int i = 0; i < b.Length; i++) {
            b[i] = Convert.ToByte(s.Substring(2 * i, 2), 16);
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

    public static void PutU32(byte[] b, int at, long v)
    {
        b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); b[at + 2] = (byte) (v >> 16); b[at + 3] = (byte) (v >> 24);
    }

    public static long GetU32(byte[] b, int at)
    {
        return (long) b[at] | (long) b[at + 1] << 8 | (long) b[at + 2] << 16 | (long) b[at + 3] << 24;
    }

    public static byte[] Hmac(byte[] key, byte[] data, int at, int length)
    {
        using (HMACSHA256 h = new HMACSHA256(key)) {
            return h.ComputeHash(data, at, length);
        }
    }

    // HMAC-SHA256 cut to its first 16 bytes
    public static byte[] Mac16(byte[] key, byte[] data, int at, int length) { return Slice(Hmac(key, data, at, length), 0, MacLength); }

    // RFC 5869 with HMAC-SHA256. No salt means 32 zero bytes, as the RFC says.
    public static byte[] Hkdf(byte[] salt, byte[] ikm, byte[] info, int length)
    {
        byte[] prk = Hmac(salt == null || salt.Length == 0 ? new byte[32] : salt, ikm, 0, ikm.Length);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            byte[] input = Concat(t, info, new byte[] { (byte) i });
            t = Hmac(prk, input, 0, input.Length);
            int n = Math.Min(t.Length, length - done);
            Array.Copy(t, 0, okm, done, n);
            done += n;
        }
        return okm;
    }

    public static bool ConstantTimeEquals(byte[] a, int aAt, byte[] b, int bAt, int length)
    {
        int diff = 0;
        for (int i = 0; i < length; i++) {
            diff |= a[aAt + i] ^ b[bAt + i];
        }
        return diff == 0;
    }

    // ---------------------------------------------------------------- keys
    public static byte[] AuthKey(byte[] pairKey, byte[] deviceId) { return Hkdf(null, pairKey, Concat(LabelAuth, deviceId), 32); }

    public static byte[] AudioKey(byte[] pairKey, byte[] deviceId, byte[] clientRandom, byte[] serverRandom)
    {
        return Hkdf(Concat(clientRandom, serverRandom), pairKey, Concat(LabelAudio, deviceId), 32);
    }

    public static byte[] CodeKey(byte[] code10) { return Hkdf(LabelPairingSalt, code10, LabelCode, 32); }

    public static byte[] PairKey(byte[] codeKey, byte[] zHash, byte[] deviceId, byte[] pairNonce, byte[] phonePoint, byte[] receiverPoint)
    {
        return Hkdf(codeKey, zHash, Concat(LabelPair, deviceId, pairNonce, phonePoint, receiverPoint), 32);
    }

    public static byte[] Confirm(byte[] pairKey) { return Mac16(pairKey, LabelConfirm, 0, LabelConfirm.Length); }

    // ---------------------------------------------------------------- packets
    public static void WriteHeader(byte[] p, int type, byte[] deviceId, int deviceIdAt, byte[] context, int contextAt)
    {
        Array.Copy(Magic, 0, p, 0, 4);
        p[4] = Version;
        p[5] = (byte) type;
        p[6] = 0;
        p[7] = 0;
        Array.Copy(deviceId, deviceIdAt, p, 8, 8);
        Array.Copy(context, contextAt, p, 16, 8);
    }

    public static void WriteHeader(byte[] p, int type, byte[] deviceId, byte[] context) { WriteHeader(p, type, deviceId, 0, context, 0); }

    // The packet's type if it has SWMC v1's header (magic, version, zero reserved bytes), else -1.
    // Everything else is up to the MAC or the tag.
    public static int TypeOf(byte[] p, int length)
    {
        if (length < Header || p[0] != 'S' || p[1] != 'W' || p[2] != 'M' || p[3] != 'C' || p[4] != Version || p[6] != 0 || p[7] != 0) {
            return -1;
        }
        return p[5];
    }

    // The last 16 bytes are the MAC of everything before them
    public static bool MacOk(byte[] key, byte[] p, int length)
    {
        if (length < Header + MacLength) {
            return false;
        }
        return ConstantTimeEquals(Mac16(key, p, 0, length - MacLength), 0, p, length - MacLength, MacLength);
    }

    static byte[] Signed(byte[] p, byte[] key)
    {
        Array.Copy(Mac16(key, p, 0, p.Length - MacLength), 0, p, p.Length - MacLength, MacLength);
        return p;
    }

    // HELLO: the device asks for a session; the context is its random number
    public static byte[] Hello(byte[] authKey, byte[] deviceId, byte[] clientRandom)
    {
        byte[] p = new byte[HelloLength];
        WriteHeader(p, THello, deviceId, clientRandom);
        return Signed(p, authKey);
    }

    // HELLO_ACK: the context echoes the device's random number; then the receiver's (which is the
    // session id), the status and 3 reserved bytes
    public static byte[] HelloAck(byte[] authKey, byte[] deviceId, byte[] clientRandom, byte[] serverRandom, int status)
    {
        byte[] p = new byte[AckLength];
        WriteHeader(p, THelloAck, deviceId, clientRandom);
        Array.Copy(serverRandom, 0, p, Header, 8);
        p[Header + 8] = (byte) status;
        return Signed(p, authKey);
    }

    // REHELLO: the receiver doesn't know the session the device sent audio for (it restarted, say);
    // the context is that session's id
    public static byte[] Rehello(byte[] authKey, byte[] deviceId, byte[] sessionId)
    {
        byte[] p = new byte[HelloLength];
        WriteHeader(p, TRehello, deviceId, sessionId);
        return Signed(p, authKey);
    }

    // AUDIO: header (context: the session id), the counter, then the VBAN packet encrypted with
    // AES-256-GCM and its tag. The nonce is bytes 16 to 28 (session id and counter) and bytes 0 to
    // 28 are authenticated. Returns the packet's length.
    public static int Audio(CngAesGcm aead, byte[] deviceId, byte[] sessionId, long counter, byte[] vban, int vbanLength, byte[] output)
    {
        WriteHeader(output, TAudio, deviceId, sessionId);
        PutU32(output, CounterAt, counter);
        return AudioAad + aead.Seal(output, 16, output, 0, AudioAad, vban, 0, vbanLength, output, AudioAad);
    }

    // The VBAN packet inside an AUDIO packet, written to output; -1 if it's malformed or not
    // authentic
    public static int OpenAudio(CngAesGcm aead, byte[] p, int length, byte[] output)
    {
        if (TypeOf(p, length) != TAudio || length < AudioAad + 1 + TagLength || length > AudioAad + MaxAudioPlaintext + TagLength) {
            return -1;
        }
        return aead.Open(p, 16, p, 0, AudioAad, p, AudioAad, length - AudioAad, output, 0);
    }

    // PAIR_REQUEST: header (context: the pairing nonce), the device's one-time public key, its
    // name (a length byte, then UTF-8), and a MAC made with the code's key
    public static byte[] PairRequest(byte[] codeKey, byte[] deviceId, byte[] pairNonce, byte[] phonePoint, string name)
    {
        byte[] n = NameBytes(name);
        byte[] p = new byte[PairRequestLength + n.Length];
        WriteHeader(p, TPairRequest, deviceId, pairNonce);
        Array.Copy(phonePoint, 0, p, Header, PointLength);
        p[Header + PointLength] = (byte) n.Length;
        Array.Copy(n, 0, p, Header + PointLength + 1, n.Length);
        return Signed(p, codeKey);
    }

    // A PAIR_REQUEST's shape: its length matches its name's, the name fits, and the key is an
    // uncompressed point. The MAC and the point itself are checked later.
    public static bool ParsePairRequest(byte[] p, int length, out int nameLength)
    {
        nameLength = 0;
        if (TypeOf(p, length) != TPairRequest || length < PairRequestLength || p[Header] != 4) {
            return false;
        }
        nameLength = p[Header + PointLength];
        return nameLength <= MaxNameBytes && length == PairRequestLength + nameLength;
    }

    // PAIR_ACCEPT: header (echoing the device's id and nonce), the receiver's one-time public key,
    // its id, its name, a confirmation made with the new pairing key, and a MAC with the code's key
    public static byte[] PairAccept(byte[] codeKey, byte[] pairKey, byte[] deviceId, byte[] pairNonce, byte[] receiverPoint, byte[] receiverId, string name)
    {
        byte[] n = NameBytes(name);
        byte[] p = new byte[PairAcceptLength + n.Length];
        WriteHeader(p, TPairAccept, deviceId, pairNonce);
        int at = Header;
        Array.Copy(receiverPoint, 0, p, at, PointLength); at += PointLength;
        Array.Copy(receiverId, 0, p, at, 8); at += 8;
        p[at++] = (byte) n.Length;
        Array.Copy(n, 0, p, at, n.Length); at += n.Length;
        Array.Copy(Confirm(pairKey), 0, p, at, MacLength);
        return Signed(p, codeKey);
    }

    public static bool ParsePairAccept(byte[] p, int length, out int nameLength)
    {
        nameLength = 0;
        if (TypeOf(p, length) != TPairAccept || length < PairAcceptLength || p[Header] != 4) {
            return false;
        }
        nameLength = p[Header + PointLength + 8];
        return nameLength <= MaxNameBytes && length == PairAcceptLength + nameLength;
    }

    // PAIR_REJECT: the request's header echoed with type 7, then why (RejectNotPairing,
    // RejectWrongCode or RejectExpired). Not authenticated, so the device waits a moment for a
    // real PAIR_ACCEPT before believing it.
    public static byte[] PairReject(byte[] request, int reason)
    {
        byte[] p = new byte[RejectLength];
        WriteHeader(p, TPairReject, request, 8, request, 16);
        p[Header] = (byte) reason;
        return p;
    }

    // The reason in a PAIR_REJECT, or -1 if it isn't one
    public static int ParsePairReject(byte[] p, int length)
    {
        return TypeOf(p, length) == TPairReject && length == RejectLength ? p[Header] : -1;
    }

    // A name as UTF-8, cut to MaxNameBytes on a character boundary
    public static byte[] NameBytes(string name)
    {
        byte[] b = Encoding.UTF8.GetBytes(name);
        while (b.Length > MaxNameBytes) {
            name = name.Substring(0, name.Length - 1);
            if (name.Length > 0 && char.IsHighSurrogate(name[name.Length - 1])) {
                name = name.Substring(0, name.Length - 1);
            }
            b = Encoding.UTF8.GetBytes(name);
        }
        return b;
    }

    // A device's name as sent, made safe to show and to store: no control or formatting
    // characters (which could break a line or reverse the text), runs of spaces made one
    public static string CleanName(string raw, string fallback)
    {
        StringBuilder sb = new StringBuilder();
        bool space = false;
        foreach (char c in raw) {
            UnicodeCategory category = char.GetUnicodeCategory(c);
            if (char.IsControl(c) || char.IsWhiteSpace(c) || category == UnicodeCategory.Format
                    || category == UnicodeCategory.LineSeparator || category == UnicodeCategory.ParagraphSeparator) {
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

    public static string CleanName(byte[] p, int at, int length, string fallback)
    {
        return CleanName(new UTF8Encoding(false, false).GetString(p, at, length), fallback);
    }

    // ---------------------------------------------------------------- pairing codes
    // Crockford's base32: no I, L, O or U, so the code can't be misread
    const string Base32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    // 10 bytes as 16 characters in groups of four, like 2491-650N-2RBH-G690
    public static string EncodeCode(byte[] ten)
    {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        foreach (byte b in ten) {
            buffer = (buffer << 8) | b;
            bits += 8;
            while (bits >= 5) {
                sb.Append(Base32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        string s = sb.ToString();
        return s.Substring(0, 4) + "-" + s.Substring(4, 4) + "-" + s.Substring(8, 4) + "-" + s.Substring(12, 4);
    }

    // The 10 bytes of a typed code, or null if it isn't 16 valid characters. Case, spaces and
    // hyphens don't matter, and O reads as 0, I and L as 1.
    public static byte[] DecodeCode(string text)
    {
        if (text == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        foreach (char typed in text.ToUpperInvariant()) {
            char c = typed;
            if (c == '-' || c == ' ') {
                continue;
            }
            if (c == 'O') {
                c = '0';
            }
            if (c == 'I' || c == 'L') {
                c = '1';
            }
            if (Base32.IndexOf(c) < 0 || clean.Length == 16) {
                return null;
            }
            clean.Append(c);
        }
        if (clean.Length != 16) {
            return null;
        }
        byte[] result = new byte[10];
        int buffer = 0, bits = 0, at = 0;
        for (int i = 0; i < 16; i++) {
            buffer = (buffer << 5) | Base32.IndexOf(clean[i]);
            bits += 5;
            if (bits >= 8) {
                result[at++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return result;
    }

#if !STARWIRE_LINUX
    // ---------------------------------------------------------------- ECDH P-256 through CNG
    // Points travel as 0x04, X, Y; CNG's key blobs are a magic number, 32, X, Y (and d).
    public static EcdhKey NewKey() { return new ECDiffieHellmanCng(256); }

    public static byte[] PublicBlob(byte[] point, int at)
    {
        if (point[at] != 4) {
            throw new CryptographicException("not an uncompressed point");
        }
        byte[] blob = new byte[8 + 64];
        BitConverter.GetBytes(0x314B4345).CopyTo(blob, 0);   // BCRYPT_ECDH_PUBLIC_P256_MAGIC, "ECK1"
        BitConverter.GetBytes(32).CopyTo(blob, 4);
        Array.Copy(point, at + 1, blob, 8, 64);
        return blob;
    }

    // A key from fixed numbers, for the known-answer tests
    public static EcdhKey FixedKey(byte[] d, byte[] x, byte[] y)
    {
        byte[] blob = new byte[8 + 96];
        BitConverter.GetBytes(0x324B4345).CopyTo(blob, 0);   // BCRYPT_ECDH_PRIVATE_P256_MAGIC, "ECK2"
        BitConverter.GetBytes(32).CopyTo(blob, 4);
        Array.Copy(x, 0, blob, 8, 32);
        Array.Copy(y, 0, blob, 40, 32);
        Array.Copy(d, 0, blob, 72, 32);
        return new ECDiffieHellmanCng(CngKey.Import(blob, CngKeyBlobFormat.EccPrivateBlob));
    }

    public static byte[] OwnPoint(EcdhKey mine)
    {
        byte[] blob = mine.PublicKey.ToByteArray();   // ECK1, 32, X, Y
        byte[] point = new byte[PointLength];
        point[0] = 4;
        Array.Copy(blob, 8, point, 1, 64);
        return point;
    }

    // SHA-256 of the shared X coordinate: CNG's "Hash" key derivation with nothing added. Throws
    // CryptographicException for a point that isn't on P-256 (CNG checks).
    public static byte[] EcdhHash(EcdhKey mine, byte[] peerPoint, int at)
    {
        mine.KeyDerivationFunction = ECDiffieHellmanKeyDerivationFunction.Hash;
        mine.HashAlgorithm = CngAlgorithm.Sha256;
        mine.SecretPrepend = null;
        mine.SecretAppend = null;
        using (CngKey peer = CngKey.Import(PublicBlob(peerPoint, at), CngKeyBlobFormat.EccPublicBlob)) {
            return mine.DeriveKeyMaterial(peer);
        }
    }
#endif
}

// The receiver's replay rule: each counter once, and only going up. A packet that arrives late is
// refused too: the player plays packets in the order they come, so it would only garble the sound.
sealed class ReplayGuard
{
    long highest = -1;

    public bool Accept(long counter)
    {
        if (counter <= highest) {
            return false;
        }
        highest = counter;
        return true;
    }
}

// Plain VBAN, as the receiver took it before SWMC: for --allow-unencrypted, for the packet inside
// SWMC, and the home-network filter every packet still goes through
static class Vban
{
    public const int HeaderSize = 28;
    static readonly int[] SampleRates = {
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600
    };

    public static bool HasMagic(byte[] p, int length)
    {
        return length >= 4 && p[0] == 'V' && p[1] == 'B' && p[2] == 'A' && p[3] == 'N';
    }

    // 16-bit PCM in one or two channels, the only kind the receiver plays; false for anything else
    public static bool ParsePcm(byte[] p, int length, out int samples, out int channels, out int rate)
    {
        samples = channels = rate = 0;
        if (length <= HeaderSize || !HasMagic(p, length)) {
            return false;
        }
        int protocol = p[4] & 0xE0;
        int rateIndex = p[4] & 0x1F;
        int format = p[7] & 0x07;
        int codec = p[7] & 0xF0;
        samples = p[5] + 1;
        channels = p[6] + 1;
        if (protocol != 0 || codec != 0 || format != 1 || rateIndex >= SampleRates.Length
                || channels > 2 || length != HeaderSize + samples * channels * 2) {
            return false;
        }
        rate = SampleRates[rateIndex];
        return true;
    }

    public static IPAddress Source(EndPoint from)
    {
        IPAddress source = ((IPEndPoint) from).Address;
        return source.IsIPv4MappedToIPv6 ? source.MapToIPv4() : source;
    }

    // Home-network and private VPN addresses (Tailscale uses 100.64.0.0/10 and fd7a::/16). Not a
    // security boundary: a source address can be faked, and 100.64.0.0/10 is also carriers' shared
    // address space. It keeps the receiver to the networks its firewall rule lets in.
    public static bool IsHomeNetwork(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) {
            return true;
        }
        byte[] b = address.GetAddressBytes();
        if (address.AddressFamily == AddressFamily.InterNetwork) {
            return b[0] == 10
                || (b[0] == 172 && (b[1] & 0xF0) == 16)
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 169 && b[1] == 254)
                || (b[0] == 100 && (b[1] & 0xC0) == 64);
        }
        return address.IsIPv6LinkLocal || address.IsIPv6SiteLocal || (b[0] & 0xFE) == 0xFC;
    }

    public static byte[] StreamNameBytes(string name)
    {
        byte[] result = new byte[16];
        byte[] ascii = Encoding.ASCII.GetBytes(name.Trim());
        Array.Copy(ascii, result, Math.Min(ascii.Length, 16));
        return result;
    }

    // VBAN stream names are compared without case, as VB-Audio's own apps do
    public static bool NameMatches(byte[] packet, byte[] wanted)
    {
        for (int i = 0; i < 16; i++) {
            if (char.ToLowerInvariant((char) packet[8 + i]) != char.ToLowerInvariant((char) wanted[i])) {
                return false;
            }
        }
        return true;
    }
}

// A device paired with this PC
sealed class MicPairing
{
    public byte[] DeviceId;     // 8 bytes, chosen by the device
    public byte[] Key;          // the 32-byte pairing key
    public string Protected;    // Key encrypted with DPAPI, in base64, as the file keeps it
    public string Name;
    public DateTime PairedAt;   // UTC
    public DateTime LastSeen;   // UTC; DateTime.MinValue until its audio first plays

    public string Id { get { return Swmc.Hex(DeviceId); } }

    public MicPairing Copy()
    {
        MicPairing c = (MicPairing) MemberwiseClone();
        c.DeviceId = (byte[]) DeviceId.Clone();
        c.Key = (byte[]) Key.Clone();
        return c;
    }
}

// The paired devices, in a file for this Windows account (normally
// %LOCALAPPDATA%\Starwire\mic-pairings.dat). Each key is encrypted with DPAPI for this account,
// so the file is no use on another account or PC. One line per device; the file is written to a
// new file first and then swapped in, so it's never left half written.
sealed class MicPairingStore
{
    static readonly byte[] Entropy = Swmc.Ascii("Starwire microphone pairing v1");
    const string Title = "# Starwire microphone: the Android devices paired with this PC, for this Windows account.\n"
        + "# Each key is encrypted by Windows (DPAPI) for this account. Unpair from the receiver's icon.\n";
    const string DateFormat = "yyyy-MM-ddTHH:mm:ssZ";
    // How often a device's "last used" date is written while it's in use
    static readonly TimeSpan TouchSaveEvery = TimeSpan.FromMinutes(10);

    public readonly string FilePath;   // null keeps the store in memory only (for tests)
    public byte[] ReceiverId;          // 8 random bytes, made once, sent in PAIR_ACCEPT
    public readonly List<MicPairing> Devices = new List<MicPairing>();
    public readonly List<string> Problems = new List<string>();   // what Load skipped, and why
    readonly Dictionary<string, DateTime> savedLastSeen = new Dictionary<string, DateTime>();
    bool unreadable;

    public MicPairingStore(string path)
    {
        FilePath = path;
    }

    public static string DefaultPath()
    {
        string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Starwire");
        return Path.Combine(folder, "mic-pairings.dat");
    }

    // Reads the file; a missing one is an empty store. Lines that can't be read (damaged, or a key
    // this account can't decrypt) are left out, listed in Problems, and dropped at the next save.
    // A file that can't be opened at all is never overwritten.
    public void Load(Action<byte[]> random)
    {
        Devices.Clear();
        Problems.Clear();
        savedLastSeen.Clear();
        ReceiverId = null;
        unreadable = false;
        string[] lines = new string[0];
        if (FilePath != null && File.Exists(FilePath)) {
            try {
                lines = File.ReadAllText(FilePath, Encoding.UTF8).Split('\n');
            }
            catch (IOException e) {
                unreadable = true;
                Problems.Add("couldn't read " + FilePath + ": " + e.Message);
            }
            catch (UnauthorizedAccessException e) {
                unreadable = true;
                Problems.Add("couldn't read " + FilePath + ": " + e.Message);
            }
        }
        for (int i = 0; i < lines.Length; i++) {
            string line = lines[i].TrimEnd('\r');
            if (line.Length == 0 || line[0] == '#') {
                continue;
            }
            string[] f = line.Split('\t');
            if (f[0] == "receiver") {
                byte[] id = f.Length == 2 ? ParseHex(f[1], 8) : null;
                if (id == null) {
                    Problems.Add("line " + (i + 1) + ": not a receiver id");
                }
                else {
                    ReceiverId = id;
                }
                continue;
            }
            if (f[0] != "device") {
                continue;   // written by a later version
            }
            string why;
            MicPairing d = ParseDevice(f, out why);
            if (d == null) {
                Problems.Add("line " + (i + 1) + ": " + why);
                continue;
            }
            if (Find(d.DeviceId) != null) {
                Problems.Add("line " + (i + 1) + ": the same device twice");
                continue;
            }
            Devices.Add(d);
            savedLastSeen[d.Id] = d.LastSeen;
        }
        if (ReceiverId == null) {
            ReceiverId = new byte[8];
            random(ReceiverId);
        }
    }

    static MicPairing ParseDevice(string[] f, out string why)
    {
        why = null;
        if (f.Length != 6) {
            why = "wrong number of fields";
            return null;
        }
        MicPairing d = new MicPairing();
        d.DeviceId = ParseHex(f[1], 8);
        if (d.DeviceId == null) {
            why = "not a device id";
            return null;
        }
        try {
            d.Key = KeyProtection.Unprotect(Convert.FromBase64String(f[2]), Entropy);
        }
        catch (FormatException) {
            why = "damaged key";
            return null;
        }
        catch (CryptographicException) {
            why = "a key this Windows account can't decrypt";
            return null;
        }
        if (d.Key.Length != 32) {
            why = "damaged key";
            return null;
        }
        d.Protected = f[2];
        if (!ParseDate(f[3], out d.PairedAt)) {
            why = "damaged date";
            return null;
        }
        d.LastSeen = DateTime.MinValue;
        if (f[4] != "-" && !ParseDate(f[4], out d.LastSeen)) {
            why = "damaged date";
            return null;
        }
        d.Name = Swmc.CleanName(f[5], "Android device");
        return d;
    }

    static bool ParseDate(string s, out DateTime date)
    {
        return DateTime.TryParseExact(s, DateFormat, CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out date);
    }

    static string Date(DateTime d) { return d.ToUniversalTime().ToString(DateFormat, CultureInfo.InvariantCulture); }

    static byte[] ParseHex(string s, int bytes)
    {
        if (s == null || s.Length != bytes * 2) {
            return null;
        }
        foreach (char c in s) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }
        return Swmc.Hex(s);
    }

    public MicPairing Find(byte[] deviceId)
    {
        foreach (MicPairing d in Devices) {
            if (Swmc.ConstantTimeEquals(d.DeviceId, 0, deviceId, 0, 8)) {
                return d;
            }
        }
        return null;
    }

    public void Save()
    {
        if (FilePath == null) {
            return;
        }
        if (unreadable) {
            throw new IOException("the pairings file couldn't be read when the receiver started, so it isn't overwritten. Restart the receiver.");
        }
        StringBuilder sb = new StringBuilder(Title);
        sb.Append("receiver\t").Append(Swmc.Hex(ReceiverId)).Append('\n');
        foreach (MicPairing d in Devices) {
            sb.Append("device\t").Append(d.Id).Append('\t').Append(d.Protected).Append('\t').Append(Date(d.PairedAt))
                .Append('\t').Append(d.LastSeen == DateTime.MinValue ? "-" : Date(d.LastSeen)).Append('\t').Append(d.Name).Append('\n');
        }
        KeyProtection.CreateFolder(Path.GetDirectoryName(Path.GetFullPath(FilePath)));
        string temp = FilePath + ".new";
        KeyProtection.WriteFile(temp, sb.ToString());
        if (File.Exists(FilePath)) {
            try {
                File.Replace(temp, FilePath, null);
            }
            catch (IOException) {
                // A volume that can't swap files (FAT, some network drives)
                File.Copy(temp, FilePath, true);
                File.Delete(temp);
            }
        }
        else {
            File.Move(temp, FilePath);
        }
        foreach (MicPairing d in Devices) {
            savedLastSeen[d.Id] = d.LastSeen;
        }
    }

    // Adds a device, or replaces the one with the same id (it paired again), and saves. If saving
    // fails, nothing changes and the error is thrown.
    public void Put(MicPairing pairing)
    {
        if (pairing.Protected == null) {
            pairing.Protected = Convert.ToBase64String(KeyProtection.Protect(pairing.Key, Entropy));
        }
        List<MicPairing> before = new List<MicPairing>(Devices);
        Devices.RemoveAll(d => Swmc.ConstantTimeEquals(d.DeviceId, 0, pairing.DeviceId, 0, 8));
        Devices.Add(pairing);
        try {
            Save();
        }
        catch {
            Devices.Clear();
            Devices.AddRange(before);
            throw;
        }
    }

    // Removes a device and saves; as with Put, nothing changes if saving fails
    public bool Remove(byte[] deviceId)
    {
        MicPairing d = Find(deviceId);
        if (d == null) {
            return false;
        }
        int at = Devices.IndexOf(d);
        Devices.RemoveAt(at);
        try {
            Save();
        }
        catch {
            Devices.Insert(at, d);
            throw;
        }
        savedLastSeen.Remove(d.Id);
        return true;
    }

    // The device's audio played: remember when, writing the file at most every TouchSaveEvery
    public void Touch(MicPairing d, DateTime when)
    {
        d.LastSeen = when;
        DateTime saved;
        if (savedLastSeen.TryGetValue(d.Id, out saved) && saved != DateTime.MinValue && when - saved < TouchSaveEvery) {
            return;
        }
        try {
            Save();
        }
        catch (IOException) {
            // Only the date; the next touch tries again
        }
        catch (UnauthorizedAccessException) {
        }
    }
}

// How a "Pair a device" window stands. UsedTwice: after it paired, another device used the code
// too, so someone else saw it and the pairing was undone.
enum PairingState { Open, Paired, Expired, TooManyTries, Cancelled, UsedTwice }

sealed class PairingStatus
{
    public PairingState State;
    public string Code;
    public long RemainingMs;
    public string DeviceName;   // once paired
    public string Address;      // where the pairing came from
    public byte[] DeviceId;
}

// One "Pair a device" window: a code that works once, for LifetimeMs, and stops working after
// MaxWrongCodes requests made with another code.
//
// Anyone who sees the code before the user has typed it can pair first: someone watching a screen
// share, say, or reading a stream that isn't encrypted. Then the user's own device uses the code
// too, and two devices having it shows it was seen, so the first pairing is undone and the user is
// told. The same goes for anyone using it after the user, until the next code. A request with a
// wrong code can't undo anything.
sealed class PairingSession
{
    public const long LifetimeMs = 5 * 60 * 1000;
    public const int MaxWrongCodes = 10;
    // After pairing, the same request (the device sends it again until it hears back, and maybe
    // to more than one of this PC's addresses) gets the same answer for this long
    public const long RepeatMs = 60 * 1000;
    // Requests already counted as wrong, so a device repeating one doesn't use up the tries
    const int RememberWrong = 32;

    readonly byte[] code;
    readonly byte[] codeKey;
    readonly long started;
    readonly byte[] receiverId;
    readonly string pcName;
    readonly Func<EcdhKey> newKey;
    readonly Func<DateTime> utcNow;
    // Keeps a new pairing; returns why it couldn't, or null
    readonly Func<MicPairing, string> keep;
    PairingState state = PairingState.Open;
    int wrongCodes;
    readonly List<string> wrongRequests = new List<string>();
    byte[] acceptedRequest, accept;
    long pairedAt;
    MicPairing made;
    string madeFrom;

    public PairingSession(byte[] code, long now, byte[] receiverId, string pcName, Func<EcdhKey> newKey,
        Func<DateTime> utcNow, Func<MicPairing, string> keep)
    {
        this.code = (byte[]) code.Clone();
        codeKey = Swmc.CodeKey(code);
        started = now;
        this.receiverId = receiverId;
        this.pcName = pcName;
        this.newKey = newKey;
        this.utcNow = utcNow;
        this.keep = keep;
    }

    public int WrongCodes { get { return wrongCodes; } }

    public PairingState State(long now)
    {
        if (state == PairingState.Open && now - started >= LifetimeMs) {
            state = PairingState.Expired;
        }
        return state;
    }

    public PairingStatus Status(long now)
    {
        PairingStatus s = new PairingStatus();
        s.State = State(now);
        s.Code = Swmc.EncodeCode(code);
        s.RemainingMs = s.State == PairingState.Open ? started + LifetimeMs - now : 0;
        if (made != null) {
            s.DeviceName = made.Name;
            s.Address = madeFrom;
            s.DeviceId = (byte[]) made.DeviceId.Clone();
        }
        return s;
    }

    public void Cancel()
    {
        if (state == PairingState.Open) {
            state = PairingState.Cancelled;
        }
    }

    // The answer to a PAIR_REQUEST from `from`, already checked by Swmc.ParsePairRequest: a
    // PAIR_ACCEPT, a PAIR_REJECT, or null for none. paired is set when this request made a new
    // pairing, and undo when it showed the code was seen, so the pairing made with it has to go.
    public byte[] Handle(byte[] p, int length, long now, string from, out MicPairing paired, out MicPairing undo)
    {
        paired = null;
        undo = null;
        PairingState current = State(now);
        if (current == PairingState.Paired) {
            if (length == acceptedRequest.Length && Swmc.ConstantTimeEquals(p, 0, acceptedRequest, 0, length)) {
                // The device that paired, asking again
                return now - pairedAt < RepeatMs ? (byte[]) accept.Clone() : Swmc.PairReject(p, Swmc.RejectNotPairing);
            }
            if (Swmc.MacOk(codeKey, p, length)) {
                state = PairingState.UsedTwice;
                undo = made;
                return Swmc.PairReject(p, Swmc.RejectExpired);
            }
            return Swmc.PairReject(p, Swmc.RejectNotPairing);
        }
        if (current == PairingState.Cancelled) {
            return Swmc.PairReject(p, Swmc.RejectNotPairing);
        }
        if (current != PairingState.Open) {
            // Expired, too many wrong codes, or used twice: a new code is needed
            return Swmc.PairReject(p, Swmc.RejectExpired);
        }
        if (!Swmc.MacOk(codeKey, p, length)) {
            string request = Swmc.Hex(p, 8, 16);   // the device's id and this request's nonce
            if (!wrongRequests.Contains(request)) {
                if (wrongRequests.Count >= RememberWrong) {
                    wrongRequests.RemoveAt(0);
                }
                wrongRequests.Add(request);
                wrongCodes++;
                if (wrongCodes >= MaxWrongCodes) {
                    state = PairingState.TooManyTries;
                }
            }
            return Swmc.PairReject(p, Swmc.RejectWrongCode);
        }

        byte[] deviceId = Swmc.Slice(p, 8, 8);
        byte[] nonce = Swmc.Slice(p, 16, 8);
        byte[] phonePoint = Swmc.Slice(p, Swmc.Header, Swmc.PointLength);
        byte[] receiverPoint, z;
        try {
            using (EcdhKey mine = newKey()) {
                receiverPoint = Swmc.OwnPoint(mine);
                z = Swmc.EcdhHash(mine, phonePoint, 0);
            }
        }
        catch (CryptographicException) {
            // Not a point on P-256 (CNG checks): a broken device, since it had the code
            return null;
        }
        MicPairing pairing = new MicPairing();
        pairing.DeviceId = deviceId;
        pairing.Key = Swmc.PairKey(codeKey, z, deviceId, nonce, phonePoint, receiverPoint);
        pairing.Name = Swmc.CleanName(p, Swmc.Header + Swmc.PointLength + 1, p[Swmc.Header + Swmc.PointLength], "Android device");
        pairing.PairedAt = utcNow();
        pairing.LastSeen = DateTime.MinValue;
        if (keep(pairing) != null) {
            return null;
        }
        accept = Swmc.PairAccept(codeKey, pairing.Key, deviceId, nonce, receiverPoint, receiverId, pcName);
        acceptedRequest = Swmc.Slice(p, 0, length);
        pairedAt = now;
        made = pairing;
        madeFrom = from;
        state = PairingState.Paired;
        paired = pairing;
        return (byte[]) accept.Clone();
    }
}

// Lets each key (a kind of warning, an address) through at most once every intervalMs, keeping
// at most `capacity` keys. When it's full, keys whose time is up make room; if none has, a new key
// is refused, so faked addresses can neither use up memory nor push out the keys already there.
sealed class RateLimit
{
    readonly Dictionary<string, long> last = new Dictionary<string, long>();
    readonly int capacity;
    readonly long intervalMs;
    long fullUntil = long.MinValue;   // while full: when the oldest key's time is up

    public RateLimit(int capacity, long intervalMs)
    {
        this.capacity = capacity;
        this.intervalMs = intervalMs;
    }

    public bool Allow(string key, long now)
    {
        long then;
        if (last.TryGetValue(key, out then)) {
            if (now - then < intervalMs) {
                return false;
            }
        }
        else if (last.Count >= capacity) {
            if (now < fullUntil) {
                return false;
            }
            List<string> old = new List<string>();
            long oldest = long.MaxValue;
            foreach (KeyValuePair<string, long> kv in last) {
                if (now - kv.Value >= intervalMs) {
                    old.Add(kv.Key);
                }
                else {
                    oldest = Math.Min(oldest, kv.Value);
                }
            }
            foreach (string k in old) {
                last.Remove(k);
            }
            if (last.Count >= capacity) {
                fullUntil = oldest + intervalMs;
                return false;
            }
        }
        last[key] = now;
        return true;
    }
}

// What the receiver has to show on its icon, gathered from other threads. However many reports
// come, at most one call waits for the UI thread (Add says when to post one), the status shows
// only the latest, and notifications come at least GapMs apart: a few wait their turn, and when
// more come the oldest waiting one is dropped.
sealed class ReportQueue
{
    public const long GapMs = 4000;
    public const int MaxWaiting = 4;

    readonly object gate = new object();
    readonly Queue<string> notes = new Queue<string>();
    string status;
    bool posted;
    long lastNote = long.MinValue / 2;

    // From any thread. True when the caller has to post a call to TakeStatus and TakeNote to the
    // UI thread; false when one is already waiting.
    public bool Add(string text, bool important)
    {
        lock (gate) {
            if (!important) {
                status = text;
            }
            else if (!notes.Contains(text)) {
                if (notes.Count >= MaxWaiting) {
                    notes.Dequeue();
                }
                notes.Enqueue(text);
            }
            if (posted) {
                return false;
            }
            posted = true;
            return true;
        }
    }

    // On the UI thread, in the posted call: the status to show, or null if it hasn't changed
    public string TakeStatus()
    {
        lock (gate) {
            posted = false;
            string s = status;
            status = null;
            return s;
        }
    }

    // On the UI thread, in the posted call and every second or so: the notification to show now,
    // or null
    public string TakeNote(long now)
    {
        lock (gate) {
            if (notes.Count == 0 || now - lastNote < GapMs) {
                return null;
            }
            lastNote = now;
            return notes.Dequeue();
        }
    }
}

// The receiver's side of the network: takes each datagram and says whether it's audio to play.
// Only audio from a paired device, decrypted and in order, plays, and only from one device at a
// time. Plain VBAN only plays with AllowUnencrypted. Everything an attacker can send is checked
// cheaply, nothing makes the state grow beyond a few sessions per paired device, the receiver
// only answers packets it can check (or with short ones), and its warnings are rate-limited by
// kind, not by address, since addresses can be faked.
//
// A recorded HELLO passes its MAC check for as long as the pairing lasts (v1 has nothing in it
// that changes), so anyone who once saw one can send it again. That only ever makes a waiting
// session, which can't carry audio without the pairing key; the limits below keep it from
// crowding out the device's own sessions.
//
// Thread-safe: the receive thread calls Handle, the icon's menu calls the pairing methods.
// Everything outside (clock, random bytes, sending, reporting) is passed in, so the tests run it
// without sockets or waiting.
sealed class SecureMicEndpoint
{
    public const long FloorMs = 3000;       // the device playing keeps the microphone until it's quiet this long
    public const long PendingMs = 30000;    // a session that hasn't carried audio by then is forgotten
    public const int MaxPending = 2;        // sessions waiting for their first audio, per device
    public const long PendingKeepMs = 2000; // a waiting session this young isn't pushed out by a new HELLO
    public const long RehelloMs = 1000;     // at most one REHELLO a second per device
    public const long WarnMs = 10 * 60 * 1000;   // the same warning at most this often
    public const int MaxAcksPerSecond = 8;  // per session: the device repeats its HELLO 4 times a second
    const int MaxSpent = 1024;              // HELLO random numbers remembered per device
    const int MaxPairingRepliesPerSecond = 20;
    const long OutputCheckMs = 2000;
    const long SaveWarnMs = 30000;

    sealed class Session : IDisposable
    {
        public byte[] ClientRandom;
        public byte[] Id;               // the receiver's random number
        public CngAesGcm Aead;
        public readonly ReplayGuard Guard = new ReplayGuard();
        public long Created;
        public bool Spent;              // made by a HELLO the device had used before: most likely recorded
        long ackSecond = -1;
        int acksThisSecond;

        // HELLO_ACKs are limited for each session, so a recorded HELLO sent again and again only
        // uses up its own
        public bool AllowAck(long now)
        {
            if (now / 1000 != ackSecond) {
                ackSecond = now / 1000;
                acksThisSecond = 0;
            }
            return ++acksThisSecond <= MaxAcksPerSecond;
        }

        public void Dispose() { Aead.Dispose(); }
    }

    sealed class Device
    {
        public MicPairing Pairing;
        public byte[] AuthKey;
        public Session Current;
        public readonly List<Session> Pending = new List<Session>();
        public long LastRehello = long.MinValue / 2;
        // The random numbers of the device's HELLOs whose sessions have ended or been dropped, the
        // last MaxSpent of them. The device makes a new one for each stream, so such a HELLO coming
        // again is most likely recorded and sent again by someone else.
        readonly HashSet<long> spent = new HashSet<long>();
        readonly Queue<long> spentOrder = new Queue<long>();

        public void Spend(Session s)
        {
            long r = BitConverter.ToInt64(s.ClientRandom, 0);
            if (spent.Add(r)) {
                spentOrder.Enqueue(r);
                if (spentOrder.Count > MaxSpent) {
                    spent.Remove(spentOrder.Dequeue());
                }
            }
            s.Dispose();
        }

        public bool IsSpent(byte[] p, int at) { return spent.Contains(BitConverter.ToInt64(p, at)); }

        public void Drop()
        {
            if (Current != null) {
                Current.Dispose();
                Current = null;
            }
            foreach (Session s in Pending) {
                s.Dispose();
            }
            Pending.Clear();
        }
    }

    readonly object gate = new object();
    readonly MicPairingStore store;
    readonly Func<long> nowMs;
    readonly Action<byte[]> random;
    readonly Action<byte[], int, EndPoint> send;
    readonly Action<string, bool> report;
    readonly Dictionary<string, Device> devices = new Dictionary<string, Device>();
    // Notifications: each kind at most once every WarnMs, however many addresses it's about. The
    // kinds are fixed, apart from "busy" for each paired device.
    readonly RateLimit notices = new RateLimit(1024, WarnMs);
    // Lines for --console about each address, at most once every WarnMs and for 256 at a time
    readonly RateLimit details = new RateLimit(256, WarnMs);
    // For each kind of warning, the other addresses since its last notification
    readonly Dictionary<string, int> othersSince = new Dictionary<string, int>();
    long saveWarnedAt = long.MinValue / 2;
    PairingSession pairing;
    PairingState pairingReported = PairingState.Open;
    Device floor;
    long floorLast;
    long pairingSecond = -1;
    int pairingRepliesThisSecond;
    bool outputReady = true;
    long outputCheckedAt = long.MinValue / 2;

    // Plays plain VBAN named StreamName from home-network addresses too (--allow-unencrypted)
    public bool AllowUnencrypted;
    public byte[] StreamName = Vban.StreamNameBytes("Stream1");
    // Whether the output is there, for HELLO_ACK's status; asked at most every 2 seconds
    public Func<bool> OutputReady;
    public Func<EcdhKey> NewPairingKey = () => Swmc.NewKey();
    public Func<DateTime> UtcNow = () => DateTime.UtcNow;
    public string PcName = Environment.MachineName;
    // How the user pairs a device, pairs again and unpairs one, as the receiver's warnings say it.
    // The defaults are the Windows icon's; the Linux receiver has commands instead.
    public string HowToPair = "right-click this icon > Pair a device";
    public string HowToPairAgain = "Choose Pair a device again";
    public string HowToUnpair = "from this icon's Paired devices";

    // What happened to the packets, for --console and the tests
    public int Played, ReplayedOrLate, Forged, Unencrypted, Unpaired, UnknownSession, Busy, NotHome, Malformed, Errors;

    // report(text, important): important ones are worth a notification; the others are details
    // for --console
    public SecureMicEndpoint(MicPairingStore store, Func<long> nowMs, Action<byte[]> random, Action<byte[], int, EndPoint> send, Action<string, bool> report)
    {
        this.store = store;
        this.nowMs = nowMs;
        this.random = random;
        this.send = send;
        this.report = report;
        foreach (MicPairing p in store.Devices) {
            AddDevice(p);
        }
    }

    // Takes one datagram. Returns true when it's audio to play: the VBAN packet is then in vbanOut
    // (vbanLength bytes), sender names who sent it, and encrypted says whether it came through SWMC.
    public bool Handle(byte[] p, int length, EndPoint from, byte[] vbanOut, out int vbanLength, out string sender, out bool encrypted)
    {
        vbanLength = 0;
        sender = null;
        encrypted = false;
        lock (gate) {
            try {
                return HandleLocked(p, length, from, vbanOut, ref vbanLength, ref sender, ref encrypted);
            }
            catch (Exception e) {
                // Shouldn't happen (the fuzz tests look for it); keep the receiver going if it does
                Errors++;
                if (notices.Allow("error", nowMs())) {
                    report("Ignored a packet that couldn't be handled: " + e.Message, false);
                }
                vbanLength = 0;
                return false;
            }
        }
    }

    bool HandleLocked(byte[] p, int length, EndPoint from, byte[] vbanOut, ref int vbanLength, ref string sender, ref bool encrypted)
    {
        bool plain = Vban.HasMagic(p, length);
        bool swmc = length >= 4 && p[0] == 'S' && p[1] == 'W' && p[2] == 'M' && p[3] == 'C';
        if (!plain && !swmc) {
            Malformed++;
            return false;
        }
        long now = nowMs();
        IPAddress source = Vban.Source(from);
        if (!Vban.IsHomeNetwork(source)) {
            NotHome++;
            Warn("home", source, now, "Ignoring audio from ", ": not on the home network.");
            return false;
        }
        if (plain) {
            return HandlePlain(p, length, source, now, vbanOut, ref vbanLength, ref sender);
        }
        switch (Swmc.TypeOf(p, length)) {
            case Swmc.THello:
                HandleHello(p, length, from, source, now);
                return false;
            case Swmc.TAudio:
                if (!HandleAudio(p, length, from, source, now, vbanOut, ref vbanLength, ref sender)) {
                    return false;
                }
                encrypted = true;
                return true;
            case Swmc.TPairRequest:
                HandlePairRequest(p, length, from, source, now);
                return false;
            default:
                // Not v1, or a type only the device receives
                Malformed++;
                return false;
        }
    }

    // A warning about packets from an address. A source address costs nothing to fake, so each
    // kind is a notification at most once every WarnMs, naming one address and how many others
    // there were since the last; each address is a line for --console at most once every WarnMs.
    // Both are capped, and neither can push out what the other warnings remember.
    void Warn(string kind, IPAddress source, long now, string before, string after)
    {
        bool newAddress = details.Allow(kind + " " + source, now);
        int others;
        othersSince.TryGetValue(kind, out others);
        if (notices.Allow(kind, now)) {
            othersSince[kind] = 0;
            report(before + source + (others == 0 ? "" : " and " + others + (others == 1 ? " other address" : " other addresses")) + after, true);
        }
        else if (newAddress) {
            othersSince[kind] = others + 1;
            report(before + source + after, false);
        }
    }

    bool HandlePlain(byte[] p, int length, IPAddress source, long now, byte[] vbanOut, ref int vbanLength, ref string sender)
    {
        if (!AllowUnencrypted) {
            Unencrypted++;
            Warn("plain", source, now, "Ignored unencrypted microphone audio from ",
                ". If that's Starwire, update it and pair it: " + HowToPair + ".");
            return false;
        }
        if (length <= Vban.HeaderSize || length > vbanOut.Length || !Vban.NameMatches(p, StreamName)) {
            Malformed++;
            return false;
        }
        Array.Copy(p, vbanOut, length);
        vbanLength = length;
        sender = source.ToString();
        Played++;
        return true;
    }

    Device Known(byte[] p, IPAddress source, long now)
    {
        Device d;
        if (devices.TryGetValue(Swmc.Hex(p, 8, 8), out d)) {
            return d;
        }
        Unpaired++;
        Warn("unpaired", source, now, "A device this PC hasn't paired tried to send its microphone (from ",
            "). To allow it, " + HowToPair + ".");
        return null;
    }

    void HandleHello(byte[] p, int length, EndPoint from, IPAddress source, long now)
    {
        if (length != Swmc.HelloLength) {
            Malformed++;
            return;
        }
        Device d = Known(p, source, now);
        if (d == null) {
            return;
        }
        if (!Swmc.MacOk(d.AuthKey, p, length)) {
            Forged++;
            return;
        }
        ExpirePending(d, now);
        // The device repeats its HELLO until it hears back: the same HELLO gets the same session
        Session s = null;
        if (d.Current != null && Swmc.ConstantTimeEquals(d.Current.ClientRandom, 0, p, 16, 8)) {
            s = d.Current;
        }
        foreach (Session pending in d.Pending) {
            if (s == null && Swmc.ConstantTimeEquals(pending.ClientRandom, 0, p, 16, 8)) {
                s = pending;
            }
        }
        if (s == null) {
            // A new session, kept aside until its first audio: a HELLO someone recorded and sent
            // again can't push the current session off. If there's no room, nothing is made and
            // the device asks again.
            bool spent = d.IsSpent(p, 16);
            if (!MakeRoom(d, spent, now)) {
                return;
            }
            s = new Session();
            s.ClientRandom = Swmc.Slice(p, 16, 8);
            s.Id = new byte[8];
            random(s.Id);
            s.Aead = new CngAesGcm(Swmc.AudioKey(d.Pairing.Key, d.Pairing.DeviceId, s.ClientRandom, s.Id));
            s.Created = now;
            s.Spent = spent;
            d.Pending.Add(s);
        }
        if (!s.AllowAck(now)) {
            return;
        }
        int status = Swmc.StatusReady;
        if (HasFloor(d, now)) {
            status = Swmc.StatusBusy;
        }
        else if (!OutputThere(now)) {
            status = Swmc.StatusNoOutput;
        }
        byte[] ack = Swmc.HelloAck(d.AuthKey, d.Pairing.DeviceId, s.ClientRandom, s.Id, status);
        send(ack, ack.Length, from);
    }

    // Another device is playing and hasn't been quiet for FloorMs
    bool HasFloor(Device d, long now)
    {
        return floor != null && floor != d && now - floorLast < FloorMs;
    }

    bool OutputThere(long now)
    {
        if (OutputReady != null && now - outputCheckedAt >= OutputCheckMs) {
            outputCheckedAt = now;
            outputReady = OutputReady();
        }
        return outputReady;
    }

    // Room for a new waiting session, if there is or can be some. Recorded HELLOs, however many
    // and however often, mustn't keep the device's new session out or make a session for every
    // packet. So a HELLO the device has used before (spent) only takes a free place; a new one can
    // push out a waiting session made by a spent HELLO, or else the oldest once it's PendingKeepMs
    // old, by when the device would have used it. Someone with recordings this receiver hasn't
    // seen (it restarted) can keep the device waiting about a second for each, once.
    bool MakeRoom(Device d, bool spent, long now)
    {
        if (d.Pending.Count < MaxPending) {
            return true;
        }
        if (spent) {
            return false;
        }
        Session victim = null;
        foreach (Session w in d.Pending) {
            if (victim == null && w.Spent) {
                victim = w;
            }
        }
        if (victim == null && now - d.Pending[0].Created >= PendingKeepMs) {
            victim = d.Pending[0];
        }
        if (victim == null) {
            return false;
        }
        d.Pending.Remove(victim);
        d.Spend(victim);
        return true;
    }

    void ExpirePending(Device d, long now)
    {
        for (int i = d.Pending.Count - 1; i >= 0; i--) {
            if (now - d.Pending[i].Created >= PendingMs) {
                d.Spend(d.Pending[i]);
                d.Pending.RemoveAt(i);
            }
        }
    }

    bool HandleAudio(byte[] p, int length, EndPoint from, IPAddress source, long now, byte[] vbanOut, ref int vbanLength, ref string sender)
    {
        if (length < Swmc.AudioAad + 1 + Swmc.TagLength || length > Swmc.AudioAad + Swmc.MaxAudioPlaintext + Swmc.TagLength
                || length - Swmc.AudioAad - Swmc.TagLength > vbanOut.Length) {
            Malformed++;
            return false;
        }
        Device d = Known(p, source, now);
        if (d == null) {
            return false;
        }
        ExpirePending(d, now);
        Session s = null;
        if (d.Current != null && Swmc.ConstantTimeEquals(d.Current.Id, 0, p, 16, 8)) {
            s = d.Current;
        }
        foreach (Session pending in d.Pending) {
            if (s == null && Swmc.ConstantTimeEquals(pending.Id, 0, p, 16, 8)) {
                s = pending;
            }
        }
        if (s == null) {
            // A session this receiver doesn't know (it restarted, or the session was replaced):
            // ask the device for a new one. The audio can't be checked, so at most once a second.
            UnknownSession++;
            if (now - d.LastRehello >= RehelloMs) {
                d.LastRehello = now;
                byte[] rehello = Swmc.Rehello(d.AuthKey, d.Pairing.DeviceId, Swmc.Slice(p, 16, 8));
                send(rehello, rehello.Length, from);
            }
            return false;
        }
        int n = Swmc.OpenAudio(s.Aead, p, length, vbanOut);
        if (n < 0) {
            Forged++;
            return false;
        }
        // Only now that the tag checked out does the counter move
        if (!s.Guard.Accept(Swmc.GetU32(p, Swmc.CounterAt))) {
            ReplayedOrLate++;
            return false;
        }
        if (s != d.Current) {
            // The device's first audio on a new session: it replaces the old one
            if (d.Current != null) {
                d.Spend(d.Current);
            }
            d.Current = s;
            d.Pending.Remove(s);
        }
        if (HasFloor(d, now)) {
            Busy++;
            if (notices.Allow("busy " + d.Pairing.Id, now)) {
                report(floor.Pairing.Name + " is sending its microphone; ignoring " + d.Pairing.Name + " until it stops.", true);
            }
            return false;
        }
        floor = d;
        floorLast = now;
        store.Touch(d.Pairing, UtcNow());
        Played++;
        vbanLength = n;
        sender = d.Pairing.Name;
        return true;
    }

    void HandlePairRequest(byte[] p, int length, EndPoint from, IPAddress source, long now)
    {
        int nameLength;
        if (!Swmc.ParsePairRequest(p, length, out nameLength)) {
            Malformed++;
            return;
        }
        byte[] reply;
        if (pairing == null) {
            reply = Swmc.PairReject(p, Swmc.RejectNotPairing);
        }
        else {
            MicPairing made, undo;
            reply = pairing.Handle(p, length, now, source.ToString(), out made, out undo);
            if (made != null) {
                AddDevice(made);
                report("Paired with " + made.Name + " (from " + source + ").", true);
            }
            if (undo != null) {
                Undo(undo);
            }
            ReportPairing(now);
        }
        if (reply == null) {
            return;
        }
        // Pairing replies aren't much bigger than what asks for them, and only so many go out
        long second = now / 1000;
        if (second != pairingSecond) {
            pairingSecond = second;
            pairingRepliesThisSecond = 0;
        }
        if (++pairingRepliesThisSecond <= MaxPairingRepliesPerSecond) {
            send(reply, reply.Length, from);
        }
    }

    // Says when the window stops working by itself
    void ReportPairing(long now)
    {
        PairingState state = pairing.State(now);
        if (state == pairingReported) {
            return;
        }
        pairingReported = state;
        if (state == PairingState.TooManyTries) {
            report("Pairing stopped after too many wrong codes. " + HowToPairAgain + " for a new code.", true);
        }
        else if (state == PairingState.Expired) {
            report("The pairing code expired. " + HowToPairAgain + " for a new one.", false);
        }
    }

    // Keeps a pairing the session made: saved first, so a pairing that couldn't be saved is never
    // confirmed to the device
    string Keep(MicPairing made)
    {
        try {
            store.Put(made);
            return null;
        }
        catch (Exception e) {
            if (e is IOException || e is UnauthorizedAccessException || e is CryptographicException) {
                // The device asks again every half second; one notification is enough
                long now = nowMs();
                if (now - saveWarnedAt >= SaveWarnMs) {
                    saveWarnedAt = now;
                    report("Couldn't save the pairing with " + made.Name + " (" + e.Message + "). Try again.", true);
                }
                return e.Message;
            }
            throw;
        }
    }

    // The pairing window found its code was used twice: the device that paired with it goes, as
    // if unpaired. If the file can't be written, it's still refused until the receiver restarts.
    void Undo(MicPairing made)
    {
        string trouble = null;
        try {
            store.Remove(made.DeviceId);
        }
        catch (Exception e) {
            if (!(e is IOException || e is UnauthorizedAccessException)) {
                throw;
            }
            trouble = e.Message;
        }
        Forget(made.DeviceId);
        report("The pairing code was used twice, so someone else may have seen it. To be safe, " + made.Name
            + " isn't paired any more. " + HowToPairAgain + " for a new code."
            + (trouble == null ? "" : " (Couldn't update the paired devices file: " + trouble + ". Unpair " + made.Name
                + " " + HowToUnpair + ".)"), true);
    }

    // Ends a device's sessions and refuses its audio from now on
    void Forget(byte[] deviceId)
    {
        Device d;
        string id = Swmc.Hex(deviceId);
        if (devices.TryGetValue(id, out d)) {
            d.Drop();
            devices.Remove(id);
            if (floor == d) {
                floor = null;
            }
        }
    }

    void AddDevice(MicPairing p)
    {
        Device old;
        if (devices.TryGetValue(p.Id, out old)) {
            // Paired again: its old sessions go with the old key
            old.Drop();
            if (floor == old) {
                floor = null;
            }
        }
        Device d = new Device();
        d.Pairing = p;
        d.AuthKey = Swmc.AuthKey(p.Key, p.DeviceId);
        devices[p.Id] = d;
    }

    // ---------------------------------------------------------------- for the icon's menu
    // Opens pairing with a new code, closing any earlier one; returns the code to show
    public string BeginPairing()
    {
        lock (gate) {
            byte[] code = new byte[10];
            random(code);
            pairing = new PairingSession(code, nowMs(), store.ReceiverId, PcName, NewPairingKey, UtcNow, Keep);
            pairingReported = PairingState.Open;
            return Swmc.EncodeCode(code);
        }
    }

    public void CancelPairing()
    {
        lock (gate) {
            if (pairing != null) {
                pairing.Cancel();
            }
        }
    }

    // How the pairing window stands, or null if none was opened
    public PairingStatus Pairing
    {
        get {
            lock (gate) {
                if (pairing == null) {
                    return null;
                }
                ReportPairing(nowMs());
                return pairing.Status(nowMs());
            }
        }
    }

    public int WrongCodes
    {
        get {
            lock (gate) {
                return pairing == null ? 0 : pairing.WrongCodes;
            }
        }
    }

    public int PairedCount
    {
        get {
            lock (gate) {
                return devices.Count;
            }
        }
    }

    // Copies of the paired devices, oldest pairing first
    public List<MicPairing> Devices()
    {
        lock (gate) {
            List<MicPairing> list = new List<MicPairing>();
            foreach (MicPairing p in store.Devices) {
                list.Add(p.Copy());
            }
            list.Sort((a, b) => a.PairedAt.CompareTo(b.PairedAt));
            return list;
        }
    }

    // Forgets a device: its sessions end now, and it has to pair again to send audio. Throws if
    // the file can't be saved (the device then stays paired).
    public bool Unpair(byte[] deviceId)
    {
        lock (gate) {
            if (!store.Remove(deviceId)) {
                return false;
            }
            Forget(deviceId);
            return true;
        }
    }

    public string Counts()
    {
        lock (gate) {
            return "played " + Played + ", replayed or late " + ReplayedOrLate + ", forged " + Forged + ", unencrypted " + Unencrypted
                + ", unpaired " + Unpaired + ", unknown session " + UnknownSession + ", busy " + Busy + ", not home network " + NotHome
                + ", malformed " + Malformed + ", wrong codes " + (pairing == null ? 0 : pairing.WrongCodes) + ", errors " + Errors;
        }
    }
}
