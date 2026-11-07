// Tests for the Linux receiver's own parts (GPL-3.0, part of Starwire): the format it asks PipeWire
// for, the jitter buffer's rules with a fake clock, the AES-GCM and key storage that stand in for
// Windows' CNG and DPAPI, and the paired devices file's permissions. Deterministic: no sockets, no
// PipeWire, no waiting. SecureMicTests (the Windows tests, built for Linux) covers the format and the
// pairing; run-tests.sh runs both, then a real receiver against tests/FakePhone.cs.
//
//   LinuxTests     runs every test; exits with 1 if any fails
//
// It also checks what the app (app/) and the commands make of the receiver's answers: PairingModel
// and TrayModel, which have no Avalonia in them.

using System;
using System.IO;
using System.Security.Cryptography;

static class LinuxTests
{
    static int passed, failed;

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

    // ---------------------------------------------------------------- the format
    // What spa_format_audio_raw_build gives in C for S16_LE, 48000 Hz, one channel (MONO), from
    // PipeWire 1.6.10's headers
    const string ReferencePod =
        "a00000000f00000003000400030000000100000000000000040000000300000001000000000000000200000000000000"
        + "040000000300000001000000000000000100010000000000040000000300000003010000000000000300010000000000"
        + "040000000400000080bb00000000000004000100000000000400000004000000010000000000000005000100000000000c0000000d00000004000000030000000200000000000000";

    static void Format()
    {
        Check("the format pod is what PipeWire's own builder makes (48000 Hz mono S16LE)", Swmc.Hex(FormatPod.MonoS16(48000)) == ReferencePod);
        byte[] at44 = FormatPod.MonoS16(44100);
        Check("and only the rate changes with the rate", BitConverter.ToInt32(at44, 8 + 8 + 3 * 24 + 16) == 44100
            && Swmc.Hex(at44).Remove(208, 8) == ReferencePod.Remove(208, 8));
    }

    // ---------------------------------------------------------------- the jitter buffer
    static byte[] Packet(int samples, int channels, Func<int, int, short> value)
    {
        byte[] p = new byte[28 + samples * channels * 2];
        for (int i = 0; i < samples; i++) {
            for (int c = 0; c < channels; c++) {
                short v = value(i, c);
                p[28 + (i * channels + c) * 2] = (byte) v;
                p[28 + (i * channels + c) * 2 + 1] = (byte) (v >> 8);
            }
        }
        return p;
    }

    static void Ring()
    {
        int counter = 0;
        // 256 samples a packet, numbered so order can be checked
        Func<byte[]> next = () => Packet(256, 1, (i, c) => (short) (counter++ % 30000));
        JitterRing r = new JitterRing(48000);
        short[] quantum = new short[480];
        long now = 0;

        r.Write(next(), 28, 256, 1, now);
        Check("nothing plays before 40 ms is buffered", r.Take(quantum, now) == 0);
        for (int i = 0; i < 8; i++) {
            r.Write(next(), 28, 256, 1, now);
        }
        // 9 packets, 2304 samples: more than 40 ms (1920), but not 40 ms on top of the quantum (2400)
        Check("nor before 40 ms on top of the quantum PipeWire takes", r.Take(quantum, now) == 0);
        r.Write(next(), 28, 256, 1, now);
        int got = r.Take(quantum, now);
        bool inOrder = got == 480;
        for (int i = 0; i < got; i++) {
            inOrder &= quantum[i] == i;
        }
        Check("then it plays, in order, a quantum at a time", inOrder);

        // Stereo becomes one channel, the two averaged
        JitterRing stereo = new JitterRing(48000);
        stereo.Take(quantum, 0);   // an app is recording
        for (int i = 0; i < 10; i++) {
            stereo.Write(Packet(256, 2, (s, c) => (short) (c == 0 ? 1000 : 3000)), 28, 256, 2, 0);
        }
        stereo.Take(quantum, 0);
        Check("two channels are made one", quantum[0] == 2000 && quantum[479] == 2000);

        // Never more than 120 ms behind
        JitterRing full = new JitterRing(48000);
        full.Take(new short[0], 0);   // an app is recording (asking for nothing yet)
        int kept = 0;
        for (int i = 0; i < 40; i++) {
            if (full.Write(next(), 28, 256, 1, 0)) {
                kept++;
            }
        }
        Check("it never holds more than 120 ms (a burst is dropped)", kept == 5760 / 256 && full.Dropped == 40 - kept && full.Buffered <= 5760);

        // Running dry builds up again
        JitterRing dry = new JitterRing(48000);
        dry.Take(quantum, 0);
        for (int i = 0; i < 10; i++) {
            dry.Write(next(), 28, 256, 1, 0);
        }
        int taken = 0;
        for (int i = 0; i < 6; i++) {
            taken += dry.Take(quantum, 10 * i);
        }
        Check("running dry is counted and it builds up again", taken == 2560 && dry.RanDry == 1 && dry.Take(quantum, 60) == 0);
        Check("(it isn't a gap yet: the stream may have ended)", dry.Gaps == 0);
        dry.Write(next(), 28, 256, 1, 75);
        Check("(not playing again on one packet)", dry.Take(quantum, 76) == 0);
        int gapsSeen = 0, trimsSeen = 0, newGaps, newTrims, stall;
        dry.TakeNews(ref gapsSeen, ref trimsSeen, out newGaps, out newTrims, out stall);
        Check("audio coming again makes it a gap, as long as no packet came (75 ms)", dry.Gaps == 1 && newGaps == 1 && stall == 75 && newTrims == 0);
        dry.TakeNews(ref gapsSeen, ref trimsSeen, out newGaps, out newTrims, out stall);
        Check("(and it's news only once)", newGaps == 0 && stall == 0);

        // A stream that ends runs dry too, but that isn't a gap
        JitterRing ends = new JitterRing(48000);
        ends.Take(quantum, 0);
        for (int i = 0; i < 10; i++) {
            ends.Write(next(), 28, 256, 1, 0);
        }
        for (int i = 0; i < 6; i++) {
            ends.Take(quantum, 10 * i);
        }
        ends.Take(quantum, 3100);
        ends.Write(next(), 28, 256, 1, 9000);
        Check("a stream that ends isn't counted as a gap, nor the next one starting", ends.RanDry == 1 && ends.Gaps == 0);

        // A steady stream, as fast as it's played: plays through with nothing skipped or run dry
        JitterRing steady = new JitterRing(48000);
        int written = 0, played = 0;
        bool silentGap = false;
        for (long ms = 0; ms < 20000; ms++) {
            // 48 samples a ms: a 256-sample packet about every 5.33 ms, a 480-sample quantum every 10
            while (written < (ms + 1) * 48) {
                steady.Write(next(), 28, 256, 1, ms);
                written += 256;
            }
            if (ms % 10 == 9) {
                int n = steady.Take(quantum, ms);
                silentGap |= played > 0 && n < 480;
                played += n;
            }
        }
        Check("a steady stream plays without a gap, skip or drop", !silentGap && steady.RanDry == 0 && steady.Trims == 0 && steady.Dropped == 0);

        // A phone whose clock runs fast fills the buffer up; 10 ms is skipped now and then
        JitterRing fast = new JitterRing(48000);
        written = 0;
        int quantaTaken = 0;
        for (long ms = 0; ms < 120000; ms++) {
            // 0.1% fast: 48.048 samples a ms
            while (written < (ms + 1) * 48048 / 1000) {
                fast.Write(next(), 28, 256, 1, ms);
                written += 256;
            }
            if (ms % 10 == 9) {
                fast.Take(quantum, ms);
                quantaTaken++;
            }
        }
        Check("a phone 0.1% fast is trimmed back, 10 ms at a time (" + fast.Trims + " trims in 2 minutes)",
            fast.Trims >= 8 && fast.Trims <= 16 && fast.Buffered < 48 * 80 && fast.Dropped == 0 && fast.RanDry == 0);

        // A quantum longer than 40 ms (2048 samples, 42.7 ms): 40 ms of 256-sample packets is exactly
        // one such quantum, so starting on 40 ms would leave nothing to spare, and one packet
        // coming 3 ms late would leave a gap. Here the packets come just in time for each quantum
        // (time counted in samples), and packet 407, the last one a quantum needs, is 3 ms late.
        short[] bigQuantum = new short[2048];
        JitterRing big = new JitterRing(48000);
        played = 0;
        silentGap = false;
        int sent = 0;
        for (long t = 1; t <= 48000 * 10; t++) {
            while (256L * (sent + 1) + (sent == 407 ? 144 : 0) <= t) {
                big.Write(next(), 28, 256, 1, t / 48);
                sent++;
            }
            if (t % 2048 == 0) {
                int n = big.Take(bigQuantum, t / 48);
                silentGap |= played > 0 && n < 2048;
                played += n;
            }
        }
        Check("a 2048-sample quantum plays without running dry when a packet is late (ran dry " + big.RanDry + ")",
            played > 48000 * 9 && !silentGap && big.RanDry == 0 && big.Dropped == 0);

        // The first test with a real phone: PipeWire's quantum was 1024 samples (21 ms) and Wi-Fi
        // sometimes held packets back for longer than the buffer rode out (about 40 ms, as on
        // Windows). Here packets come just in time for each quantum, then none for 50 ms right
        // after one is taken (they all come at once after that).
        short[] phoneQuantum = new short[1024];
        JitterRing stall50 = new JitterRing(48000);
        played = 0;
        silentGap = false;
        sent = 0;
        for (long t = 1; t <= 48000 * 10; t++) {
            while (true) {
                long due = 256L * (sent + 1);
                if (due > 1024 * 235 && due <= 1024 * 235 + 2400) {
                    due = 1024 * 235 + 2400;
                }
                if (due > t) {
                    break;
                }
                stall50.Write(next(), 28, 256, 1, t / 48);
                sent++;
            }
            if (t % 1024 == 0) {
                int n = stall50.Take(phoneQuantum, t / 48);
                silentGap |= played > 0 && n < 1024;
                played += n;
            }
        }
        Check("with a 1024-sample quantum, 50 ms with no packets leaves no gap (gaps " + stall50.Gaps + ")",
            played > 48000 * 9 && !silentGap && stall50.Gaps == 0 && stall50.RanDry == 0 && stall50.Dropped == 0);
        Check("and that wait is counted, as 40 ms or more but not 60", stall50.Waits(0) == 1 && stall50.Waits(1) == 1 && stall50.Waits(2) == 0);

        // The phone stops: after 3 seconds what's left goes
        JitterRing idle = new JitterRing(48000);
        idle.Take(quantum, 0);
        for (int i = 0; i < 4; i++) {
            idle.Write(next(), 28, 256, 1, 0);
        }
        Check("what's left is kept while it might go on", idle.Buffered == 1024 && idle.Take(quantum, 2000) == 0 && idle.Buffered == 1024);
        Check("and forgotten after 3 seconds of nothing", idle.Take(quantum, 3001) == 0 && idle.Buffered == 0);

        // The second test with a real phone: nothing recorded from the microphone for a while (no
        // app had it open), then Discord did. PipeWire takes nothing while no app records.
        short[] q1024 = new short[1024];
        JitterRing late = new JitterRing(48000);
        late.Take(q1024, 0);   // an app recorded, then stopped; a second later the phone sends
        int lastSample = 0;
        sent = 0;
        for (long t = 1; t <= 48000 * 3; t++) {
            if (48000 + 256L * (sent + 1) <= t) {
                late.Write(next(), 28, 256, 1, t / 48);
                lastSample = (counter - 1) % 30000;
                sent++;
            }
        }
        int startAt = 48 * JitterRing.StartMs + 1024;
        Check("while no app records, only the newest audio is kept (" + late.Buffered + " samples), and none of it counts as dropped",
            late.Buffered <= startAt && late.Buffered > startAt - 256 && late.Dropped == 0 && late.Unheard == sent);
        // The oldest sample kept is the one Buffered samples back from the last
        int oldestKept = (lastSample - late.Buffered + 1 + 30000) % 30000;
        int firstTaken = late.Take(q1024, 3000) == 1024 ? q1024[0] : -1;
        Check("an app that starts recording gets the newest audio, not what came seconds before", firstTaken == oldestKept);
        played = 0;
        silentGap = false;
        for (long t = 48000L * 3 + 1; t <= 48000L * 15; t++) {
            if (48000 + 256L * (sent + 1) <= t) {
                late.Write(next(), 28, 256, 1, t / 48);
                sent++;
            }
            if (t % 1024 == 0) {
                int n = late.Take(q1024, t / 48);
                silentGap |= n < 1024;
                played += n;
            }
        }
        Check("and plays on with no trims, gaps or drops (trims " + late.Trims + ", gaps " + late.Gaps + ", dropped " + late.Dropped + ")",
            !silentGap && late.Trims == 0 && late.Gaps == 0 && late.Dropped == 0);
    }

    // ---------------------------------------------------------------- AES-GCM and keys
    static void Crypto()
    {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }
        using (CngAesGcm aead = new CngAesGcm(key)) {
            byte[] nonce = new byte[12];
            byte[] aad = Swmc.Ascii("header");
            byte[] plain = Swmc.Ascii("the microphone");
            byte[] sealedBox = new byte[plain.Length + 16];
            aead.Seal(nonce, 0, aad, 0, aad.Length, plain, 0, plain.Length, sealedBox, 0);
            byte[] output = new byte[64];
            for (int i = 0; i < output.Length; i++) {
                output[i] = 0x5A;
            }
            sealedBox[3] ^= 1;
            bool refused = aead.Open(nonce, 0, aad, 0, aad.Length, sealedBox, 0, sealedBox.Length, output, 0) == -1;
            bool untouched = true;
            foreach (byte b in output) {
                untouched &= b == 0x5A;
            }
            Check("a tampered packet is refused and the output is left as it was", refused && untouched);
            sealedBox[3] ^= 1;
            int n = aead.Open(nonce, 0, aad, 0, aad.Length, sealedBox, 0, sealedBox.Length, output, 0);
            Check("the packet as sent opens", n == plain.Length && Swmc.Hex(output, 0, n) == Swmc.Hex(plain));
        }

        byte[] entropy = Swmc.Ascii("Starwire microphone pairing v1");
        byte[] stored = KeyProtection.Protect(key, entropy);
        Check("a stored key reads back", Swmc.Hex(KeyProtection.Unprotect(stored, entropy)) == Swmc.Hex(key));
        Check("a key stored for something else is refused", Throws(() => KeyProtection.Unprotect(KeyProtection.Protect(key, Swmc.Ascii("other")), entropy)));
        byte[] damaged = (byte[]) stored.Clone();
        damaged[5] ^= 1;
        Check("a damaged key is refused", Throws(() => KeyProtection.Unprotect(damaged, entropy)));
        Check("a cut-short key is refused", Throws(() => KeyProtection.Unprotect(new byte[10], entropy)));
    }

    static bool Throws(Action a)
    {
        try {
            a();
            return false;
        }
        catch (CryptographicException) {
            return true;
        }
    }

    // ---------------------------------------------------------------- what the app and commands show
    static void Models()
    {
        // A pairing, as the receiver sends it
        PairingModel p = new PairingModel();
        Check("a pairing starts waiting for its code", p.Step == PairingStep.Waiting && !p.Finished);
        p.Feed("code 2491-650N-2RBH-G690");
        bool changed = p.Feed("open 300");
        Check("then shows the code and the time left", p.Step == PairingStep.Open && p.Code == "2491-650N-2RBH-G690" && changed
            && p.TimeLeft == "It works once, for the next 5:00.");
        Check("the same second again changes nothing", !p.Feed("open 300") && p.Feed("open 299") && p.TimeLeft.EndsWith("4:59."));
        p.Feed("paired\tPixel 8\t192.0.2.7\ta0a1a2a3a4a5a6a7");
        Check("pairing says who and from where, and how to undo it", p.Step == PairingStep.Paired && p.Finished
            && p.Title == "Paired with Pixel 8." && p.Detail("x").StartsWith("From 192.0.2.7.") && p.DeviceId == "a0a1a2a3a4a5a6a7");
        string[][] ends = {
            new[] { "expired", "That code has expired." }, new[] { "toomany", "Too many wrong codes." },
            new[] { "usedtwice", "The code was used twice." }, new[] { "replaced", "A new code was made." },
            new[] { "cancelled", "Pairing stopped." },
        };
        bool endsOk = true;
        foreach (string[] e in ends) {
            PairingModel q = new PairingModel();
            q.Feed("code AAAA-BBBB-CCCC-DDDD");
            q.Feed(e[0]);
            endsOk &= q.Finished && q.Title == e[1] && q.Detail("Run it again").Length > 0;
        }
        Check("every other way a pairing ends has its own words", endsOk);
        PairingModel gone = new PairingModel();
        gone.Feed("code AAAA-BBBB-CCCC-DDDD");
        gone.Closed();
        PairingModel done = new PairingModel();
        done.Feed("paired\tPixel 8\t192.0.2.7\ta0a1a2a3a4a5a6a7");
        done.Closed();
        Check("the receiver stopping mid-pairing is said, but not after it paired",
            gone.Step == PairingStep.ReceiverStopped && done.Step == PairingStep.Paired);
        Check("no pairing words have an em dash in them",
            (PairingModel.Instructions + PairingModel.Caution + PairingModel.NotMine).IndexOf('\u2014') < 0);

        // The app's view of "watch"
        TrayModel t = new TrayModel();
        Check("before the receiver answers, the app says it isn't running", !t.Connected && t.Headline == "The receiver isn't running"
            && t.Tooltip.EndsWith("isn't running"));
        t.Feed("port\t6980");
        t.Feed("status\tNo device paired yet: run starwire-mic pair");
        t.Feed("mic\tready");
        t.Feed("devices\t0\t0000000000000000");
        Check("with no device paired, it says how to pair", t.Connected && t.Headline == "No device paired yet" && t.Hint.StartsWith("Pair your")
            && t.PortLine == "UDP port 6980");
        t.DevicesChanged = false;
        Check("a heartbeat changes nothing", !t.Feed("beat"));
        Check("a new device makes the app ask for the list again", t.Feed("devices\t1\t1111111111111111") && t.DevicesChanged && t.PairedCount == 1);
        t.Feed("status\tWaiting for a paired device (UDP 6980)");
        Check("then it waits", t.Headline == "Waiting for a paired device" && !t.Receiving);
        t.Feed("status\tReceiving the microphone from Tab (S9) (encrypted)");
        Check("receiving names the device, brackets in its name and all", t.Receiving && t.Sender == "Tab (S9)"
            && t.Headline == "Receiving from Tab (S9)" && t.Tooltip == "Starwire microphone: Receiving from Tab (S9)");
        t.Feed("mic\tmissing");
        Check("a missing microphone is said first", t.Tooltip.Contains("PipeWire") && t.Hint.Contains("PipeWire"));
        Check("there's no warning unless unencrypted VBAN is allowed", t.Warning == null);
        t.Feed("unencrypted\tStream1");
        Check("and then it warns", t.Warning != null && t.Warning.StartsWith("Not safe"));
        t.Lost();
        Check("losing the receiver says so again", !t.Connected && t.Headline == "The receiver isn't running" && t.Warning == null);

        // The answer to "devices"
        System.Collections.Generic.List<ControlClient.Device> list = ControlClient.ParseDevices(new System.Collections.Generic.List<string> {
            "a0a1a2a3a4a5a6a7\tPixel 8\t2026-11-01\t-", "b1b2b3b4b5b6b7b8\tTab\t2026-10-30\t2026-11-07", "too\tfew" });
        DateTime today = new DateTime(2026, 11, 7, 12, 0, 0, DateTimeKind.Utc);
        Check("the devices list reads back, and says when each was used", list.Count == 2 && list[0].When(today) == "paired 2026-11-01, not used yet"
            && list[1].When(today) == "last used today" && list[1].When(today.AddDays(1)) == "last used 2026-11-07");
    }

    // ---------------------------------------------------------------- the paired devices file
    const UnixFileMode GroupOrOther = UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute
        | UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute;

    static void Files(string temp)
    {
        string folder = Path.Combine(temp, "Starwire");
        string path = Path.Combine(folder, "mic-pairings.dat");
        MicPairingStore store = new MicPairingStore(path);
        int seed = 1;
        store.Load(b => { for (int i = 0; i < b.Length; i++) { b[i] = (byte) seed++; } });
        MicPairing p = new MicPairing();
        p.DeviceId = Swmc.Hex("a0a1a2a3a4a5a6a7");
        p.Key = new byte[32];
        p.Name = "Pixel 8";
        p.PairedAt = new DateTime(2026, 11, 7, 12, 0, 0, DateTimeKind.Utc);
        p.LastSeen = DateTime.MinValue;
        store.Put(p);
        Check("the paired devices file is 0600", File.GetUnixFileMode(path) == (UnixFileMode.UserRead | UnixFileMode.UserWrite));
        Check("its folder is 0700", File.GetUnixFileMode(folder) == (UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute));

        // Left readable by someone (an older copy, a restore from backup): saving fixes both
        File.SetUnixFileMode(path, File.GetUnixFileMode(path) | UnixFileMode.GroupRead | UnixFileMode.OtherRead);
        File.SetUnixFileMode(folder, File.GetUnixFileMode(folder) | UnixFileMode.GroupRead | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute);
        store.Remove(p.DeviceId);
        Check("saving again makes a file others could read 0600", (File.GetUnixFileMode(path) & GroupOrOther) == 0);
        Check("and its folder 0700", (File.GetUnixFileMode(folder) & GroupOrOther) == 0);
        Check("no temporary file is left behind", !File.Exists(path + ".new"));

        MicPairingStore back = new MicPairingStore(path);
        back.Load(b => { });
        Check("and it reads back", back.Problems.Count == 0 && back.Devices.Count == 0 && Swmc.Hex(back.ReceiverId) == Swmc.Hex(store.ReceiverId));
    }

    static int Main(string[] args)
    {
        string temp = Path.Combine(Path.GetTempPath(), "starwire-linux-tests-" + Environment.ProcessId);
        Directory.CreateDirectory(temp);
        Console.WriteLine("Linux receiver tests, CLR " + Environment.Version);
        try {
            Format();
            Ring();
            Crypto();
            Files(temp);
            Models();
        }
        catch (Exception e) {
            Check("no test threw (" + e + ")", false);
        }
        finally {
            Directory.Delete(temp, true);
        }
        Console.WriteLine(failed == 0 ? "All " + passed + " checks passed" : failed + " of " + (passed + failed) + " checks FAILED");
        return failed == 0 ? 0 : 1;
    }
}
