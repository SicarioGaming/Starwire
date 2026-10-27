// The USB helper's side of the USB tunnel (GPL-3.0, part of Starwire), as docs/dev/usb-protocol.md
// describes it: a TLS server for Starwire on TCP 6981, BIND checked against the microphone
// receiver's pairings file (read only, fresh at each BIND), the per-phone Allow USB list, then a
// relay on 127.0.0.1 that the usbip-win2 driver attaches the shared device through (UsbRelay.cs).
//
// Everything that arrives from the network is untrusted until BIND checks out: before that a
// connection gets a TLS handshake and at most a REFUSE, at most 4 connections wait for their BIND
// (1 for each address), each for 10 s, and the pairings file is read at most 4 times a second.
//
// Built with the C# compiler that comes with Windows, like the rest of the helper, so it's C# 5.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;

// ---------------------------------------------------------------- time
// Milliseconds that only go forward, so the tests can use a clock of their own
interface IUsbClock
{
    long Now { get; }
    void Sleep(int ms);
}

sealed class UsbSystemClock : IUsbClock
{
    static readonly Stopwatch watch = Stopwatch.StartNew();

    public long Now { get { return watch.ElapsedMilliseconds; } }

    public void Sleep(int ms)
    {
        Thread.Sleep(ms);
    }
}

// ---------------------------------------------------------------- reading the pairings file
// At most 4 reads of the pairings file a second, in all. A read that comes when they're used up
// waits its turn, until its deadline.
sealed class UsbReadLimiter
{
    public const int PerSecond = 4;
    readonly IUsbClock clock;
    readonly Queue<long> recent = new Queue<long>();
    readonly object gate = new object();

    public UsbReadLimiter(IUsbClock clock)
    {
        this.clock = clock;
    }

    // True when a read may go ahead now; false if its turn wouldn't come before the deadline
    public bool WaitTurn(long deadline)
    {
        while (true) {
            long wait;
            lock (gate) {
                long now = clock.Now;
                while (recent.Count > 0 && now - recent.Peek() >= 1000) {
                    recent.Dequeue();
                }
                if (recent.Count < PerSecond) {
                    recent.Enqueue(now);
                    return true;
                }
                wait = recent.Peek() + 1000 - now;
                if (now + wait > deadline) {
                    return false;
                }
            }
            clock.Sleep((int) Math.Max(1, wait));
        }
    }
}

// One device line of the pairings file, without its key decrypted
sealed class UsbPairingLine
{
    public byte[] DeviceId;
    // The base64 text of the encrypted key, exactly as the file has it
    public string KeyField;
    public string Name;

    public string Id { get { return Swut.Hex(DeviceId); } }
}

// What a BIND needs: K_usb, and whether there was a pairing at all
sealed class UsbBindKey
{
    public byte[] UsbKey;
    public bool Paired;
    public string KeyField;
    public string Name;

    public void Wipe()
    {
        Swut.Wipe(UsbKey);
    }
}

// Reads the microphone receiver's pairings file (MicPairingStore in SecureMic.cs writes it) without
// ever writing, moving or locking it, and without its Load, which decrypts every key and opens the
// file shared for reading only. While a file is open shared for reading only, the receiver's save
// (File.Replace, or File.Copy on a volume that can't swap files) fails, so pairing and unpairing
// would fail. This opens it shared for reading, writing and deleting, reads it whole, closes it at
// once, and decrypts at most one key.
sealed class UsbPairingReader
{
    // The receiver's DPAPI entropy for the keys (MicPairingStore)
    static readonly byte[] Entropy = Swut.Ascii("Starwire microphone pairing v1");
    const string DateFormat = "yyyy-MM-ddTHH:mm:ssZ";
    const int MaxFileBytes = 1024 * 1024;

    public readonly string FilePath;
    readonly UsbReadLimiter limiter;
    readonly Action<byte[]> random;
    // A random key this account encrypted when the helper started, decrypted instead of a pairing's
    // when a BIND's device id has none, so that costs the same work as a wrong MAC
    readonly string dummyField;
    int reads;

    public UsbPairingReader(string filePath, UsbReadLimiter limiter, Action<byte[]> random)
    {
        FilePath = filePath;
        this.limiter = limiter;
        this.random = random;
        byte[] dummy = new byte[Swut.KeyLength];
        random(dummy);
        dummyField = Convert.ToBase64String(ProtectedData.Protect(dummy, Entropy, DataProtectionScope.CurrentUser));
        Swut.Wipe(dummy);
    }

    // How many times the file was opened, for the tests
    public int Reads { get { return Thread.VolatileRead(ref reads); } }

    public static string DefaultPath()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Starwire", "mic-pairings.dat");
    }

    // How the helper always opens the file: for reading only, shared for reading, writing and deleting
    public static FileStream OpenShared(string path)
    {
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.SequentialScan);
    }

    // The device lines, read fresh, once a read's turn comes. A missing file has none. Null, with
    // the reason, if the file couldn't be read, or late set if the turn didn't come before the
    // deadline.
    public List<UsbPairingLine> Read(long deadline, out string problem, out bool late)
    {
        late = !limiter.WaitTurn(deadline);
        if (late) {
            problem = "no read of the pairings file was free in time";
            return null;
        }
        return ReadNow(out problem);
    }

    List<UsbPairingLine> ReadNow(out string problem)
    {
        byte[] bytes;
        try {
            Interlocked.Increment(ref reads);
            using (FileStream f = OpenShared(FilePath)) {
                if (f.Length > MaxFileBytes) {
                    problem = "the pairings file is over " + MaxFileBytes / 1024 + " KB";
                    return null;
                }
                MemoryStream m = new MemoryStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = f.Read(buffer, 0, buffer.Length)) > 0) {
                    m.Write(buffer, 0, n);
                    if (m.Length > MaxFileBytes) {
                        problem = "the pairings file is over " + MaxFileBytes / 1024 + " KB";
                        return null;
                    }
                }
                bytes = m.ToArray();
            }
        }
        catch (FileNotFoundException) {
            problem = null;
            return new List<UsbPairingLine>();
        }
        catch (DirectoryNotFoundException) {
            problem = null;
            return new List<UsbPairingLine>();
        }
        catch (IOException e) {
            problem = "couldn't read the pairings file: " + e.Message;
            return null;
        }
        catch (UnauthorizedAccessException e) {
            problem = "couldn't read the pairings file: " + e.Message;
            return null;
        }
        problem = null;
        return Parse(new UTF8Encoding(false, false).GetString(bytes));
    }

    // The device lines the receiver would load, apart from decrypting their keys. Lines that don't
    // read are skipped, as the receiver skips them; of two lines for one device the first counts.
    public static List<UsbPairingLine> Parse(string text)
    {
        List<UsbPairingLine> lines = new List<UsbPairingLine>();
        foreach (string raw in text.Split('\n')) {
            string line = raw.TrimEnd('\r');
            if (line.Length == 0 || line[0] == '#') {
                continue;
            }
            string[] f = line.Split('\t');
            if (f[0] != "device" || f.Length != 6) {
                continue;
            }
            byte[] id = ParseId(f[1]);
            DateTime date;
            if (id == null || f[2].Length == 0 || !ParseDate(f[3], out date) || (f[4] != "-" && !ParseDate(f[4], out date))) {
                continue;
            }
            bool twice = false;
            foreach (UsbPairingLine l in lines) {
                twice |= Swut.ConstantTimeEquals(l.DeviceId, 0, id, 0, Swut.IdLength);
            }
            if (twice) {
                continue;
            }
            UsbPairingLine p = new UsbPairingLine();
            p.DeviceId = id;
            p.KeyField = f[2];
            p.Name = Swut.CleanName(new UTF8Encoding(false).GetBytes(f[5]), "Android device");
            lines.Add(p);
        }
        return lines;
    }

    static byte[] ParseId(string s)
    {
        if (s == null || s.Length != 2 * Swut.IdLength) {
            return null;
        }
        foreach (char c in s) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }
        return Swut.Hex(s);
    }

    static bool ParseDate(string s, out DateTime date)
    {
        return DateTime.TryParseExact(s, DateFormat, CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out date);
    }

    // The line for this device id, found in constant time: every line is compared, and the first
    // match is picked without a branch on the result. Null if there's none.
    public static UsbPairingLine Find(List<UsbPairingLine> lines, byte[] deviceId)
    {
        int index = 0;
        int found = 0;
        for (int i = 0; i < lines.Count; i++) {
            int diff = 0;
            for (int j = 0; j < Swut.IdLength; j++) {
                diff |= lines[i].DeviceId[j] ^ deviceId[j];
            }
            int equal = ((diff | -diff) >> 31) + 1;   // 1 when diff is 0, else 0
            int take = equal & (found ^ 1);
            index = (index & ~(-take)) | (i & -take);
            found |= equal;
        }
        return found == 1 ? lines[index] : null;
    }

    // For a BIND: works out K_usb from this device's pairing key and wipes the pairing key. With no
    // pairing it decrypts the dummy key instead and hands back a random K_usb, so an unknown device
    // id and a wrong MAC get the same answer after the same work. Null if the read's turn didn't come
    // before the deadline.
    public UsbBindKey ForBind(byte[] deviceId, long deadline, out string problem)
    {
        bool late;
        List<UsbPairingLine> lines = Read(deadline, out problem, out late);
        if (late) {
            return null;
        }
        // A file that can't be read has no pairings
        UsbPairingLine line = Find(lines ?? new List<UsbPairingLine>(), deviceId);
        string field = line != null ? line.KeyField : dummyField;
        bool paired = line != null;
        byte[] pairKey = null;
        try {
            pairKey = ProtectedData.Unprotect(Convert.FromBase64String(field), Entropy, DataProtectionScope.CurrentUser);
        }
        catch (FormatException) {
            paired = false;
        }
        catch (CryptographicException) {
            paired = false;
        }
        if (pairKey == null || pairKey.Length != Swut.KeyLength) {
            Swut.Wipe(pairKey);
            pairKey = new byte[Swut.KeyLength];
            random(pairKey);
            paired = false;
        }
        UsbBindKey key = new UsbBindKey();
        key.UsbKey = Swut.UsbKey(pairKey, deviceId);
        Swut.Wipe(pairKey);
        if (!paired) {
            random(key.UsbKey);
        }
        key.Paired = paired;
        key.KeyField = paired ? field : null;
        key.Name = paired ? line.Name : null;
        return key;
    }
}

// ---------------------------------------------------------------- Allow USB, per phone
// The helper's own list of the phones allowed USB: usb-allowed.dat, next to the pairings file.
// Each line is a device id and the SHA-256 of that pairing's key field, so a phone that pairs
// again, or another device that pairs with its id, needs Allow USB again. The helper never writes
// the pairings file, only this one, and only a file it wrote itself.
sealed class UsbAllowList
{
    const string Header = "# Starwire USB helper: the phones allowed to share USB devices with this PC. Change it from the helper's icon.";
    const string Columns = "# device id\tSHA-256 of the pairing's key field";
    const int MaxFileBytes = 64 * 1024;

    public readonly string FilePath;

    public UsbAllowList(string filePath)
    {
        FilePath = filePath;
    }

    public static string NextTo(string pairingsFile)
    {
        return Path.Combine(Path.GetDirectoryName(Path.GetFullPath(pairingsFile)), "usb-allowed.dat");
    }

    public static string KeyHash(string keyField)
    {
        using (SHA256 sha = SHA256.Create()) {
            return Swut.Hex(sha.ComputeHash(new UTF8Encoding(false).GetBytes(keyField)));
        }
    }

    // Device id to hash. A missing file allows nobody. Null, with the reason, for a file that can't
    // be read or isn't the helper's: that allows nobody either, and it's never replaced.
    public Dictionary<string, string> Load(out string problem)
    {
        Dictionary<string, string> allowed = new Dictionary<string, string>(StringComparer.Ordinal);
        string text;
        try {
            using (FileStream f = new FileStream(FilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                if (f.Length > MaxFileBytes) {
                    problem = FilePath + " is over " + MaxFileBytes / 1024 + " KB";
                    return null;
                }
                using (StreamReader r = new StreamReader(f, new UTF8Encoding(false))) {
                    text = r.ReadToEnd();
                }
            }
        }
        catch (FileNotFoundException) {
            problem = null;
            return allowed;
        }
        catch (DirectoryNotFoundException) {
            problem = null;
            return allowed;
        }
        catch (IOException e) {
            problem = "couldn't read " + FilePath + ": " + e.Message;
            return null;
        }
        catch (UnauthorizedAccessException e) {
            problem = "couldn't read " + FilePath + ": " + e.Message;
            return null;
        }
        string[] lines = text.Replace("\r", "").Split('\n');
        if (lines.Length == 0 || lines[0] != Header) {
            problem = FilePath + " isn't the helper's list (its first line isn't the helper's heading), so it allows nobody "
                + "and the helper won't change it";
            return null;
        }
        for (int i = 1; i < lines.Length; i++) {
            string[] f = lines[i].Split('\t');
            if (f.Length == 2 && IsHex(f[0], 2 * Swut.IdLength) && IsHex(f[1], 64)) {
                allowed[f[0].ToLowerInvariant()] = f[1].ToLowerInvariant();
            }
        }
        problem = null;
        return allowed;
    }

    static bool IsHex(string s, int length)
    {
        if (s.Length != length) {
            return false;
        }
        foreach (char c in s) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // Whether Allow USB is ticked for this phone, with this pairing
    public bool IsAllowed(byte[] deviceId, string keyField)
    {
        if (deviceId == null || keyField == null) {
            return false;
        }
        string problem;
        Dictionary<string, string> allowed = Load(out problem);
        string hash;
        if (allowed == null || !allowed.TryGetValue(Swut.Hex(deviceId), out hash)) {
            return false;
        }
        byte[] want = Swut.Ascii(KeyHash(keyField));
        byte[] got = Swut.Ascii(hash);
        return want.Length == got.Length && Swut.ConstantTimeEquals(want, 0, got, 0, want.Length);
    }

    // Ticks or unticks one phone and saves the list, keeping only lines that still match a pairing
    // in the file (so a phone that was unpaired drops out). Written whole to a new file and moved
    // over the old one.
    public bool Set(UsbPairingLine phone, bool allow, List<UsbPairingLine> paired, out string problem)
    {
        Dictionary<string, string> allowed = Load(out problem);
        if (allowed == null) {
            return false;
        }
        if (allow) {
            allowed[phone.Id] = KeyHash(phone.KeyField);
        }
        else {
            allowed.Remove(phone.Id);
        }
        StringBuilder s = new StringBuilder();
        s.Append(Header).Append("\r\n").Append(Columns).Append("\r\n");
        int kept = 0;
        foreach (UsbPairingLine p in paired) {
            string hash;
            if (allowed.TryGetValue(p.Id, out hash) && hash == KeyHash(p.KeyField)) {
                s.Append(p.Id).Append('\t').Append(hash).Append("\r\n");
                kept++;
            }
        }
        try {
            if (kept == 0) {
                if (File.Exists(FilePath)) {
                    File.Delete(FilePath);
                }
                problem = null;
                return true;
            }
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(FilePath)));
            string next = FilePath + ".new";
            File.WriteAllText(next, s.ToString(), new UTF8Encoding(false));
            if (File.Exists(FilePath)) {
                File.Replace(next, FilePath, null);
            }
            else {
                File.Move(next, FilePath);
            }
            problem = null;
            return true;
        }
        catch (Exception e) {
            problem = "couldn't save " + FilePath + ": " + e.Message;
            return false;
        }
    }
}

// Whether USB is on in the helper's menu: usb-settings.txt next to the pairings file. Off until
// you turn it on.
sealed class UsbSettings
{
    public readonly string FilePath;

    public UsbSettings(string filePath)
    {
        FilePath = filePath;
    }

    public static string NextTo(string pairingsFile)
    {
        return Path.Combine(Path.GetDirectoryName(Path.GetFullPath(pairingsFile)), "usb-settings.txt");
    }

    public bool UsbOn()
    {
        try {
            foreach (string line in File.ReadAllLines(FilePath)) {
                if (line.Trim() == "usb=on") {
                    return true;
                }
            }
        }
        catch (Exception) {
        }
        return false;
    }

    public void SetUsbOn(bool on)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(FilePath)));
        File.WriteAllText(FilePath, "# Starwire USB helper: whether phones can share USB devices with this PC. Change it from the helper's icon.\r\n"
            + (on ? "usb=on" : "usb=off") + "\r\n", new UTF8Encoding(false));
    }
}

// ---------------------------------------------------------------- connections waiting for BIND
// At most 4 connections that haven't finished their BIND, and at most 1 from each address: a new
// one from an address that already has one takes its place. When 4 wait, a new one pushes out the
// oldest if that one has waited 2 s; otherwise the new one is closed at once. Each gets 10 s from
// its TCP connection to a valid BIND.
sealed class UsbWaitingRoom
{
    public const int MaxWaiting = 4;
    public const long PushOutAfterMs = 2000;
    public const long BindWithinMs = 10000;

    public sealed class Ticket
    {
        public readonly string Address;
        public readonly long Since;
        internal readonly Action Close;
        internal bool Gone;

        internal Ticket(string address, long since, Action close)
        {
            Address = address;
            Since = since;
            Close = close;
        }

        public long Deadline { get { return Since + BindWithinMs; } }
    }

    readonly IUsbClock clock;
    readonly List<Ticket> waiting = new List<Ticket>();
    readonly object gate = new object();

    public UsbWaitingRoom(IUsbClock clock)
    {
        this.clock = clock;
    }

    public int Count
    {
        get {
            lock (gate) {
                return waiting.Count;
            }
        }
    }

    // A place for a new connection from this address, or null when it must be closed at once.
    // close is how to close it if it's pushed out or runs out of time.
    public Ticket Admit(string address, Action close)
    {
        List<Action> closing = new List<Action>();
        Ticket t = null;
        lock (gate) {
            long now = clock.Now;
            for (int i = waiting.Count - 1; i >= 0; i--) {
                if (waiting[i].Address == address) {
                    waiting[i].Gone = true;
                    closing.Add(waiting[i].Close);
                    waiting.RemoveAt(i);
                }
            }
            if (waiting.Count >= MaxWaiting) {
                Ticket oldest = waiting[0];
                if (now - oldest.Since >= PushOutAfterMs) {
                    oldest.Gone = true;
                    closing.Add(oldest.Close);
                    waiting.RemoveAt(0);
                }
            }
            if (waiting.Count < MaxWaiting) {
                t = new Ticket(address, now, close);
                waiting.Add(t);
            }
        }
        foreach (Action a in closing) {
            Quietly(a);
        }
        return t;
    }

    // Whether it was pushed out, ran out of time or left
    public bool IsGone(Ticket t)
    {
        lock (gate) {
            return t.Gone;
        }
    }

    // The connection's BIND is done with, or it closed. False if it had already been pushed out or
    // had run out of time.
    public bool Leave(Ticket t)
    {
        if (t == null) {
            return false;
        }
        lock (gate) {
            if (t.Gone) {
                return false;
            }
            t.Gone = true;
            waiting.Remove(t);
            return true;
        }
    }

    // Closes the connections that have waited 10 s. How many it closed.
    public int Sweep()
    {
        List<Action> closing = new List<Action>();
        lock (gate) {
            long now = clock.Now;
            for (int i = waiting.Count - 1; i >= 0; i--) {
                if (now >= waiting[i].Deadline) {
                    waiting[i].Gone = true;
                    closing.Add(waiting[i].Close);
                    waiting.RemoveAt(i);
                }
            }
        }
        foreach (Action a in closing) {
            Quietly(a);
        }
        return closing.Count;
    }

    public void CloseAll()
    {
        List<Action> closing = new List<Action>();
        lock (gate) {
            foreach (Ticket t in waiting) {
                t.Gone = true;
                closing.Add(t.Close);
            }
            waiting.Clear();
        }
        foreach (Action a in closing) {
            Quietly(a);
        }
    }

    static void Quietly(Action a)
    {
        try {
            a();
        }
        catch (Exception) {
        }
    }
}

// ---------------------------------------------------------------- TLS
// The helper's certificate, made when it starts: self-signed, CN=Starwire, a P-256 key, a random
// serial, nothing that names the PC. SslStream on Windows only takes a private key that's in a
// saved key container (with a key that lives only in memory, the handshake fails with "No
// credentials are available in the security package", checked on Windows 11 with .NET Framework
// 4.8.1), so the key goes in a container of its own for this Windows account, deleted when the
// helper stops and again when it next starts, in case it crashed.
sealed class UsbTlsIdentity : IDisposable
{
    public readonly X509Certificate2 Certificate;
    public readonly byte[] Fingerprint;
    readonly CngKey key;
    readonly string container;

    UsbTlsIdentity(X509Certificate2 certificate, CngKey key, string container)
    {
        Certificate = certificate;
        Fingerprint = Swut.Fingerprint(certificate.RawData);
        this.key = key;
        this.container = container;
    }

    public static UsbTlsIdentity Create(string container, Action<byte[]> random)
    {
        DeleteContainer(container);
        CngKeyCreationParameters p = new CngKeyCreationParameters();
        p.ExportPolicy = CngExportPolicies.None;
        p.KeyUsage = CngKeyUsages.Signing;
        p.Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
        CngKey key = CngKey.Create(CngAlgorithm.ECDsaP256, container, p);
        try {
            ECDsaCng ec = new ECDsaCng(key);
            X500DistinguishedName name = new X500DistinguishedName("CN=Starwire");
            CertificateRequest request = new CertificateRequest(name, ec, HashAlgorithmName.SHA256);
            byte[] serial = new byte[16];
            random(serial);
            serial[0] = (byte) ((serial[0] & 0x7F) | 0x40);   // positive, and 16 bytes long
            DateTimeOffset from = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
            DateTimeOffset to = new DateTimeOffset(2036, 1, 1, 0, 0, 0, TimeSpan.Zero);
            X509Certificate2 bare = request.Create(name, X509SignatureGenerator.CreateForECDsa(ec), from, to, serial);
            X509Certificate2 certificate = bare.CopyWithPrivateKey(ec);
            bare.Dispose();
            return new UsbTlsIdentity(certificate, key, container);
        }
        catch {
            key.Delete();
            throw;
        }
    }

    public static void DeleteContainer(string container)
    {
        try {
            if (CngKey.Exists(container)) {
                CngKey.Open(container).Delete();
            }
        }
        catch (CryptographicException) {
        }
    }

    // The certificate has a P-256 key (id-ecPublicKey with prime256v1)
    public static bool IsP256(X509Certificate certificate)
    {
        if (certificate == null) {
            return false;
        }
        try {
            X509Certificate2 c = certificate as X509Certificate2 ?? new X509Certificate2(certificate);
            byte[] parameters = c.GetKeyAlgorithmParameters();
            byte[] p256 = { 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07 };
            return c.GetKeyAlgorithm() == "1.2.840.10045.2.1" && parameters != null && parameters.Length == p256.Length
                && Swut.ConstantTimeEquals(parameters, 0, p256, 0, p256.Length);
        }
        catch (CryptographicException) {
            return false;
        }
    }

    public const SslProtocols Tls13 = (SslProtocols) 12288;
    // CALG_ECDH_EPHEM, as SslStream reports an ECDHE key exchange
    const int EcdheExchange = 0xAE06;

    // TLS 1.3, or TLS 1.2 with ECDHE and AES-GCM. .NET Framework can't choose Windows' cipher suites,
    // so the helper checks the one the handshake chose. SslStream reports a hash strength of 0 only
    // for AEAD suites (GCM): a CBC suite has 160 or more (checked with OpenSSL's s_client against
    // SslStream on Windows 11). Windows' TLS has no ChaCha20-Poly1305 for a server there.
    public static bool SessionOk(SslStream ssl, out string why)
    {
        bool tls13 = ssl.SslProtocol == Tls13;
        bool tls12 = ssl.SslProtocol == SslProtocols.Tls12;
        bool aes = ssl.CipherAlgorithm == CipherAlgorithmType.Aes128 || ssl.CipherAlgorithm == CipherAlgorithmType.Aes256;
        if (!(tls13 || tls12) || !aes || ssl.HashStrength != 0 || (tls12 && (int) ssl.KeyExchangeAlgorithm != EcdheExchange)) {
            why = "the TLS session isn't TLS 1.3, or TLS 1.2 with ECDHE and AES-GCM (" + ssl.SslProtocol + ", "
                + ssl.CipherAlgorithm + ", hash strength " + ssl.HashStrength + ", key exchange " + (int) ssl.KeyExchangeAlgorithm + ")";
            return false;
        }
        if (!IsP256(ssl.RemoteCertificate)) {
            why = "Starwire's certificate is missing or hasn't a P-256 key";
            return false;
        }
        why = null;
        return true;
    }

    public void Dispose()
    {
        Certificate.Dispose();
        try {
            key.Delete();
        }
        catch (CryptographicException) {
        }
        DeleteContainer(container);
    }
}

// Reads and writes whole frames over the TLS stream. Writes come from several threads (the relay,
// KEEPALIVE), so they take turns. After every read it checks that TLS still has the certificate
// BIND was made over: SslStream on .NET Framework lets the phone start a TLS 1.2 renegotiation and
// goes along with it (checked with OpenSSL's s_client), and afterwards hands back another
// certificate object. The helper ends the session then.
sealed class SwutStream
{
    readonly Stream stream;
    readonly IUsbClock clock;
    readonly Func<bool> sameTls;
    readonly object writing = new object();
    long lastSent, lastHeard;

    public SwutStream(Stream stream, IUsbClock clock, Func<bool> sameTls)
    {
        this.stream = stream;
        this.clock = clock;
        this.sameTls = sameTls;
        lastSent = lastHeard = clock.Now;
    }

    public long LastSent { get { return Interlocked.Read(ref lastSent); } }
    public long LastHeard { get { return Interlocked.Read(ref lastHeard); } }

    // One whole frame, or null when its header doesn't read. Throws IOException when the connection
    // closes, and SwutRenegotiatedException after a renegotiation.
    public byte[] ReadFrame()
    {
        byte[] header = new byte[Swut.FrameHeader];
        ReadExactly(header, 0, header.Length);
        int length = Swut.FrameLength(header, 0);
        if (length < 0) {
            return null;
        }
        byte[] f = new byte[length];
        Array.Copy(header, f, header.Length);
        ReadExactly(f, header.Length, length - header.Length);
        Interlocked.Exchange(ref lastHeard, clock.Now);
        return f;
    }

    void ReadExactly(byte[] b, int at, int n)
    {
        while (n > 0) {
            int got = stream.Read(b, at, n);
            if (!sameTls()) {
                throw new SwutRenegotiatedException();
            }
            if (got <= 0) {
                throw new EndOfStreamException("the connection closed");
            }
            at += got;
            n -= got;
        }
    }

    public void Write(byte[] frame)
    {
        lock (writing) {
            stream.Write(frame, 0, frame.Length);
            stream.Flush();
            Interlocked.Exchange(ref lastSent, clock.Now);
        }
    }

    // The next bytes of the USB/IP stream, in DATA frames of at most 16384 bytes
    public void WriteData(byte[] b)
    {
        lock (writing) {
            for (int at = 0; at < b.Length; at += Swut.MaxPayload) {
                byte[] f = Swut.Data(b, at, Math.Min(Swut.MaxPayload, b.Length - at));
                stream.Write(f, 0, f.Length);
            }
            stream.Flush();
            Interlocked.Exchange(ref lastSent, clock.Now);
        }
    }
}

sealed class SwutRenegotiatedException : IOException
{
    public SwutRenegotiatedException() : base("the phone renegotiated TLS")
    {
    }
}

// ---------------------------------------------------------------- the driver, for the tunnel
// How the tunnel attaches and detaches, so the tests can give it a fake driver
interface IUsbAttacher
{
    // Whether the PC's USB/IP driver is there and a version the helper knows. False, with the reason.
    bool Ready(out string problem);

    // Asks the driver to attach the device the relay at 127.0.0.1:relayPort serves with this bus
    // id. Blocks while the driver connects to the relay and imports it. True when it's attached.
    // handle, when not null, is what to detach later, even if it failed (the driver may have
    // attached it without saying where).
    bool Attach(int relayPort, string busId, out object handle, out string problem);

    // Detaches exactly what Attach attached, if the driver still holds it, and nothing else
    bool Detach(object handle, out string problem);
}

// The real driver, through the helper's driver side (UsbHelper, StarwireUsbHelper.cs): its version
// check, its list of what the helper attached, its lock, and its own-port detach
sealed class UsbDriverAttacher : IUsbAttacher
{
    readonly UsbHelper.Opener open;
    readonly OwnedDevices owned;
    readonly Action<byte[]> random;
    readonly string mutexName;
    readonly Action<string> log;

    // The real driver is Win32Vhci.Open with StarwireUsbHelper.DriverMutex; the tests pass a fake
    public UsbDriverAttacher(UsbHelper.Opener open, OwnedDevices owned, Action<byte[]> random, string mutexName, Action<string> log)
    {
        this.open = open;
        this.owned = owned;
        this.random = random;
        this.mutexName = mutexName;
        this.log = log;
    }

    UsbHelper Helper()
    {
        return new UsbHelper(open, owned, random, mutexName, delegate(string s) { log("driver: " + s.Trim()); });
    }

    public bool Ready(out string problem)
    {
        return Helper().TunnelReady(out problem);
    }

    public bool Attach(int relayPort, string busId, out object handle, out string problem)
    {
        OwnedDevice mine;
        bool ok = Helper().TunnelAttach(relayPort, busId, out mine, out problem);
        handle = mine;
        return ok;
    }

    public bool Detach(object handle, out string problem)
    {
        return Helper().TunnelDetach((OwnedDevice) handle, out problem);
    }
}

// ---------------------------------------------------------------- the server
sealed class UsbTunnelOptions
{
    public const int DefaultPort = 6981;

    public IPAddress ListenAddress = IPAddress.IPv6Any;
    public int Port = DefaultPort;
    // The microphone receiver's pairings file, and the helper's own list next to it
    public string PairingsFile;
    public string AllowFile;
    public string TlsContainer;
    public IUsbClock Clock = new UsbSystemClock();
    public Action<byte[]> Random;
    public Action<string> Log = delegate { };
    // Home-network, private VPN and loopback addresses only: a filter, not a security boundary
    public Func<IPAddress, bool> AddressOk = Requests.IsHomeAddress;
    public IUsbAttacher Attacher;
    public UsbRelay.PeerCheck RelayPeerOk = UsbRelay.SystemProcessOnly;
    // With AutoTick off (the tests), nothing happens on time until Tick is called
    public bool AutoTick = true;
    public int TickEveryMs = 200;
    public int ShareWithinMs = 10000;
    public int KeepaliveEveryMs = 1000;
    public int SilentForMs = 8000;
    public int WatchEveryMs = 10000;
    // How long the end of a session waits for an attach still in the driver
    public int AttachWaitMs = 30000;
    public bool WatchFiles = true;
    // Shared with the menu's reads of the pairings file, so all of them count towards 4 a second
    public UsbReadLimiter Limiter;
    // For the tests: runs on a connection's thread right after it got its place in the waiting room
    public Action<UsbWaitingRoom.Ticket> AfterAdmit = null;
}

sealed class UsbTunnelServer : IDisposable
{
    readonly UsbTunnelOptions options;
    readonly IUsbClock clock;
    readonly UsbTlsIdentity identity;
    readonly UsbReadLimiter limiter;
    readonly UsbPairingReader pairings;
    readonly UsbAllowList allowList;
    readonly UsbWaitingRoom room;
    readonly List<UsbTunnelSession> sessions = new List<UsbTunnelSession>();
    readonly object gate = new object();
    TcpListener listener;
    Thread acceptThread;
    Timer ticker;
    FileSystemWatcher watcher;
    UsbTunnelSession sharing;
    volatile bool stopping;
    int port;

    // Something the menu shows changed: listening, a device shared or given back
    public event Action Changed;

    public UsbTunnelServer(UsbTunnelOptions options)
    {
        this.options = options;
        clock = options.Clock;
        limiter = options.Limiter ?? new UsbReadLimiter(clock);
        pairings = new UsbPairingReader(options.PairingsFile, limiter, options.Random);
        allowList = new UsbAllowList(options.AllowFile ?? UsbAllowList.NextTo(options.PairingsFile));
        room = new UsbWaitingRoom(clock);
        identity = UsbTlsIdentity.Create(options.TlsContainer, options.Random);
    }

    public UsbTunnelOptions Options { get { return options; } }
    public IUsbClock Clock { get { return clock; } }
    public UsbPairingReader Pairings { get { return pairings; } }
    public UsbAllowList AllowList { get { return allowList; } }
    public UsbWaitingRoom Room { get { return room; } }
    public byte[] Fingerprint { get { return identity.Fingerprint; } }
    public int Port { get { return port; } }

    // The session sharing a device, if one is
    public UsbTunnelSession Sharing
    {
        get {
            lock (gate) {
                return sharing;
            }
        }
    }

    public int SessionCount
    {
        get {
            lock (gate) {
                return sessions.Count;
            }
        }
    }

    internal void Log(string s)
    {
        try {
            options.Log(s);
        }
        catch (Exception) {
        }
    }

    internal void NotifyChanged()
    {
        Action a = Changed;
        if (a != null) {
            try {
                a();
            }
            catch (Exception) {
            }
        }
    }

    public void Start()
    {
        listener = new TcpListener(options.ListenAddress, options.Port);
        if (options.ListenAddress.AddressFamily == AddressFamily.InterNetworkV6) {
            listener.Server.DualMode = true;
        }
        listener.ExclusiveAddressUse = true;
        listener.Start();
        port = ((IPEndPoint) listener.LocalEndpoint).Port;
        acceptThread = new Thread(AcceptLoop);
        acceptThread.IsBackground = true;
        acceptThread.Name = "USB tunnel: accept";
        acceptThread.Start();
        if (options.AutoTick) {
            ticker = new Timer(delegate { Safely("the timer", Tick); }, null, options.TickEveryMs, options.TickEveryMs);
        }
        if (options.WatchFiles) {
            try {
                string folder = Path.GetDirectoryName(Path.GetFullPath(options.PairingsFile));
                if (Directory.Exists(folder)) {
                    watcher = new FileSystemWatcher(folder);
                    watcher.NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size;
                    FileSystemEventHandler changed = delegate { Recheck(); };
                    watcher.Changed += changed;
                    watcher.Created += changed;
                    watcher.Deleted += changed;
                    watcher.Renamed += delegate { Recheck(); };
                    watcher.EnableRaisingEvents = true;
                }
            }
            catch (Exception e) {
                Log("not watching the pairings file for changes: " + e.Message + ". It's still checked every 10 s.");
            }
        }
        Log("listening on TCP " + port);
        NotifyChanged();
    }

    // Stops listening, closes the connections still waiting for BIND, and ends every session with
    // this reason (END 4 when USB is turned off, 8 when the helper closes), giving its device back
    public void Stop(int reason)
    {
        stopping = true;
        if (ticker != null) {
            ticker.Dispose();
        }
        if (watcher != null) {
            watcher.Dispose();
        }
        try {
            if (listener != null) {
                listener.Stop();
            }
        }
        catch (Exception) {
        }
        room.CloseAll();
        List<UsbTunnelSession> all;
        lock (gate) {
            all = new List<UsbTunnelSession>(sessions);
        }
        foreach (UsbTunnelSession s in all) {
            s.End(reason, 0, true);
        }
        foreach (UsbTunnelSession s in all) {
            s.WaitFinished(40000);
        }
        if (acceptThread != null) {
            acceptThread.Join(2000);
        }
        NotifyChanged();
    }

    public void Dispose()
    {
        if (!stopping) {
            Stop(Swut.EndClosing);
        }
        identity.Dispose();
    }

    // The time-driven rules: the 10 s for BIND, the 10 s for SHARE, KEEPALIVE, the 8 s of silence,
    // and the 10 s pairing check. Runs a few times a second, or when the tests call it.
    public void Tick()
    {
        room.Sweep();
        List<UsbTunnelSession> all;
        lock (gate) {
            all = new List<UsbTunnelSession>(sessions);
        }
        foreach (UsbTunnelSession s in all) {
            s.Tick();
        }
    }

    // Checks again now whether each sharing phone is still paired and allowed (the files changed,
    // or Allow USB was unticked in the menu)
    public void Recheck()
    {
        List<UsbTunnelSession> all;
        lock (gate) {
            all = new List<UsbTunnelSession>(sessions);
        }
        foreach (UsbTunnelSession s in all) {
            s.WantRecheck();
        }
        if (!options.AutoTick) {
            return;
        }
        ThreadPool.QueueUserWorkItem(delegate { Safely("a check of the pairings", Tick); });
    }

    // Ends the shared device's session as stopped by you (END 1)
    public bool StopSharing()
    {
        UsbTunnelSession s = Sharing;
        if (s == null) {
            return false;
        }
        s.End(Swut.EndStopped, 0, true);
        return true;
    }

    internal bool Claim(UsbTunnelSession s)
    {
        lock (gate) {
            if (sharing != null && sharing != s) {
                return false;
            }
            sharing = s;
            return true;
        }
    }

    internal void Forget(UsbTunnelSession s)
    {
        lock (gate) {
            if (sharing == s) {
                sharing = null;
            }
            sessions.Remove(s);
        }
        NotifyChanged();
    }

    void AcceptLoop()
    {
        while (!stopping) {
            TcpClient tcp;
            try {
                tcp = listener.AcceptTcpClient();
            }
            catch (Exception) {
                if (stopping) {
                    return;
                }
                Thread.Sleep(50);
                continue;
            }
            // Only a connection that gets a place in the waiting room gets a thread of its own, so a
            // flood of connections costs no more than closing them
            Safely("a new connection", delegate {
                IPAddress address;
                UsbWaitingRoom.Ticket ticket = Admit(tcp, out address);
                if (ticket == null) {
                    return;
                }
                Thread t = new Thread(delegate() { Safely("a connection", delegate { Serve(tcp, address, ticket); }); });
                t.IsBackground = true;
                t.Name = "USB tunnel: connection";
                t.Start();
            });
        }
    }

    // A place in the waiting room for a new connection from a home-network or private VPN address,
    // or null after closing it
    UsbWaitingRoom.Ticket Admit(TcpClient tcp, out IPAddress address)
    {
        address = null;
        try {
            address = ((IPEndPoint) tcp.Client.RemoteEndPoint).Address;
            if (address.IsIPv4MappedToIPv6) {
                address = address.MapToIPv4();
            }
        }
        catch (Exception) {
            Quietly(tcp.Close);
            return null;
        }
        if (stopping || !options.AddressOk(address)) {
            Log("closed a connection from " + address + ": not a home-network or private VPN address");
            Quietly(tcp.Close);
            return null;
        }
        UsbWaitingRoom.Ticket ticket = room.Admit(address.ToString(), delegate { tcp.Close(); });
        if (ticket == null) {
            Log("closed a connection from " + address + ": 4 others are waiting for their BIND, none for 2 s yet");
            Quietly(tcp.Close);
        }
        return ticket;
    }

    // One connection, from its place in the waiting room to BIND_ACK or REFUSE, then its session
    void Serve(TcpClient tcp, IPAddress address, UsbWaitingRoom.Ticket ticket)
    {
        if (options.AfterAdmit != null) {
            options.AfterAdmit(ticket);
        }
        SslStream ssl = null;
        bool handedOver = false;
        UsbBindKey key = null;
        try {
            // From here the waiting room may close the connection at any moment (another one from
            // the same address took its place, or it ran out of time), so everything that touches
            // it is in here
            tcp.NoDelay = true;
            ssl = new SslStream(tcp.GetStream(), false, PeerCertificateOk);
            ssl.AuthenticateAsServer(identity.Certificate, true, SslProtocols.Tls12 | UsbTlsIdentity.Tls13, false);
            string why;
            if (!UsbTlsIdentity.SessionOk(ssl, out why)) {
                Log("closed a connection from " + address + ": " + why);
                return;
            }
            X509Certificate peer = ssl.RemoteCertificate;
            byte[] phoneFingerprint = Swut.Fingerprint(peer.GetRawCertData());
            byte[] nonce = new byte[Swut.NonceLength];
            options.Random(nonce);
            byte[] hello = Swut.Hello(nonce, Swut.LowestVersion, Swut.HighestVersion);
            SslStream bound = ssl;
            SwutStream frames = new SwutStream(ssl, clock, delegate { return ReferenceEquals(bound.RemoteCertificate, peer); });
            frames.Write(hello);
            byte[] frame = frames.ReadFrame();
            if (frame == null) {
                Log("closed a connection from " + address + ": what came where BIND should isn't a frame");
                return;
            }
            int refusal = Swut.BindRefusal(hello, frame);
            if (refusal != 0) {
                Refuse(ticket, ssl, frames, refusal, address);
                return;
            }
            byte[] deviceId = Swut.BindDeviceId(frame);
            string problem;
            key = pairings.ForBind(deviceId, ticket.Deadline, out problem);
            if (key == null) {
                Log("closed a connection from " + address + ": " + problem);
                return;
            }
            if (problem != null) {
                Log("the pairings file: " + problem);
            }
            if (!Swut.BindChecksOut(key.UsbKey, hello, phoneFingerprint, identity.Fingerprint, frame) || !key.Paired) {
                Refuse(ticket, ssl, frames, Swut.RefuseNotPaired, address);
                return;
            }
            if (!allowList.IsAllowed(deviceId, key.KeyField)) {
                Refuse(ticket, ssl, frames, Swut.RefuseNotAllowed, address);
                return;
            }
            byte[] ack = Swut.BindAck(key.UsbKey, hello, phoneFingerprint, identity.Fingerprint, frame);
            // From here TLS protects the session; only the key field is kept, to notice a new pairing
            key.Wipe();
            if (!room.Leave(ticket)) {
                Log("closed a connection from " + address + ": its 10 s for BIND ran out");
                return;
            }
            UsbTunnelSession session = new UsbTunnelSession(this, tcp, ssl, frames, deviceId, key.KeyField, key.Name, address.ToString());
            lock (gate) {
                if (stopping) {
                    return;
                }
                sessions.Add(session);
            }
            try {
                frames.Write(ack);
            }
            catch {
                Forget(session);
                throw;
            }
            handedOver = true;
            Log(key.Name + " (" + Swut.Hex(deviceId) + ") at " + address + ": BIND checked out");
            session.Run();
        }
        catch (Exception e) {
            if (!handedOver) {
                Log("closed a connection from " + address + ": " + e.Message);
            }
        }
        finally {
            if (key != null) {
                key.Wipe();
            }
            room.Leave(ticket);
            if (!handedOver) {
                if (ssl != null) {
                    Quietly(ssl.Close);
                }
                Quietly(tcp.Close);
            }
        }
    }

    // The TLS handshake's own check of Starwire's certificate: there must be one, with a P-256 key.
    // Chains, names and dates don't count: BIND makes the certificate count.
    static bool PeerCertificateOk(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors)
    {
        return UsbTlsIdentity.IsP256(certificate);
    }

    void Refuse(UsbWaitingRoom.Ticket ticket, SslStream ssl, SwutStream frames, int reason, IPAddress address)
    {
        room.Leave(ticket);
        Log("refused a connection from " + address + ": REFUSE " + reason);
        try {
            frames.Write(Swut.Refuse(reason));
            ssl.ShutdownAsync().Wait(2000);
        }
        catch (Exception) {
        }
    }

    internal static void Quietly(Action a)
    {
        try {
            a();
        }
        catch (Exception) {
        }
    }

    // For the start of every thread and timer: an exception that got this far is logged, never
    // let out, since one that leaves a thread ends the whole helper, and what arrives from the
    // network mustn't be able to do that
    internal void Safely(string what, Action a)
    {
        try {
            a();
        }
        catch (Exception e) {
            Log(what + ": " + e.GetType().Name + ": " + e.Message);
        }
    }
}

// ---------------------------------------------------------------- one phone's session
// From BIND_ACK: SHARE and SHARE_ACK, the attach through the relay, DATA both ways, KEEPALIVE,
// the pairing check, and giving the device back however the session ends
sealed class UsbTunnelSession
{
    public const int Silent = 0;   // an end without END: the phone ended it, went quiet, or closed

    readonly UsbTunnelServer server;
    readonly UsbTunnelOptions options;
    readonly IUsbClock clock;
    readonly TcpClient tcp;
    readonly SslStream ssl;
    readonly SwutStream frames;
    public readonly byte[] DeviceId;
    readonly string keyField;
    public readonly string PhoneName;
    public readonly string Address;
    readonly long shareDeadline;
    readonly ManualResetEvent finished = new ManualResetEvent(false);
    readonly object attachGate = new object();
    int ending;
    volatile bool shareSeen, shareAcked, recheck;
    long lastWatch;
    volatile string deviceName;
    UsbRelay relay;
    UsbipStreamCheck check;
    Thread attachThread;
    object attachHandle;
    volatile bool attached;
    // Set once Finish has taken what there was to detach; an attach that ends later detaches itself
    bool given;
    // How it ended, for the tests and the log
    public volatile int EndReason = -1;
    public volatile int EndDetail;

    internal UsbTunnelSession(UsbTunnelServer server, TcpClient tcp, SslStream ssl, SwutStream frames, byte[] deviceId,
        string keyField, string phoneName, string address)
    {
        this.server = server;
        options = server.Options;
        clock = server.Clock;
        this.tcp = tcp;
        this.ssl = ssl;
        this.frames = frames;
        DeviceId = deviceId;
        this.keyField = keyField;
        PhoneName = phoneName;
        Address = address;
        shareDeadline = clock.Now + options.ShareWithinMs;
        lastWatch = clock.Now;
    }

    public string DeviceName { get { return deviceName; } }
    public bool Attached { get { return attached; } }
    public long LastHeard { get { return frames.LastHeard; } }
    public bool Ending { get { return Thread.VolatileRead(ref ending) != 0; } }

    public bool WaitFinished(int ms)
    {
        return finished.WaitOne(ms);
    }

    public void WantRecheck()
    {
        recheck = true;
    }

    // On the connection's thread, after BIND_ACK. Returns once the session is over and its device
    // is given back.
    public void Run()
    {
        try {
            byte[] f = frames.ReadFrame();
            int type = Swut.TypeOf(f);
            if (type == Swut.TEnd) {
                End(Silent, 0, false);
                return;
            }
            SwutShare share = type == Swut.TShare ? Swut.ParseShare(f) : null;
            if (share == null) {
                End(Swut.EndProtocol, 0, true);
                return;
            }
            shareSeen = true;
            deviceName = share.DisplayName();
            int status, detail;
            Decide(share, out status, out detail);
            server.Log(PhoneName + ": SHARE for " + deviceName + " (bus ID " + share.BusId + "): SHARE_ACK " + status
                + (detail != 0 ? ", rule " + detail : ""));
            frames.Write(Swut.ShareAck(status, detail));
            if (status != Swut.ShareAttaching) {
                End(Silent, 0, false);
                return;
            }
            shareAcked = true;
            server.NotifyChanged();
            StartRelay(share);
            while (!Ending) {
                f = frames.ReadFrame();
                type = Swut.TypeOf(f);
                if (type == Swut.TData && f.Length > Swut.FrameHeader) {
                    ToDriver(f);
                }
                else if (type == Swut.TKeepalive && Swut.IsKeepalive(f)) {
                    continue;
                }
                else if (type == Swut.TEnd && Swut.Code(f, Swut.TEnd) >= 0) {
                    server.Log(PhoneName + " ended the session: END " + Swut.Code(f, Swut.TEnd));
                    End(Silent, 0, false);
                }
                else {
                    End(Swut.EndProtocol, 0, true);
                }
            }
        }
        catch (SwutRenegotiatedException) {
            End(Swut.EndProtocol, 0, true);
        }
        catch (Exception e) {
            if (!Ending) {
                server.Log(PhoneName + ": the connection closed (" + e.Message + ")");
            }
            End(Silent, 0, false);
        }
        finally {
            finished.WaitOne();
        }
    }

    // SHARE_ACK's status: the device rules first, then busy, then the driver
    void Decide(SwutShare share, out int status, out int detail)
    {
        UsbDecision d = UsbDeviceRules.ForHelper(share);
        detail = 0;
        if (d.Verdict == UsbVerdict.Refuse) {
            status = Swut.ShareRefused;
            detail = d.Rule;
            return;
        }
        if (!server.Claim(this)) {
            status = Swut.ShareBusy;
            return;
        }
        string problem;
        if (!options.Attacher.Ready(out problem)) {
            server.Log("the USB/IP driver isn't ready: " + problem);
            status = Swut.ShareNoDriver;
            return;
        }
        status = Swut.ShareAttaching;
    }

    void StartRelay(SwutShare share)
    {
        check = new UsbipStreamCheck(share);
        relay = new UsbRelay(options.RelayPeerOk, server.Log);
        relay.Start(FromDriver, DriverClosed);
        attachThread = new Thread(delegate() { server.Safely("the attach", delegate { AttachNow(share.BusId); }); });
        attachThread.IsBackground = true;
        attachThread.Name = "USB tunnel: attach";
        attachThread.Start();
    }

    void AttachNow(string busId)
    {
        object handle;
        string problem;
        bool ok = false;
        try {
            ok = options.Attacher.Attach(relay.Port, busId, out handle, out problem);
        }
        catch (Exception e) {
            handle = null;
            problem = e.Message;
        }
        relay.StopAccepting();
        bool late;
        lock (attachGate) {
            // The session finished while the driver was still attaching (it waited AttachWaitMs): nobody
            // else will detach this, so this thread does
            late = given;
            if (!late) {
                attachHandle = handle;
                attached = ok;
            }
        }
        if (late) {
            if (handle != null) {
                string why;
                bool gone = options.Attacher.Detach(handle, out why);
                server.Log(PhoneName + ": the driver finished attaching after the session ended" + (gone ? "; detached it" : "; couldn't detach it: " + why));
            }
            return;
        }
        if (!ok) {
            server.Log(PhoneName + ": the driver couldn't attach " + deviceName + ": " + problem);
            End(Swut.EndAttachFailed, 0, true);
            return;
        }
        server.Log(PhoneName + ": " + deviceName + " attached");
        server.NotifyChanged();
    }

    // Bytes the driver sent through the relay: checked, then on to the phone in DATA frames, apart
    // from the requests the relay answers itself
    void FromDriver(byte[] b, int n)
    {
        if (Ending) {
            return;
        }
        List<byte[]> toPhone = new List<byte[]>();
        List<byte[]> toDriver = new List<byte[]>();
        if (!check.FromDriver(b, 0, n, toPhone, toDriver)) {
            server.Log(PhoneName + ": the driver's USB/IP stream broke the rules: " + check.Problem);
            End(check.EndReason, check.EndRule, true);
            return;
        }
        try {
            foreach (byte[] m in toDriver) {
                relay.SendToDriver(m);
            }
            foreach (byte[] m in toPhone) {
                frames.WriteData(m);
            }
        }
        catch (Exception e) {
            server.Log(PhoneName + ": couldn't pass the driver's data on: " + e.Message);
            End(Silent, 0, false);
        }
    }

    void DriverClosed()
    {
        if (!Ending) {
            server.Log(PhoneName + ": the driver closed its connection to the relay");
            End(Swut.EndAttachFailed, 0, true);
        }
    }

    // A DATA frame from the phone: checked, then on to the driver
    void ToDriver(byte[] f)
    {
        List<byte[]> toDriver = new List<byte[]>();
        if (!check.FromPhone(f, Swut.FrameHeader, f.Length - Swut.FrameHeader, toDriver)) {
            server.Log(PhoneName + ": the device's USB/IP stream broke the rules: " + check.Problem);
            End(check.EndReason, check.EndRule, true);
            return;
        }
        try {
            foreach (byte[] m in toDriver) {
                relay.SendToDriver(m);
            }
        }
        catch (Exception e) {
            server.Log(PhoneName + ": couldn't pass the device's data on: " + e.Message);
            End(Swut.EndAttachFailed, 0, true);
        }
    }

    // The time-driven rules, a few times a second
    public void Tick()
    {
        if (Ending) {
            return;
        }
        long now = clock.Now;
        if (!shareSeen && now >= shareDeadline) {
            server.Log(PhoneName + ": no SHARE within " + options.ShareWithinMs / 1000 + " s");
            End(Silent, 0, false);
            return;
        }
        if (!shareAcked) {
            return;
        }
        if (now - frames.LastHeard >= options.SilentForMs) {
            server.Log(PhoneName + ": nothing heard for " + options.SilentForMs / 1000 + " s");
            End(Silent, 0, false);
            return;
        }
        if (now - frames.LastSent >= options.KeepaliveEveryMs) {
            try {
                frames.Write(Swut.Keepalive());
            }
            catch (Exception) {
                End(Silent, 0, false);
                return;
            }
        }
        if (recheck || now - Interlocked.Read(ref lastWatch) >= options.WatchEveryMs) {
            CheckPairing(now);
        }
    }

    // Still paired with the same key, and still allowed? Reads the pairings file as BIND does,
    // within the same 4 reads a second, without decrypting anything: the key field it kept at BIND
    // changes when the phone pairs again.
    void CheckPairing(long now)
    {
        string problem;
        bool late;
        List<UsbPairingLine> lines = server.Pairings.Read(now, out problem, out late);
        if (late) {
            recheck = true;   // no read free just now: the next tick tries again
            return;
        }
        recheck = false;
        Interlocked.Exchange(ref lastWatch, now);
        if (lines == null) {
            server.Log(PhoneName + ": couldn't check its pairing (" + problem + "). Trying again in "
                + options.WatchEveryMs / 1000 + " s.");
            return;
        }
        UsbPairingLine line = UsbPairingReader.Find(lines, DeviceId);
        if (line == null || line.KeyField != keyField) {
            server.Log(PhoneName + ": its pairing was removed or changed");
            End(Swut.EndUnpaired, 0, true);
            return;
        }
        if (!server.AllowList.IsAllowed(DeviceId, keyField)) {
            server.Log(PhoneName + ": Allow USB was unticked for it");
            End(Swut.EndUnpaired, 0, true);
        }
    }

    // Ends the session, once, from any thread: sends END with this reason unless it's Silent, gives
    // the device back (its own port only), then closes the relay and the tunnel
    public void End(int reason, int detail, bool send)
    {
        if (Interlocked.Exchange(ref ending, 1) != 0) {
            return;
        }
        EndReason = reason;
        EndDetail = detail;
        ThreadPool.QueueUserWorkItem(delegate { server.Safely("the end of a session", delegate { Finish(reason, detail, send && reason != Silent); }); });
    }

    void Finish(int reason, int detail, bool send)
    {
        try {
            if (send) {
                try {
                    frames.Write(Swut.End(reason, detail));
                }
                catch (Exception) {
                }
                server.Log(PhoneName + ": sent END " + reason + (detail != 0 ? ", rule " + detail : ""));
            }
            if (relay != null) {
                relay.StopAccepting();
                if (attachThread != null && attachThread.IsAlive) {
                    // Still attaching: closing the relay's connection makes the driver give up
                    relay.CloseDriver();
                    attachThread.Join(options.AttachWaitMs);
                }
            }
            object handle;
            lock (attachGate) {
                handle = attachHandle;
                attachHandle = null;
                given = true;
            }
            if (handle != null) {
                string problem;
                if (options.Attacher.Detach(handle, out problem)) {
                    server.Log(PhoneName + ": gave " + deviceName + " back");
                }
                else {
                    server.Log(PhoneName + ": couldn't detach " + deviceName + ": " + problem);
                }
            }
            attached = false;
            if (relay != null) {
                relay.Dispose();
            }
            try {
                ssl.ShutdownAsync().Wait(2000);
            }
            catch (Exception) {
            }
            UsbTunnelServer.Quietly(ssl.Close);
            UsbTunnelServer.Quietly(tcp.Close);
        }
        finally {
            server.Forget(this);
            finished.Set();
        }
    }
}

// ---------------------------------------------------------------- what the icon's menu drives
// One paired phone, as the Allow USB menu shows it
sealed class UsbPhone
{
    public UsbPairingLine Line;
    public bool Allowed;
}

// USB on or off, Allow USB for each phone, Stop, and the line the menu shows. The server only
// exists, and only listens, while USB is on.
sealed class UsbTunnelService : IDisposable
{
    readonly UsbTunnelOptions options;
    readonly UsbSettings settings;
    readonly UsbAllowList allowList;
    readonly UsbPairingReader pairings;
    readonly object gate = new object();
    UsbTunnelServer server;
    string problem;

    public event Action Changed;

    public UsbTunnelService(UsbTunnelOptions options)
    {
        this.options = options;
        if (options.Limiter == null) {
            options.Limiter = new UsbReadLimiter(options.Clock);
        }
        if (options.AllowFile == null) {
            options.AllowFile = UsbAllowList.NextTo(options.PairingsFile);
        }
        settings = new UsbSettings(UsbSettings.NextTo(options.PairingsFile));
        allowList = new UsbAllowList(options.AllowFile);
        pairings = new UsbPairingReader(options.PairingsFile, options.Limiter, options.Random);
    }

    public bool On { get { return settings.UsbOn(); } }

    public UsbTunnelServer Server
    {
        get {
            lock (gate) {
                return server;
            }
        }
    }

    public bool IsSharing
    {
        get {
            UsbTunnelServer s = Server;
            return s != null && s.Sharing != null;
        }
    }

    void Notify()
    {
        Action a = Changed;
        if (a != null) {
            a();
        }
    }

    // Starts or stops listening to match the setting
    public void Apply()
    {
        if (settings.UsbOn()) {
            Start();
        }
        else {
            Stop(Swut.EndUsbOff);
        }
    }

    public void SetOn(bool on)
    {
        settings.SetUsbOn(on);
        Apply();
    }

    void Start()
    {
        lock (gate) {
            if (server != null) {
                return;
            }
            UsbTunnelServer s = null;
            try {
                s = new UsbTunnelServer(options);
                s.Changed += Notify;
                s.Start();
                server = s;
                problem = null;
            }
            catch (Exception e) {
                problem = e.Message;
                options.Log("couldn't start listening on TCP " + options.Port + ": " + e.Message);
                if (s != null) {
                    s.Dispose();
                }
            }
        }
        Notify();
    }

    void Stop(int reason)
    {
        UsbTunnelServer s;
        lock (gate) {
            s = server;
            server = null;
            problem = null;
        }
        if (s != null) {
            s.Stop(reason);
            s.Dispose();
            options.Log("stopped listening");
        }
        Notify();
    }

    public string Status()
    {
        UsbTunnelServer s = Server;
        if (s == null) {
            if (problem != null) {
                return "USB sharing couldn't start: " + problem;
            }
            return settings.UsbOn() ? "USB sharing is starting." : "USB sharing is off.";
        }
        UsbTunnelSession shared = s.Sharing;
        if (shared != null) {
            return (shared.Attached ? "Sharing " : "Attaching ") + (shared.DeviceName ?? "a USB device") + " from " + shared.PhoneName + ".";
        }
        return "USB sharing is on (TCP " + s.Port + "). No device is shared.";
    }

    public bool StopSharing()
    {
        UsbTunnelServer s = Server;
        return s != null && s.StopSharing();
    }

    // The paired phones and whether each has Allow USB, or null with the reason
    public List<UsbPhone> Phones(out string why)
    {
        bool late;
        List<UsbPairingLine> lines = pairings.Read(options.Clock.Now + 1500, out why, out late);
        if (lines == null) {
            return null;
        }
        string listProblem;
        Dictionary<string, string> allowed = allowList.Load(out listProblem);
        List<UsbPhone> phones = new List<UsbPhone>();
        foreach (UsbPairingLine line in lines) {
            UsbPhone p = new UsbPhone();
            p.Line = line;
            string hash;
            p.Allowed = allowed != null && allowed.TryGetValue(line.Id, out hash) && hash == UsbAllowList.KeyHash(line.KeyField);
            phones.Add(p);
        }
        why = listProblem;
        return phones;
    }

    // Ticks or unticks Allow USB for a phone with this pairing. Unticking ends its session at once.
    public bool SetAllowed(UsbPairingLine phone, bool allow, out string why)
    {
        bool late;
        List<UsbPairingLine> lines = pairings.Read(options.Clock.Now + 1500, out why, out late);
        if (lines == null) {
            return false;
        }
        UsbPairingLine now = UsbPairingReader.Find(lines, phone.DeviceId);
        if (allow && (now == null || now.KeyField != phone.KeyField)) {
            why = "its pairing changed. Open the menu again.";
            return false;
        }
        if (!allowList.Set(phone, allow, lines, out why)) {
            return false;
        }
        options.Log(phone.Name + " (" + phone.Id + "): Allow USB " + (allow ? "ticked" : "unticked"));
        UsbTunnelServer s = Server;
        if (s != null && !allow) {
            s.Recheck();
        }
        Notify();
        return true;
    }

    public void Dispose()
    {
        Stop(Swut.EndClosing);
    }
}

// The helper's log, for finding out what happened on a real attach: usb-helper-log.txt next to the
// pairings file, at most 512 KB, then the older half goes to usb-helper-log.old.txt. No keys go in it.
sealed class UsbLog
{
    const long MaxBytes = 512 * 1024;
    readonly string path;
    readonly object gate = new object();

    public UsbLog(string path)
    {
        this.path = path;
    }

    public static string NextTo(string pairingsFile)
    {
        return Path.Combine(Path.GetDirectoryName(Path.GetFullPath(pairingsFile)), "usb-helper-log.txt");
    }

    public void Write(string line)
    {
        lock (gate) {
            try {
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                FileInfo f = new FileInfo(path);
                if (f.Exists && f.Length > MaxBytes) {
                    string old = Path.Combine(Path.GetDirectoryName(path), "usb-helper-log.old.txt");
                    File.Copy(path, old, true);
                    File.Delete(path);
                }
                File.AppendAllText(path, DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture) + "Z  "
                    + line + "\r\n", new UTF8Encoding(false));
            }
            catch (Exception) {
            }
        }
    }
}
