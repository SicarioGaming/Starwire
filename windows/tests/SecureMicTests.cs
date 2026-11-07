// Tests for SecureMic.cs, the receiver's encrypted microphone format (GPL-3.0, part of Starwire):
// known answers from the standards, the vectors shared with the Android side, tampering, replays,
// the pairing rules, the endpoint's rules with a fake clock, the paired devices file, and a
// seeded fuzz of every parser. Deterministic: no sockets, no waiting, fixed random bytes.
// run-tests.ps1 builds it with the C# compiler that comes with Windows and runs it.
//
//   SecureMicTests.exe <swmc-v1-vectors.txt>     runs every test; exits with 1 if any fails
//   SecureMicTests.exe tone <seconds> <input> <frequency> <level>
//                                                for e2e-loopback.ps1: records the microphone
//                                                and says in how many 100 ms stretches it heard
//                                                the tone louder than level (0 to 1)

#if STARWIRE_LINUX
using EcdhKey = System.Security.Cryptography.ECDiffieHellman;
#else
using EcdhKey = System.Security.Cryptography.ECDiffieHellmanCng;
#endif
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

static class SecureMicTests
{
    // The fixed inputs the shared vectors come from
    static readonly byte[] PairKeyFixed = Swmc.Hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static readonly byte[] DeviceId = Swmc.Hex("a0a1a2a3a4a5a6a7");
    static readonly byte[] ClientRandom = Swmc.Hex("c0c1c2c3c4c5c6c7");
    static readonly byte[] ServerRandom = Swmc.Hex("5051525354555657");
    static readonly byte[] Code = Swmc.Hex("11121314151617181920");
    static readonly byte[] PairNonce = Swmc.Hex("7071727374757677");
    static readonly byte[] ReceiverId = Swmc.Hex("b0b1b2b3b4b5b6b7");
    // RFC 5903 section 8.1 (256-bit random ECP group): i is the device, r the receiver
    static readonly byte[] EcdhI = Swmc.Hex("c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433");
    static readonly byte[] EcdhGix = Swmc.Hex("dad0b65394221cf9b051e1feca5787d098dfe637fc90b9ef945d0c3772581180");
    static readonly byte[] EcdhGiy = Swmc.Hex("5271a0461cdb8252d61f1c456fa3e59ab1f45b33accf5f58389e0577b8990bb3");
    static readonly byte[] EcdhR = Swmc.Hex("c6ef9c5d78ae012a011164acb397ce2088685d8f06bf9be0b283ab46476bee53");
    static readonly byte[] EcdhGrx = Swmc.Hex("d12dfb5289c8d4f81208b70270398c342296970a0bccb74c736fc7554494bf63");
    static readonly byte[] EcdhGry = Swmc.Hex("56fbf3ca366cc23e8157854c13c58d6aac23f046ada30f8353e74f33039872ab");
    static readonly byte[] EcdhGirx = Swmc.Hex("d6840f6b42f6edafd13116e0e12565202fef8e9ece7dce03812464d04b9442de");
    // The vectors file both sides test against, byte for byte
    const string VectorsSha256 = "bc79b6d1e099e89bbfc93125a987afb96a02bd5bfe8f4480ced36b369bfd810a";
    static readonly DateTime Epoch = new DateTime(2026, 10, 23, 5, 0, 0, DateTimeKind.Utc);

    static int passed, failed;
    static string temp;

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

    static bool Same(byte[] a, byte[] b)
    {
        return a != null && b != null && a.Length == b.Length && Swmc.ConstantTimeEquals(a, 0, b, 0, a.Length);
    }

    static byte[] Point(byte[] x, byte[] y) { return Swmc.Concat(new byte[] { 4 }, x, y); }

    static byte[] PhonePoint() { return Point(EcdhGix, EcdhGiy); }
    static byte[] ReceiverPoint() { return Point(EcdhGrx, EcdhGry); }

    // A VBAN packet as Starwire sends it: 256 mono samples at 48 kHz, stream Stream1
    static byte[] VbanPacket(int frame, int sampleOffset)
    {
        byte[] b = new byte[28 + 512];
        b[0] = (byte) 'V'; b[1] = (byte) 'B'; b[2] = (byte) 'A'; b[3] = (byte) 'N';
        b[4] = 3; b[5] = 255; b[6] = 0; b[7] = 1;
        Array.Copy(Swmc.Ascii("Stream1"), 0, b, 8, 7);
        Swmc.PutU32(b, 24, frame);
        for (int i = 0; i < 256; i++) {
            short s = (short) ((i + sampleOffset) * 37 - 16000);
            b[28 + i * 2] = (byte) s;
            b[29 + i * 2] = (byte) (s >> 8);
        }
        return b;
    }

    static MicPairing Pairing(byte[] id, byte[] key, string name)
    {
        MicPairing p = new MicPairing();
        p.DeviceId = (byte[]) id.Clone();
        p.Key = (byte[]) key.Clone();
        p.Name = name;
        p.PairedAt = Epoch;
        p.LastSeen = DateTime.MinValue;
        return p;
    }

    // ---------------------------------------------------------------- test rigs
    // An endpoint with a fake clock, chosen random bytes, and a list of what it sent and said
    sealed class Rig
    {
        public long Now;
        public readonly Queue<byte[]> Randoms = new Queue<byte[]>();
        public readonly Random Fill = new Random(7);
        public readonly List<byte[]> Sent = new List<byte[]>();
        public readonly List<string> Reports = new List<string>();
        // The important reports, which the icon shows as notifications
        public readonly List<string> Notices = new List<string>();
        public int RandomCalls;
        public readonly MicPairingStore Store;
        public readonly SecureMicEndpoint Endpoint;
        public EndPoint From = new IPEndPoint(IPAddress.Parse("192.168.1.20"), 40000);
        public readonly byte[] Out = new byte[2048];
        public int OutLength;
        public string Sender;
        public bool Encrypted;

        public Rig(MicPairingStore store, params MicPairing[] devices)
        {
            if (store == null) {
                store = new MicPairingStore(null);
                store.Load(Random);
                store.ReceiverId = (byte[]) ReceiverId.Clone();
            }
            Store = store;
            foreach (MicPairing d in devices) {
                Store.Devices.Add(d);
            }
            Endpoint = new SecureMicEndpoint(Store, () => Now, Random,
                (b, n, to) => Sent.Add(Swmc.Slice(b, 0, n)), (text, important) => {
                    Reports.Add(text);
                    if (important) {
                        Notices.Add(text);
                    }
                });
            Endpoint.NewPairingKey = () => Swmc.FixedKey(EcdhR, EcdhGrx, EcdhGry);
            Endpoint.UtcNow = () => Epoch.AddMilliseconds(Now);
            Endpoint.PcName = "TEST-PC";
        }

        void Random(byte[] b)
        {
            RandomCalls++;
            if (Randoms.Count > 0) {
                Array.Copy(Randoms.Dequeue(), b, b.Length);
            }
            else {
                Fill.NextBytes(b);
            }
        }

        public bool Feed(byte[] p) { return Feed(p, p.Length); }

        public bool Feed(byte[] p, int length)
        {
            return Endpoint.Handle(p, length, From, Out, out OutLength, out Sender, out Encrypted);
        }

        public byte[] Last { get { return Sent.Count == 0 ? null : Sent[Sent.Count - 1]; } }

        public int ReportsWith(string text)
        {
            int n = 0;
            foreach (string r in Reports) {
                if (r.Contains(text)) {
                    n++;
                }
            }
            return n;
        }
    }

    // The device's side, as far as the tests need it
    sealed class Phone
    {
        public readonly byte[] Id, Key, AuthKey;

        public Phone(byte[] id, byte[] key)
        {
            Id = id;
            Key = key;
            AuthKey = Swmc.AuthKey(key, id);
        }

        public byte[] Hello(byte[] clientRandom) { return Swmc.Hello(AuthKey, Id, clientRandom); }

        public bool AckOk(byte[] ack, byte[] clientRandom, int status)
        {
            return ack != null && ack.Length == Swmc.AckLength && Swmc.TypeOf(ack, ack.Length) == Swmc.THelloAck
                && Swmc.MacOk(AuthKey, ack, ack.Length) && Swmc.ConstantTimeEquals(ack, 8, Id, 0, 8)
                && Swmc.ConstantTimeEquals(ack, 16, clientRandom, 0, 8) && ack[Swmc.Header + 8] == status;
        }

        public Sender Session(byte[] clientRandom, byte[] ack)
        {
            byte[] serverRandom = Swmc.Slice(ack, Swmc.Header, 8);
            return new Sender(Id, serverRandom, Swmc.AudioKey(Key, Id, clientRandom, serverRandom));
        }
    }

    sealed class Sender
    {
        public readonly byte[] DeviceId, SessionId;
        readonly CngAesGcm aead;

        public Sender(byte[] deviceId, byte[] sessionId, byte[] key)
        {
            DeviceId = deviceId;
            SessionId = sessionId;
            aead = new CngAesGcm(key);
        }

        public byte[] Audio(long counter, byte[] vban)
        {
            byte[] p = new byte[2048];
            int n = Swmc.Audio(aead, DeviceId, SessionId, counter, vban, vban.Length, p);
            return Swmc.Slice(p, 0, n);
        }

        public byte[] Audio(long counter) { return Audio(counter, VbanPacket((int) counter, 0)); }
    }

    static byte[] Bytes(int n, byte first)
    {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (first + i);
        }
        return b;
    }

    // ---------------------------------------------------------------- the standards' known answers
    static void KnownAnswers()
    {
        using (CngAesGcm tc16 = new CngAesGcm(Swmc.Hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"))) {
            byte[] pt = Swmc.Hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
            byte[] aad = Swmc.Hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
            byte[] iv = Swmc.Hex("cafebabefacedbaddecaf888");
            byte[] ct = new byte[pt.Length + 16];
            tc16.Seal(iv, 0, aad, 0, aad.Length, pt, 0, pt.Length, ct, 0);
            Check("GCM spec test case 16 (AES-256) through CNG", Swmc.Hex(ct) ==
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
                + "76fc6ece0f4e1768cddf8853bb2d551b");
            byte[] back = new byte[pt.Length];
            Check("GCM test case 16 decrypts back", tc16.Open(iv, 0, aad, 0, aad.Length, ct, 0, ct.Length, back, 0) == pt.Length && Same(back, pt));
            ct[ct.Length - 1] ^= 1;
            byte[] untouched = new byte[pt.Length];
            Check("GCM test case 16 with a changed tag is refused, output untouched",
                tc16.Open(iv, 0, aad, 0, aad.Length, ct, 0, ct.Length, untouched, 0) == -1 && Same(untouched, new byte[pt.Length]));
        }
        Check("HKDF RFC 5869 test case 1", Swmc.Hex(Swmc.Hkdf(Swmc.Hex("000102030405060708090a0b0c"),
            Swmc.Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Swmc.Hex("f0f1f2f3f4f5f6f7f8f9"), 42))
            == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        Check("HKDF RFC 5869 test case 3 (no salt, no info)", Swmc.Hex(Swmc.Hkdf(null,
            Swmc.Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), new byte[0], 42))
            == "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
        byte[] zi, zr;
        using (EcdhKey phone = Swmc.FixedKey(EcdhI, EcdhGix, EcdhGiy))
        using (EcdhKey receiver = Swmc.FixedKey(EcdhR, EcdhGrx, EcdhGry)) {
            zi = Swmc.EcdhHash(phone, ReceiverPoint(), 0);
            zr = Swmc.EcdhHash(receiver, PhonePoint(), 0);
            Check("CNG gives back the public point it was given", Same(Swmc.OwnPoint(receiver), ReceiverPoint()));
        }
        byte[] expect;
        using (SHA256 sha = SHA256.Create()) {
            expect = sha.ComputeHash(EcdhGirx);
        }
        Check("ECDH P-256 RFC 5903 8.1 through CNG, both ways, gives SHA-256 of the shared X", Same(zi, expect) && Same(zr, expect));
    }

    // ---------------------------------------------------------------- the shared vectors
    static Dictionary<string, string> ReadVectors(string path)
    {
        Dictionary<string, string> v = new Dictionary<string, string>();
        foreach (string line in File.ReadAllLines(path)) {
            int eq = line.IndexOf('=');
            if (eq > 0) {
                v[line.Substring(0, eq)] = line.Substring(eq + 1);
            }
        }
        return v;
    }

    // Every line, made here from the fixed inputs
    static Dictionary<string, string> MakeVectors()
    {
        Dictionary<string, string> v = new Dictionary<string, string>();
        using (CngAesGcm tc16 = new CngAesGcm(Swmc.Hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"))) {
            byte[] pt = Swmc.Hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
            byte[] aad = Swmc.Hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
            byte[] ct = new byte[pt.Length + 16];
            tc16.Seal(Swmc.Hex("cafebabefacedbaddecaf888"), 0, aad, 0, aad.Length, pt, 0, pt.Length, ct, 0);
            v["kat.gcm_tc16"] = Swmc.Hex(ct);
        }
        v["kat.hkdf_rfc5869_1"] = Swmc.Hex(Swmc.Hkdf(Swmc.Hex("000102030405060708090a0b0c"),
            Swmc.Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Swmc.Hex("f0f1f2f3f4f5f6f7f8f9"), 42));
        byte[] z;
        using (EcdhKey receiver = Swmc.FixedKey(EcdhR, EcdhGrx, EcdhGry)) {
            z = Swmc.EcdhHash(receiver, PhonePoint(), 0);
        }
        v["kat.ecdh_rfc5903_sha256"] = Swmc.Hex(z);
        byte[] kAuth = Swmc.AuthKey(PairKeyFixed, DeviceId);
        byte[] kAudio = Swmc.AudioKey(PairKeyFixed, DeviceId, ClientRandom, ServerRandom);
        v["key.auth"] = Swmc.Hex(kAuth);
        v["key.audio"] = Swmc.Hex(kAudio);
        v["pkt.hello"] = Swmc.Hex(Swmc.Hello(kAuth, DeviceId, ClientRandom));
        v["pkt.hello_ack"] = Swmc.Hex(Swmc.HelloAck(kAuth, DeviceId, ClientRandom, ServerRandom, 0));
        v["pkt.rehello"] = Swmc.Hex(Swmc.Rehello(kAuth, DeviceId, ServerRandom));
        byte[] vban = VbanPacket(7, 0);
        v["vban.frame7"] = Swmc.Hex(vban);
        Sender s = new Sender(DeviceId, ServerRandom, kAudio);
        v["pkt.audio7"] = Swmc.Hex(s.Audio(7, vban));
        v["pkt.audio_last"] = Swmc.Hex(s.Audio(0xFFFFFFFFL, vban));
        v["pair.code"] = Swmc.EncodeCode(Code);
        byte[] kCode = Swmc.CodeKey(Code);
        v["pair.code_key"] = Swmc.Hex(kCode);
        v["pkt.pair_request"] = Swmc.Hex(Swmc.PairRequest(kCode, DeviceId, PairNonce, PhonePoint(), "Test phone"));
        byte[] kPair = Swmc.PairKey(kCode, z, DeviceId, PairNonce, PhonePoint(), ReceiverPoint());
        v["pair.key"] = Swmc.Hex(kPair);
        v["pkt.pair_accept"] = Swmc.Hex(Swmc.PairAccept(kCode, kPair, DeviceId, PairNonce, ReceiverPoint(), ReceiverId, "TEST-PC"));
        return v;
    }

    static void SharedVectors(string path)
    {
        byte[] file = File.ReadAllBytes(path);
        using (SHA256 sha = SHA256.Create()) {
            Check("the vectors file is the shared one, byte for byte (SHA-256 " + VectorsSha256.Substring(0, 12) + "...)",
                Swmc.Hex(sha.ComputeHash(file)) == VectorsSha256);
        }
        Dictionary<string, string> want = ReadVectors(path);
        Dictionary<string, string> made = MakeVectors();
        foreach (KeyValuePair<string, string> kv in want) {
            string mine;
            Check("vector " + kv.Key, made.TryGetValue(kv.Key, out mine) && mine == kv.Value);
        }
        Check("every vector made here is in the file (" + want.Count + " lines)", made.Count == want.Count);
        Check("an AUDIO packet is 584 bytes", Swmc.Hex(want["pkt.audio7"]).Length == 584);

        // The vectors' packets, taken by the endpoint the way the receiver takes them
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        rig.Feed(Swmc.Hex(want["pkt.audio7"]));
        Check("audio for a session it doesn't know gets the vectors' REHELLO", rig.Sent.Count == 1 && Swmc.Hex(rig.Last) == want["pkt.rehello"]);
        rig.Randoms.Enqueue(ServerRandom);
        rig.Feed(Swmc.Hex(want["pkt.hello"]));
        Check("the vectors' HELLO gets the vectors' HELLO_ACK", rig.Sent.Count == 2 && Swmc.Hex(rig.Last) == want["pkt.hello_ack"]);
        Check("the vectors' AUDIO plays the vectors' VBAN packet",
            rig.Feed(Swmc.Hex(want["pkt.audio7"])) && rig.Encrypted && Swmc.Hex(rig.Out, 0, rig.OutLength) == want["vban.frame7"]);
        Check("the same AUDIO again is refused (replay)", !rig.Feed(Swmc.Hex(want["pkt.audio7"])) && rig.Endpoint.ReplayedOrLate == 1);
        Check("the vectors' AUDIO with counter 0xFFFFFFFF plays", rig.Feed(Swmc.Hex(want["pkt.audio_last"])));
        Sender s = phone.Session(ClientRandom, Swmc.Hex(want["pkt.hello_ack"]));
        Check("after 0xFFFFFFFF the counter can't wrap to 0 on the same session", !rig.Feed(s.Audio(0)) && rig.Endpoint.ReplayedOrLate == 2);

        Rig pair = new Rig(null);
        pair.Randoms.Enqueue(Code);
        Check("the vectors' pairing code is shown as " + want["pair.code"], pair.Endpoint.BeginPairing() == want["pair.code"]);
        pair.Feed(Swmc.Hex(want["pkt.pair_request"]));
        Check("the vectors' PAIR_REQUEST gets the vectors' PAIR_ACCEPT", pair.Sent.Count == 1 && Swmc.Hex(pair.Last) == want["pkt.pair_accept"]);
        MicPairing paired = pair.Store.Find(DeviceId);
        Check("and the device is paired with the vectors' key and name",
            paired != null && Swmc.Hex(paired.Key) == want["pair.key"] && paired.Name == "Test phone" && pair.Endpoint.PairedCount == 1);
        Check("the vectors' codes decode back", Same(Swmc.DecodeCode(want["pair.code"]), Code));
    }

    // ---------------------------------------------------------------- tampering
    static void Tampering()
    {
        byte[] kAudio = Swmc.AudioKey(PairKeyFixed, DeviceId, ClientRandom, ServerRandom);
        Sender sender = new Sender(DeviceId, ServerRandom, kAudio);
        byte[] good = sender.Audio(7, VbanPacket(7, 0));
        byte[] output = new byte[2048];
        using (CngAesGcm aead = new CngAesGcm(kAudio)) {
            Check("a good AUDIO packet opens", Swmc.OpenAudio(aead, good, good.Length, output) == 540);
            bool all = true;
            for (int at = 0; at < good.Length; at++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] t = (byte[]) good.Clone();
                    t[at] ^= (byte) (1 << bit);
                    if (Swmc.OpenAudio(aead, t, t.Length, output) != -1) {
                        all = false;
                    }
                }
            }
            Check("every single-bit change in a 584-byte AUDIO packet is refused (4672 of them)", all);
            all = true;
            for (int length = 0; length < good.Length; length++) {
                if (Swmc.OpenAudio(aead, good, length, output) != -1) {
                    all = false;
                }
            }
            Check("every shortened AUDIO packet (0 to 583 bytes) is refused, without an exception", all);
            byte[] big = new byte[Swmc.AudioAad + Swmc.MaxAudioPlaintext + Swmc.TagLength + 1];
            Array.Copy(good, big, good.Length);
            Check("an oversized AUDIO packet is refused before decrypting", Swmc.OpenAudio(aead, big, big.Length, output) == -1);
            byte[] longer = new byte[good.Length + 1];
            Array.Copy(good, longer, good.Length);
            Check("an AUDIO packet with a byte added is refused", Swmc.OpenAudio(aead, longer, longer.Length, output) == -1);
        }
        using (CngAesGcm wrong = new CngAesGcm(Swmc.AudioKey(Swmc.Hex("ff0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), DeviceId, ClientRandom, ServerRandom))) {
            Check("AUDIO under another pairing key is refused", Swmc.OpenAudio(wrong, good, good.Length, output) == -1);
        }
        using (CngAesGcm otherDevice = new CngAesGcm(Swmc.AudioKey(PairKeyFixed, Swmc.Hex("a0a1a2a3a4a5a6a8"), ClientRandom, ServerRandom))) {
            Check("AUDIO under another device's key is refused", Swmc.OpenAudio(otherDevice, good, good.Length, output) == -1);
        }
        byte[] kAuth = Swmc.AuthKey(PairKeyFixed, DeviceId);
        byte[] hello = Swmc.Hello(kAuth, DeviceId, ClientRandom);
        byte[] relabelled = (byte[]) hello.Clone();
        relabelled[5] = Swmc.TRehello;
        Check("a HELLO relabelled as a REHELLO fails its MAC", !Swmc.MacOk(kAuth, relabelled, relabelled.Length));
        byte[] otherId = (byte[]) hello.Clone();
        otherId[15] ^= 1;
        Check("a HELLO with another device id fails under that id's key", !Swmc.MacOk(Swmc.AuthKey(PairKeyFixed, Swmc.Slice(otherId, 8, 8)), otherId, otherId.Length));
        Check("a shortened HELLO fails", !Swmc.MacOk(kAuth, hello, hello.Length - 1));
        byte[] v2 = (byte[]) good.Clone();
        v2[4] = 2;
        Check("version 2 isn't read as v1", Swmc.TypeOf(v2, v2.Length) == -1);
        byte[] reserved = (byte[]) good.Clone();
        reserved[6] = 1;
        Check("non-zero reserved bytes aren't read as v1", Swmc.TypeOf(reserved, reserved.Length) == -1);
        byte[] plain = VbanPacket(1, 0);
        Check("plain VBAN isn't an SWMC packet", Swmc.TypeOf(plain, plain.Length) == -1);
        byte[] retyped = (byte[]) good.Clone();
        retyped[5] = Swmc.THello;
        using (CngAesGcm aead = new CngAesGcm(kAudio)) {
            Check("AUDIO relabelled as another type isn't opened", Swmc.OpenAudio(aead, retyped, retyped.Length, output) == -1);
        }
    }

    // ---------------------------------------------------------------- the replay rule
    static void Replays()
    {
        ReplayGuard g = new ReplayGuard();
        Check("the first counter is accepted", g.Accept(0));
        Check("the next counter is accepted", g.Accept(1));
        Check("the same counter again is refused (replay)", !g.Accept(1));
        Check("a jump ahead (lost packets) is accepted", g.Accept(5));
        Check("an older counter (a late packet) is refused", !g.Accept(4));
        ReplayGuard w = new ReplayGuard();
        Check("counter 0xFFFFFFFF is accepted", w.Accept(0xFFFFFFFFL));
        Check("and then 0 (a wrap) is refused", !w.Accept(0));
    }

    // ---------------------------------------------------------------- pairing codes
    static void Codes()
    {
        string code = Swmc.EncodeCode(Code);
        Check("the code is 16 characters in groups of four", code == "2491-650N-2RBH-G690");
        Check("it decodes back", Same(Swmc.DecodeCode(code), Code));
        Check("lower case, spaces, O for 0 and I or L for 1 are fine",
            Same(Swmc.DecodeCode("2491 65on 2rbh g69o"), Code) && Same(Swmc.DecodeCode("2491650N2RBHG690"), Code)
            && Same(Swmc.DecodeCode(" 249l-65oN-2RBH-G69O "), Code));
        Check("too short or too long is refused", Swmc.DecodeCode("2491-650N-2RBH-G69") == null && Swmc.DecodeCode("2491-650N-2RBH-G6900") == null
            && Swmc.DecodeCode("") == null && Swmc.DecodeCode(null) == null);
        Check("U and other letters outside the alphabet are refused", Swmc.DecodeCode("U491-650N-2RBH-G690") == null
            && Swmc.DecodeCode("2491-650N-2RBH-G69!") == null && Swmc.DecodeCode("2491_650N_2RBH_G690") == null);
        Random r = new Random(5);
        bool all = true;
        for (int i = 0; i < 2000; i++) {
            byte[] ten = new byte[10];
            r.NextBytes(ten);
            if (!Same(Swmc.DecodeCode(Swmc.EncodeCode(ten)), ten)) {
                all = false;
            }
        }
        Check("2000 random codes round-trip", all);
    }

    // ---------------------------------------------------------------- the endpoint
    static void Sessions()
    {
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        rig.Randoms.Enqueue(ServerRandom);
        rig.Feed(phone.Hello(ClientRandom));
        Check("a paired device's HELLO gets one HELLO_ACK, status ready", rig.Sent.Count == 1 && phone.AckOk(rig.Last, ClientRandom, Swmc.StatusReady));
        rig.Now = 250;
        rig.Feed(phone.Hello(ClientRandom));
        Check("the same HELLO again gets the same HELLO_ACK", rig.Sent.Count == 2 && Same(rig.Sent[0], rig.Sent[1]));
        Sender s1 = phone.Session(ClientRandom, rig.Sent[0]);
        Check("audio on the session plays, decrypted, named after the device",
            rig.Feed(s1.Audio(0)) && rig.Encrypted && rig.Sender == "Test phone" && Swmc.Hex(rig.Out, 0, rig.OutLength) == Swmc.Hex(VbanPacket(0, 0)));
        Check("the next counter plays", rig.Feed(s1.Audio(1)));
        Check("a jump ahead plays", rig.Feed(s1.Audio(10)));
        Check("a late packet doesn't", !rig.Feed(s1.Audio(9)) && rig.Endpoint.ReplayedOrLate == 1);
        Check("a replayed packet doesn't", !rig.Feed(s1.Audio(10)) && rig.Endpoint.ReplayedOrLate == 2);
        byte[] forged = s1.Audio(11);
        forged[100] ^= 4;
        Check("a forged packet doesn't, and doesn't use up its counter", !rig.Feed(forged) && rig.Endpoint.Forged == 1 && rig.Feed(s1.Audio(11)));
        byte[] counterChanged = s1.Audio(12);
        Swmc.PutU32(counterChanged, Swmc.CounterAt, 500);
        Check("a packet with its counter changed doesn't", !rig.Feed(counterChanged) && rig.Endpoint.Forged == 2 && rig.Feed(s1.Audio(12)));

        // A recorded HELLO sent again can't take the session over
        rig.Now = 1000;
        rig.Feed(phone.Hello(ClientRandom));
        Check("a replayed HELLO gets the current session's ACK again", Same(rig.Last, rig.Sent[0]));
        Check("and the current session keeps playing", rig.Feed(s1.Audio(13)));
        byte[] old = Bytes(8, 0x10);
        rig.Feed(phone.Hello(old));
        Check("an older recorded HELLO makes only a waiting session", rig.Sent.Count == 4 && phone.AckOk(rig.Last, old, Swmc.StatusReady));
        Sender waiting = phone.Session(old, rig.Last);
        Check("the current session keeps playing after it", rig.Feed(s1.Audio(14)));
        byte[] c3 = Bytes(8, 0x30), c4 = Bytes(8, 0x40);
        rig.Feed(phone.Hello(c3));
        Sender s3 = phone.Session(c3, rig.Last);
        int sent = rig.Sent.Count;
        rig.Feed(phone.Hello(c4));
        Check("at most 2 sessions wait per device: with both under 2 s old, another HELLO gets no answer", rig.Sent.Count == sent);
        Check("however many HELLOs come, the session that's playing keeps playing", rig.Feed(s1.Audio(15)));
        rig.Now = 1000 + SecureMicEndpoint.PendingKeepMs - 1;
        rig.Feed(phone.Hello(c4));
        Check("still none just before the oldest is 2 s old", rig.Sent.Count == sent);
        rig.Now = 1000 + SecureMicEndpoint.PendingKeepMs;
        rig.Feed(phone.Hello(c4));
        Check("then the HELLO is answered and the oldest waiting session goes", phone.AckOk(rig.Last, c4, Swmc.StatusReady)
            && !rig.Feed(waiting.Audio(0)) && rig.Endpoint.UnknownSession == 1);
        Check("the device's first audio on a new session plays and replaces the old one", rig.Feed(s3.Audio(0)));
        Check("the old session's audio is now refused", !rig.Feed(s1.Audio(16)) && rig.Endpoint.UnknownSession == 2);
        rig.Feed(phone.Hello(ClientRandom));
        Check("and the old session's HELLO now only makes a waiting session", phone.AckOk(rig.Last, ClientRandom, Swmc.StatusReady)
            && !Same(rig.Last, rig.Sent[0]) && rig.Feed(s3.Audio(1)));

        // A waiting session is forgotten after 30 s
        rig.Now = 10000;
        rig.Feed(s3.Audio(2));
        byte[] c5 = Bytes(8, 0x50), c6 = Bytes(8, 0x60);
        rig.Feed(phone.Hello(c5));
        Check("a new HELLO pushes out the waiting session the used one made", phone.AckOk(rig.Last, c5, Swmc.StatusReady));
        Sender s5 = phone.Session(c5, rig.Last);
        rig.Feed(phone.Hello(c6));
        Sender s6 = phone.Session(c6, rig.Last);
        rig.Now = 10000 + SecureMicEndpoint.PendingMs - 1;
        Check("a waiting session still works just before 30 s", rig.Feed(s6.Audio(0)));
        rig.Now = 10000 + SecureMicEndpoint.PendingMs;
        Check("and is forgotten at 30 s", !rig.Feed(s5.Audio(0)));
        Check("nothing threw", rig.Endpoint.Errors == 0);
    }

    static void Rehellos()
    {
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        Sender unknown = new Sender(DeviceId, ServerRandom, Swmc.AudioKey(PairKeyFixed, DeviceId, ClientRandom, ServerRandom));
        rig.Feed(unknown.Audio(0));
        Check("audio for a session it doesn't know (the receiver restarted) gets a REHELLO",
            rig.Sent.Count == 1 && Same(rig.Last, Swmc.Rehello(phone.AuthKey, DeviceId, ServerRandom)));
        rig.Now = 400;
        rig.Feed(unknown.Audio(1));
        rig.Now = 999;
        rig.Feed(unknown.Audio(2));
        Check("but at most one a second", rig.Sent.Count == 1);
        rig.Now = 1000;
        rig.Feed(unknown.Audio(3));
        Check("and another after a second", rig.Sent.Count == 2);
        Check("none of that audio played", rig.Endpoint.Played == 0 && rig.Endpoint.UnknownSession == 4);
    }

    static void Floor()
    {
        byte[] idB = Swmc.Hex("b1b2b3b4b5b6b7b8");
        byte[] keyB = Bytes(32, 0x80);
        Phone a = new Phone(DeviceId, PairKeyFixed), b = new Phone(idB, keyB);
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Phone A"), Pairing(idB, keyB, "Tablet B"));
        rig.Feed(a.Hello(ClientRandom));
        Sender sa = a.Session(ClientRandom, rig.Last);
        Check("device A plays", rig.Feed(sa.Audio(0)) && rig.Sender == "Phone A");
        rig.Now = 1000;
        rig.Feed(b.Hello(ClientRandom));
        Check("device B's HELLO while A plays gets status busy", b.AckOk(rig.Last, ClientRandom, Swmc.StatusBusy));
        Sender sb = b.Session(ClientRandom, rig.Last);
        rig.Now = 1500;
        Check("B's audio doesn't play while A is talking", !rig.Feed(sb.Audio(0)) && rig.Endpoint.Busy == 1);
        Check("and the PC says so once", rig.ReportsWith("Phone A is sending its microphone; ignoring Tablet B until it stops.") == 1);
        rig.Now = 2000;
        Check("A keeps playing", rig.Feed(sa.Audio(1)));
        rig.Now = 2000 + SecureMicEndpoint.FloorMs - 1;
        Check("B still waits until A has been quiet for 3 s", !rig.Feed(sb.Audio(1)) && rig.Endpoint.Busy == 2);
        Check("without saying it again", rig.ReportsWith("ignoring Tablet B") == 1);
        rig.Now = 2000 + SecureMicEndpoint.FloorMs;
        Check("then B plays", rig.Feed(sb.Audio(2)) && rig.Sender == "Tablet B");
        rig.Now += 100;
        byte[] c2 = Bytes(8, 0x22);
        rig.Feed(a.Hello(c2));
        Check("and now A gets status busy", a.AckOk(rig.Last, c2, Swmc.StatusBusy));
        rig.Endpoint.OutputReady = () => false;
        rig.Now += 5000;
        byte[] c3 = Bytes(8, 0x33);
        rig.Feed(a.Hello(c3));
        Check("with no output, HELLO_ACK says so (status 1)", a.AckOk(rig.Last, c3, Swmc.StatusNoOutput));
    }

    static void Refusals()
    {
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        byte[] plain = VbanPacket(3, 0);
        Check("plain VBAN is refused by default", !rig.Feed(plain) && rig.Endpoint.Unencrypted == 1);
        Check("with one message saying to update Starwire and pair",
            rig.ReportsWith("Ignored unencrypted microphone audio from 192.168.1.20. If that's Starwire, update it and pair it: right-click this icon > Pair a device.") == 1);
        rig.Now = 1000;
        rig.Feed(plain);
        rig.Now = SecureMicEndpoint.WarnMs - 1;
        rig.Feed(plain);
        Check("not repeated for that address for 10 minutes", rig.ReportsWith("Ignored unencrypted") == 1 && rig.Endpoint.Unencrypted == 3);
        rig.Now = SecureMicEndpoint.WarnMs;
        rig.Feed(plain);
        Check("then said again", rig.ReportsWith("Ignored unencrypted") == 2);
        Check("plain VBAN gets no answer", rig.Sent.Count == 0);

        rig.From = new IPEndPoint(IPAddress.Parse("8.8.8.8"), 40000);
        rig.Feed(phone.Hello(ClientRandom));
        Check("a HELLO from outside the home network gets no answer", rig.Sent.Count == 0 && rig.Endpoint.NotHome == 1);
        rig.From = new IPEndPoint(IPAddress.Parse("::ffff:8.8.4.4"), 40000);
        rig.Feed(phone.Hello(ClientRandom));
        Check("nor from a mapped IPv4 address outside it", rig.Sent.Count == 0 && rig.Endpoint.NotHome == 2);
        rig.From = new IPEndPoint(IPAddress.Parse("192.168.1.20"), 40000);

        Phone stranger = new Phone(Swmc.Hex("0102030405060708"), Bytes(32, 9));
        rig.Feed(stranger.Hello(ClientRandom));
        Check("a device that isn't paired gets no answer", rig.Sent.Count == 0 && rig.Endpoint.Unpaired == 1);
        Check("and the PC says one hasn't been paired", rig.ReportsWith("A device this PC hasn't paired tried to send its microphone (from 192.168.1.20)") == 1);
        rig.Feed(stranger.Hello(ClientRandom));
        Check("once", rig.ReportsWith("hasn't paired") == 1);
        byte[] badMac = phone.Hello(ClientRandom);
        badMac[Swmc.HelloLength - 1] ^= 1;
        rig.Feed(badMac);
        Check("a HELLO with a wrong MAC gets no answer", rig.Sent.Count == 0 && rig.Endpoint.Forged == 1);
        byte[] shortHello = Swmc.Slice(phone.Hello(ClientRandom), 0, Swmc.HelloLength - 1);
        rig.Feed(shortHello);
        Check("nor a short one", rig.Sent.Count == 0);
        byte[] ack = Swmc.HelloAck(phone.AuthKey, DeviceId, ClientRandom, ServerRandom, 0);
        rig.Feed(ack);
        rig.Feed(Swmc.Rehello(phone.AuthKey, DeviceId, ServerRandom));
        Check("packets only a device receives are ignored", rig.Sent.Count == 0 && rig.Endpoint.Played == 0);

        // --allow-unencrypted: plain VBAN with the stream name, from home-network addresses only
        Rig open = new Rig(null);
        open.Endpoint.AllowUnencrypted = true;
        Check("with AllowUnencrypted, plain VBAN named Stream1 plays", open.Feed(plain) && !open.Encrypted && open.Sender == "192.168.1.20"
            && open.OutLength == plain.Length);
        byte[] otherName = VbanPacket(4, 0);
        otherName[8] = (byte) 'X';
        Check("but not with another stream name", !open.Feed(otherName));
        byte[] upper = VbanPacket(5, 0);
        Array.Copy(Swmc.Ascii("STREAM1"), 0, upper, 8, 7);
        Check("names match without case, as before", open.Feed(upper));
        open.From = new IPEndPoint(IPAddress.Parse("203.0.113.9"), 6000);
        Check("nor from outside the home network", !open.Feed(plain) && open.Endpoint.NotHome == 1);
        open.From = new IPEndPoint(IPAddress.Parse("100.101.102.103"), 6000);
        Check("from a VPN address (Tailscale's range) it plays", open.Feed(plain));
        open.From = new IPEndPoint(IPAddress.Parse("fd7a:115c:a1e0::1"), 6000);
        Check("and from a private IPv6 one", open.Feed(plain));
        open.From = new IPEndPoint(IPAddress.Loopback, 6000);
        Check("and from this PC", open.Feed(plain));
        Check("nothing threw", rig.Endpoint.Errors == 0 && open.Endpoint.Errors == 0);

        // Unpairing ends the device's sessions
        rig.Feed(phone.Hello(ClientRandom));
        Sender s = phone.Session(ClientRandom, rig.Last);
        Check("before unpairing, the device plays", rig.Feed(s.Audio(0)));
        Check("unpairing works", rig.Endpoint.Unpair(DeviceId) && rig.Endpoint.PairedCount == 0 && rig.Store.Devices.Count == 0);
        int sent = rig.Sent.Count;
        Check("after unpairing, its audio is refused", !rig.Feed(s.Audio(1)));
        rig.Now += SecureMicEndpoint.WarnMs;
        rig.Feed(phone.Hello(ClientRandom));
        Check("and its HELLO gets no answer", rig.Sent.Count == sent);
    }

    // Recorded HELLOs sent again. Anyone who once saw one of the device's HELLOs on the network can
    // send it as often as they like. It can't carry audio, but nor may it keep the device from
    // getting its HELLO_ACKs, push the device's new session out, or have the receiver make a new
    // session for every packet.
    static void ReplayedHellos()
    {
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        byte[] recorded = phone.Hello(Bytes(8, 0x10));
        byte[] fresh = Bytes(8, 0x20);

        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        rig.Now = 5000;
        for (int i = 0; i < 11; i++) {
            rig.Feed(recorded);
        }
        int answered = rig.Sent.Count;
        rig.Now = 5100;
        rig.Feed(phone.Hello(fresh));
        Check("a recorded HELLO sent 11 times at the start of a second: the device's new HELLO still gets its HELLO_ACK",
            rig.Sent.Count == answered + 1 && phone.AckOk(rig.Last, fresh, Swmc.StatusReady));
        Check("and the recorded one gets at most 8 HELLO_ACKs that second", answered <= 8);
        Check("and the device's new session plays", rig.Feed(phone.Session(fresh, rig.Last).Audio(0)));
        int acked = 0;
        for (int second = 6; second < 16; second++) {
            rig.Now = second * 1000;
            for (int i = 0; i < 11; i++) {
                rig.Feed(recorded);
            }
            rig.Now += 300;
            byte[] c = Bytes(8, (byte) (0x60 + 8 * second));
            int sent = rig.Sent.Count;
            rig.Feed(phone.Hello(c));
            if (rig.Sent.Count == sent + 1 && phone.AckOk(rig.Last, c, Swmc.StatusReady) && rig.Feed(phone.Session(c, rig.Last).Audio(0))) {
                acked++;
            }
        }
        Check("the same every second for 10 seconds: each of the device's new HELLOs is answered and plays", acked == 10);

        // Two recorded HELLOs sent straight after the device's own
        Rig evict = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        evict.Now = 1000;
        evict.Feed(phone.Hello(fresh));
        Sender s = phone.Session(fresh, evict.Last);
        evict.Feed(phone.Hello(Bytes(8, 0x31)));
        evict.Feed(phone.Hello(Bytes(8, 0x32)));
        evict.Now = 1050;
        Check("two recorded HELLOs sent right after the device's own don't push its new session out", evict.Feed(s.Audio(0)));

        // Three recorded HELLOs sent in turn, fast
        Rig cycle = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        byte[][] three = { phone.Hello(Bytes(8, 0x41)), phone.Hello(Bytes(8, 0x42)), phone.Hello(Bytes(8, 0x43)) };
        cycle.Now = 1000;
        int made = cycle.RandomCalls;
        for (int i = 0; i < 300; i++) {
            cycle.Now += 5;
            cycle.Feed(three[i % 3]);
        }
        made = cycle.RandomCalls - made;
        Check("three recorded HELLOs sent in turn for 1.5 s make 2 sessions, not one a packet (" + made + ")", made == 2);

        // HELLOs from the device's earlier streams, which this receiver has seen: they can't push out
        // anything, so the device's new HELLO gets in at once
        Rig earlier = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        byte[][] old = { Bytes(8, 0x51), Bytes(8, 0x52), Bytes(8, 0x53) };
        foreach (byte[] c in old) {
            earlier.Now += 60000;
            earlier.Feed(phone.Hello(c));
            earlier.Feed(phone.Session(c, earlier.Last).Audio(0));
        }
        earlier.Now += 60000;
        earlier.Feed(phone.Hello(old[0]));
        earlier.Feed(phone.Hello(old[1]));
        earlier.Feed(phone.Hello(fresh));
        Sender mine = phone.Session(fresh, earlier.Last);
        Check("the device's new HELLO is answered while HELLOs from its earlier streams wait", phone.AckOk(earlier.Last, fresh, Swmc.StatusReady));
        int sessions = earlier.RandomCalls;
        for (int i = 0; i < 50; i++) {
            earlier.Now += 5;
            earlier.Feed(phone.Hello(old[i % 3]));
        }
        Check("sending those again and again makes no new sessions (" + (earlier.RandomCalls - sessions) + ")", earlier.RandomCalls == sessions);
        Check("and can't push the device's session out", earlier.Feed(mine.Audio(0)));

        // After the receiver restarts it has seen none of them. Four recorded HELLOs, two every 5 ms,
        // against the device asking every 250 ms: it gets in within about a second for each
        Rig restarted = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        byte[][] four = { phone.Hello(Bytes(8, 0x71)), phone.Hello(Bytes(8, 0x72)), phone.Hello(Bytes(8, 0x73)), phone.Hello(Bytes(8, 0x74)) };
        Sender got = null;
        long playedAt = -1;
        int next = 0;
        for (long t = 0; t <= 10000 && playedAt < 0; t += 5) {
            restarted.Now = t;
            restarted.Feed(four[next++ % 4]);
            restarted.Feed(four[next++ % 4]);
            if (got != null) {
                if (restarted.Feed(got.Audio(t))) {
                    playedAt = t;
                }
                got = null;
            }
            if (t % 250 == 0) {
                int sent = restarted.Sent.Count;
                restarted.Feed(phone.Hello(fresh));
                if (restarted.Sent.Count == sent + 1 && phone.AckOk(restarted.Last, fresh, Swmc.StatusReady)) {
                    got = phone.Session(fresh, restarted.Last);
                }
            }
        }
        Check("four recorded HELLOs the receiver hasn't seen, sent nonstop: the device's session plays within 5 s (" + playedAt + " ms)",
            playedAt >= 0 && playedAt <= 5000);
        Check("nothing threw", rig.Endpoint.Errors + evict.Endpoint.Errors + cycle.Endpoint.Errors + earlier.Endpoint.Errors + restarted.Endpoint.Errors == 0);
    }

    static IPAddress LinkLocal(int i)
    {
        byte[] b = new byte[16];
        b[0] = 0xfe;
        b[1] = 0x80;
        b[12] = (byte) (i >> 24);
        b[13] = (byte) (i >> 16);
        b[14] = (byte) (i >> 8);
        b[15] = (byte) i;
        return new IPAddress(b);
    }

    // Faked source addresses: a source address costs nothing to fake, and link-local ones get past
    // the firewall rule and the home-network filter. However many come, the PC shows one
    // notification of each kind, its memory stays bounded, and the other warnings' limits hold.
    static void Floods()
    {
        Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
        byte[] vban = Swmc.Ascii("VBAN");
        Random r = new Random(3);
        byte[] id = new byte[8], context = new byte[8];
        for (int i = 0; i < 20000; i++) {
            rig.Now += 10;
            rig.From = new IPEndPoint(LinkLocal(i), 40000);
            rig.Feed(vban);
            byte[] hello = new byte[Swmc.HelloLength];
            r.NextBytes(hello);
            r.NextBytes(id);
            r.NextBytes(context);
            Swmc.WriteHeader(hello, Swmc.THello, id, context);
            rig.Feed(hello);
            rig.From = new IPEndPoint(new IPAddress(new byte[] { 203, 0, (byte) (i >> 8), (byte) i }), 40000);
            rig.Feed(vban);
        }
        Check("20000 packets of each kind from 20000 faked addresses (unencrypted, unpaired, not home): one notification of each ("
            + rig.Notices.Count + ")", rig.Notices.Count == 3 && rig.Endpoint.Unencrypted == 20000 && rig.Endpoint.Unpaired == 20000);
        Check("and at most 256 more lines for --console (" + (rig.Reports.Count - rig.Notices.Count) + ")", rig.Reports.Count - rig.Notices.Count <= 256);
        rig.Now += SecureMicEndpoint.WarnMs;
        rig.From = new IPEndPoint(LinkLocal(-1), 40000);
        rig.Feed(vban);
        Check("10 minutes later the next notification says there were other addresses",
            rig.Notices.Count == 4 && rig.Notices[3].Contains("Ignored unencrypted microphone audio from " + LinkLocal(-1) + " and ")
            && rig.Notices[3].Contains(" other addresses."));

        // A second address after the first: a line for --console, not another notification
        Rig two = new Rig(null);
        two.Feed(VbanPacket(1, 0));
        two.From = new IPEndPoint(IPAddress.Parse("192.168.1.21"), 40000);
        two.Feed(VbanPacket(2, 0));
        Check("unencrypted audio from a second address soon after: no second notification, a line for --console",
            two.Notices.Count == 1 && two.ReportsWith("from 192.168.1.21") == 1);
        two.Feed(VbanPacket(3, 0));
        Check("and that address's line isn't repeated", two.ReportsWith("from 192.168.1.21") == 1);

        // The other warnings neither drown in a flood of addresses nor get reset by one
        byte[] idB = Swmc.Hex("b1b2b3b4b5b6b7b8");
        byte[] keyB = Bytes(32, 0x80);
        Phone a = new Phone(DeviceId, PairKeyFixed), b = new Phone(idB, keyB);
        Rig floor = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Phone A"), Pairing(idB, keyB, "Tablet B"));
        for (int i = 0; i < 1000; i++) {
            floor.From = new IPEndPoint(LinkLocal(i), 40000);
            floor.Feed(vban);
        }
        floor.From = new IPEndPoint(IPAddress.Parse("192.168.1.20"), 40000);
        floor.Feed(a.Hello(ClientRandom));
        Sender sa = a.Session(ClientRandom, floor.Last);
        floor.Feed(b.Hello(ClientRandom));
        Sender sb = b.Session(ClientRandom, floor.Last);
        floor.Feed(sa.Audio(0));
        floor.Feed(sb.Audio(0));
        Check("after a flood of addresses, \"busy\" is still said", floor.Notices.Count == 2 && floor.ReportsWith("ignoring Tablet B") == 1);
        for (int i = 1000; i < 2000; i++) {
            floor.From = new IPEndPoint(LinkLocal(i), 40000);
            floor.Feed(vban);
        }
        floor.From = new IPEndPoint(IPAddress.Parse("192.168.1.20"), 40000);
        floor.Now = 1000;
        floor.Feed(sa.Audio(1));
        floor.Feed(sb.Audio(1));
        Check("and another flood doesn't reset its limit: said once", floor.ReportsWith("ignoring Tablet B") == 1);
        Check("nothing threw", rig.Endpoint.Errors + two.Endpoint.Errors + floor.Endpoint.Errors == 0);
    }

    // What waits for the icon: however many reports come from the receive thread, one call waits
    // for the UI thread, the status is the latest, and notifications come a few seconds apart
    static void Queue()
    {
        ReportQueue q = new ReportQueue();
        int posts = 0;
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.Length; t++) {
            int n = t;
            threads[t] = new Thread(() => {
                for (int i = 0; i < 5000; i++) {
                    if (q.Add(i % 2 == 0 ? "status " + n + " " + i : "note " + n + " " + i, i % 2 == 1)) {
                        Interlocked.Increment(ref posts);
                    }
                }
            });
            threads[t].Start();
        }
        foreach (Thread t in threads) {
            t.Join();
        }
        Check("20000 reports from 4 threads ask for one call to the UI thread (" + posts + ")", posts == 1);
        string status = q.TakeStatus();
        Check("the status shown is one of the last ones", status != null && status.EndsWith(" 4998"));
        Check("and isn't shown again", q.TakeStatus() == null);
        string first = q.TakeNote(0);
        Check("a notification shows straight away", first != null && first.StartsWith("note "));
        Check("the next only after 4 s", q.TakeNote(ReportQueue.GapMs - 1) == null && q.TakeNote(ReportQueue.GapMs) != null);
        int waiting = 0;
        for (long now = 2 * ReportQueue.GapMs; q.TakeNote(now) != null; now += ReportQueue.GapMs) {
            waiting++;
        }
        Check("at most 4 wait their turn (" + (waiting + 2) + ")", waiting + 2 <= ReportQueue.MaxWaiting);
        Check("after the call, the next report asks for another", q.Add("Receiving the microphone from Pixel", false) && !q.Add("x", true));
        q.TakeStatus();
        q.Add("Paired with Pixel", true);
        q.Add("Paired with Pixel", true);
        long at = 100 * ReportQueue.GapMs;
        Check("the same notification twice while it waits shows once",
            q.TakeNote(at) == "x" && q.TakeNote(at + ReportQueue.GapMs) == "Paired with Pixel" && q.TakeNote(at + 2 * ReportQueue.GapMs) == null);
    }

    // ---------------------------------------------------------------- pairing
    static byte[] Request(byte[] code, byte[] deviceId, byte[] nonce, byte[] point, string name)
    {
        return Swmc.PairRequest(Swmc.CodeKey(code), deviceId, nonce, point, name);
    }

    static bool IsReject(byte[] reply, byte[] request, int reason)
    {
        return reply != null && Swmc.ParsePairReject(reply, reply.Length) == reason && Swmc.ConstantTimeEquals(reply, 8, request, 8, 16);
    }

    static void PairingRules()
    {
        byte[] typo = (byte[]) Code.Clone();
        typo[9] ^= 1;
        byte[] request = Request(Code, DeviceId, PairNonce, PhonePoint(), "Test phone");

        Rig closed = new Rig(null);
        closed.Feed(request);
        Check("a pairing request when the PC isn't pairing gets PAIR_REJECT 1 (25 bytes, echoing the request)",
            closed.Sent.Count == 1 && closed.Last.Length == Swmc.RejectLength && IsReject(closed.Last, request, Swmc.RejectNotPairing));
        Check("and pairs nothing", closed.Endpoint.PairedCount == 0);

        Rig rig = new Rig(null);
        rig.Randoms.Enqueue(Code);
        rig.Endpoint.BeginPairing();
        byte[] wrong = Request(typo, DeviceId, Bytes(8, 1), PhonePoint(), "Test phone");
        rig.Feed(wrong);
        Check("a wrong code gets PAIR_REJECT 2", IsReject(rig.Last, wrong, Swmc.RejectWrongCode));
        rig.Feed(wrong);
        Check("the same wrong request again (the device repeats it) counts once", IsReject(rig.Last, wrong, Swmc.RejectWrongCode) && rig.Endpoint.WrongCodes == 1);
        for (int i = 2; i <= PairingSession.MaxWrongCodes; i++) {
            rig.Now += 100;
            rig.Feed(Request(typo, DeviceId, Bytes(8, (byte) (i * 8)), PhonePoint(), "Test phone"));
        }
        Check("after 10 wrong codes pairing stops", rig.Endpoint.Pairing.State == PairingState.TooManyTries && rig.Endpoint.WrongCodes == 10);
        Check("and the PC says so once", rig.ReportsWith("Pairing stopped after too many wrong codes") == 1);
        rig.Feed(request);
        Check("then even the right code gets PAIR_REJECT 3", IsReject(rig.Last, request, Swmc.RejectExpired) && rig.Endpoint.PairedCount == 0);

        Rig early = new Rig(null);
        early.Randoms.Enqueue(Code);
        early.Endpoint.BeginPairing();
        early.Now = PairingSession.LifetimeMs - 1;
        Check("1 ms before 5 minutes the code still works", early.Endpoint.Pairing.State == PairingState.Open
            && early.Endpoint.Pairing.RemainingMs == 1);
        early.Feed(request);
        Check("and pairs", early.Last != null && Swmc.TypeOf(early.Last, early.Last.Length) == Swmc.TPairAccept && early.Endpoint.PairedCount == 1);

        Rig late = new Rig(null);
        late.Randoms.Enqueue(Code);
        late.Endpoint.BeginPairing();
        late.Now = PairingSession.LifetimeMs;
        late.Feed(request);
        Check("at exactly 5 minutes the code has expired: PAIR_REJECT 3", IsReject(late.Last, request, Swmc.RejectExpired) && late.Endpoint.PairedCount == 0);
        Check("and the window says so", late.Endpoint.Pairing.State == PairingState.Expired);

        // Once only, but the same request is answered the same way for a minute
        Rig once = new Rig(null);
        once.Randoms.Enqueue(Code);
        once.Endpoint.BeginPairing();
        once.Now = 1000;
        once.Feed(request);
        byte[] accept = once.Last;
        PairingStatus done = once.Endpoint.Pairing;
        Check("the right code pairs", Swmc.TypeOf(accept, accept.Length) == Swmc.TPairAccept && done.State == PairingState.Paired
            && done.DeviceName == "Test phone" && done.Address == "192.168.1.20" && Same(done.DeviceId, DeviceId)
            && once.ReportsWith("Paired with Test phone (from 192.168.1.20).") == 1);
        once.Now = 1000 + PairingSession.RepeatMs - 1;
        once.Feed(request);
        Check("the same request again gets the same PAIR_ACCEPT", Same(once.Last, accept) && once.Endpoint.PairedCount == 1);
        once.Now = 1000 + PairingSession.RepeatMs;
        once.Feed(request);
        Check("after a minute the same request gets PAIR_REJECT 1", IsReject(once.Last, request, Swmc.RejectNotPairing) && once.Endpoint.PairedCount == 1);
        byte[] another = Request(Code, Swmc.Hex("0f0e0d0c0b0a0908"), Bytes(8, 0x99), PhonePoint(), "Second phone");
        once.Feed(another);
        Check("another request with the same code gets PAIR_REJECT 3: a code works once", IsReject(once.Last, another, Swmc.RejectExpired)
            && once.Endpoint.Pairing.State == PairingState.UsedTwice);

        Rig cancelled = new Rig(null);
        cancelled.Randoms.Enqueue(Code);
        cancelled.Endpoint.BeginPairing();
        cancelled.Endpoint.CancelPairing();
        cancelled.Feed(request);
        Check("after Cancel the code gets PAIR_REJECT 1", IsReject(cancelled.Last, request, Swmc.RejectNotPairing) && cancelled.Endpoint.PairedCount == 0);

        // Malformed requests: no answer at all
        Rig shape = new Rig(null);
        shape.Randoms.Enqueue(Code);
        shape.Endpoint.BeginPairing();
        byte[] longName = Request(Code, DeviceId, PairNonce, PhonePoint(), new string('x', 80));
        Check("a name longer than 64 bytes is cut to 64 when sent", longName.Length == Swmc.PairRequestLength + 64);
        byte[] lying = (byte[]) request.Clone();
        lying[Swmc.Header + Swmc.PointLength] = 65;
        byte[] compressed = (byte[]) request.Clone();
        compressed[Swmc.Header] = 2;
        byte[] longer = new byte[request.Length + 1];
        Array.Copy(request, longer, request.Length);
        shape.Feed(Swmc.Slice(request, 0, request.Length - 1));
        shape.Feed(lying);
        shape.Feed(compressed);
        shape.Feed(longer);
        Check("short, too long, a wrong name length or a compressed point get no answer", shape.Sent.Count == 0 && shape.Endpoint.Malformed == 4
            && shape.Endpoint.WrongCodes == 0);
        byte[] offCurve = PhonePoint();
        offCurve[64] ^= 1;
        shape.Feed(Request(Code, DeviceId, PairNonce, offCurve, "Broken"));
        Check("the right code with a point that isn't on P-256 gets no answer and pairs nothing",
            shape.Sent.Count == 0 && shape.Endpoint.PairedCount == 0 && shape.Endpoint.Errors == 0);
        Check("and pairing stays open", shape.Endpoint.Pairing.State == PairingState.Open);
        byte[] zeros = Point(new byte[32], new byte[32]);
        shape.Feed(Request(Code, DeviceId, PairNonce, zeros, "Zero"));
        Check("nor with the point (0, 0)", shape.Sent.Count == 0 && shape.Endpoint.PairedCount == 0 && shape.Endpoint.Errors == 0);
        byte[] control = Request(Code, DeviceId, Bytes(8, 0x55), PhonePoint(), "Evil\u202e\r\nname\t\u0007 phone ");
        shape.Feed(control);
        MicPairing cleaned = shape.Store.Find(DeviceId);
        Check("a name's control and direction characters are taken out", cleaned != null && cleaned.Name == "Evil name phone");
    }

    // Someone who reads the code off the screen (a screen share, a stream that isn't encrypted) can
    // pair before the user does. When the user's own device then uses the code too, that shows the
    // code was seen: the first pairing is undone and the user is told.
    static void CodeUsedTwice()
    {
        byte[] other = Request(Code, Swmc.Hex("0f0e0d0c0b0a0908"), Bytes(8, 0x99), PhonePoint(), "Pixel 8");
        byte[] mine = Request(Code, DeviceId, PairNonce, PhonePoint(), "Pixel 8");
        Rig rig = new Rig(null);
        rig.Randoms.Enqueue(Code);
        rig.Endpoint.BeginPairing();
        rig.Feed(other);
        Phone first = new Phone(Swmc.Hex("0f0e0d0c0b0a0908"), rig.Store.Find(Swmc.Hex("0f0e0d0c0b0a0908")).Key);
        Check("whoever uses the code first pairs", Swmc.TypeOf(rig.Last, rig.Last.Length) == Swmc.TPairAccept && rig.Endpoint.PairedCount == 1);
        rig.Now = 1000;
        byte[] typo = (byte[]) Code.Clone();
        typo[0] ^= 1;
        rig.Feed(Request(typo, DeviceId, Bytes(8, 0x42), PhonePoint(), "Pixel 8"));
        Check("a request with a wrong code afterwards changes nothing (it can't undo a pairing)", rig.Endpoint.PairedCount == 1);
        rig.Feed(other);
        Check("the same request again isn't a second use", Swmc.TypeOf(rig.Last, rig.Last.Length) == Swmc.TPairAccept && rig.Endpoint.PairedCount == 1);
        rig.Now = 40000;
        rig.Feed(mine);
        Check("another device using the same code undoes the first pairing", rig.Endpoint.PairedCount == 0 && rig.Store.Devices.Count == 0);
        Check("and doesn't pair either: PAIR_REJECT 3, get a new code", IsReject(rig.Last, mine, Swmc.RejectExpired));
        Check("the PC says the code was used twice, once", rig.Notices.Count == 2 && rig.ReportsWith("The pairing code was used twice") == 1
            && rig.Endpoint.Pairing.State == PairingState.UsedTwice && rig.Endpoint.Pairing.DeviceName == "Pixel 8");
        int sent = rig.Sent.Count;
        rig.Feed(first.Hello(ClientRandom));
        Check("the first device's HELLO gets no answer now", rig.Sent.Count == sent && rig.Endpoint.Unpaired == 1);
        rig.Feed(other);
        rig.Feed(mine);
        Check("and the code stays used up", IsReject(rig.Last, mine, Swmc.RejectExpired) && rig.Endpoint.PairedCount == 0);

        // Also after the window was closed, as long as no new code was made
        Rig closed = new Rig(null);
        closed.Randoms.Enqueue(Code);
        closed.Endpoint.BeginPairing();
        closed.Feed(mine);
        closed.Endpoint.CancelPairing();
        closed.Now = PairingSession.LifetimeMs + 60000;
        closed.Feed(other);
        Check("someone using the code after the user, even after the window closed, undoes the pairing too",
            closed.Endpoint.PairedCount == 0 && IsReject(closed.Last, other, Swmc.RejectExpired));
        Check("nothing threw", rig.Endpoint.Errors + closed.Endpoint.Errors == 0);
    }

    // The whole flow with fresh keys: pair, then HELLO and audio under the new key
    static void PairingFlow()
    {
        Rig rig = new Rig(null);
        rig.Endpoint.NewPairingKey = () => Swmc.NewKey();
        string shown = rig.Endpoint.BeginPairing();
        byte[] code = Swmc.DecodeCode(shown.ToLowerInvariant().Replace("-", " "));
        byte[] codeKey = Swmc.CodeKey(code);
        byte[] id = Swmc.Hex("c1c2c3c4c5c6c7c8"), nonce = Bytes(8, 0x41);
        using (EcdhKey mine = Swmc.NewKey()) {
            byte[] myPoint = Swmc.OwnPoint(mine);
            rig.Feed(Swmc.PairRequest(codeKey, id, nonce, myPoint, "Pixel"));
            byte[] accept = rig.Last;
            int nameLength;
            Check("the device gets a well-formed PAIR_ACCEPT with a good MAC", accept != null && Swmc.ParsePairAccept(accept, accept.Length, out nameLength)
                && Swmc.MacOk(codeKey, accept, accept.Length) && Swmc.ConstantTimeEquals(accept, 8, id, 0, 8) && Swmc.ConstantTimeEquals(accept, 16, nonce, 0, 8));
            byte[] receiverPoint = Swmc.Slice(accept, Swmc.Header, Swmc.PointLength);
            byte[] key = Swmc.PairKey(codeKey, Swmc.EcdhHash(mine, receiverPoint, 0), id, nonce, myPoint, receiverPoint);
            int confirmAt = Swmc.Header + Swmc.PointLength + 8 + 1 + accept[Swmc.Header + Swmc.PointLength + 8];
            Check("its confirmation matches the key the device worked out", Swmc.ConstantTimeEquals(Swmc.Confirm(key), 0, accept, confirmAt, 16));
            Check("the PAIR_ACCEPT names the PC and the receiver id", Swmc.CleanName(accept, Swmc.Header + Swmc.PointLength + 9, 7, "") == "TEST-PC"
                && Swmc.ConstantTimeEquals(accept, Swmc.Header + Swmc.PointLength, ReceiverId, 0, 8));
            Check("both sides have the same key", Same(rig.Store.Find(id).Key, key));
            Phone phone = new Phone(id, key);
            rig.Feed(phone.Hello(ClientRandom));
            Check("the new device's HELLO is answered", phone.AckOk(rig.Last, ClientRandom, Swmc.StatusReady));
            Check("and its audio plays", rig.Feed(phone.Session(ClientRandom, rig.Last).Audio(0)) && rig.Sender == "Pixel");

            // Pairing again (same device id, new key) replaces the old key
            rig.Endpoint.BeginPairing();
            byte[] code2 = Swmc.DecodeCode(rig.Endpoint.Pairing.Code);
            using (EcdhKey again = Swmc.NewKey()) {
                byte[] point2 = Swmc.OwnPoint(again);
                byte[] codeKey2 = Swmc.CodeKey(code2);
                rig.Feed(Swmc.PairRequest(codeKey2, id, nonce, point2, "Pixel"));
                byte[] accept2 = rig.Last;
                byte[] rp2 = Swmc.Slice(accept2, Swmc.Header, Swmc.PointLength);
                byte[] key2 = Swmc.PairKey(codeKey2, Swmc.EcdhHash(again, rp2, 0), id, nonce, point2, rp2);
                int sent = rig.Sent.Count;
                rig.Feed(phone.Hello(Bytes(8, 0x71)));
                Check("after pairing again the old key's HELLO is refused", rig.Sent.Count == sent);
                Phone renewed = new Phone(id, key2);
                rig.Feed(renewed.Hello(Bytes(8, 0x72)));
                Check("and the new key's is answered", renewed.AckOk(rig.Last, Bytes(8, 0x72), Swmc.StatusReady) && rig.Store.Devices.Count == 1);
            }
        }
    }

    // ---------------------------------------------------------------- the paired devices file
    static void Store()
    {
        string path = Path.Combine(temp, "pairings.dat");
        Random fill = new Random(11);
        Action<byte[]> random = b => fill.NextBytes(b);
        MicPairingStore s = new MicPairingStore(path);
        s.Load(random);
        Check("a missing file is an empty store with a new receiver id", s.Devices.Count == 0 && s.ReceiverId.Length == 8 && s.Problems.Count == 0
            && !File.Exists(path));
        byte[] keyA = Bytes(32, 0x11), keyB = Bytes(32, 0x51);
        byte[] idB = Swmc.Hex("b1b2b3b4b5b6b7b8");
        s.Put(Pairing(DeviceId, keyA, "Pixel 8"));
        s.Put(Pairing(idB, keyB, "Tab & S9 \u00fcber \U0001F3AE"));
        string text = File.ReadAllText(path, Encoding.UTF8);
        Check("the file is written, with names and without the keys in the clear", File.Exists(path) && text.Contains("Pixel 8")
            && !text.Contains(Swmc.Hex(keyA)) && !text.Contains(Convert.ToBase64String(keyA)) && !text.Contains(Swmc.Hex(keyB)));
        Check("no temporary file is left", !File.Exists(path + ".new"));
        MicPairingStore back = new MicPairingStore(path);
        back.Load(random);
        MicPairing a = back.Find(DeviceId), bb = back.Find(idB);
        Check("it reads back: ids, keys, names, dates, receiver id", back.Devices.Count == 2 && a != null && bb != null && Same(a.Key, keyA)
            && Same(bb.Key, keyB) && a.Name == "Pixel 8" && bb.Name == "Tab & S9 \u00fcber \U0001F3AE" && a.PairedAt == Epoch
            && a.LastSeen == DateTime.MinValue && Same(back.ReceiverId, s.ReceiverId) && back.Problems.Count == 0);

        DateTime t1 = Epoch.AddHours(1);
        back.Touch(a, t1);
        Check("the first use is written straight away", File.ReadAllText(path).Contains("2026-10-23T06:00:00Z"));
        back.Touch(a, t1.AddMinutes(5));
        Check("later uses not for 10 minutes", !File.ReadAllText(path).Contains("2026-10-23T06:05:00Z"));
        back.Touch(a, t1.AddMinutes(10));
        Check("then written again", File.ReadAllText(path).Contains("2026-10-23T06:10:00Z"));

        Check("removing a device", back.Remove(DeviceId) && !back.Remove(DeviceId));
        MicPairingStore third = new MicPairingStore(path);
        third.Load(random);
        Check("is kept", third.Devices.Count == 1 && third.Find(idB) != null);

        // Damaged lines are left out and reported; the rest still loads
        string otherEntropy = Convert.ToBase64String(KeyProtection.Protect(keyA, Swmc.Ascii("other")));
        string shortKey = Convert.ToBase64String(KeyProtection.Protect(new byte[5], Swmc.Ascii("Starwire microphone pairing v1")));
        File.AppendAllText(path, "device\tzz\tAAAA\t2026-10-23T05:00:00Z\t-\tBad id\n"
            + "device\ta0a1a2a3a4a5a6a7\tnot base64!\t2026-10-23T05:00:00Z\t-\tBad key\n"
            + "device\ta0a1a2a3a4a5a6a7\t" + otherEntropy + "\t2026-10-23T05:00:00Z\t-\tOther entropy\n"
            + "device\ta0a1a2a3a4a5a6a7\t" + shortKey + "\t2026-10-23T05:00:00Z\t-\tShort key\n"
            + "device\ta0a1a2a3a4a5a6a7\t" + otherEntropy + "\tyesterday\t-\tBad date\n"
            + "device\ttoo\tfew\n"
            + "receiver\txyz\n"
            + "something a later version writes\tfine\n");
        MicPairingStore damaged = new MicPairingStore(path);
        damaged.Load(random);
        Check("damaged lines are left out and listed (7 of them)", damaged.Devices.Count == 1 && damaged.Problems.Count == 7);
        string idBLine = null;
        foreach (string line in File.ReadAllLines(path)) {
            if (line.StartsWith("device\tb1b2")) {
                idBLine = line;
            }
        }
        File.AppendAllText(path, idBLine + "\n");
        damaged.Load(random);
        Check("the same device twice is listed too", damaged.Devices.Count == 1 && damaged.Problems.Count == 8);

        // A file it can't open is never overwritten
        string before = File.ReadAllText(path);
        using (FileStream locked = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None)) {
            damaged.Load(random);
        }
        Check("a file it couldn't open is reported", damaged.Problems.Count == 1 && damaged.Problems[0].StartsWith("couldn't read"));
        bool refused = false;
        try {
            damaged.Put(Pairing(DeviceId, keyA, "Pixel 8"));
        }
        catch (IOException) {
            refused = true;
        }
        Check("and not overwritten afterwards", refused && File.ReadAllText(path) == before && damaged.Devices.Count == 0);

        // A pairing that can't be saved isn't kept, or confirmed to the device
        string blocker = Path.Combine(temp, "a file");
        File.WriteAllText(blocker, "");
        MicPairingStore nowhere = new MicPairingStore(Path.Combine(Path.Combine(blocker, "folder"), "pairings.dat"));
        nowhere.Load(random);
        Rig rig = new Rig(nowhere);
        rig.Randoms.Enqueue(Code);
        rig.Endpoint.BeginPairing();
        rig.Feed(Request(Code, DeviceId, PairNonce, PhonePoint(), "Test phone"));
        Check("a pairing that can't be saved gets no PAIR_ACCEPT and isn't kept", rig.Sent.Count == 0 && rig.Endpoint.PairedCount == 0
            && nowhere.Devices.Count == 0 && rig.ReportsWith("Couldn't save the pairing with Test phone") == 1);
        Check("and pairing stays open to try again", rig.Endpoint.Pairing.State == PairingState.Open);
        Check("the device repeating its request doesn't repeat the message", Repeat(rig) && rig.ReportsWith("Couldn't save the pairing") == 1);
    }

    static bool Repeat(Rig rig)
    {
        rig.Now += 500;
        rig.Feed(Request(Code, DeviceId, PairNonce, PhonePoint(), "Test phone"));
        return rig.Sent.Count == 0;
    }

    // ---------------------------------------------------------------- fuzz
    static byte[] Mutate(byte[] seed, Random r, byte[] deviceId, byte[] sessionId)
    {
        byte[] m;
        switch (r.Next(7)) {
            case 0:
                m = (byte[]) seed.Clone();
                for (int i = r.Next(1, 5); i > 0 && m.Length > 0; i--) {
                    m[r.Next(m.Length)] ^= (byte) (1 << r.Next(8));
                }
                break;
            case 1:
                m = (byte[]) seed.Clone();
                for (int i = r.Next(1, 9); i > 0 && m.Length > 0; i--) {
                    m[r.Next(m.Length)] = (byte) r.Next(256);
                }
                break;
            case 2:
                m = Swmc.Slice(seed, 0, r.Next(seed.Length));
                break;
            case 3:
                m = new byte[seed.Length + r.Next(1, 65)];
                r.NextBytes(m);
                Array.Copy(seed, m, seed.Length);
                break;
            case 4:
                m = (byte[]) seed.Clone();
                if (m.Length > 5) {
                    m[5] = (byte) r.Next(256);
                }
                break;
            case 5:
                m = (byte[]) seed.Clone();
                if (m.Length > Swmc.Header + Swmc.PointLength) {
                    m[Swmc.Header + Swmc.PointLength] = (byte) r.Next(256);
                }
                break;
            default:
                // Random bytes, often with a v1 header and the paired device's id and session
                m = new byte[r.Next(0, 1500)];
                r.NextBytes(m);
                if (m.Length >= Swmc.Header && r.Next(2) == 0) {
                    Swmc.WriteHeader(m, r.Next(10), deviceId, sessionId);
                }
                else if (m.Length >= 4 && r.Next(4) == 0) {
                    m[0] = (byte) 'V'; m[1] = (byte) 'B'; m[2] = (byte) 'A'; m[3] = (byte) 'N';
                }
                break;
        }
        return m;
    }

    static void Fuzz()
    {
        Dictionary<string, string> v = MakeVectors();
        Phone phone = new Phone(DeviceId, PairKeyFixed);
        List<byte[]> seeds = new List<byte[]>();
        foreach (string k in new string[] { "pkt.hello", "pkt.hello_ack", "pkt.rehello", "pkt.audio7", "pkt.audio_last", "pkt.pair_request", "pkt.pair_accept" }) {
            seeds.Add(Swmc.Hex(v[k]));
        }
        seeds.Add(Swmc.PairReject(Swmc.Hex(v["pkt.pair_request"]), 2));
        seeds.Add(VbanPacket(1, 0));
        seeds.Add(new byte[0]);

        string[] sources = { "192.168.1.20", "10.1.2.3", "127.0.0.1", "8.8.8.8", "fe80::1", "fd00::5", "2001:db8::1", "::ffff:192.168.0.9", "100.64.0.1" };
        foreach (bool allow in new bool[] { false, true }) {
            Rig rig = new Rig(null, Pairing(DeviceId, PairKeyFixed, "Test phone"));
            rig.Endpoint.AllowUnencrypted = allow;
            rig.Randoms.Enqueue(ServerRandom);
            rig.Feed(phone.Hello(ClientRandom));
            Random r = new Random(allow ? 20261024 : 20261023);
            int played = 0, unchanged = 0;
            for (int i = 0; i < 100000; i++) {
                if (i % 1000 == 0) {
                    rig.Endpoint.BeginPairing();
                }
                byte[] seed = seeds[r.Next(seeds.Count)];
                byte[] m = Mutate(seed, r, DeviceId, ServerRandom);
                if (Same(m, seed)) {
                    unchanged++;
                    continue;
                }
                rig.From = new IPEndPoint(IPAddress.Parse(sources[r.Next(sources.Length)]), r.Next(1, 65536));
                rig.Now += r.Next(0, 40);
                if (rig.Feed(m)) {
                    played++;
                    if (rig.Encrypted) {
                        Console.WriteLine("  fuzz played an encrypted packet: " + Swmc.Hex(m));
                        played += 1000000;
                    }
                }
            }
            if (allow) {
                Check("fuzz with unencrypted allowed: 100000 changed packets, nothing threw, no encrypted audio played (plain "
                    + played + ")", rig.Endpoint.Errors == 0 && played < 1000000);
            }
            else {
                Check("fuzz: 100000 changed or random packets from many addresses, nothing threw, nothing played",
                    rig.Endpoint.Errors == 0 && played == 0);
            }
        }

        Random q = new Random(99);
        bool ok = true;
        const string alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghijklmnopqrstuvwxyzOIL-_ !\u00e9\u0000";
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int n = q.Next(0, 24); n > 0; n--) {
                sb.Append(alphabet[q.Next(alphabet.Length)]);
            }
            string typed = sb.ToString();
            try {
                byte[] got = Swmc.DecodeCode(typed);
                if (got != null) {
                    string norm = typed.ToUpperInvariant().Replace("-", "").Replace(" ", "").Replace('O', '0').Replace('I', '1').Replace('L', '1');
                    if (Swmc.EncodeCode(got).Replace("-", "") != norm) {
                        ok = false;
                    }
                }
            }
            catch (Exception e) {
                Console.WriteLine("  DecodeCode threw on \"" + typed + "\": " + e.Message);
                ok = false;
            }
        }
        Check("fuzz: 20000 typed codes decode without throwing, and only to what was typed", ok);

        ok = true;
        byte[] buffer = new byte[1600];
        for (int i = 0; i < 50000; i++) {
            int length = q.Next(0, buffer.Length);
            q.NextBytes(buffer);
            if (q.Next(3) == 0) {
                Swmc.WriteHeader(buffer, q.Next(8), DeviceId, ServerRandom);
            }
            if (q.Next(5) == 0) {
                buffer[0] = (byte) 'V'; buffer[1] = (byte) 'B'; buffer[2] = (byte) 'A'; buffer[3] = (byte) 'N';
                buffer[4] = (byte) q.Next(0, 32); buffer[7] = 1;
            }
            try {
                int nameLength, samples, channels, rate;
                Swmc.TypeOf(buffer, length);
                Swmc.ParsePairRequest(buffer, length, out nameLength);
                Swmc.ParsePairAccept(buffer, length, out nameLength);
                Swmc.ParsePairReject(buffer, length);
                Swmc.MacOk(phone.AuthKey, buffer, length);
                if (Vban.ParsePcm(buffer, length, out samples, out channels, out rate)
                        && (length != Vban.HeaderSize + samples * channels * 2 || channels > 2 || rate == 0)) {
                    ok = false;
                }
            }
            catch (Exception e) {
                Console.WriteLine("  a parser threw: " + e.Message);
                ok = false;
            }
        }
        Check("fuzz: 50000 random buffers through every packet parser, nothing threw", ok);

        ok = true;
        string path = Path.Combine(temp, "fuzz.dat");
        string[] tokens = { "device", "receiver", "\t", "\n", "\r\n", "#", "a0a1a2a3a4a5a6a7", "zz", "-", "2026-10-23T05:00:00Z", "AAAA", "====", "\u00fc", " " };
        for (int i = 0; i < 300; i++) {
            if (q.Next(3) == 0) {
                byte[] raw = new byte[q.Next(0, 400)];
                q.NextBytes(raw);
                File.WriteAllBytes(path, raw);
            }
            else {
                StringBuilder sb = new StringBuilder();
                for (int n = q.Next(0, 60); n > 0; n--) {
                    sb.Append(tokens[q.Next(tokens.Length)]);
                }
                File.WriteAllText(path, sb.ToString());
            }
            try {
                MicPairingStore s = new MicPairingStore(path);
                s.Load(b => q.NextBytes(b));
                if (s.ReceiverId == null || s.ReceiverId.Length != 8) {
                    ok = false;
                }
            }
            catch (Exception e) {
                Console.WriteLine("  loading a damaged file threw: " + e.Message);
                ok = false;
            }
        }
        Check("fuzz: 300 damaged paired devices files load without throwing", ok);
    }

#if !STARWIRE_LINUX
    // ---------------------------------------------------------------- for e2e-loopback.ps1
    // (Windows only: it records through waveIn. The Linux tests record with pw-record.)
    [StructLayout(LayoutKind.Sequential)]
    struct WaveFormat { public short Tag, Channels; public int Rate, BytesPerSecond; public short BlockAlign, Bits, Size; }
    [StructLayout(LayoutKind.Sequential)]
    struct WaveHeader { public IntPtr Data; public int Length, Recorded; public IntPtr User; public int Flags, Loops; public IntPtr Next, Reserved; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct WaveInCaps { public short Mid, Pid; public int DriverVersion; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Name; public int Formats; public short Channels, Reserved; }
    [DllImport("winmm.dll")] static extern int waveInGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveInGetDevCapsW")] static extern int waveInGetDevCaps(IntPtr id, out WaveInCaps caps, int size);
    [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle, int id, ref WaveFormat format, IntPtr callback, IntPtr instance, int flags);
    [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);

    // The level of a sine at `frequency` in s[from, from + n), as a fraction of full scale
    static double Goertzel(short[] s, int from, int n, double frequency)
    {
        double w = 2 * Math.PI * frequency / 48000, c = 2 * Math.Cos(w), a = 0, b = 0;
        for (int i = 0; i < n; i++) {
            double x = s[from + i] / 32768.0 + c * a - b;
            b = a;
            a = x;
        }
        return Math.Sqrt(Math.Max(0, a * a + b * b - c * a * b)) * 2 / n;
    }

    static int Tone(string[] args)
    {
        int seconds = int.Parse(args[1]);
        string input = args[2];
        double frequency = double.Parse(args[3], System.Globalization.CultureInfo.InvariantCulture);
        double level = double.Parse(args[4], System.Globalization.CultureInfo.InvariantCulture);
        int id = -1;
        for (int i = 0; i < waveInGetNumDevs() && id < 0; i++) {
            WaveInCaps caps;
            if (waveInGetDevCaps(new IntPtr(i), out caps, Marshal.SizeOf(typeof(WaveInCaps))) == 0
                    && caps.Name.StartsWith(input, StringComparison.OrdinalIgnoreCase)) {
                id = i;
            }
        }
        if (id < 0) {
            Console.WriteLine("TONE no-input " + input);
            return 2;
        }
        WaveFormat f = new WaveFormat();
        f.Tag = 1; f.Channels = 1; f.Rate = 48000; f.BytesPerSecond = 96000; f.BlockAlign = 2; f.Bits = 16;
        IntPtr handle;
        if (waveInOpen(out handle, id, ref f, IntPtr.Zero, IntPtr.Zero, 0) != 0) {
            Console.WriteLine("TONE no-open " + input);
            return 3;
        }
        int bytes = seconds * 96000;
        IntPtr data = Marshal.AllocHGlobal(bytes);
        WaveHeader h = new WaveHeader();
        h.Data = data;
        h.Length = bytes;
        int size = Marshal.SizeOf(typeof(WaveHeader));
        IntPtr hp = Marshal.AllocHGlobal(size);
        Marshal.StructureToPtr(h, hp, false);
        waveInPrepareHeader(handle, hp, size);
        waveInAddBuffer(handle, hp, size);
        waveInStart(handle);
        Thread.Sleep(seconds * 1000 + 300);
        waveInReset(handle);
        h = (WaveHeader) Marshal.PtrToStructure(hp, typeof(WaveHeader));
        short[] s = new short[h.Recorded / 2];
        Marshal.Copy(data, s, 0, s.Length);
        waveInUnprepareHeader(handle, hp, size);
        waveInClose(handle);
        Marshal.FreeHGlobal(hp);
        Marshal.FreeHGlobal(data);
        const int window = 4800;
        int heard = 0, windows = 0;
        double strongest = 0;
        for (int at = 0; at + window <= s.Length; at += window) {
            double a = Goertzel(s, at, window, frequency);
            strongest = Math.Max(strongest, a);
            windows++;
            if (a >= level) {
                heard++;
            }
        }
        Console.WriteLine("TONE heard=" + heard + " windows=" + windows + " strongest=" + strongest.ToString("0.00000", System.Globalization.CultureInfo.InvariantCulture));
        return 0;
    }
#endif

    static int Main(string[] args)
    {
#if !STARWIRE_LINUX
        if (args.Length == 5 && args[0] == "tone") {
            return Tone(args);
        }
#endif
        if (args.Length != 1) {
            Console.WriteLine("SecureMicTests.exe <swmc-v1-vectors.txt>");
            return 2;
        }
        temp = Path.Combine(Path.GetTempPath(), "starwire-mic-tests-" + System.Diagnostics.Process.GetCurrentProcess().Id);
        Directory.CreateDirectory(temp);
        Console.WriteLine("SecureMic tests, " + (IntPtr.Size * 8) + "-bit, CLR " + Environment.Version);
        try {
            KnownAnswers();
            SharedVectors(args[0]);
            Tampering();
            Replays();
            Codes();
            Sessions();
            Rehellos();
            Floor();
            Refusals();
            ReplayedHellos();
            Floods();
            Queue();
            PairingRules();
            CodeUsedTwice();
            PairingFlow();
            Store();
            Fuzz();
        }
        catch (Exception e) {
            Check("no test threw (" + e + ")", false);
        }
        finally {
            try {
                Directory.Delete(temp, true);
            }
            catch (IOException) {
            }
        }
        Console.WriteLine((failed == 0 ? "All " + passed + " checks passed" : failed + " of " + (passed + failed) + " checks FAILED")
            + " (" + (IntPtr.Size * 8) + "-bit)");
        return failed == 0 ? 0 : 1;
    }
}
