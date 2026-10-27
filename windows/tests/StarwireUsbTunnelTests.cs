// Tests for the USB helper's side of the USB tunnel (GPL-3.0, part of Starwire): the codec against
// the shared vectors, the device rules against the shared cases, and the relay's USB/IP checks,
// fed bytes as the driver and the phone's exporter would send them.
//
// Deterministic: no clock, fixed bytes, and no network beyond a connection to itself on
// 127.0.0.1, to read who owns it in Windows' connection table.
//
//   StarwireUsbTunnelTests.exe <folder with swut-v1-vectors.txt and swut-v1-policy.txt>
// run-usb-helper-tests.ps1 builds and runs it; exits with 1 if any check fails.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

static class StarwireUsbTunnelTests
{
    // The contract's SHA-256 of each shared file (docs/dev/usb-protocol.md, "Test vectors")
    const string VectorsSha256 = "b955594038a2a423525024796861a3f43748799c8e10e4260dea9f6eae60a085";
    const string PolicySha256 = "1acfa0a186b4bf0f68e90317897cdae9afc83d38bece590b55003dcb793599b4";

    static int passed, failed;
    static string vectorFolder;
    static int pid;
    static Dictionary<string, byte[]> V;

    // The vectors' inputs: the microphone's pairing key and device id
    static readonly byte[] PairKey = Range(0x00, 32);
    static readonly byte[] DeviceId = Range(0xa0, 8);

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
        try {
            Vectors();
            BindAnswers();
            ShareLayouts();
            PolicyCases();
            StreamCheck();
            TcpOwnership();
        }
        catch (Exception e) {
            failed++;
            Console.WriteLine("FAIL the tests themselves threw: " + e);
        }
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

}
