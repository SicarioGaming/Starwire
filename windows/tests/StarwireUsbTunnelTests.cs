// Tests for the USB helper's side of the USB tunnel (GPL-3.0, part of Starwire): the codec against
// the shared vectors, the device rules against the shared cases, the reads of the microphone
// receiver's pairings file (with the receiver's own MicPairingStore from SecureMic.cs doing the
// pairing and unpairing), the Allow USB list, the limits on connections waiting for BIND, the
// relay's USB/IP checks, and the whole path on 127.0.0.1: a phone made here talks TLS to the
// helper's server, BINDs and SHAREs, and a fake driver attaches the device through the relay with
// the helper's own driver client, as usbip-win2 would. Nothing is attached for real.
//
// Deterministic: no network beyond 127.0.0.1, fixed random bytes, and a clock of the tests' own for
// every rule that depends on time. Waits for the other side of a socket have a limit, so a broken
// build fails instead of hanging. Its files go in a folder in TEMP named after the test's process.
// The TLS keys it makes (one for the helper per server, one for the test phones) are deleted at
// the end.
//
//   StarwireUsbTunnelTests.exe <folder with swut-v1-vectors.txt and swut-v1-policy.txt>
// run-usb-helper-tests.ps1 builds and runs it; exits with 1 if any check fails.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;

static class StarwireUsbTunnelTests
{
    // The contract's SHA-256 of each shared file (docs/dev/usb-protocol.md, "Test vectors")
    const string VectorsSha256 = "b955594038a2a423525024796861a3f43748799c8e10e4260dea9f6eae60a085";
    const string PolicySha256 = "1acfa0a186b4bf0f68e90317897cdae9afc83d38bece590b55003dcb793599b4";
    // usbip-win2 0.9.8.1's usbip2_ude.sys, as KnownDriver pins it
    const string ReleaseSysSha256 = "abd4fc43dce40e027e4acc884cbfe92433e026206f6565c5ee5284a39441169a";
    const int WaitMs = 20000;

    static int passed, failed;
    static string temp, vectorFolder, lockName;
    static int pid;
    static Dictionary<string, byte[]> V;
    static UsbTlsIdentity phoneCert;
    static readonly List<string> containers = new List<string>();

    // The vectors' inputs: the microphone's pairing key and device id, and a second phone
    static readonly byte[] PairKey = Range(0x00, 32);
    static readonly byte[] DeviceId = Range(0xa0, 8);
    static readonly byte[] OtherKey = Range(0x20, 32);
    static readonly byte[] OtherId = Range(0xb0, 8);
    static readonly byte[] ThirdKey = Range(0x40, 32);

    static void Check(string what, bool ok)
    {
        if (ok) {
            passed++;
            Console.WriteLine("PASS " + what);
        }
        else {
            failed++;
            Console.WriteLine("FAIL " + what);
        }
    }

    static int Main(string[] args)
    {
        pid = Process.GetCurrentProcess().Id;
        vectorFolder = args.Length > 0 ? args[0] : Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "vectors");
        temp = Path.Combine(Path.GetTempPath(), "StarwireUsbTunnelTests-" + pid);
        lockName = @"Local\StarwireUsbTunnelTests." + pid;
        if (Directory.Exists(temp)) {
            Directory.Delete(temp, true);
        }
        Directory.CreateDirectory(temp);
        int leftover = DeleteLeftoverKeys();
        if (leftover > 0) {
            Console.WriteLine("Deleted " + leftover + " TLS keys an earlier run of these tests left behind when it crashed.");
        }
        try {
            Vectors();
            BindAnswers();
            ShareLayouts();
            PolicyCases();
            Limiter();
            PairingReads();
            PairingFileSharing();
            AllowList();
            WaitingRoom();
            StreamCheck();
            Tls();
            Renegotiation();
            TeardownWrites();
            TcpOwnership();
            phoneCert = UsbTlsIdentity.Create(Container("phone"), Seeded(7));
            ServerBind();
            ServerWaiting();
            ServerSweptBind();
            ShareRoundTrip();
            ShareRefusals();
            SessionEnds();
            FailClosedCheck();
            UsbOff();
            StuckTeardown();
            DriverStuckTeardown();
            SecurityChecks();
            BoundedWorkers();
            Service();
        }
        catch (Exception e) {
            failed++;
            Console.WriteLine("FAIL the tests themselves threw: " + e);
        }
        finally {
            if (phoneCert != null) {
                phoneCert.Dispose();
            }
            foreach (string c in containers) {
                UsbTlsIdentity.DeleteContainer(c);
            }
            try {
                Directory.Delete(temp, true);
            }
            catch (Exception) {
            }
        }
        bool keysGone = true;
        foreach (string c in containers) {
            keysGone &= !CngKey.Exists(c);
        }
        Check("every TLS key the tests made is deleted", keysGone);
        Console.WriteLine();
        Console.WriteLine(failed == 0 ? "All " + passed + " checks passed." : failed + " of " + (passed + failed) + " checks failed.");
        return failed == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------- small helpers
    static byte[] Range(int first, int n)
    {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (first + i);
        }
        return b;
    }

    static byte[] Repeat(int value, int n)
    {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) value;
        }
        return b;
    }

    static bool Eq(byte[] a, byte[] b)
    {
        if (a == null || b == null || a.Length != b.Length) {
            return false;
        }
        for (int i = 0; i < a.Length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    static byte[] Cat(params byte[][] parts)
    {
        return Swut.Concat(parts);
    }

    static string Sha256Hex(byte[] b)
    {
        using (SHA256 sha = SHA256.Create()) {
            return Swut.Hex(sha.ComputeHash(b));
        }
    }

    // Fixed random bytes, the same every run
    static Action<byte[]> Seeded(int seed)
    {
        Random r = new Random(seed);
        return delegate(byte[] b) {
            lock (r) {
                r.NextBytes(b);
            }
        };
    }

    [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
    static extern int NCryptOpenStorageProvider(out IntPtr provider, string name, int flags);
    [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
    static extern int NCryptEnumKeys(IntPtr provider, string scope, out IntPtr keyName, ref IntPtr state, int flags);
    [DllImport("ncrypt.dll")]
    static extern int NCryptFreeBuffer(IntPtr buffer);
    [DllImport("ncrypt.dll")]
    static extern int NCryptFreeObject(IntPtr handle);

    // The keys of an earlier run of these tests that crashed before it could delete them: named
    // after its process, which is gone. This account's own keys only, and only the tests'.
    static int DeleteLeftoverKeys()
    {
        const string prefix = "StarwireUsbTunnelTests.";
        List<string> names = new List<string>();
        IntPtr provider;
        if (NCryptOpenStorageProvider(out provider, "Microsoft Software Key Storage Provider", 0) != 0) {
            return 0;
        }
        IntPtr state = IntPtr.Zero;
        try {
            IntPtr keyName;
            while (NCryptEnumKeys(provider, null, out keyName, ref state, 0) == 0) {
                string name = Marshal.PtrToStringUni(Marshal.ReadIntPtr(keyName));
                NCryptFreeBuffer(keyName);
                if (name != null && name.StartsWith(prefix, StringComparison.Ordinal)) {
                    names.Add(name);
                }
            }
        }
        finally {
            if (state != IntPtr.Zero) {
                NCryptFreeBuffer(state);
            }
            NCryptFreeObject(provider);
        }
        int deleted = 0;
        foreach (string name in names) {
            string[] parts = name.Split('.');
            int owner;
            if (parts.Length < 3 || !int.TryParse(parts[1], out owner) || owner == pid || Running(owner)) {
                continue;
            }
            UsbTlsIdentity.DeleteContainer(name);
            deleted++;
        }
        return deleted;
    }

    static bool Running(int process)
    {
        try {
            return Process.GetProcessById(process).ProcessName.StartsWith("StarwireUsbTunnelTests", StringComparison.OrdinalIgnoreCase);
        }
        catch (ArgumentException) {
            return false;
        }
    }

    static string Container(string what)
    {
        string c = "StarwireUsbTunnelTests." + pid + "." + what;
        containers.Add(c);
        return c;
    }

    static bool Throws(Action a)
    {
        try {
            a();
            return false;
        }
        catch (Exception) {
            return true;
        }
    }

    // Waits for something another thread does, with a limit; the limit only matters when it fails
    static bool WaitUntil(Func<bool> condition)
    {
        Stopwatch w = Stopwatch.StartNew();
        while (w.ElapsedMilliseconds < WaitMs) {
            if (condition()) {
                return true;
            }
            Thread.Sleep(5);
        }
        return condition();
    }

    static Dictionary<string, byte[]> LoadVectors(string file)
    {
        Dictionary<string, byte[]> v = new Dictionary<string, byte[]>();
        foreach (string line in File.ReadAllLines(file)) {
            if (line.Length == 0 || line[0] == '#') {
                continue;
            }
            int eq = line.IndexOf('=');
            v[line.Substring(0, eq)] = Swut.Hex(line.Substring(eq + 1));
        }
        return v;
    }

    // A clock that only moves when the tests move it; a wait moves it on by that long
    sealed class FakeClock : IUsbClock
    {
        long now = 1000000;

        public long Now { get { return Interlocked.Read(ref now); } }

        public void Advance(long ms)
        {
            Interlocked.Add(ref now, ms);
        }

        public void Sleep(int ms)
        {
            Advance(ms);
        }
    }

    // A clock a second further on at every look, so no read ever waits
    sealed class JumpClock : IUsbClock
    {
        long now;

        public long Now { get { return Interlocked.Add(ref now, 1000); } }

        public void Sleep(int ms)
        {
        }
    }

    // A clock whose sleeps block until the test releases them, once the time has come, so a test
    // can hold a waiting BIND inside the limiter and act while it waits
    sealed class GateClock : IUsbClock
    {
        long now = 1000000;
        readonly object g = new object();
        bool released;
        public volatile int Sleepers;

        public long Now { get { lock (g) { return now; } } }

        public void Advance(long ms)
        {
            lock (g) {
                now += ms;
                Monitor.PulseAll(g);
            }
        }

        public void Release()
        {
            lock (g) {
                released = true;
                Monitor.PulseAll(g);
            }
        }

        public void Sleep(int ms)
        {
            lock (g) {
                long until = now + ms;
                Sleepers++;
                try {
                    while (!(released && now >= until)) {
                        Monitor.Wait(g, 50);
                    }
                }
                finally {
                    Sleepers--;
                }
            }
        }
    }

    static MicPairing Pairing(byte[] id, byte[] key, string name)
    {
        MicPairing p = new MicPairing();
        p.DeviceId = (byte[]) id.Clone();
        p.Key = (byte[]) key.Clone();
        p.Name = name;
        p.PairedAt = new DateTime(2026, 10, 26, 12, 0, 0, DateTimeKind.Utc);
        p.LastSeen = DateTime.MinValue;
        return p;
    }

    // ---------------------------------------------------------------- USB/IP messages, as the tests send them
    static byte[] ImportRequest(string busId)
    {
        byte[] m = new byte[UsbipStreamCheck.ImportRequestLength];
        UsbipStreamCheck.PutBe16(m, 0, UsbipStreamCheck.Version);
        UsbipStreamCheck.PutBe16(m, 2, UsbipStreamCheck.OpReqImport);
        Encoding.ASCII.GetBytes(busId).CopyTo(m, 8);
        return m;
    }

    static byte[] ImportReply(SwutShare s, int speed, int vendor)
    {
        byte[] m = new byte[UsbipStreamCheck.ImportReplyLength];
        UsbipStreamCheck.PutBe16(m, 0, UsbipStreamCheck.Version);
        UsbipStreamCheck.PutBe16(m, 2, UsbipStreamCheck.OpRepImport);
        Encoding.ASCII.GetBytes("/devices/test/1-1").CopyTo(m, 8);
        Encoding.ASCII.GetBytes(s.BusId).CopyTo(m, 264);
        UsbipStreamCheck.PutBe32(m, 296, 1);
        UsbipStreamCheck.PutBe32(m, 300, 2);
        UsbipStreamCheck.PutBe32(m, 304, (uint) speed);
        byte[] d = s.DeviceDescriptor;
        UsbipStreamCheck.PutBe16(m, 308, vendor >= 0 ? vendor : Swut.GetU16(d, 8));
        UsbipStreamCheck.PutBe16(m, 310, Swut.GetU16(d, 10));
        UsbipStreamCheck.PutBe16(m, 312, Swut.GetU16(d, 12));
        m[314] = d[4];
        m[315] = d[5];
        m[316] = d[6];
        m[317] = s.Configuration[5];
        m[318] = d[17];
        m[319] = s.Configuration[4];
        return m;
    }

    static byte[] Setup(int requestType, int request, int value, int index, int length)
    {
        return new byte[] { (byte) requestType, (byte) request, (byte) value, (byte) (value >> 8),
            (byte) index, (byte) (index >> 8), (byte) length, (byte) (length >> 8) };
    }

    static byte[] CmdSubmit(uint seq, bool input, int ep, int length, byte[] setup, byte[] data, int isoPackets)
    {
        byte[] h = new byte[UsbipStreamCheck.Header];
        UsbipStreamCheck.PutBe32(h, 0, UsbipStreamCheck.CmdSubmit);
        UsbipStreamCheck.PutBe32(h, 4, seq);
        UsbipStreamCheck.PutBe32(h, 8, (1u << 16) | 2);
        UsbipStreamCheck.PutBe32(h, 12, input ? 1u : 0u);
        UsbipStreamCheck.PutBe32(h, 16, (uint) ep);
        UsbipStreamCheck.PutBe32(h, 24, (uint) length);
        UsbipStreamCheck.PutBe32(h, 32, isoPackets > 0 ? (uint) isoPackets : 0xFFFFFFFF);
        if (setup != null) {
            Array.Copy(setup, 0, h, 40, 8);
        }
        return Cat(h, input || data == null ? new byte[0] : data, new byte[isoPackets * 16]);
    }

    static byte[] RetSubmit(uint seq, int status, byte[] data, int isoPackets)
    {
        byte[] h = new byte[UsbipStreamCheck.Header];
        UsbipStreamCheck.PutBe32(h, 0, UsbipStreamCheck.RetSubmit);
        UsbipStreamCheck.PutBe32(h, 4, seq);
        UsbipStreamCheck.PutBe32(h, 20, unchecked((uint) status));
        UsbipStreamCheck.PutBe32(h, 24, (uint) (data == null ? 0 : data.Length));
        UsbipStreamCheck.PutBe32(h, 32, isoPackets > 0 ? (uint) isoPackets : 0);
        return Cat(h, data ?? new byte[0], new byte[isoPackets * 16]);
    }

    // An OUT transfer's reply: how much went out, and no data
    static byte[] RetSubmitOut(uint seq, int actual)
    {
        byte[] h = RetSubmit(seq, 0, null, 0);
        UsbipStreamCheck.PutBe32(h, 24, (uint) actual);
        return h;
    }

    static byte[] CmdUnlink(uint seq, uint target)
    {
        byte[] h = new byte[UsbipStreamCheck.Header];
        UsbipStreamCheck.PutBe32(h, 0, UsbipStreamCheck.CmdUnlink);
        UsbipStreamCheck.PutBe32(h, 4, seq);
        UsbipStreamCheck.PutBe32(h, 20, target);
        return h;
    }

    static byte[] RetUnlink(uint seq, int status)
    {
        return UsbipStreamCheck.UnlinkReply(seq, status);
    }

    static SwutShare Pad()
    {
        return Swut.ParseShare(V["frame.share"]);
    }

    // ---------------------------------------------------------------- the codec against the vectors
    static void Vectors()
    {
        string file = Path.Combine(vectorFolder, "swut-v1-vectors.txt");
        Check("the vectors file is the contract's, byte for byte (SHA-256)", Sha256Hex(File.ReadAllBytes(file)) == VectorsSha256);
        V = LoadVectors(file);
        Check("HKDF: RFC 5869 test case 1", Eq(Swut.Hkdf(Range(0x00, 13), Repeat(0x0b, 22), Range(0xf0, 10), 42), V["kat.hkdf_rfc5869_1"]));
        byte[] data = Encoding.ASCII.GetBytes("Test With Truncation");
        Check("MAC16: RFC 4231 test case 5", Eq(Swut.Mac16(Repeat(0x0c, 20), data, 0, data.Length), V["kat.mac16_rfc4231_5"]));
        byte[] usbKey = Swut.UsbKey(PairKey, DeviceId);
        Check("K_usb from the microphone's pairing key and device id", Eq(usbKey, V["key.usb"]));
        Check("K_usb isn't the microphone's K_auth (SecureMic.cs)", !Eq(usbKey, Swmc.AuthKey(PairKey, DeviceId)));
        byte[] fpPhone = Swut.Fingerprint(V["cert.phone"]);
        byte[] fpHelper = Swut.Fingerprint(V["cert.helper"]);
        Check("Starwire's certificate's fingerprint", Eq(fpPhone, V["fp.phone"]));
        Check("the helper's certificate's fingerprint", Eq(fpHelper, V["fp.helper"]));
        Check("the vectors' certificates have P-256 keys", UsbTlsIdentity.IsP256(new X509Certificate2(V["cert.phone"]))
            && UsbTlsIdentity.IsP256(new X509Certificate2(V["cert.helper"])));
        byte[] hello = Swut.Hello(Range(0xd0, 16), 1, 1);
        Check("HELLO", Eq(hello, V["frame.hello"]) && Swut.IsHello(hello) && Swut.ChooseVersion(hello) == 1);
        byte[] bind = Swut.Bind(usbKey, hello, fpPhone, fpHelper, 1, DeviceId, Range(0xe0, 16));
        Check("BIND", Eq(bind, V["frame.bind"]));
        Check("the helper takes the vectors' BIND", Swut.BindRefusal(hello, V["frame.bind"]) == 0
            && Eq(Swut.BindDeviceId(V["frame.bind"]), DeviceId)
            && Swut.BindChecksOut(usbKey, hello, fpPhone, fpHelper, V["frame.bind"]));
        byte[] ack = Swut.BindAck(usbKey, hello, fpPhone, fpHelper, V["frame.bind"]);
        Check("BIND_ACK", Eq(ack, V["frame.bind_ack"]) && Swut.BindAckChecksOut(usbKey, hello, fpPhone, fpHelper, bind, ack));
        Check("a BIND made with the microphone's K_auth doesn't check out",
            !Swut.BindChecksOut(usbKey, hello, fpPhone, fpHelper, V["refuse.bind_mic_key"]));
        Check("  and it is the BIND that K_auth gives",
            Eq(Swut.Bind(Swmc.AuthKey(PairKey, DeviceId), hello, fpPhone, fpHelper, 1, DeviceId, Range(0xe0, 16)), V["refuse.bind_mic_key"]));
        Check("a BIND with the fingerprints the other way round doesn't check out",
            !Swut.BindChecksOut(usbKey, hello, fpPhone, fpHelper, V["refuse.bind_swapped_fingerprints"])
            && Swut.BindChecksOut(usbKey, hello, fpHelper, fpPhone, V["refuse.bind_swapped_fingerprints"]));
        Check("a BIND made over a HELLO that offered versions 1 to 2 doesn't check out over this one",
            !Swut.BindChecksOut(usbKey, hello, fpPhone, fpHelper, V["refuse.bind_other_hello"])
            && Swut.BindChecksOut(usbKey, Swut.Hello(Range(0xd0, 16), 1, 2), fpPhone, fpHelper, V["refuse.bind_other_hello"]));
        byte[] otherBind = Swut.Bind(usbKey, hello, fpPhone, fpHelper, 1, DeviceId, Range(0xf0, 16));
        Check("a BIND_ACK for the other BIND doesn't check out",
            !Swut.BindAckChecksOut(usbKey, hello, fpPhone, fpHelper, bind, V["refuse.bind_ack_other_bind"])
            && Swut.BindAckChecksOut(usbKey, hello, fpPhone, fpHelper, otherBind, V["refuse.bind_ack_other_bind"]));
        Check("REFUSE 1", Eq(Swut.Refuse(Swut.RefuseNotPaired), V["frame.refuse_not_paired"])
            && Swut.Code(V["frame.refuse_not_paired"], Swut.TRefuse) == 1);
        SwutShare share = Swut.ParseShare(V["frame.share"]);
        Check("SHARE reads: the test pad 1209:0001, bus ID 1-1, \"Test pad\", one report descriptor", share != null
            && share.BusId == "1-1" && share.DisplayName() == "Test pad" && share.VendorId == 0x1209 && share.ProductId == 0x0001
            && share.Reports.Length == 1 && share.ReportInterfaces[0] == 0 && share.Reports[0].Length == 40 && !share.Confirmed);
        Check("SHARE writes back the same bytes", share != null && Eq(Swut.Share(0, "1-1", "Test pad", share.DeviceDescriptor,
            share.Configuration, share.ReportInterfaces, share.Reports), V["frame.share"]));
        Check("SHARE_ACK 0", Eq(Swut.ShareAck(0, 0), V["frame.share_ack"]));
        Check("SHARE_ACK 2, rule 5", Eq(Swut.ShareAck(Swut.ShareRefused, Swut.RuleUnconfirmedInput), V["frame.share_ack_unconfirmed_keyboard"])
            && Swut.Detail(V["frame.share_ack_unconfirmed_keyboard"], Swut.TShareAck) == 5);
        byte[] import = ImportRequest("1-1");
        Check("DATA carrying OP_REQ_IMPORT for bus ID 1-1", Eq(Swut.Data(import, 0, import.Length), V["frame.data_import"]));
        Check("KEEPALIVE", Eq(Swut.Keepalive(), V["frame.keepalive"]) && Swut.IsKeepalive(V["frame.keepalive"]));
        Check("END 1", Eq(Swut.End(Swut.EndStopped, 0), V["frame.end_stopped"]) && Swut.Code(V["frame.end_stopped"], Swut.TEnd) == 1);
    }

    // The order the helper answers whatever comes where BIND should
    static void BindAnswers()
    {
        byte[] hello = V["frame.hello"];
        byte[] bind = V["frame.bind"];
        byte[] v2 = (byte[]) bind.Clone();
        v2[4] = 2;
        byte[] v0 = (byte[]) bind.Clone();
        v0[4] = 0;
        byte[] reserved = (byte[]) bind.Clone();
        reserved[5] = 1;
        byte[] longer = new byte[bind.Length + 1];
        Array.Copy(bind, longer, bind.Length);
        longer[2] = 45;
        byte[] longerV2 = (byte[]) longer.Clone();
        longerV2[4] = 2;
        Check("BIND for version 2: REFUSE 2", Swut.BindRefusal(hello, v2) == Swut.RefuseNoVersion);
        Check("BIND for version 0: REFUSE 2", Swut.BindRefusal(hello, v0) == Swut.RefuseNoVersion);
        Check("BIND with a reserved byte set: REFUSE 3", Swut.BindRefusal(hello, reserved) == Swut.RefuseMalformed);
        Check("BIND a byte too long: REFUSE 3", Swut.BindRefusal(hello, longer) == Swut.RefuseMalformed);
        Check("BIND a byte too long, for version 2: REFUSE 2, the version first", Swut.BindRefusal(hello, longerV2) == Swut.RefuseNoVersion);
        Check("BIND with no payload: REFUSE 3", Swut.BindRefusal(hello, new byte[] { 2, 0, 0, 0 }) == Swut.RefuseMalformed);
        Check("a SHARE where BIND should be: REFUSE 3", Swut.BindRefusal(hello, V["frame.share"]) == Swut.RefuseMalformed);
        Check("a header with an unknown type, a reserved byte or a payload over 16384 doesn't read",
            Swut.FrameLength(new byte[] { 10, 0, 0, 0 }, 0) < 0 && Swut.FrameLength(new byte[] { 2, 1, 44, 0 }, 0) < 0
            && Swut.FrameLength(new byte[] { 7, 0, 0x01, 0x40 }, 0) < 0 && Swut.FrameLength(new byte[] { 7, 0, 0x00, 0x40 }, 0) == 16388);
    }

    static void ShareLayouts()
    {
        SwutShare pad = Pad();
        Func<string, string, byte[]> make = (bus, name) => Swut.Share(0, bus, name, pad.DeviceDescriptor, pad.Configuration,
            pad.ReportInterfaces, pad.Reports);
        byte[] good = make("1-3:1", "Pad");
        Check("a bus ID like 1-3:1 is allowed", Swut.ParseShare(good) != null);
        foreach (string bad in new string[] { "1-1 ", "1-1\"&", "1|1", "", new string('1', 32) }) {
            byte[] f = (byte[]) good.Clone();
            byte[] busBytes = Encoding.ASCII.GetBytes(bad);
            // Rebuild with the bus ID written straight in, around Share's own checks
            byte[] body = Cat(new byte[] { 0, (byte) busBytes.Length }, busBytes, new byte[] { 3 }, Encoding.ASCII.GetBytes("Pad"),
                pad.DeviceDescriptor, new byte[] { (byte) pad.Configuration.Length, (byte) (pad.Configuration.Length >> 8) },
                pad.Configuration, new byte[] { 1, 0, (byte) pad.Reports[0].Length, 0 }, pad.Reports[0]);
            f = Cat(new byte[] { 5, 0, (byte) body.Length, (byte) (body.Length >> 8) }, body);
            Check("a SHARE with the bus ID \"" + bad.Replace("\"", "'") + "\" doesn't read", Swut.ParseShare(f) == null);
        }
        byte[] extra = Cat(V["frame.share"], new byte[] { 0 });
        extra[2] = (byte) (extra.Length - 4);
        Check("a SHARE with a byte left over doesn't read", Swut.ParseShare(extra) == null);
        byte[] flagged = (byte[]) V["frame.share"].Clone();
        flagged[4] = 2;
        Check("a SHARE with an unknown flag doesn't read", Swut.ParseShare(flagged) == null);
        Check("a name over 64 bytes is cut on a character", Swut.NameBytes(new string('é', 40)).Length == 64
            && Swut.NameBytes(new string('a', 63) + "é").Length == 63);
        Check("control characters leave a name before it's shown", Swut.CleanName(Encoding.UTF8.GetBytes("Pad\r\n‮X"), "x") == "Pad X");
    }

    // ---------------------------------------------------------------- the device rules' shared cases
    static void PolicyCases()
    {
        string file = Path.Combine(vectorFolder, "swut-v1-policy.txt");
        Check("the device rules' cases file is the contract's, byte for byte (SHA-256)", Sha256Hex(File.ReadAllBytes(file)) == PolicySha256);
        Dictionary<string, string> shares = new Dictionary<string, string>();
        Dictionary<string, string> wants = new Dictionary<string, string>();
        List<string> order = new List<string>();
        foreach (string line in File.ReadAllLines(file)) {
            int eq = line.IndexOf('=');
            if (eq < 0) {
                continue;
            }
            string key = line.Substring(0, eq);
            if (key.EndsWith(".share", StringComparison.Ordinal)) {
                string name = key.Substring(0, key.Length - 6);
                shares[name] = line.Substring(eq + 1);
                order.Add(name);
            }
            else if (key.EndsWith(".want", StringComparison.Ordinal)) {
                wants[key.Substring(0, key.Length - 5)] = line.Substring(eq + 1);
            }
        }
        Check("63 cases, each with its decision", order.Count == 63 && wants.Count == 63);
        Check("the pad case is the vectors' SHARE", shares.ContainsKey("pad") && Eq(Swut.Hex(shares["pad"]), V["frame.share"]));
        List<int> rules = new List<int>();
        foreach (string name in order) {
            SwutShare s = Swut.ParseShare(Swut.Hex(shares[name]));
            UsbDecision d = UsbDeviceRules.Decide(s);
            string want;
            wants.TryGetValue(name, out want);
            Check("device rules: " + name + " is " + want + (s != null && d.ToString() != want ? " (the helper says " + d + ")" : ""),
                s != null && d.ToString() == want);
            if (d.Verdict == UsbVerdict.Refuse && !rules.Contains(d.Rule)) {
                rules.Add(d.Rule);
            }
        }
        bool all = true;
        for (int r = 1; r <= 8; r++) {
            all &= r == Swut.RuleUnconfirmedInput || rules.Contains(r);
        }
        Check("the cases reach every rule SHARE can break (1 to 4 and 6 to 8)", all);
        byte[] keyboard = Swut.Hex(shares["hid_boot_keyboard"]);
        Check("the helper refuses a keyboard you didn't confirm: rule 5",
            UsbDeviceRules.ForHelper(Swut.ParseShare(keyboard)).ToString() == "refuse 5");
        keyboard[4] = Swut.FlagConfirmedInput;
        Check("  and takes it with SHARE's flag", UsbDeviceRules.ForHelper(Swut.ParseShare(keyboard)).Verdict == UsbVerdict.Ask);
        Check("the helper takes the pad without the flag", UsbDeviceRules.ForHelper(Pad()).Verdict == UsbVerdict.Allow);
    }

    // ---------------------------------------------------------------- reading the pairings file
    static void Limiter()
    {
        FakeClock clock = new FakeClock();
        UsbReadLimiter limiter = new UsbReadLimiter(clock);
        long start = clock.Now;
        bool three = limiter.WaitTurn(start) && limiter.WaitTurn(start) && limiter.WaitTurn(start);
        Check("BINDs get 3 reads of the pairings file in a second", three);
        // Before the fix BINDs got all 4, so connections sending BINDs could hold up the pairing
        // check after an unpair, and the menu
        Check("  not a 4th, with no time to wait", !limiter.WaitTurn(start));
        Check("  nor with 999 ms to wait", !limiter.WaitTurn(start + 999) && clock.Now == start);
        Check("the 4th is kept for the pairing check and the menu: it goes ahead at once", limiter.WaitTurnFirst(start) && clock.Now == start);
        Check("  and a 5th read doesn't, with no time to wait", !limiter.WaitTurnFirst(start));
        Check("a BIND goes ahead once a second has passed, after waiting exactly that", limiter.WaitTurn(start + 1000) && clock.Now == start + 1000);
        Check("the pairing check waits for the oldest read to leave the second, no longer", limiter.WaitTurn(clock.Now) && limiter.WaitTurn(clock.Now)
            && limiter.WaitTurnFirst(clock.Now) && limiter.WaitTurnFirst(start + 2000) && clock.Now == start + 2000);

        clock = new FakeClock();
        limiter = new UsbReadLimiter(clock);
        start = clock.Now;
        limiter.WaitTurn(start);
        limiter.WaitTurn(start);
        limiter.WaitTurn(start);
        int asked = 0;
        Check("a BIND whose connection was closed while it waited stops waiting, and takes no read", !limiter.WaitTurn(start + 5000, () => ++asked > 1)
            && clock.Now == start + 1000 && limiter.WaitTurn(clock.Now) && limiter.WaitTurn(clock.Now) && limiter.WaitTurn(clock.Now));
    }

    static string PairingsFolder(string name)
    {
        string folder = Path.Combine(temp, name, "Starwire");
        Directory.CreateDirectory(folder);
        return folder;
    }

    static void PairingReads()
    {
        string path = Path.Combine(PairingsFolder("reads"), "mic-pairings.dat");
        MicPairingStore store = new MicPairingStore(path);
        store.Load(Seeded(1));
        store.Put(Pairing(DeviceId, PairKey, "Test phone"));
        store.Put(Pairing(OtherId, OtherKey, "Other phone"));
        FakeClock clock = new FakeClock();
        UsbPairingReader reader = new UsbPairingReader(path, new UsbReadLimiter(clock), Seeded(2));
        byte[] before = File.ReadAllBytes(path);
        DateTime written = File.GetLastWriteTimeUtc(path);
        string problem;
        bool late;
        List<UsbPairingLine> lines = reader.Read(clock.Now, out problem, out late);
        Check("the helper reads the receiver's pairings", lines != null && lines.Count == 2 && lines[0].Name == "Test phone"
            && lines[0].KeyField == store.Find(DeviceId).Protected);
        UsbBindKey key = reader.ForBind(DeviceId, clock.Now + 10000, out problem);
        Check("for a paired phone's BIND it works out the vectors' K_usb from its key", key != null && key.Paired
            && Eq(key.UsbKey, V["key.usb"]) && key.KeyField == store.Find(DeviceId).Protected && key.Name == "Test phone");
        UsbBindKey none = reader.ForBind(Range(0x10, 8), clock.Now + 10000, out problem);
        Check("for an unknown device id: not paired, a random K_usb, no key field", none != null && !none.Paired
            && !Eq(none.UsbKey, V["key.usb"]) && none.UsbKey.Length == 32 && none.KeyField == null);
        key.Wipe();
        Check("K_usb is wiped when the helper is done with it", Eq(key.UsbKey, new byte[32]));
        Check("each BIND read the file again", reader.Reads == 3);
        UsbBindKey tooMany = reader.ForBind(DeviceId, clock.Now, out problem);
        Check("after the menu's read and 2 BINDs in a second, a 3rd BIND has to wait: with no time left, it gets nothing", tooMany == null
            && reader.Reads == 3);
        List<UsbPairingLine> check = reader.Read(clock.Now, out problem, out late);
        List<UsbPairingLine> fifth = reader.Read(clock.Now, out problem, out late);
        Check("  while the pairing check gets the 4th read, and a 5th has to wait", check != null && fifth == null && late
            && reader.Reads == 4);
        Check("reading never changed the file", Eq(File.ReadAllBytes(path), before) && File.GetLastWriteTimeUtc(path) == written);

        string text = "# a comment\nreceiver\t0102030405060708\n"
            + "device\tzz00000000000000\tAAAA\t2026-10-26T12:00:00Z\t-\tbad id\n"
            + "device\ta0a1a2a3a4a5a6a7\tFIRST\t2026-10-26T12:00:00Z\t-\tFirst\r\n"
            + "device\ta0a1a2a3a4a5a6a7\tSECOND\t2026-10-26T12:00:00Z\t-\tSecond\n"
            + "device\tb0b1b2b3b4b5b6b7\tBAD\tnot a date\t-\tBad date\n"
            + "device\tc0c1c2c3c4c5c6c7\tFIELD\t2026-10-26T12:00:00Z\t2026-10-26T13:00:00Z\tLast\n"
            + "device\td0d1d2d3d4d5d6d7\tFIVE\t2026-10-26T12:00:00Z\t-\n"
            + "later\tsomething\n";
        List<UsbPairingLine> parsed = UsbPairingReader.Parse(text);
        Check("lines that don't read are skipped, and of two for one device the first counts", parsed.Count == 2
            && parsed[0].KeyField == "FIRST" && parsed[1].KeyField == "FIELD");
        List<UsbPairingLine> twice = new List<UsbPairingLine>();
        twice.Add(parsed[1]);
        twice.Add(parsed[0]);
        UsbPairingLine copy = new UsbPairingLine();
        copy.DeviceId = parsed[0].DeviceId;
        copy.KeyField = "LATER";
        twice.Add(copy);
        Check("the line search finds the first match wherever it is",
            UsbPairingReader.Find(twice, DeviceId).KeyField == "FIRST" && UsbPairingReader.Find(twice, Range(0xc0, 8)).KeyField == "FIELD"
            && UsbPairingReader.Find(twice, Range(0x11, 8)) == null && UsbPairingReader.Find(new List<UsbPairingLine>(), DeviceId) == null);

        UsbPairingReader missing = new UsbPairingReader(Path.Combine(temp, "no-such-folder", "mic-pairings.dat"), new UsbReadLimiter(clock), Seeded(3));
        clock.Advance(1000);
        List<UsbPairingLine> empty = missing.Read(clock.Now, out problem, out late);
        UsbBindKey nobody = missing.ForBind(DeviceId, clock.Now, out problem);
        Check("a missing pairings file has no pairings", empty != null && empty.Count == 0 && nobody != null && !nobody.Paired);
    }

    // The receiver must still be able to pair and unpair while the helper reads the file
    static void PairingFileSharing()
    {
        string path = Path.Combine(PairingsFolder("sharing"), "mic-pairings.dat");
        MicPairingStore store = new MicPairingStore(path);
        store.Load(Seeded(4));
        store.Put(Pairing(DeviceId, PairKey, "Test phone"));
        bool put, removed;
        using (FileStream held = UsbPairingReader.OpenShared(path)) {
            put = !Throws(delegate { store.Put(Pairing(OtherId, OtherKey, "Other phone")); });
            removed = !Throws(delegate { store.Remove(OtherId); });
        }
        Check("the receiver pairs while the helper holds the file open the way it reads it", put);
        Check("the receiver unpairs while the helper holds the file open the way it reads it", removed);
        bool blocked;
        // The control: File.ReadAllText (the receiver's own Load) opens the file shared for reading only
        using (FileStream held = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
            blocked = Throws(delegate { store.Put(Pairing(OtherId, OtherKey, "Other phone")); });
        }
        Check("(the control) with the file open as File.ReadAllText opens it, the receiver can't save", blocked);
        foreach (string f in Directory.GetFiles(Path.GetDirectoryName(path), "*.new")) {
            File.Delete(f);
        }
        store.Load(Seeded(5));
        UsbPairingReader reader = new UsbPairingReader(path, new UsbReadLimiter(new JumpClock()), Seeded(6));
        int reads = 0;
        bool stop = false;
        Thread t = new Thread(delegate() {
            while (!Volatile.Read(ref stop)) {
                string p;
                bool late;
                reader.Read(0, out p, out late);
                Interlocked.Increment(ref reads);
            }
        });
        t.Start();
        WaitUntil(() => Thread.VolatileRead(ref reads) > 0);
        int failures = 0;
        for (int i = 0; i < 30; i++) {
            if (Throws(delegate { store.Put(Pairing(OtherId, OtherKey, "Other phone")); })) {
                failures++;
            }
            if (Throws(delegate { store.Remove(OtherId); })) {
                failures++;
            }
        }
        Volatile.Write(ref stop, true);
        t.Join();
        Check("the receiver paired and unpaired 30 times while the helper read the file over and over (" + reads + " reads, "
            + failures + " failed saves)", failures == 0 && reads > 30);
    }

    // ---------------------------------------------------------------- Allow USB
    static void AllowList()
    {
        string folder = PairingsFolder("allow");
        string path = Path.Combine(folder, "mic-pairings.dat");
        MicPairingStore store = new MicPairingStore(path);
        store.Load(Seeded(8));
        store.Put(Pairing(DeviceId, PairKey, "Test phone"));
        store.Put(Pairing(OtherId, OtherKey, "Other phone"));
        UsbAllowList list = new UsbAllowList(UsbAllowList.NextTo(path));
        byte[] pairingsBefore = File.ReadAllBytes(path);
        Check("the list goes next to the pairings file", list.FilePath == Path.Combine(folder, "usb-allowed.dat"));
        List<UsbPairingLine> lines = UsbPairingReader.Parse(File.ReadAllText(path));
        UsbPairingLine phone = UsbPairingReader.Find(lines, DeviceId);
        string problem;
        Check("nobody is allowed USB at first", !list.IsAllowed(DeviceId, phone.KeyField) && !File.Exists(list.FilePath));
        Check("Allow USB ticked for a phone", list.Set(phone, true, lines, out problem) && list.IsAllowed(DeviceId, phone.KeyField)
            && !list.IsAllowed(OtherId, UsbPairingReader.Find(lines, OtherId).KeyField));
        string[] written = File.ReadAllLines(list.FilePath);
        Check("its line is the device id and the SHA-256 of the pairing's key field", written.Length == 3
            && written[2] == Swut.Hex(DeviceId) + "\t" + UsbAllowList.KeyHash(phone.KeyField));
        Check("Allow USB doesn't carry over to another key field for the same id", !list.IsAllowed(DeviceId, phone.KeyField + "A"));
        Check("ticking Allow USB never touched the pairings file", Eq(File.ReadAllBytes(path), pairingsBefore));
        store.Put(Pairing(DeviceId, ThirdKey, "Test phone"));
        List<UsbPairingLine> again = UsbPairingReader.Parse(File.ReadAllText(path));
        UsbPairingLine repaired = UsbPairingReader.Find(again, DeviceId);
        Check("a phone that pairs again needs Allow USB again", repaired.KeyField != phone.KeyField
            && !list.IsAllowed(DeviceId, repaired.KeyField));
        store.Remove(DeviceId);
        List<UsbPairingLine> unpaired = UsbPairingReader.Parse(File.ReadAllText(path));
        UsbPairingLine other = UsbPairingReader.Find(unpaired, OtherId);
        Check("the next save drops the line of a phone that was unpaired", list.Set(other, true, unpaired, out problem)
            && File.ReadAllLines(list.FilePath).Length == 3 && File.ReadAllLines(list.FilePath)[2].StartsWith(Swut.Hex(OtherId), StringComparison.Ordinal));
        Check("unticking the last phone removes the list", list.Set(other, false, unpaired, out problem) && !File.Exists(list.FilePath));
        File.WriteAllText(list.FilePath, "someone else's file\r\n" + Swut.Hex(OtherId) + "\t" + UsbAllowList.KeyHash(other.KeyField) + "\r\n");
        byte[] foreign = File.ReadAllBytes(list.FilePath);
        Check("a list that isn't the helper's allows nobody, and the helper won't replace it", !list.IsAllowed(OtherId, other.KeyField)
            && !list.Set(other, true, unpaired, out problem) && Eq(File.ReadAllBytes(list.FilePath), foreign));
        File.Delete(list.FilePath);
    }

    // ---------------------------------------------------------------- connections waiting for BIND
    static void WaitingRoom()
    {
        FakeClock clock = new FakeClock();
        UsbWaitingRoom room = new UsbWaitingRoom(clock);
        List<string> closed = new List<string>();
        Func<string, Action> closer = name => delegate { closed.Add(name); };

        // 1 per address and a per-address rate limit: a second connection from an address within
        // its 2 s grace is closed by the caller (the room returns null) and does NOT take the
        // first one's place. Before the fix a flood from one address kept replacing itself, each
        // one pushing out the last and burning a pairing-file read.
        UsbWaitingRoom.Ticket a1 = room.Admit("10.0.0.1", closer("a1"));
        UsbWaitingRoom.Ticket a1b = room.Admit("10.0.0.1", closer("a1b"));
        Check("a second connection from an address within its grace is refused, and the first keeps its place",
            a1 != null && a1b == null && room.Count == 1 && closed.Count == 0 && !room.IsGone(a1));

        UsbWaitingRoom.Ticket b = room.Admit("10.0.0.2", closer("b"));
        UsbWaitingRoom.Ticket c = room.Admit("10.0.0.3", closer("c"));
        UsbWaitingRoom.Ticket d = room.Admit("10.0.0.4", closer("d"));
        Check("four addresses fill the room", room.Count == 4 && b != null && c != null && d != null && closed.Count == 0);

        clock.Advance(1999);
        UsbWaitingRoom.Ticket e = room.Admit("10.0.0.5", closer("e"));
        Check("with 4 waiting and the oldest not past 2 s, a fresh address is closed, evicting no one",
            e == null && room.Count == 4 && closed.Count == 0);
        clock.Advance(1);
        UsbWaitingRoom.Ticket f = room.Admit("10.0.0.6", closer("f"));
        Check("once the oldest has waited 2 s, a fresh address pushes out only the oldest",
            f != null && closed.Count == 1 && closed[0] == "a1" && room.Count == 4 && room.IsGone(a1));
        Check("the pushed-out connection can't finish its BIND", !room.Leave(a1));

        // After its grace an address may start a new connection, which takes over its old one
        UsbWaitingRoom.Ticket b2 = room.Admit("10.0.0.2", closer("b2"));
        Check("after its grace, an address's new connection takes over its old one, evicting no other address",
            b2 != null && room.IsGone(b) && closed.Contains("b") && room.Count == 4);
        Check("one that finished its BIND leaves", room.Leave(f) && room.Count == 3);

        // The rate-limit record is pruned once its grace is past, so it stays bounded
        clock.Advance(2000);
        room.Admit("10.0.0.50", closer("x"));
        Check("the per-address record is pruned to the last grace window", room.Tracked <= 2);

        // 10 s from each connection's start closes one that never finished its BIND
        FakeClock c2 = new FakeClock();
        UsbWaitingRoom room2 = new UsbWaitingRoom(c2);
        List<string> closed2 = new List<string>();
        Func<string, Action> closer2 = name => delegate { closed2.Add(name); };
        room2.Admit("10.0.1.1", closer2("p"));
        room2.Admit("10.0.1.2", closer2("q"));
        c2.Advance(9999);
        Check("nobody runs out of time before 10 s", room2.Sweep() == 0);
        c2.Advance(1);
        Check("10 s after its TCP connection, a connection without a valid BIND is closed",
            room2.Sweep() == 2 && room2.Count == 0 && closed2.Contains("p") && closed2.Contains("q"));
    }

    // ---------------------------------------------------------------- the relay's USB/IP checks
    static UsbipStreamCheck Imported(SwutShare share, List<byte[]> toPhone, List<byte[]> toDriver)
    {
        UsbipStreamCheck c = new UsbipStreamCheck(share);
        byte[] req = ImportRequest(share.BusId);
        c.FromDriver(req, 0, req.Length, toPhone, toDriver);
        byte[] rep = ImportReply(share, 2, -1);
        c.FromPhone(rep, 0, rep.Length, toDriver);
        toPhone.Clear();
        toDriver.Clear();
        return c;
    }

    static bool Driver(UsbipStreamCheck c, byte[] m, List<byte[]> toPhone, List<byte[]> toDriver)
    {
        return c.FromDriver(m, 0, m.Length, toPhone, toDriver);
    }

    static bool Phone(UsbipStreamCheck c, byte[] m, List<byte[]> toDriver)
    {
        return c.FromPhone(m, 0, m.Length, toDriver);
    }

    static void StreamCheck()
    {
        SwutShare pad = Pad();
        List<byte[]> toPhone = new List<byte[]>(), toDriver = new List<byte[]>();
        UsbipStreamCheck c = new UsbipStreamCheck(pad);
        byte[] req = ImportRequest("1-1");
        Check("the driver's OP_REQ_IMPORT goes on, in pieces too", c.FromDriver(req, 0, 10, toPhone, toDriver) && toPhone.Count == 0
            && c.FromDriver(req, 10, 30, toPhone, toDriver) && toPhone.Count == 1 && Eq(toPhone[0], req));
        byte[] rep = ImportReply(pad, 2, -1);
        Check("the device's OP_REP_IMPORT that fits SHARE goes on", c.FromPhone(rep, 0, 100, toDriver) && toDriver.Count == 0
            && c.FromPhone(rep, 100, rep.Length - 100, toDriver) && toDriver.Count == 1 && Eq(toDriver[0], rep) && c.Imported);

        Func<Func<UsbipStreamCheck, bool>, UsbipStreamCheck> fresh = run => {
            UsbipStreamCheck x = new UsbipStreamCheck(pad);
            run(x);
            return x;
        };
        UsbipStreamCheck w = fresh(x => Driver(x, ImportRequest("1-2"), new List<byte[]>(), new List<byte[]>()));
        Check("an OP_REQ_IMPORT for another bus ID: END 9", w.EndReason == Swut.EndProtocol);
        byte[] early = RetSubmit(1, 0, null, 0);
        w = fresh(x => Phone(x, early, new List<byte[]>()));
        Check("USB/IP from the phone before the driver asks: END 9", w.EndReason == Swut.EndProtocol);
        foreach (string what in new string[] { "vendor", "status", "busid", "speed5", "speed0", "interfaces", "config" }) {
            byte[] r = ImportReply(pad, what == "speed5" ? 5 : what == "speed0" ? 0 : 2, what == "vendor" ? 0x054c : -1);
            if (what == "status") {
                r = new byte[8];
                UsbipStreamCheck.PutBe16(r, 0, UsbipStreamCheck.Version);
                UsbipStreamCheck.PutBe16(r, 2, UsbipStreamCheck.OpRepImport);
                UsbipStreamCheck.PutBe32(r, 4, 1);
            }
            if (what == "busid") {
                r[266] = (byte) '2';
            }
            if (what == "interfaces") {
                r[319] = 2;
            }
            if (what == "config") {
                r[317] = 7;
            }
            byte[] reply = r;
            w = fresh(x => Driver(x, ImportRequest("1-1"), new List<byte[]>(), new List<byte[]>()) && Phone(x, reply, new List<byte[]>()));
            int rule = what == "speed5" ? Swut.RuleTooFast : Swut.RuleMismatch;
            Check("an import reply with another " + what + ": END 6, rule " + rule, w.EndReason == Swut.EndRules && w.EndRule == rule);
        }
        byte[] zeroConfig = ImportReply(pad, 2, -1);
        zeroConfig[317] = 0;
        w = fresh(x => Driver(x, ImportRequest("1-1"), new List<byte[]>(), new List<byte[]>()) && Phone(x, zeroConfig, new List<byte[]>()));
        Check("an import reply with configuration value 0 is fine", w.Imported && w.EndReason == 0);

        // Descriptors, before the driver gets them
        c = Imported(pad, toPhone, toDriver);
        byte[] getDevice = CmdSubmit(1, true, 0, 18, Setup(0x80, 6, 0x0100, 0, 18), null, 0);
        Check("GET_DESCRIPTOR for the device goes to the phone", Driver(c, getDevice, toPhone, toDriver) && toPhone.Count == 1 && Eq(toPhone[0], getDevice));
        byte[] deviceReply = RetSubmit(1, 0, pad.DeviceDescriptor, 0);
        Check("  and SHARE's device descriptor comes back to the driver", Phone(c, deviceReply, toDriver) && toDriver.Count == 1 && Eq(toDriver[0], deviceReply));
        toPhone.Clear();
        toDriver.Clear();
        Driver(c, CmdSubmit(2, true, 0, 8, Setup(0x80, 6, 0x0100, 0, 8), null, 0), toPhone, toDriver);
        Check("the start of it, for a shorter request, is fine", Phone(c, RetSubmit(2, 0, Swut.Slice(pad.DeviceDescriptor, 0, 8), 0), toDriver) && toDriver.Count == 1);
        Driver(c, CmdSubmit(3, true, 0, 255, Setup(0x80, 6, 0x0200, 0, 255), null, 0), toPhone, toDriver);
        Check("configuration 0 comes back as SHARE described it", Phone(c, RetSubmit(3, 0, pad.Configuration, 0), toDriver));
        Driver(c, CmdSubmit(4, true, 0, 255, Setup(0x81, 6, 0x2200, 0, 255), null, 0), toPhone, toDriver);
        Check("interface 0's report descriptor comes back as SHARE described it", Phone(c, RetSubmit(4, 0, pad.Reports[0], 0), toDriver));
        Driver(c, CmdSubmit(5, true, 0, 255, Setup(0x81, 6, 0x2200, 1, 255), null, 0), toPhone, toDriver);
        Check("a report descriptor SHARE didn't give can come back empty", Phone(c, RetSubmit(5, -32, null, 0), toDriver));
        Driver(c, CmdSubmit(6, true, 0, 255, Setup(0x80, 6, 0x0300 | 2, 0x409, 255), null, 0), toPhone, toDriver);
        Check("other string descriptors aren't checked in version 1", Phone(c, RetSubmit(6, 0, Range(0x10, 20), 0), toDriver) && c.EndReason == 0);
        foreach (string what in new string[] { "device", "config0", "config1", "report", "noreport" }) {
            UsbipStreamCheck d = Imported(pad, new List<byte[]>(), new List<byte[]>());
            byte[] setup = what == "device" ? Setup(0x80, 6, 0x0100, 0, 18) : what == "config0" ? Setup(0x80, 6, 0x0200, 0, 255)
                : what == "config1" ? Setup(0x80, 6, 0x0201, 0, 255) : what == "report" ? Setup(0x81, 6, 0x2200, 0, 255) : Setup(0x81, 6, 0x2200, 3, 255);
            byte[] good = what == "device" ? pad.DeviceDescriptor : what == "report" ? pad.Reports[0] : pad.Configuration;
            byte[] bad = (byte[]) good.Clone();
            bad[bad.Length - 1] ^= 1;
            if (what == "config1" || what == "noreport") {
                bad = Range(9, 9);
            }
            List<byte[]> sent = new List<byte[]>();
            Driver(d, CmdSubmit(9, true, 0, 255, setup, null, 0), new List<byte[]>(), new List<byte[]>());
            bool ok = Phone(d, RetSubmit(9, 0, bad, 0), sent);
            Check("a " + what + " reply SHARE didn't describe ends the session (END 6, rule 9) before the driver gets it",
                !ok && d.EndReason == Swut.EndRules && d.EndRule == Swut.RuleMismatch && sent.Count == 0);
        }

        // What the relay answers itself
        c = Imported(pad, toPhone, toDriver);
        string[] names = { "string descriptor 0xEE", "the BOS descriptor", "an other-speed configuration", "a vendor request to the device",
            "a vendor request to an interface", "a request of the reserved type" };
        byte[][] setups = { Setup(0x80, 6, 0x03EE, 0, 18), Setup(0x80, 6, 0x0F00, 0, 5), Setup(0x80, 6, 0x0700, 0, 9),
            Setup(0xC0, 1, 0, 0, 16), Setup(0x41, 1, 0, 0, 0), Setup(0xE0, 1, 0, 0, 4) };
        for (int i = 0; i < names.Length; i++) {
            toPhone.Clear();
            toDriver.Clear();
            uint seq = (uint) (20 + i);
            bool input = (setups[i][0] & 0x80) != 0;
            Driver(c, CmdSubmit(seq, input, 0, input ? 64 : 0, setups[i], null, 0), toPhone, toDriver);
            Check(names[i] + " never reaches the device: the relay stalls it (status -32, same seqnum)", toPhone.Count == 0
                && toDriver.Count == 1 && toDriver[0].Length == 48 && UsbipStreamCheck.Be32u(toDriver[0], 0) == UsbipStreamCheck.RetSubmit
                && UsbipStreamCheck.Be32u(toDriver[0], 4) == seq && UsbipStreamCheck.Be32(toDriver[0], 20) == -32
                && UsbipStreamCheck.Be32(toDriver[0], 24) == 0);
        }
        toPhone.Clear();
        toDriver.Clear();
        Driver(c, CmdUnlink(40, 21), toPhone, toDriver);
        Check("a CMD_UNLINK for a request the relay answered gets RET_UNLINK status 0 from the relay", toPhone.Count == 0 && toDriver.Count == 1
            && UsbipStreamCheck.Be32u(toDriver[0], 0) == UsbipStreamCheck.RetUnlink && UsbipStreamCheck.Be32u(toDriver[0], 4) == 40
            && UsbipStreamCheck.Be32(toDriver[0], 20) == 0);
        Check("the relay answered " + c.Stalled + " requests itself", c.Stalled == 6);

        // Transfers on other endpoints, unlinks, isochronous packets
        toPhone.Clear();
        toDriver.Clear();
        Driver(c, CmdSubmit(50, true, 1, 64, null, null, 0), toPhone, toDriver);
        Check("an interrupt IN transfer and its 8 bytes", toPhone.Count == 1 && Phone(c, RetSubmit(50, 0, Range(1, 8), 0), toDriver) && toDriver.Count == 1);
        toPhone.Clear();
        toDriver.Clear();
        byte[] outCmd = CmdSubmit(51, false, 2, 3, null, new byte[] { 1, 2, 3 }, 0);
        Check("an OUT transfer carries its data to the phone", Driver(c, outCmd, toPhone, toDriver) && toPhone.Count == 1 && toPhone[0].Length == 51
            && Phone(c, RetSubmitOut(51, 3), toDriver) && toDriver.Count == 1 && toDriver[0].Length == 48);
        toPhone.Clear();
        toDriver.Clear();
        Driver(c, CmdSubmit(52, true, 3, 192 * 3, null, null, 3), toPhone, toDriver);
        Check("an isochronous IN transfer with its 3 packet descriptors", toPhone.Count == 1 && toPhone[0].Length == 48 + 48
            && Phone(c, RetSubmit(52, 0, Range(0, 100), 3), toDriver) && toDriver.Count == 1 && toDriver[0].Length == 48 + 100 + 48);
        toPhone.Clear();
        toDriver.Clear();
        Driver(c, CmdSubmit(53, true, 1, 64, null, null, 0), toPhone, toDriver);
        Driver(c, CmdUnlink(54, 53), toPhone, toDriver);
        Check("an unlink goes to the phone, and its RET_UNLINK back", toPhone.Count == 2 && Phone(c, RetUnlink(54, -104), toDriver) && toDriver.Count == 1);
        Check("  after which the unlinked transfer gets no reply", !Phone(c, RetSubmit(53, 0, null, 0), new List<byte[]>()) && c.EndReason == Swut.EndProtocol);

        Func<byte[], int> fromDriverEnds = m => {
            UsbipStreamCheck x = Imported(pad, new List<byte[]>(), new List<byte[]>());
            Driver(x, m, new List<byte[]>(), new List<byte[]>());
            return x.EndReason;
        };
        Check("a transfer over 1 MiB: END 9", fromDriverEnds(CmdSubmit(60, true, 1, 1024 * 1024 + 1, null, null, 0)) == Swut.EndProtocol);
        Check("a transfer of exactly 1 MiB is fine", fromDriverEnds(CmdSubmit(60, true, 1, 1024 * 1024, null, null, 0)) == 0);
        byte[] manyIso = CmdSubmit(61, true, 3, 16, null, null, 0);
        UsbipStreamCheck.PutBe32(manyIso, 32, 1025);
        Check("more than 1024 isochronous packets: END 9", fromDriverEnds(manyIso) == Swut.EndProtocol);
        byte[] unknown = CmdSubmit(62, true, 1, 8, null, null, 0);
        UsbipStreamCheck.PutBe32(unknown, 0, 7);
        Check("a command USB/IP doesn't have: END 9", fromDriverEnds(unknown) == Swut.EndProtocol);
        byte[] direction = CmdSubmit(63, true, 1, 8, null, null, 0);
        UsbipStreamCheck.PutBe32(direction, 12, 2);
        Check("a direction that's neither IN nor OUT: END 9", fromDriverEnds(direction) == Swut.EndProtocol);
        UsbipStreamCheck many = Imported(pad, new List<byte[]>(), new List<byte[]>());
        bool going = true;
        for (uint s = 1; s <= 4096 && going; s++) {
            going = Driver(many, CmdSubmit(s, true, 1, 8, null, null, 0), new List<byte[]>(), new List<byte[]>());
        }
        Check("4096 transfers can wait", going);
        Check("  a 4097th: END 9", !Driver(many, CmdSubmit(5000, true, 1, 8, null, null, 0), new List<byte[]>(), new List<byte[]>())
            && many.EndReason == Swut.EndProtocol);
        UsbipStreamCheck twice = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Driver(twice, CmdSubmit(70, true, 1, 8, null, null, 0), new List<byte[]>(), new List<byte[]>());
        Check("the same seqnum twice: END 9", !Driver(twice, CmdSubmit(70, true, 1, 8, null, null, 0), new List<byte[]>(), new List<byte[]>()));
        UsbipStreamCheck more = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Driver(more, CmdSubmit(80, true, 1, 8, null, null, 0), new List<byte[]>(), new List<byte[]>());
        Check("a reply with more data than was asked for: END 9", !Phone(more, RetSubmit(80, 0, Range(0, 9), 0), new List<byte[]>())
            && more.EndReason == Swut.EndProtocol);
        UsbipStreamCheck stranger = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Check("a reply to a transfer nobody asked for: END 9", !Phone(stranger, RetSubmit(81, 0, null, 0), new List<byte[]>()));
        UsbipStreamCheck strangeUnlink = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Check("a RET_UNLINK nobody asked for: END 9", !Phone(strangeUnlink, RetUnlink(82, 0), new List<byte[]>()));
        UsbipStreamCheck command = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Check("a command from the phone: END 9", !Phone(command, CmdSubmit(83, true, 1, 8, null, null, 0), new List<byte[]>()));
        UsbipStreamCheck isoCount = Imported(pad, new List<byte[]>(), new List<byte[]>());
        Driver(isoCount, CmdSubmit(84, true, 3, 64, null, null, 2), new List<byte[]>(), new List<byte[]>());
        Check("a reply with another number of isochronous packets: END 9", !Phone(isoCount, RetSubmit(84, 0, null, 3), new List<byte[]>()));
    }

    // ---------------------------------------------------------------- TLS
    static void Tls()
    {
        string container = Container("tls");
        UsbTlsIdentity id = UsbTlsIdentity.Create(container, Seeded(9));
        X509Certificate2 c = id.Certificate;
        Check("the helper's certificate: self-signed CN=Starwire, a P-256 key, ECDSA with SHA-256, no extensions",
            c.Subject == "CN=Starwire" && c.Issuer == "CN=Starwire" && UsbTlsIdentity.IsP256(c)
            && c.SignatureAlgorithm.Value == "1.2.840.10045.4.3.2" && c.Extensions.Count == 0 && c.HasPrivateKey);
        Check("  with a 16-byte random serial", c.GetSerialNumber().Length == 16);
        Check("  its key is in a container of its own while it's in use", CngKey.Exists(container));
        Check("  its fingerprint is SHA-256 of its DER bytes", Eq(id.Fingerprint, Swut.Fingerprint(c.RawData)));
        id.Dispose();
        Check("the container is deleted when the helper's done with it", !CngKey.Exists(container));
        CngKeyCreationParameters p = new CngKeyCreationParameters();
        p.Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
        CngKey.Create(CngAlgorithm.ECDsaP256, container, p).Dispose();
        UsbTlsIdentity again = null;
        bool made = !Throws(delegate { again = UsbTlsIdentity.Create(container, Seeded(10)); });
        Check("a container a crash left behind is deleted at the next start", made && again != null);
        if (again != null) {
            again.Dispose();
        }
        Check("no certificate isn't a P-256 one", !UsbTlsIdentity.IsP256(null));
    }

    // Teardown must never wait on a write to a phone that stopped reading. TryWrite takes the write
    // lock only if it comes free within its time, so the end of a session can't hang behind another
    // thread blocked holding it. Deterministic: a helper thread holds the lock while this runs.
    static void TeardownWrites()
    {
        MemoryStream m = new MemoryStream();
        SwutStream frames = new SwutStream(m, new FakeClock(), () => true);
        Check("TryWrite writes when the lock is free", frames.TryWrite(Swut.Keepalive(), 2000) && m.ToArray().Length == Swut.Keepalive().Length);

        // Another thread holds the write lock and never lets go, as a relay thread blocked writing
        // to a stuck phone would
        ManualResetEvent holding = new ManualResetEvent(false);
        ManualResetEvent release = new ManualResetEvent(false);
        BlockingStream blocker = new BlockingStream(holding, release);
        SwutStream held = new SwutStream(blocker, new FakeClock(), () => true);
        Thread t = new Thread(delegate() {
            held.Write(Swut.Keepalive());   // blocks inside the stream, holding the write lock
        });
        t.IsBackground = true;
        t.Start();
        Check("a thread blocked in a write holds the lock", holding.WaitOne(WaitMs));
        Stopwatch w = Stopwatch.StartNew();
        bool got = held.TryWrite(Swut.End(Swut.EndStopped, 0), 300);
        long took = w.ElapsedMilliseconds;
        Check("TryWrite gives up instead of waiting forever for a held lock", !got && took >= 250 && took < 5000);
        release.Set();
        t.Join(WaitMs);
    }

    // A stream whose first write blocks until released, so a test can hold SwutStream's write lock
    sealed class BlockingStream : Stream
    {
        readonly ManualResetEvent holding, release;
        public BlockingStream(ManualResetEvent holding, ManualResetEvent release) { this.holding = holding; this.release = release; }
        public override void Write(byte[] b, int at, int n) { holding.Set(); release.WaitOne(WaitMs); }
        public override void Flush() { }
        public override bool CanRead { get { return false; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return true; } }
        public override long Length { get { return 0; } }
        public override long Position { get { return 0; } set { } }
        public override int Read(byte[] b, int at, int n) { return 0; }
        public override long Seek(long o, SeekOrigin r) { return 0; }
        public override void SetLength(long v) { }
    }

    // SslStream lets the phone renegotiate TLS 1.2, and hands back another certificate afterwards:
    // SwutStream ends there
    static void Renegotiation()
    {
        MemoryStream m = new MemoryStream(Cat(Swut.Keepalive(), Swut.Keepalive()));
        bool same = true;
        SwutStream frames = new SwutStream(m, new FakeClock(), () => same);
        byte[] first = frames.ReadFrame();
        same = false;
        bool ended = false;
        try {
            frames.ReadFrame();
        }
        catch (SwutRenegotiatedException) {
            ended = true;
        }
        Check("a frame after TLS renegotiated isn't taken", first != null && ended);
        MemoryStream bad = new MemoryStream(new byte[] { 0x0A, 0, 0, 0 });
        Check("a header that doesn't read comes back as null", new SwutStream(bad, new FakeClock(), () => true).ReadFrame() == null);
    }

    static void TcpOwnership()
    {
        TcpListener l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        TcpClient client = new TcpClient();
        client.Connect(IPAddress.Loopback, ((IPEndPoint) l.LocalEndpoint).Port);
        Socket s = l.AcceptSocket();
        int owner = TcpOwners.OwnerOf((IPEndPoint) s.RemoteEndPoint, (IPEndPoint) s.LocalEndPoint);
        Check("Windows' connection table says who owns a connection to the relay's port (this process, " + pid + ")", owner == pid);
        string why;
        Check("the relay only takes the driver's connection from the System process, so not this one",
            !UsbRelay.SystemProcessOnly((IPEndPoint) s.LocalEndPoint, (IPEndPoint) s.RemoteEndPoint, out why) && why.Contains(pid.ToString()));
        s.Close();
        client.Close();
        l.Stop();
    }

    // ---------------------------------------------------------------- a fake driver over real sockets
    // Bytes the fake driver received from the relay, for the tests to take in order
    sealed class Inbox
    {
        readonly List<byte> bytes = new List<byte>();
        bool closed;

        public void Add(byte[] b, int n)
        {
            lock (bytes) {
                for (int i = 0; i < n; i++) {
                    bytes.Add(b[i]);
                }
                Monitor.PulseAll(bytes);
            }
        }

        public void Close()
        {
            lock (bytes) {
                closed = true;
                Monitor.PulseAll(bytes);
            }
        }

        public bool Closed
        {
            get {
                lock (bytes) {
                    return closed;
                }
            }
        }

        public int Count
        {
            get {
                lock (bytes) {
                    return bytes.Count;
                }
            }
        }

        public byte[] Take(int n)
        {
            lock (bytes) {
                Stopwatch w = Stopwatch.StartNew();
                while (bytes.Count < n && !closed && w.ElapsedMilliseconds < WaitMs) {
                    Monitor.Wait(bytes, 50);
                }
                if (bytes.Count < n) {
                    return null;
                }
                byte[] r = bytes.GetRange(0, n).ToArray();
                bytes.RemoveRange(0, n);
                return r;
            }
        }
    }

    // usbip-win2's driver as the helper's driver client sees it, but the attach really connects to
    // the address and port it's given, imports the device there, and keeps the connection, as the
    // driver does. A detach closes that connection.
    sealed class SocketVhci : IVhciDriver
    {
        public readonly DriverIdentity Id = new DriverIdentity();
        readonly object gate = new object();
        readonly Dictionary<int, ImportedDevice> devices = new Dictionary<int, ImportedDevice>();
        readonly Dictionary<int, Socket> sockets = new Dictionary<int, Socket>();
        public readonly List<int> Plugouts = new List<int>();
        public readonly List<string> Attaches = new List<string>();
        public Inbox Received;
        public Socket Connection;
        public int Plugins;
        // When set, an attach holds the device but doesn't answer until it's signalled, like a
        // driver that's slow to finish
        public ManualResetEvent Hold;
        // Reset to make the driver stop reading from the relay, as one stuck mid-transfer would;
        // set again to let it read
        public readonly ManualResetEvent Reading = new ManualResetEvent(true);
        // When > 0, the driver socket's receive buffer is pinned to this, so a paused driver makes
        // the relay's SendToDriver block after a bounded amount (TCP flow control), not after the
        // OS has autotuned a buffer big enough to swallow the whole transfer
        public int ReceiveBufferSize;

        public SocketVhci()
        {
            Id.Version = "12.1.30.403";
            Id.Provider = "USBIP-WIN2";
            Id.Service = "usbip2_ude";
            Id.ImageSha256 = ReleaseSysSha256;
        }

        public DriverIdentity Identity { get { return Id; } }

        public void Dispose()
        {
        }

        public int Attached
        {
            get {
                lock (gate) {
                    return devices.Count;
                }
            }
        }

        public void Send(byte[] m)
        {
            Connection.Send(m);
        }

        public int Control(uint code, byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            if (code == UsbipAbi.GetImportedDevices) {
                lock (gate) {
                    int n = 0;
                    foreach (ImportedDevice d in devices.Values) {
                        UsbipAbi.PutDevice(output, UsbipAbi.ListHeaderSize + n * UsbipAbi.DeviceSize, d);
                        n++;
                    }
                    returned = UsbipAbi.ListHeaderSize + n * UsbipAbi.DeviceSize;
                }
                return 0;
            }
            if (code == UsbipAbi.PluginHardwareOnce) {
                return Plugin(input, output, out returned);
            }
            if (code == UsbipAbi.PlugoutHardware) {
                int port = UsbipAbi.GetInt(input, 4);
                lock (gate) {
                    if (!devices.ContainsKey(port)) {
                        return UsbipAbi.ErrorInvalidParameter;
                    }
                    devices.Remove(port);
                    Plugouts.Add(port);
                    try {
                        sockets[port].Shutdown(SocketShutdown.Both);
                        sockets[port].Close();
                    }
                    catch (Exception) {
                    }
                    sockets.Remove(port);
                }
                return 0;
            }
            if (code == UsbipAbi.StopAttachAttempts) {
                returned = UsbipAbi.StopSize;
                return 0;
            }
            return 1;
        }

        int Plugin(byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            Interlocked.Increment(ref Plugins);
            string host, service, busId;
            UsbipAbi.GetLocation(input, UsbipAbi.PluginLocation, out host, out service, out busId);
            string serial = UsbipAbi.GetString(input, UsbipAbi.PluginSerial, UsbipAbi.SerialSize);
            lock (gate) {
                Attaches.Add(host + "|" + service + "|" + busId);
            }
            Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            s.ReceiveTimeout = WaitMs;
            if (ReceiveBufferSize > 0) {
                s.ReceiveBufferSize = ReceiveBufferSize;
            }
            try {
                s.Connect(IPAddress.Parse(host), int.Parse(service));
                s.Send(ImportRequest(busId));
                byte[] reply = ReceiveExactly(s, UsbipStreamCheck.ImportReplyLength);
                if (reply == null) {
                    s.Close();
                    return 64;   // ERROR_NETNAME_DELETED: the connection closed during the attach
                }
                ImportedDevice d = new ImportedDevice();
                d.Host = host;
                d.Service = service;
                d.BusId = busId;
                d.Serial = serial;
                d.Vendor = UsbipStreamCheck.Be16(reply, 308);
                d.Product = UsbipStreamCheck.Be16(reply, 310);
                d.Speed = UsbipStreamCheck.Be32(reply, 304);
                int port;
                lock (gate) {
                    port = 1;
                    while (devices.ContainsKey(port)) {
                        port++;
                    }
                    d.Port = port;
                    devices[port] = d;
                    sockets[port] = s;
                }
                Inbox inbox = new Inbox();
                Received = inbox;
                Connection = s;
                ManualResetEvent reading = Reading;
                Thread t = new Thread(delegate() {
                    byte[] buffer = new byte[65536];
                    try {
                        int n;
                        s.ReceiveTimeout = 0;
                        while (reading.WaitOne() && (n = s.Receive(buffer)) > 0) {
                            inbox.Add(buffer, n);
                        }
                    }
                    catch (Exception) {
                    }
                    inbox.Close();
                });
                t.IsBackground = true;
                t.Start();
                ManualResetEvent hold = Hold;
                if (hold != null) {
                    hold.WaitOne(WaitMs);
                }
                UsbipAbi.PutInt(output, 0, UsbipAbi.AttachReplySize);
                UsbipAbi.PutInt(output, 4, port);
                returned = UsbipAbi.AttachReplySize;
                return 0;
            }
            catch (Exception) {
                s.Close();
                return 1225;   // ERROR_CONNECTION_REFUSED
            }
        }

        static byte[] ReceiveExactly(Socket s, int n)
        {
            byte[] b = new byte[n];
            int at = 0;
            try {
                while (at < n) {
                    int got = s.Receive(b, at, n - at, SocketFlags.None);
                    if (got <= 0) {
                        return null;
                    }
                    at += got;
                }
            }
            catch (Exception) {
                return null;
            }
            return b;
        }
    }

    // An attacher that reports the device attached without ever connecting to the relay, so a test
    // can check the relay stops accepting once the attach is done (it doesn't rely on the fake
    // driver, which always connects)
    sealed class NoConnectAttacher : IUsbAttacher
    {
        public volatile int RelayPort;
        public volatile bool Attached;
        public bool Ready(out string problem) { problem = null; return true; }
        public bool Attach(int relayPort, string busId, out object handle, out string problem)
        {
            RelayPort = relayPort;
            handle = new object();
            problem = null;
            Attached = true;
            return true;
        }
        public bool Detach(object handle, out string problem) { problem = null; return true; }
    }

    // ---------------------------------------------------------------- a phone, as the tests act it
    sealed class TestPhone : IDisposable
    {
        public TcpClient Tcp;
        public SslStream Ssl;
        public byte[] Hello, PhoneFp, HelperFp, LastBind;
        readonly List<byte> pending = new List<byte>();
        public byte[] Other;

        public static TestPhone Connect(int port, X509Certificate2 cert, IPAddress from)
        {
            TestPhone p = new TestPhone();
            p.Tcp = new TcpClient(AddressFamily.InterNetwork);
            if (from != null) {
                p.Tcp.Client.Bind(new IPEndPoint(from, 0));
            }
            p.Tcp.Connect(IPAddress.Loopback, port);
            p.Tcp.ReceiveTimeout = WaitMs;
            p.Tcp.SendTimeout = WaitMs;
            p.Ssl = new SslStream(p.Tcp.GetStream(), false, delegate { return true; }, delegate { return cert; });
            X509CertificateCollection certs = new X509CertificateCollection();
            if (cert != null) {
                certs.Add(cert);
            }
            p.Ssl.AuthenticateAsClient("Starwire", certs, SslProtocols.Tls12 | UsbTlsIdentity.Tls13, false);
            p.PhoneFp = cert != null ? Swut.Fingerprint(cert.RawData) : null;
            p.HelperFp = Swut.Fingerprint(p.Ssl.RemoteCertificate.GetRawCertData());
            p.Hello = p.Read();
            return p;
        }

        public static TestPhone Connect(int port)
        {
            return Connect(port, phoneCert.Certificate, null);
        }

        // The next whole frame, or null when the connection closed
        public byte[] Read()
        {
            try {
                byte[] h = ReadExactly(4);
                if (h == null) {
                    return null;
                }
                int length = h[2] | h[3] << 8;
                byte[] rest = ReadExactly(length);
                return rest == null ? null : Cat(h, rest);
            }
            catch (IOException) {
                return null;
            }
            catch (ObjectDisposedException) {
                return null;
            }
        }

        byte[] ReadExactly(int n)
        {
            byte[] b = new byte[n];
            int at = 0;
            while (at < n) {
                int got = Ssl.Read(b, at, n - at);
                if (got <= 0) {
                    return null;
                }
                at += got;
            }
            return b;
        }

        // The next frame that isn't a KEEPALIVE
        public byte[] ReadNotKeepalive()
        {
            while (true) {
                byte[] f = Read();
                if (f == null || !Swut.IsKeepalive(f)) {
                    return f;
                }
            }
        }

        public void Send(byte[] f)
        {
            Ssl.Write(f);
            Ssl.Flush();
        }

        public byte[] Bind(byte[] pairKey, byte[] deviceId)
        {
            byte[] usbKey = Swut.UsbKey(pairKey, deviceId);
            LastBind = Swut.Bind(usbKey, Hello, PhoneFp, HelperFp, Swut.ChooseVersion(Hello), deviceId, Range(0x60, 16));
            Send(LastBind);
            return Read();
        }

        public bool AckChecksOut(byte[] ack, byte[] pairKey, byte[] deviceId)
        {
            return Swut.BindAckChecksOut(Swut.UsbKey(pairKey, deviceId), Hello, PhoneFp, HelperFp, LastBind, ack);
        }

        // USB/IP bytes to the helper, in this many DATA frames
        public void SendData(byte[] b, int pieces)
        {
            int at = 0;
            for (int i = 0; i < pieces; i++) {
                int n = i == pieces - 1 ? b.Length - at : b.Length / pieces;
                Send(Swut.Data(b, at, n));
                at += n;
            }
        }

        // The next n bytes of USB/IP from the helper, out of its DATA frames. Null if another frame
        // comes first (it's in Other) or the connection closes.
        public byte[] ReadData(int n)
        {
            while (pending.Count < n) {
                byte[] f = ReadNotKeepalive();
                if (f == null || f[0] != Swut.TData) {
                    Other = f;
                    return null;
                }
                for (int i = 4; i < f.Length; i++) {
                    pending.Add(f[i]);
                }
            }
            byte[] r = pending.GetRange(0, n).ToArray();
            pending.RemoveRange(0, n);
            return r;
        }

        public void Dispose()
        {
            try {
                Ssl.Close();
            }
            catch (Exception) {
            }
            Tcp.Close();
        }
    }

    // ---------------------------------------------------------------- the server, on 127.0.0.1
    // When set, the next Rig uses this clock for the server instead of its FakeClock (then it's
    // cleared). For tests that need a clock whose sleeps they control.
    static IUsbClock NextRigClock;

    sealed class Rig : IDisposable
    {
        public FakeClock Clock = new FakeClock();
        public MicPairingStore Store;
        public string Pairings;
        public UsbTunnelOptions Options = new UsbTunnelOptions();
        public UsbTunnelServer Server;
        public SocketVhci Driver = new SocketVhci();
        public OwnedDevices Owned;
        public UsbAllowList Allow;
        public bool NoDriver;
        public readonly List<string> Log = new List<string>();

        public Rig(string name) : this(name, 0)
        {
        }

        // perAddressEveryMs 0 (the default) turns off the per-address rate limit, so a test can
        // open many connections from 127.0.0.1; the waiting-room tests pass the real 2 s.
        public Rig(string name, long perAddressEveryMs)
        {
            string folder = PairingsFolder(name);
            Pairings = Path.Combine(folder, "mic-pairings.dat");
            Store = new MicPairingStore(Pairings);
            Store.Load(Seeded(11));
            Store.Put(Pairing(DeviceId, PairKey, "Test phone"));
            Store.Put(Pairing(OtherId, OtherKey, "Other phone"));
            Allow = new UsbAllowList(UsbAllowList.NextTo(Pairings));
            AllowPhone(DeviceId, true);
            Owned = new OwnedDevices(Path.Combine(folder, "usb-attached.txt"));
            Options.ListenAddress = IPAddress.Loopback;
            Options.Port = 0;
            Options.PairingsFile = Pairings;
            Options.TlsContainer = Container("helper-" + name);
            Options.Clock = NextRigClock ?? Clock;
            NextRigClock = null;
            Options.Random = Seeded(12);
            Options.Log = delegate(string s) {
                lock (Log) {
                    Log.Add(s);
                }
            };
            Options.AutoTick = false;
            Options.WatchFiles = false;
            Options.PerAddressEveryMs = perAddressEveryMs;
            Options.RelayPeerOk = delegate(IPEndPoint local, IPEndPoint remote, out string why) {
                int owner = TcpOwners.OwnerOf(remote, local);
                why = owner == pid ? null : "owned by " + owner;
                return owner == pid;
            };
            Options.Attacher = new UsbDriverAttacher(delegate(out string problem) {
                if (NoDriver) {
                    problem = "This PC has no USB/IP driver running.";
                    return null;
                }
                problem = null;
                return Driver;
            }, Owned, Seeded(13), lockName, Options.Log);
            Server = new UsbTunnelServer(Options);
            Server.Start();
        }

        public void AllowPhone(byte[] id, bool allow)
        {
            List<UsbPairingLine> lines = UsbPairingReader.Parse(File.ReadAllText(Pairings));
            string problem;
            Allow.Set(UsbPairingReader.Find(lines, id), allow, lines, out problem);
        }

        public int Port { get { return Server.Port; } }

        // A phone that has BINDed and SHAREd this frame; the SHARE_ACK in Ack
        public TestPhone Share(byte[] key, byte[] id, byte[] share, out byte[] ack)
        {
            TestPhone p = TestPhone.Connect(Port);
            p.Bind(key, id);
            p.Send(share);
            ack = p.ReadNotKeepalive();
            return p;
        }

        public bool Idle()
        {
            return WaitUntil(() => Server.SessionCount == 0);
        }

        public void Dispose()
        {
            Server.Dispose();
        }
    }

    static int Refusal(byte[] f)
    {
        return Swut.Code(f, Swut.TRefuse);
    }

    static void ServerBind()
    {
        using (Rig rig = new Rig("bind")) {
            byte[] before = File.ReadAllBytes(rig.Pairings);
            DateTime written = File.GetLastWriteTimeUtc(rig.Pairings);
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("over TLS the helper sends HELLO first, offering version 1", Swut.IsHello(p.Hello) && Swut.ChooseVersion(p.Hello) == 1);
                Check("the helper's certificate in TLS is its own, P-256", UsbTlsIdentity.IsP256(p.Ssl.RemoteCertificate)
                    && Eq(p.HelperFp, rig.Server.Fingerprint));
                Check("TLS 1.3 or TLS 1.2 with AES-GCM", (p.Ssl.SslProtocol == UsbTlsIdentity.Tls13 || p.Ssl.SslProtocol == SslProtocols.Tls12)
                    && p.Ssl.HashStrength == 0);
                byte[] ack = p.Bind(PairKey, DeviceId);
                Check("a paired phone with Allow USB gets a BIND_ACK that checks out", ack != null && ack[0] == Swut.TBindAck
                    && p.AckChecksOut(ack, PairKey, DeviceId));
            }
            rig.Clock.Advance(1000);
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("an unpaired phone: REFUSE 1", Refusal(p.Bind(ThirdKey, Range(0x30, 8))) == Swut.RefuseNotPaired && p.Read() == null);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("a paired device id with the wrong key: REFUSE 1", Refusal(p.Bind(ThirdKey, DeviceId)) == Swut.RefuseNotPaired);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                p.LastBind = Swut.Bind(Swmc.AuthKey(PairKey, DeviceId), p.Hello, p.PhoneFp, p.HelperFp, 1, DeviceId, Range(0x60, 16));
                p.Send(p.LastBind);
                Check("a BIND made with the microphone's K_auth: REFUSE 1", Refusal(p.Read()) == Swut.RefuseNotPaired);
            }
            rig.Clock.Advance(1000);
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                p.LastBind = Swut.Bind(Swut.UsbKey(PairKey, DeviceId), p.Hello, p.HelperFp, p.PhoneFp, 1, DeviceId, Range(0x60, 16));
                p.Send(p.LastBind);
                Check("a BIND with the fingerprints the other way round: REFUSE 1", Refusal(p.Read()) == Swut.RefuseNotPaired);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("a paired phone without Allow USB: REFUSE 4", Refusal(p.Bind(OtherKey, OtherId)) == Swut.RefuseNotAllowed);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                byte[] bind = Swut.Bind(Swut.UsbKey(PairKey, DeviceId), p.Hello, p.PhoneFp, p.HelperFp, 1, DeviceId, Range(0x60, 16));
                bind[4] = 2;
                p.Send(bind);
                Check("a BIND for version 2: REFUSE 2", Refusal(p.Read()) == Swut.RefuseNoVersion);
            }
            rig.Clock.Advance(1000);
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                p.Send(Swut.Keepalive());
                Check("a KEEPALIVE where BIND should be: REFUSE 3", Refusal(p.Read()) == Swut.RefuseMalformed);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                p.Send(new byte[] { 0x0A, 0, 0, 0 });
                Check("a header that doesn't read: closed, with no answer", p.Read() == null);
            }
            int reads = rig.Server.Pairings.Reads;
            Check("the pairings file was read once for each BIND that got as far as its MAC (" + reads + ")", reads == 6);
            bool refused = false;
            try {
                using (TestPhone p = TestPhone.Connect(rig.Port, null, null)) {
                    refused = p.Hello == null;
                }
            }
            catch (Exception) {
                refused = true;
            }
            Check("a phone without a certificate gets no HELLO", refused);
            string rsaContainer = Container("rsa");
            CngKeyCreationParameters kp = new CngKeyCreationParameters();
            kp.Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
            kp.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
            using (CngKey rsaKey = CngKey.Create(CngAlgorithm.Rsa, rsaContainer, kp)) {
                RSACng rsa = new RSACng(rsaKey);
                CertificateRequest request = new CertificateRequest("CN=Starwire", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                X509Certificate2 rsaCert = request.CreateSelfSigned(new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero),
                    new DateTimeOffset(2036, 1, 1, 0, 0, 0, TimeSpan.Zero));
                Check("an RSA certificate isn't a P-256 one", !UsbTlsIdentity.IsP256(rsaCert));
                bool noHello = false;
                try {
                    using (TestPhone p = TestPhone.Connect(rig.Port, rsaCert, null)) {
                        noHello = p.Hello == null;
                    }
                }
                catch (Exception) {
                    noHello = true;
                }
                Check("a phone with an RSA certificate gets no HELLO", noHello);
                rsaKey.Delete();
            }
            Check("the pairings file's bytes are unchanged after all of that", Eq(File.ReadAllBytes(rig.Pairings), before)
                && File.GetLastWriteTimeUtc(rig.Pairings) == written);
        }
    }

    static void ServerWaiting()
    {
        using (Rig rig = new Rig("waiting", UsbWaitingRoom.PerAddressEveryMs)) {
            // A connection that a later one from its address pushes out just as its thread starts.
            // Before the fix, its thread then threw on the closed connection, and an exception that
            // leaves a thread ends the whole helper: anyone on the network could stop it. The
            // replacement now needs the address's 2 s grace to have passed.
            int admitted = 0;
            rig.Options.AfterAdmit = t => {
                if (Interlocked.Increment(ref admitted) == 1) {
                    WaitUntil(() => rig.Server.Room.IsGone(t));
                }
            };
            Socket first = RawConnect(rig.Port, "127.0.0.2");
            WaitUntil(() => Thread.VolatileRead(ref admitted) == 1);
            rig.Clock.Advance(2000);
            Socket replacing = RawConnect(rig.Port, "127.0.0.2");
            Check("a connection pushed out just as its thread starts is closed", Closed(first));
            rig.Options.AfterAdmit = null;
            replacing.Close();
            first.Close();
            WaitUntil(() => rig.Server.Room.Count == 0);
            rig.Clock.Advance(2000);
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("  and the helper carries on: a phone still gets its BIND_ACK", p.AckChecksOut(p.Bind(PairKey, DeviceId), PairKey, DeviceId));
            }
            rig.Clock.Advance(2000);
            List<Socket> open = new List<Socket>();
            for (int i = 2; i <= 5; i++) {
                open.Add(RawConnect(rig.Port, "127.0.0." + i));
            }
            Check("4 connections from 4 addresses wait for their BIND", WaitUntil(() => rig.Server.Room.Count == 4));
            // A second connection from an address that already has one waiting, within its grace,
            // is closed and does not evict that address's own connection or any other's
            Socket sameAddr = RawConnect(rig.Port, "127.0.0.3");
            Check("a second connection from an address within its grace is refused, evicting nobody", Closed(sameAddr)
                && WaitUntil(() => rig.Server.Room.Count == 4));
            Socket fifth = RawConnect(rig.Port, "127.0.0.6");
            Check("a 5th fresh address, with none waiting 2 s yet, is closed at once", Closed(fifth) && rig.Server.Room.Count == 4);
            rig.Clock.Advance(2000);
            Socket sixth = RawConnect(rig.Port, "127.0.0.7");
            Check("once they've waited 2 s, a fresh address pushes out only the oldest", Closed(open[0]) && WaitUntil(() => rig.Server.Room.Count == 4));
            Socket again = RawConnect(rig.Port, "127.0.0.3");
            Check("after its grace, a new connection from an address that has one takes its place", Closed(open[1])
                && WaitUntil(() => rig.Server.Room.Count == 4));
            rig.Clock.Advance(8000);
            rig.Server.Tick();
            Check("10 s after their TCP connections, those still without a BIND are closed", Closed(open[2]) && Closed(open[3])
                && rig.Server.Room.Count == 2);
            rig.Clock.Advance(2000);
            rig.Server.Tick();
            Check("  and so are the newer ones, 10 s after theirs", Closed(sixth) && Closed(again) && rig.Server.Room.Count == 0);
            rig.Options.AddressOk = a => !a.Equals(IPAddress.Parse("127.0.0.9"));
            Socket foreign = RawConnect(rig.Port, "127.0.0.9");
            Check("a connection from an address that isn't allowed is closed before TLS", Closed(foreign) && rig.Server.Room.Count == 0);
            foreach (Socket s in new Socket[] { sameAddr, fifth, sixth, again, foreign }) {
                s.Close();
            }
            foreach (Socket s in open) {
                s.Close();
            }
        }
    }

    static Socket RawConnect(int port, string from)
    {
        Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        s.Bind(new IPEndPoint(IPAddress.Parse(from), 0));
        s.Connect(IPAddress.Loopback, port);
        return s;
    }

    // The other side closed it: a read gives 0 bytes
    static bool Closed(Socket s)
    {
        try {
            s.ReceiveTimeout = WaitMs;
            return s.Receive(new byte[16]) == 0;
        }
        catch (SocketException e) {
            // A reset closes it too; running out of time means it's still open
            return e.SocketErrorCode != SocketError.TimedOut;
        }
    }

    // A BIND whose connection the waiting room closes while it waits for a pairing-file read must
    // stop waiting and read the file 0 more times, so dead connections don't use up the 3 reads a
    // second. Uses a clock whose one sleep the test releases, so it's deterministic.
    static void ServerSweptBind()
    {
        GateClock gate = new GateClock();
        NextRigClock = gate;
        using (Rig rig = new Rig("swept")) {
            long t0 = gate.Now;
            // Take this second's 3 BIND reads, so the next BIND has to wait for a turn
            bool three = rig.Server.Limiter.WaitTurn(t0) && rig.Server.Limiter.WaitTurn(t0) && rig.Server.Limiter.WaitTurn(t0);
            int before = rig.Server.Pairings.Reads;
            TestPhone a = TestPhone.Connect(rig.Port, phoneCert.Certificate, IPAddress.Parse("127.0.0.2"));
            byte[] id = Range(0x30, 8);
            a.LastBind = Swut.Bind(Swut.UsbKey(ThirdKey, id), a.Hello, a.PhoneFp, a.HelperFp, 1, id, Range(0x60, 16));
            a.Send(a.LastBind);
            bool waiting = WaitUntil(() => gate.Sleepers == 1);
            // 10 s pass and the waiting room sweeps the connection while its BIND is still waiting
            gate.Advance(UsbWaitingRoom.BindWithinMs);
            int swept = rig.Server.Room.Sweep();
            bool closed = a.Read() == null;
            gate.Release();
            WaitUntil(() => gate.Sleepers == 0);
            int after = rig.Server.Pairings.Reads;
            Check("a BIND swept from the waiting room stops waiting and reads the pairings file 0 more times",
                three && waiting && swept >= 1 && closed && after == before);
            a.Dispose();
        }
        NextRigClock = null;
    }

    // The phone answers the driver's import through the tunnel, and the driver attaches
    static bool Import(Rig rig, TestPhone p, SwutShare share, int speed)
    {
        byte[] req = p.ReadData(UsbipStreamCheck.ImportRequestLength);
        if (req == null || !Eq(req, ImportRequest(share.BusId))) {
            return false;
        }
        p.SendData(ImportReply(share, speed, -1), 2);
        return WaitUntil(() => rig.Server.Sharing != null && rig.Server.Sharing.Attached);
    }

    static void ShareRoundTrip()
    {
        using (Rig rig = new Rig("share")) {
            byte[] before = File.ReadAllBytes(rig.Pairings);
            SwutShare pad = Pad();
            byte[] ack;
            TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Check("the pad: SHARE_ACK 0, attaching", Swut.Code(ack, Swut.TShareAck) == Swut.ShareAttaching);
            byte[] req = p.ReadData(UsbipStreamCheck.ImportRequestLength);
            Check("the driver's OP_REQ_IMPORT for bus ID 1-1 reaches the phone in DATA", Eq(req, ImportRequest("1-1")));
            p.SendData(ImportReply(pad, 2, -1), 3);
            Check("with the phone's OP_REP_IMPORT the driver attaches it", WaitUntil(() => rig.Server.Sharing != null && rig.Server.Sharing.Attached)
                && rig.Driver.Attached == 1);
            string[] location = rig.Driver.Attaches[0].Split('|');
            Check("it attached through the relay: 127.0.0.1, the relay's port, the bus ID", location[0] == "127.0.0.1"
                && int.Parse(location[1]) > 0 && int.Parse(location[1]) != rig.Port && location[2] == "1-1");
            rig.Owned.Load();
            Check("and it's on the helper's list of what it attached", rig.Owned.Devices.Count == 1 && rig.Owned.Devices[0].Host == "127.0.0.1"
                && rig.Owned.Devices[0].BusId == "1-1" && rig.Owned.Devices[0].Port == 1);
            Check("the menu would show the device and the phone", rig.Server.Sharing.DeviceName == "Test pad" && rig.Server.Sharing.PhoneName == "Test phone");

            byte[] getDevice = CmdSubmit(1, true, 0, 18, Setup(0x80, 6, 0x0100, 0, 18), null, 0);
            rig.Driver.Send(getDevice);
            Check("the driver's GET_DESCRIPTOR goes through the relay to the phone", Eq(p.ReadData(48), getDevice));
            byte[] reply = RetSubmit(1, 0, pad.DeviceDescriptor, 0);
            p.SendData(reply, 1);
            Check("  and the device descriptor comes back to the driver", Eq(rig.Driver.Received.Take(reply.Length), reply));
            rig.Driver.Send(CmdSubmit(2, true, 0, 16, Setup(0xC0, 1, 0, 0, 16), null, 0));
            byte[] stall = rig.Driver.Received.Take(48);
            Check("a vendor request gets the relay's stall, and never reaches the phone", stall != null
                && UsbipStreamCheck.Be32u(stall, 4) == 2 && UsbipStreamCheck.Be32(stall, 20) == -32);
            rig.Driver.Send(CmdSubmit(3, true, 0, 5, Setup(0x80, 6, 0x0F00, 0, 5), null, 0));
            byte[] bos = rig.Driver.Received.Take(48);
            Check("so does the BOS descriptor", bos != null && UsbipStreamCheck.Be32u(bos, 4) == 3 && UsbipStreamCheck.Be32(bos, 20) == -32);
            byte[] interrupt = CmdSubmit(4, true, 1, 64, null, null, 0);
            rig.Driver.Send(interrupt);
            Check("the phone's next message is the driver's 4th, the interrupt transfer", Eq(p.ReadData(48), interrupt));
            byte[] report = RetSubmit(4, 0, Range(0x80, 8), 0);
            p.SendData(report, 1);
            Check("  and its 8 bytes come back", Eq(rig.Driver.Received.Take(report.Length), report));
            rig.Clock.Advance(1000);
            rig.Server.Tick();
            Check("after 1 s with nothing sent, the helper sends a KEEPALIVE", Swut.IsKeepalive(p.Read()));
            p.Send(Swut.End(Swut.EndStopped, 0));
            Check("the phone's END 1 gives the device back: the helper detaches its own port, once", rig.Idle()
                && rig.Driver.Plugouts.Count == 1 && rig.Driver.Plugouts[0] == 1 && rig.Driver.Attached == 0);
            rig.Owned.Load();
            Check("  and takes it off its list", rig.Owned.Devices.Count == 0);
            Check("  and closes the relay", WaitUntil(() => rig.Driver.Received.Closed));
            Check("  and the tunnel", p.Read() == null);
            p.Dispose();
            Check("the pairings file's bytes are unchanged after a session", Eq(File.ReadAllBytes(rig.Pairings), before));

            // A second session on the same server
            TestPhone q = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Check("the pad shares again", Swut.Code(ack, Swut.TShareAck) == Swut.ShareAttaching && Import(rig, q, pad, 2));
            TestPhone other = null;
            rig.AllowPhone(OtherId, true);
            rig.Clock.Advance(1000);
            byte[] busy;
            other = rig.Share(OtherKey, OtherId, V["frame.share"], out busy);
            Check("another phone, while a device is shared: SHARE_ACK 1, busy", Swut.Code(busy, Swut.TShareAck) == Swut.ShareBusy
                && other.Read() == null);
            other.Dispose();
            rig.AllowPhone(OtherId, false);
            q.Dispose();
            Check("a phone that drops the connection gets its device given back", rig.Idle() && rig.Driver.Plugouts.Count == 2
                && rig.Driver.Attached == 0);
        }
    }

    static byte[] PolicyShare(string name, int flags)
    {
        foreach (string line in File.ReadAllLines(Path.Combine(vectorFolder, "swut-v1-policy.txt"))) {
            if (line.StartsWith(name + ".share=", StringComparison.Ordinal)) {
                byte[] f = Swut.Hex(line.Substring(name.Length + 7));
                f[4] = (byte) flags;
                return f;
            }
        }
        return null;
    }

    static void ShareRefusals()
    {
        using (Rig rig = new Rig("refusals")) {
            byte[] ack;
            TestPhone p = rig.Share(PairKey, DeviceId, PolicyShare("hid_boot_keyboard", 0), out ack);
            Check("a keyboard without your yes: SHARE_ACK 2, rule 5, and nothing is attached", Swut.Code(ack, Swut.TShareAck) == Swut.ShareRefused
                && Swut.Detail(ack, Swut.TShareAck) == Swut.RuleUnconfirmedInput && p.Read() == null && rig.Driver.Plugins == 0);
            p.Dispose();
            rig.Clock.Advance(1000);
            p = rig.Share(PairKey, DeviceId, PolicyShare("interface_storage", 0), out ack);
            Check("storage: SHARE_ACK 2, rule 2", Swut.Code(ack, Swut.TShareAck) == Swut.ShareRefused && Swut.Detail(ack, Swut.TShareAck) == Swut.RuleStorage);
            p.Dispose();
            rig.Idle();
            byte[] broken = (byte[]) V["frame.share"].Clone();
            broken[5] = 0;   // a bus ID 0 bytes long
            p = TestPhone.Connect(rig.Port);
            p.Bind(PairKey, DeviceId);
            p.Send(broken);
            byte[] end = p.ReadNotKeepalive();
            Check("a SHARE that doesn't read: END 9", Swut.Code(end, Swut.TEnd) == Swut.EndProtocol);
            p.Dispose();
            rig.Clock.Advance(1000);
            rig.NoDriver = true;
            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Check("no USB/IP driver on the PC: SHARE_ACK 3", Swut.Code(ack, Swut.TShareAck) == Swut.ShareNoDriver);
            p.Dispose();
            rig.NoDriver = false;
            rig.Idle();
            p = rig.Share(PairKey, DeviceId, PolicyShare("hid_boot_keyboard", Swut.FlagConfirmedInput), out ack);
            Check("the keyboard with your yes: SHARE_ACK 0", Swut.Code(ack, Swut.TShareAck) == Swut.ShareAttaching);
            Check("  the driver asks for it", p.ReadData(UsbipStreamCheck.ImportRequestLength) != null);
            p.Send(Swut.End(Swut.EndStopped, 0));
            Check("  and an END before the import finishes leaves nothing attached", rig.Idle() && rig.Driver.Attached == 0
                && rig.Driver.Plugouts.Count == 0);
            p.Dispose();
            rig.Owned.Load();
            Check("  nor on the helper's list", rig.Owned.Devices.Count == 0);
            rig.Clock.Advance(1000);
            SwutShare pad = Pad();
            foreach (int speed in new int[] { 5, 3 }) {
                p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
                p.ReadData(UsbipStreamCheck.ImportRequestLength);
                byte[] rep = ImportReply(pad, speed, speed == 5 ? -1 : 0x054c);
                p.SendData(rep, 1);
                end = p.ReadNotKeepalive();
                int rule = speed == 5 ? Swut.RuleTooFast : Swut.RuleMismatch;
                Check((speed == 5 ? "a USB 3 device" : "an import reply with another vendor") + ": END 6, rule " + rule + ", and nothing stays attached",
                    Swut.Code(end, Swut.TEnd) == Swut.EndRules && Swut.Detail(end, Swut.TEnd) == rule && rig.Idle() && rig.Driver.Attached == 0);
                p.Dispose();
                rig.Clock.Advance(1000);
            }
            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            byte[] get = CmdSubmit(1, true, 0, 18, Setup(0x80, 6, 0x0100, 0, 18), null, 0);
            rig.Driver.Send(get);
            p.ReadData(48);
            byte[] lie = (byte[]) pad.DeviceDescriptor.Clone();
            lie[4] = 0x08;   // now it says it's storage
            p.SendData(RetSubmit(1, 0, lie, 0), 1);
            end = p.ReadNotKeepalive();
            Check("a device descriptor SHARE didn't describe: END 6, rule 9", Swut.Code(end, Swut.TEnd) == Swut.EndRules
                && Swut.Detail(end, Swut.TEnd) == Swut.RuleMismatch);
            Check("  the driver never got it, and the device was detached", rig.Idle() && rig.Driver.Received.Take(1) == null
                && rig.Driver.Plugouts.Count == 1);
            p.Dispose();
            rig.Clock.Advance(1000);
            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            p.Send(V["frame.hello"]);
            end = p.ReadNotKeepalive();
            Check("a frame out of order after SHARE_ACK: END 9, and the device given back", Swut.Code(end, Swut.TEnd) == Swut.EndProtocol
                && rig.Idle() && rig.Driver.Plugouts.Count == 2);
            p.Dispose();
        }
    }

    // How a session ends besides the phone's END: unpaired, unticked, silent, no SHARE, Stop
    static void SessionEnds()
    {
        using (Rig rig = new Rig("ends")) {
            SwutShare pad = Pad();
            byte[] ack;
            TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            rig.Store.Remove(DeviceId);
            rig.Clock.Advance(1000);
            rig.Server.Recheck();
            rig.Server.Tick();
            byte[] end = p.ReadNotKeepalive();
            Check("unpaired in the receiver's menu while sharing: END 5, and the device given back", Swut.Code(end, Swut.TEnd) == Swut.EndUnpaired
                && rig.Idle() && rig.Driver.Plugouts.Count == 1);
            p.Dispose();
            rig.Store.Put(Pairing(DeviceId, PairKey, "Test phone"));
            rig.AllowPhone(DeviceId, true);
            rig.Clock.Advance(1000);

            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            rig.Store.Put(Pairing(DeviceId, PairKey, "Test phone"));
            // A word from the phone half way, so the 8 s of silence don't come first
            rig.Clock.Advance(5000);
            p.Send(Swut.Keepalive());
            UsbTunnelSession shared = rig.Server.Sharing;
            WaitUntil(() => shared.LastHeard == rig.Clock.Now);
            rig.Clock.Advance(4999);
            rig.Server.Tick();
            Check("paired again: nothing happens before the 10 s check", rig.Server.Sharing == shared && Swut.IsKeepalive(p.Read()));
            rig.Clock.Advance(1);
            rig.Server.Tick();
            end = p.ReadNotKeepalive();
            Check("paired again (a new key field), found at the 10 s check: END 5", Swut.Code(end, Swut.TEnd) == Swut.EndUnpaired && rig.Idle());
            p.Dispose();
            rig.AllowPhone(DeviceId, true);
            rig.Clock.Advance(1000);

            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            rig.AllowPhone(DeviceId, false);
            rig.Clock.Advance(1000);
            rig.Server.Recheck();
            rig.Server.Tick();
            end = p.ReadNotKeepalive();
            Check("Allow USB unticked while sharing: END 5", Swut.Code(end, Swut.TEnd) == Swut.EndUnpaired && rig.Idle());
            p.Dispose();
            rig.AllowPhone(DeviceId, true);
            rig.Clock.Advance(1000);

            // Other hosts send BINDs while the phone is unpaired. Before the fix their reads could
            // use up all 4 of the second, and the pairing check found none free: the session went on.
            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            int plugouts = rig.Driver.Plugouts.Count;
            rig.Clock.Advance(1000);
            rig.Store.Remove(DeviceId);
            int refused = 0;
            for (int i = 2; i <= 4; i++) {
                using (TestPhone a = TestPhone.Connect(rig.Port, phoneCert.Certificate, IPAddress.Parse("127.0.0." + i))) {
                    if (Refusal(a.Bind(ThirdKey, Range(0x30, 8))) == Swut.RefuseNotPaired) {
                        refused++;
                    }
                }
            }
            bool fourthBind = rig.Server.Limiter.WaitTurn(rig.Clock.Now);
            rig.Server.Recheck();
            rig.Server.Tick();
            end = p.ReadNotKeepalive();
            Check("unpaired while other hosts' BINDs take this second's reads: they get 3, and the pairing check still ends the session (END 5)",
                refused == 3 && !fourthBind && Swut.Code(end, Swut.TEnd) == Swut.EndUnpaired && rig.Idle()
                && rig.Driver.Plugouts.Count == plugouts + 1);
            p.Dispose();
            rig.Store.Put(Pairing(DeviceId, PairKey, "Test phone"));
            rig.AllowPhone(DeviceId, true);
            rig.Clock.Advance(1000);

            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            plugouts = rig.Driver.Plugouts.Count;
            rig.Clock.Advance(7999);
            rig.Server.Tick();
            Check("7.999 s without a word from the phone: still shared", rig.Server.Sharing != null && Swut.IsKeepalive(p.Read()));
            rig.Clock.Advance(1);
            rig.Server.Tick();
            Check("8 s: closed without an END, and the device given back", rig.Idle() && p.Read() == null && rig.Driver.Plugouts.Count == plugouts + 1);
            p.Dispose();
            rig.Clock.Advance(1000);

            p = TestPhone.Connect(rig.Port);
            p.Bind(PairKey, DeviceId);
            rig.Clock.Advance(9999);
            rig.Server.Tick();
            Check("no SHARE within 9.999 s: still open", rig.Server.SessionCount == 1);
            rig.Clock.Advance(1);
            rig.Server.Tick();
            Check("no SHARE within 10 s: closed", p.Read() == null && rig.Idle());
            p.Dispose();
            rig.Clock.Advance(1000);

            // The driver still hasn't answered the attach when the session ends, and the wait for it
            // runs out: the attach detaches its device itself once the driver answers
            rig.Options.AttachWaitMs = 50;
            rig.Driver.Hold = new ManualResetEvent(false);
            plugouts = rig.Driver.Plugouts.Count;
            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            p.ReadData(UsbipStreamCheck.ImportRequestLength);
            p.SendData(ImportReply(pad, 2, -1), 1);
            WaitUntil(() => rig.Driver.Attached == 1);
            p.Send(Swut.End(Swut.EndStopped, 0));
            Check("a session that ends while the driver is still attaching finishes without waiting for it", rig.Idle()
                && rig.Driver.Plugouts.Count == plugouts && rig.Driver.Attached == 1);
            rig.Driver.Hold.Set();
            Check("  and once the driver answers, that device is detached at once", WaitUntil(() => rig.Driver.Plugouts.Count == plugouts + 1
                && rig.Driver.Attached == 0));
            p.Dispose();
            rig.Driver.Hold = null;
            rig.Options.AttachWaitMs = 30000;
            rig.Clock.Advance(1000);

            p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            plugouts = rig.Driver.Plugouts.Count;
            Check("Stop in the menu", rig.Server.StopSharing());
            end = p.ReadNotKeepalive();
            Check("  sends END 1 and gives the device back", Swut.Code(end, Swut.TEnd) == Swut.EndStopped && rig.Idle()
                && rig.Driver.Plugouts.Count == plugouts + 1);
            p.Dispose();
        }
    }

    // The running pairing check fails closed: one unreadable read of the pairings file keeps the
    // session (a transient lock), but two in a row end it with END 5, as an unreadable Allow USB
    // list already does. Deterministic: the test holds the file so the read can't succeed.
    static void FailClosedCheck()
    {
        using (Rig rig = new Rig("failclosed")) {
            SwutShare pad = Pad();
            byte[] ack;
            TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            Import(rig, p, pad, 2);
            using (FileStream hold = new FileStream(rig.Pairings, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) {
                rig.Server.Recheck();
                rig.Server.Tick();
                Check("one unreadable pairing check keeps the session", rig.Server.SessionCount == 1);
                rig.Server.Recheck();
                rig.Server.Tick();
                byte[] end = p.ReadNotKeepalive();
                Check("a second unreadable pairing check in a row ends the session: END 5",
                    end != null && Swut.Code(end, Swut.TEnd) == Swut.EndUnpaired && rig.Idle());
            }
            p.Dispose();
        }
    }

    static void UsbOff()
    {
        Rig rig = new Rig("off");
        SwutShare pad = Pad();
        byte[] ack;
        TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
        Import(rig, p, pad, 2);
        Socket waiting = RawConnect(rig.Port, "127.0.0.2");
        WaitUntil(() => rig.Server.Room.Count == 1);
        rig.Server.Stop(Swut.EndUsbOff);
        byte[] end = p.ReadNotKeepalive();
        Check("USB turned off while sharing: END 4, and the device given back", Swut.Code(end, Swut.TEnd) == Swut.EndUsbOff
            && rig.Driver.Plugouts.Count == 1 && rig.Driver.Attached == 0);
        Check("  connections waiting for BIND are closed", Closed(waiting));
        bool refused = false;
        try {
            TcpClient c = new TcpClient();
            c.Connect(IPAddress.Loopback, rig.Port);
            c.Close();
        }
        catch (SocketException) {
            refused = true;
        }
        Check("  and nothing listens any more", refused);
        p.Dispose();
        waiting.Close();
        rig.Dispose();
    }

    // A phone that shares a device, imports it, then stops reading while the driver keeps sending.
    // Returns the phone and a way to stop the flood once the pipeline to the phone is full, so a
    // teardown has to unblock a relay thread stuck writing to the phone to finish.
    sealed class Stuck : IDisposable
    {
        public TestPhone Phone;
        volatile bool stop;
        int attempted, done;
        Thread flood;

        public static Stuck Make(Rig rig, SwutShare pad)
        {
            byte[] ack;
            Stuck s = new Stuck();
            s.Phone = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            if (!Import(rig, s.Phone, pad, 2)) {
                throw new Exception("the stuck setup couldn't import the device");
            }
            s.flood = new Thread(delegate() {
                byte[] data = new byte[1024 * 1024];
                for (uint seq = 100; seq < 200 && !s.stop; seq++) {
                    try {
                        Interlocked.Increment(ref s.attempted);
                        rig.Driver.Send(CmdSubmit(seq, false, 2, data.Length, null, data, 0));
                        Interlocked.Increment(ref s.done);
                    }
                    catch (Exception) {
                        return;
                    }
                }
            });
            s.flood.IsBackground = true;
            s.flood.Start();
            // The pipeline is full and the relay's thread is blocked writing to the phone once one
            // send has gone and another is stuck in flight. The phone never reads, so this stays
            // true; it's a real condition, not a timed guess.
            WaitUntil(() => Thread.VolatileRead(ref s.attempted) >= 2 && Thread.VolatileRead(ref s.attempted) - Thread.VolatileRead(ref s.done) >= 1);
            return s;
        }

        public void Dispose()
        {
            stop = true;
            if (Phone != null) {
                Phone.Dispose();
            }
            if (flood != null) {
                flood.Join(WaitMs);
            }
        }
    }

    // A session where the DRIVER side stops reading the relay: the phone answers large IN transfers
    // the driver asked for, but the driver doesn't drain them, so a relay thread is stuck in
    // SendToDriver holding the relay's write lock. Returns once that thread is blocked.
    sealed class DriverStuck : IDisposable
    {
        public TestPhone Phone;
        SocketVhci driver;
        volatile bool stop;
        int attempted, done;
        Thread flood;

        public static DriverStuck Make(Rig rig, SwutShare pad)
        {
            byte[] ack;
            DriverStuck s = new DriverStuck();
            s.driver = rig.Driver;
            // Pin a small receive buffer so the paused driver makes SendToDriver block after a
            // bounded amount, not after the OS autotunes a buffer big enough for the whole transfer
            rig.Driver.ReceiveBufferSize = 4096;
            s.Phone = rig.Share(PairKey, DeviceId, V["frame.share"], out ack);
            if (!Import(rig, s.Phone, pad, 2)) {
                throw new Exception("the driver-stuck setup couldn't import the device");
            }
            TestPhone p = s.Phone;
            const int n = 8;
            const int len = 1024 * 1024;
            // The driver asks for n large IN transfers; the phone reads each request
            for (uint seq = 10; seq < 10 + n; seq++) {
                rig.Driver.Send(CmdSubmit(seq, true, 1, len, null, null, 0));
            }
            for (int i = 0; i < n; i++) {
                if (p.ReadData(48) == null) {
                    throw new Exception("the phone didn't get the driver's request");
                }
            }
            // The driver stops draining the relay, then the phone answers; the relay's SendToDriver
            // fills the driver socket and blocks, holding the relay's write lock
            rig.Driver.Reading.Reset();
            s.flood = new Thread(delegate() {
                byte[] data = new byte[len];
                // 128 pieces so each DATA frame is under the 16 KiB payload limit
                for (uint seq = 10; seq < 10 + n && !s.stop; seq++) {
                    try {
                        Interlocked.Increment(ref s.attempted);
                        p.SendData(RetSubmit(seq, 0, data, 0), 128);
                        Interlocked.Increment(ref s.done);
                    }
                    catch (Exception) {
                        return;
                    }
                }
            });
            s.flood.IsBackground = true;
            s.flood.Start();
            // Wait until the relay is actually blocked in SendToDriver, holding its write lock (the
            // driver stopped reading, so Send can't return). A real condition the relay reports,
            // not a timed guess.
            if (!WaitUntil(() => { UsbTunnelSession sh = rig.Server.Sharing; return sh != null && sh.RelaySending; })) {
                throw new Exception("the driver-stuck setup didn't block the relay");
            }
            return s;
        }

        public void Dispose()
        {
            stop = true;
            if (driver != null) {
                driver.Reading.Set();   // let the fake driver's reader thread exit
            }
            if (Phone != null) {
                Phone.Dispose();
            }
            if (flood != null) {
                flood.Join(WaitMs);
            }
        }
    }

    // Teardown must always finish: with the relay's thread stuck writing to a phone that stopped
    // reading, unpairing, Stop sharing and USB off must still detach the device and end the session
    // within a few seconds. Before the fix the end of a session waited behind that stuck write (it
    // held the write lock), so the device stayed attached and Server.Stop took 40 s.
    //
    // Send timeouts are off (WriteTimeoutMs = 0), so a socket timeout can never be what finishes
    // teardown: the stuck write would block forever, and only closing the socket unblocks it. The
    // wall-clock limit is a failure bound, not a timing assertion; the checks are on the outcome
    // (the device detached, the session gone), not on how long it took.
    static void StuckTeardown()
    {
        SwutShare pad = Pad();

        using (Rig rig = new Rig("stuck-unpair")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            using (Stuck s = Stuck.Make(rig, pad)) {
                int plugouts = rig.Driver.Plugouts.Count;
                rig.Store.Remove(DeviceId);
                rig.Server.Recheck();
                // Move the clock past KEEPALIVE's time, so a Tick now would send one, and run the
                // Tick off this thread. The pairing check must come first and end the session; if
                // the KEEPALIVE were sent first it would block on the lock the stuck relay holds
                // (send timeouts are off), so this Tick would never reach the pairing check.
                rig.Clock.Advance(rig.Options.KeepaliveEveryMs + 1);
                // On a worker thread (as the real timer runs it), wrapped as the helper wraps timer
                // ticks, so a Tick that blocks can't hang this test thread or crash the suite
                Thread t = new Thread(delegate() { try { rig.Server.Tick(); } catch (Exception) { } });
                t.IsBackground = true;
                t.Start();
                bool done = WaitUntil(() => rig.Server.SessionCount == 0 && rig.Driver.Plugouts.Count == plugouts + 1);
                Check("unpaired while the relay is stuck writing to the phone, KEEPALIVE due: pairing check ran first, detached and ended",
                    done && rig.Driver.Attached == 0);
            }
        }

        using (Rig rig = new Rig("stuck-stop")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            using (Stuck s = Stuck.Make(rig, pad)) {
                int plugouts = rig.Driver.Plugouts.Count;
                Check("Stop sharing returns without waiting on the stuck write", rig.Server.StopSharing());
                bool done = WaitUntil(() => rig.Server.SessionCount == 0 && rig.Driver.Plugouts.Count == plugouts + 1);
                Check("Stop sharing while the relay is stuck: detached and the session ended", done && rig.Driver.Attached == 0);
            }
        }

        using (Rig rig = new Rig("stuck-usboff")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            Stuck s = Stuck.Make(rig, pad);
            int plugouts = rig.Driver.Plugouts.Count;
            rig.Server.Stop(Swut.EndUsbOff);
            Check("USB off (Server.Stop) while the relay is stuck: it returned, the device is detached and the session gone",
                rig.Driver.Plugouts.Count == plugouts + 1 && rig.Driver.Attached == 0 && rig.Server.SessionCount == 0);
            s.Dispose();
        }
    }

    // The matching case the review found broke teardown: the DRIVER side stops reading the relay
    // (a large IN transfer it doesn't drain), so a relay thread is stuck in SendToDriver holding
    // the relay's write lock. Unpair, Stop sharing and USB off must still detach within the failure
    // bound. Before the fix CloseDriver took that same lock, so teardown deadlocked and Server.Stop
    // took 40 s. Send timeouts are off, so only closing the driver socket can unblock it.
    static void DriverStuckTeardown()
    {
        SwutShare pad = Pad();

        using (Rig rig = new Rig("drv-unpair")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            using (DriverStuck s = DriverStuck.Make(rig, pad)) {
                int plugouts = rig.Driver.Plugouts.Count;
                rig.Store.Remove(DeviceId);
                rig.Server.Recheck();
                rig.Server.Tick();
                bool done = WaitUntil(() => rig.Server.SessionCount == 0 && rig.Driver.Plugouts.Count == plugouts + 1);
                Check("unpaired while the relay is stuck in SendToDriver (driver not reading): detached and ended",
                    done && rig.Driver.Attached == 0);
            }
        }

        using (Rig rig = new Rig("drv-stop")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            using (DriverStuck s = DriverStuck.Make(rig, pad)) {
                int plugouts = rig.Driver.Plugouts.Count;
                Check("Stop sharing returns while the driver side is stuck", rig.Server.StopSharing());
                bool done = WaitUntil(() => rig.Server.SessionCount == 0 && rig.Driver.Plugouts.Count == plugouts + 1);
                Check("Stop sharing while the driver side is stuck: detached and ended", done && rig.Driver.Attached == 0);
            }
        }

        using (Rig rig = new Rig("drv-usboff")) {
            rig.Options.WriteTimeoutMs = 0;
            rig.Options.TeardownWriteMs = 1000;
            DriverStuck s = DriverStuck.Make(rig, pad);
            int plugouts = rig.Driver.Plugouts.Count;
            rig.Server.Stop(Swut.EndUsbOff);
            Check("USB off while the driver side is stuck: returned, detached, session gone",
                rig.Driver.Plugouts.Count == plugouts + 1 && rig.Driver.Attached == 0 && rig.Server.SessionCount == 0);
            s.Dispose();
        }
    }

    // Checks that had no test failing without them: a wrong-MAC BIND for a paired, not-allowed
    // phone must get REFUSE 1 (not REFUSE 4, which would leak Allow USB to a host without the key);
    // a valid BIND whose connection timed out gets no BIND_ACK; the relay takes only the driver's
    // peer; K_usb is wiped before the session runs; the renegotiation guard compares the
    // certificate; and the cipher-suite rule rejects CBC and TLS 1.2 without ECDHE.
    static void SecurityChecks()
    {
        // The cipher-suite rule on negotiated values (m7 CBC, m8 ECDHE)
        string why;
        Check("TLS 1.3 with AES-GCM is taken",
            UsbTlsIdentity.CipherSuiteOk(UsbTlsIdentity.Tls13, CipherAlgorithmType.Aes128, 0, 0, out why));
        Check("TLS 1.2 with ECDHE and AES-GCM is taken",
            UsbTlsIdentity.CipherSuiteOk(SslProtocols.Tls12, CipherAlgorithmType.Aes256, 0, UsbTlsIdentity.EcdheExchangeValue, out why));
        Check("a CBC suite (hash strength not 0) is refused",
            !UsbTlsIdentity.CipherSuiteOk(UsbTlsIdentity.Tls13, CipherAlgorithmType.Aes128, 160, 0, out why));
        Check("TLS 1.2 without ECDHE is refused",
            !UsbTlsIdentity.CipherSuiteOk(SslProtocols.Tls12, CipherAlgorithmType.Aes128, 0, 0, out why));
        Check("TLS 1.1 is refused", !UsbTlsIdentity.CipherSuiteOk(SslProtocols.Tls11, CipherAlgorithmType.Aes128, 0, UsbTlsIdentity.EcdheExchangeValue, out why));
        Check("a non-AES cipher is refused", !UsbTlsIdentity.CipherSuiteOk(UsbTlsIdentity.Tls13, CipherAlgorithmType.Rc4, 0, 0, out why));

        using (Rig rig = new Rig("sec")) {
            // m6: a paired phone without Allow USB that sends the wrong key gets REFUSE 1, before
            // any Allow USB check, so a host without the key can't tell Allow USB's state
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("a wrong-MAC BIND for a paired, not-allowed phone: REFUSE 1, not REFUSE 4",
                    Refusal(p.Bind(ThirdKey, OtherId)) == Swut.RefuseNotPaired);
            }
            rig.Clock.Advance(1000);
            // m14: K_usb is wiped before the session runs, not kept for its whole life
            byte[] seen = null;
            rig.Options.AfterBindAck = k => seen = (byte[]) k.Clone();
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                p.Bind(PairKey, DeviceId);
                Check("K_usb is wiped right after BIND_ACK", seen != null && Eq(seen, new byte[32]));
            }
            rig.Options.AfterBindAck = null;
            rig.Clock.Advance(1000);
            // m5: a valid BIND whose connection left the waiting room before BIND_ACK gets no ACK
            rig.Options.BeforeLeave = t => t.Gone = true;
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                byte[] ack = p.Bind(PairKey, DeviceId);
                Check("a valid BIND whose connection timed out gets no BIND_ACK", ack == null && rig.Server.SessionCount == 0);
            }
            rig.Options.BeforeLeave = null;
            rig.Clock.Advance(1000);
            // m3: the renegotiation guard holds while the certificate is unchanged, and fails for
            // another certificate object (a renegotiation hands back a different one)
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                X509Certificate peer = p.Ssl.RemoteCertificate;
                Check("the renegotiation guard is true for the same certificate",
                    UsbTunnelServer.SameCertificate(p.Ssl, peer)());
                Check("  and false for another certificate object",
                    !UsbTunnelServer.SameCertificate(p.Ssl, phoneCert.Certificate)());
            }
        }

        // m9: the relay takes only the driver's peer. With the peer check refusing every
        // connection, the driver's connection to the relay is dropped, the import never reaches the
        // phone, and nothing attaches; the session ends on its own.
        using (Rig rig = new Rig("sec-relay")) {
            rig.Options.RelayPeerOk = delegate(IPEndPoint local, IPEndPoint remote, out string w) { w = "not the driver"; return false; };
            int plugouts = rig.Driver.Plugouts.Count;
            byte[] ack;
            using (TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack)) {
                Check("with the relay's peer check refusing, SHARE still gets SHARE_ACK 0 but nothing attaches and the session ends",
                    Swut.Code(ack, Swut.TShareAck) == Swut.ShareAttaching
                    && WaitUntil(() => rig.Server.SessionCount == 0) && rig.Driver.Attached == 0 && rig.Driver.Plugouts.Count == plugouts);
            }
        }

        // m10: once the attach is done the relay stops accepting. An attacher that attaches without
        // ever connecting to the relay leaves the relay still listening unless AttachNow stops it;
        // a connection to the relay port must then be refused.
        using (Rig rig = new Rig("sec-relaystop")) {
            NoConnectAttacher att = new NoConnectAttacher();
            rig.Options.Attacher = att;
            byte[] ack;
            using (TestPhone p = rig.Share(PairKey, DeviceId, V["frame.share"], out ack)) {
                bool attaching = Swut.Code(ack, Swut.TShareAck) == Swut.ShareAttaching;
                // The session's Attached flips only after AttachNow has called StopAccepting, so
                // waiting on it avoids racing the stop
                bool attached = WaitUntil(() => { UsbTunnelSession sh = rig.Server.Sharing; return sh != null && sh.Attached && att.RelayPort != 0; });
                bool refused = false;
                try {
                    using (Socket probe = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp)) {
                        probe.Connect(IPAddress.Loopback, att.RelayPort);
                    }
                }
                catch (SocketException) {
                    refused = true;
                }
                Check("once the attach is done the relay stops accepting (a connection to its port is refused)",
                    attaching && attached && refused);
            }
        }
    }

    // Worker threads and per-connection state must not grow without limit: under churn the waiting
    // room never holds more than its cap and its per-address record stays bounded, and repeated
    // stuck sessions are each reclaimed so nothing piles up.
    static void BoundedWorkers()
    {
        using (Rig rig = new Rig("bounded-churn", UsbWaitingRoom.PerAddressEveryMs)) {
            int over = 0, maxRoom = 0;
            // 240 connections from 60 addresses, back to back
            List<Socket> sockets = new List<Socket>();
            for (int i = 0; i < 240; i++) {
                Socket sock = null;
                try {
                    sock = RawConnect(rig.Port, "127.0." + (i % 60) + ".1");
                    sockets.Add(sock);
                }
                catch (Exception) {
                }
                int n = rig.Server.Room.Count;
                if (n > maxRoom) {
                    maxRoom = n;
                }
                if (n > UsbWaitingRoom.MaxWaiting) {
                    over++;
                }
                if (i % 40 == 0) {
                    rig.Clock.Advance(250);
                }
            }
            // The waiting-room cap also bounds per-connection worker threads: only a connection
            // that gets a place gets a thread of its own (AcceptLoop), so at most MaxWaiting run
            Check("under a flood of connections the waiting room never holds more than its cap (so at most "
                + UsbWaitingRoom.MaxWaiting + " connection threads)", over == 0 && maxRoom <= UsbWaitingRoom.MaxWaiting);
            // The per-address record is pruned past the grace window, so it stays bounded however
            // many addresses have connected. After the grace passes and one more connects, only the
            // recent entries remain, far below the 60 distinct addresses seen.
            rig.Clock.Advance(UsbWaitingRoom.PerAddressEveryMs + 1);
            try { RawConnect(rig.Port, "127.0.200.1").Close(); } catch (Exception) { }
            Check("the per-address record is pruned, not left to grow with the addresses seen",
                WaitUntil(() => rig.Server.Room.Tracked <= 4));
            // Closing every socket lets each admitted connection's handshake fail and leave the
            // room, so it drains without depending on the sweep's timing
            foreach (Socket sock in sockets) {
                try { sock.Close(); } catch (Exception) { }
            }
            Check("once the connections close, the waiting room drains", WaitUntil(() => rig.Server.Room.Count == 0));
        }

        // Three stuck sessions in a row are each reclaimed: the session list returns to empty and
        // the server keeps working, so sessions (and their threads) don't accumulate
        SwutShare pad = Pad();
        using (Rig rig = new Rig("bounded-stuck")) {
            rig.Options.TeardownWriteMs = 500;
            for (int i = 0; i < 3; i++) {
                using (Stuck s = Stuck.Make(rig, pad)) {
                    rig.Server.StopSharing();
                    Check("stuck session " + (i + 1) + " is reclaimed", WaitUntil(() => rig.Server.SessionCount == 0));
                }
                rig.Clock.Advance(1000);
            }
            using (TestPhone p = TestPhone.Connect(rig.Port)) {
                Check("the server still takes a BIND after repeated stuck sessions", p.AckChecksOut(p.Bind(PairKey, DeviceId), PairKey, DeviceId));
            }
        }
    }

    // The menu's side: off until you turn it on, and the line it shows
    static void Service()
    {
        string folder = PairingsFolder("service");
        string pairings = Path.Combine(folder, "mic-pairings.dat");
        MicPairingStore store = new MicPairingStore(pairings);
        store.Load(Seeded(14));
        store.Put(Pairing(DeviceId, PairKey, "Test phone"));
        UsbTunnelOptions o = new UsbTunnelOptions();
        o.ListenAddress = IPAddress.Loopback;
        o.Port = 0;
        o.PairingsFile = pairings;
        o.TlsContainer = Container("service");
        FakeClock clock = new FakeClock();
        o.Clock = clock;
        o.Random = Seeded(15);
        o.AutoTick = false;
        o.WatchFiles = false;
        o.Attacher = new UsbDriverAttacher(delegate(out string problem) { problem = "none"; return null; }, new OwnedDevices(Path.Combine(folder, "usb-attached.txt")),
            Seeded(16), lockName, delegate { });
        UsbTunnelService service = new UsbTunnelService(o);
        service.Apply();
        Check("USB sharing is off until you turn it on: nothing listens", !service.On && service.Server == null
            && service.Status() == "USB sharing is off.");
        string why;
        List<UsbPhone> phones = service.Phones(out why);
        Check("the menu lists the paired phone, without Allow USB", phones != null && phones.Count == 1 && phones[0].Line.Name == "Test phone"
            && !phones[0].Allowed);
        // Before the fix BINDs could take all 4 reads of a second, and the menu's read waited
        clock.Advance(1000);
        long at = clock.Now;
        int binds = 0;
        for (int i = 0; i < 4; i++) {
            if (o.Limiter.WaitTurn(clock.Now)) {
                binds++;
            }
        }
        Check("the menu's read of the paired phones doesn't wait behind BINDs: they get 3 reads a second, it gets the 4th at once",
            binds == 3 && service.Phones(out why) != null && clock.Now == at);
        clock.Advance(1000);
        Check("ticking Allow USB for it", service.SetAllowed(phones[0].Line, true, out why) && service.Phones(out why)[0].Allowed);
        service.SetOn(true);
        Check("turned on, it listens and says so", service.On && service.Server != null && service.Status().StartsWith("USB sharing is on", StringComparison.Ordinal));
        using (TestPhone p = TestPhone.Connect(service.Server.Port)) {
            Check("  and a phone with Allow USB gets BIND_ACK", p.AckChecksOut(p.Bind(PairKey, DeviceId), PairKey, DeviceId));
        }
        UsbTunnelService restarted = new UsbTunnelService(o);
        Check("the setting stays on for the next start", restarted.On);
        service.SetOn(false);
        Check("turned off, it stops listening", !service.On && service.Server == null && !restarted.On);
        Check("its TLS key is gone once it's off", !CngKey.Exists(o.TlsContainer));
        service.Dispose();
    }
}
