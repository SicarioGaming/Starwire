// Test phone for run-tests.sh (GPL-3.0, part of Starwire): plays Starwire's part against a receiver
// running on this PC, as windows/tests/SwmcTestSender.java does for the Windows receiver. It sends a
// wrong code and then the right one, checks the answers, has an unpaired device say HELLO, streams a
// quiet 1 kHz tone in real time, and sends one audio packet again, which must be refused. With a
// node name, it records that PipeWire microphone while it streams, to show the tone came out.
//
// It builds its packets with windows/SecureMic.cs, the receiver's own code. That's fine for a test
// of the receiver's plumbing (sockets, the jitter buffer, PipeWire), and the format itself is held
// to tests/vectors/swmc-v1-vectors.txt by SecureMicTests, which the Android app's tests share.
//
//   FakePhone <port> <code> <packets> <amplitude> [<PipeWire node to record>]
//
// Prints PASS and FAIL lines, a SENT line with what the receiver should report, and with a node a
// HEARD line. Exits with 1 if any check failed.

using EcdhKey = System.Security.Cryptography.ECDiffieHellman;
using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Threading;

static class FakePhone
{
    static int failures;

    static void Check(string what, bool ok)
    {
        Console.WriteLine((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    // Sends p and waits up to ms for an answer of that type (-1: any), or null
    static byte[] Exchange(Socket s, EndPoint to, byte[] p, int ms, int type)
    {
        s.SendTo(p, to);
        Stopwatch clock = Stopwatch.StartNew();
        byte[] b = new byte[2048];
        while (clock.ElapsedMilliseconds < ms) {
            s.ReceiveTimeout = Math.Max(1, ms - (int) clock.ElapsedMilliseconds);
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            int n;
            try {
                n = s.ReceiveFrom(b, ref from);
            }
            catch (SocketException) {
                return null;
            }
            if (type < 0 || Swmc.TypeOf(b, n) == type) {
                return Swmc.Slice(b, 0, n);
            }
        }
        return null;
    }

    static byte[] RandomBytes(int n)
    {
        byte[] b = new byte[n];
        RandomNumberGenerator.Fill(b);
        return b;
    }

    // 256 mono samples at 48 kHz: a quiet 1 kHz sine, laid out as Starwire's VBAN packets are
    static byte[] Vban(int frame, int amplitude)
    {
        byte[] p = new byte[Vban_Header + 512];
        p[0] = (byte) 'V'; p[1] = (byte) 'B'; p[2] = (byte) 'A'; p[3] = (byte) 'N';
        p[4] = 3;          // 48000 Hz
        p[5] = 255;        // 256 samples
        p[6] = 0;          // mono
        p[7] = 1;          // 16-bit PCM
        global::Vban.StreamNameBytes("Stream1").CopyTo(p, 8);
        BitConverter.GetBytes(frame).CopyTo(p, 24);
        for (int i = 0; i < 256; i++) {
            long t = (long) frame * 256 + i;
            short v = (short) Math.Round(amplitude * Math.Sin(2 * Math.PI * 1000 * t / 48000.0));
            p[Vban_Header + 2 * i] = (byte) v;
            p[Vban_Header + 2 * i + 1] = (byte) (v >> 8);
        }
        return p;
    }

    const int Vban_Header = 28;

    static int Main(string[] args)
    {
        if (args.Length != 4 && args.Length != 5) {
            Console.WriteLine("FakePhone <port> <code> <packets> <amplitude> [<PipeWire node to record>]");
            return 2;
        }
        int port = int.Parse(args[0]);
        byte[] code = Swmc.DecodeCode(args[1]);
        int packets = int.Parse(args[2]);
        int amplitude = int.Parse(args[3]);
        string node = args.Length == 5 ? args[4] : null;
        Check("the receiver printed a code that reads back", code != null);
        if (code == null) {
            return 1;
        }

        using (Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)) {
            s.Bind(new IPEndPoint(IPAddress.Loopback, 0));
            EndPoint receiver = new IPEndPoint(IPAddress.Loopback, port);
            byte[] deviceId = RandomBytes(8);

            // A wrong code first: refused, as a wrong code
            using (EcdhKey wrongKey = Swmc.NewKey()) {
                byte[] wrong = (byte[]) code.Clone();
                wrong[0] ^= 1;
                byte[] request = Swmc.PairRequest(Swmc.CodeKey(wrong), deviceId, RandomBytes(8), Swmc.OwnPoint(wrongKey), "Wrong code");
                byte[] reject = Exchange(s, receiver, request, 1500, Swmc.TPairReject);
                Check("a wrong code gets a PAIR_REJECT saying so", reject != null && Swmc.ParsePairReject(reject, reject.Length) == Swmc.RejectWrongCode);
            }

            // Then the right one, as the app does: the request again every half second until answered
            byte[] pairKey = null;
            using (EcdhKey mine = Swmc.NewKey()) {
                byte[] codeKey = Swmc.CodeKey(code);
                byte[] nonce = RandomBytes(8);
                byte[] phonePoint = Swmc.OwnPoint(mine);
                byte[] request = Swmc.PairRequest(codeKey, deviceId, nonce, phonePoint, "Fake phone");
                byte[] accept = null;
                for (int attempt = 0; attempt < 10 && accept == null; attempt++) {
                    accept = Exchange(s, receiver, request, 500, Swmc.TPairAccept);
                }
                int nameLength;
                bool shaped = accept != null && Swmc.ParsePairAccept(accept, accept.Length, out nameLength);
                Check("the right code gets a PAIR_ACCEPT", shaped);
                if (!shaped) {
                    return 1;
                }
                Check("its MAC is made with the code's key", Swmc.MacOk(codeKey, accept, accept.Length));
                byte[] receiverPoint = Swmc.Slice(accept, Swmc.Header, Swmc.PointLength);
                byte[] z = Swmc.EcdhHash(mine, receiverPoint, 0);
                pairKey = Swmc.PairKey(codeKey, z, deviceId, nonce, phonePoint, receiverPoint);
                int confirmAt = accept.Length - 2 * Swmc.MacLength;
                Check("its confirmation matches the pairing key this side worked out",
                    Swmc.ConstantTimeEquals(Swmc.Confirm(pairKey), 0, accept, confirmAt, Swmc.MacLength));
                Check("asking again gets the same answer", Swmc.ConstantTimeEquals(Exchange(s, receiver, request, 1000, Swmc.TPairAccept) ?? new byte[accept.Length], 0, accept, 0, accept.Length));
            }

            // A device that isn't paired gets no answer
            byte[] strangerHello = Swmc.Hello(Swmc.AuthKey(RandomBytes(32), RandomBytes(8)), RandomBytes(8), RandomBytes(8));
            Check("a HELLO from a device that isn't paired gets no answer", Exchange(s, receiver, strangerHello, 700, -1) == null);

            // A session
            byte[] authKey = Swmc.AuthKey(pairKey, deviceId);
            byte[] clientRandom = RandomBytes(8);
            byte[] hello = Swmc.Hello(authKey, deviceId, clientRandom);
            byte[] ack = null;
            for (int attempt = 0; attempt < 8 && ack == null; attempt++) {
                ack = Exchange(s, receiver, hello, 250, Swmc.THelloAck);
            }
            Check("HELLO gets a HELLO_ACK with a good MAC", ack != null && ack.Length == Swmc.AckLength && Swmc.MacOk(authKey, ack, ack.Length));
            if (ack == null) {
                return 1;
            }
            Check("the receiver says it's ready (its microphone is there)", ack[Swmc.Header + 8] == Swmc.StatusReady);
            byte[] sessionId = Swmc.Slice(ack, Swmc.Header, 8);

            Process recorder = null;
            Thread reading = null;
            short[] heard = new short[48000 * (packets / 187 + 3)];
            int heardCount = 0;
            if (node != null) {
                ProcessStartInfo start = new ProcessStartInfo("pw-record");
                foreach (string a in new[] { "--raw", "--target", node, "--rate", "48000", "--channels", "1", "--format", "s16", "-" }) {
                    start.ArgumentList.Add(a);
                }
                start.UseShellExecute = false;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;
                recorder = Process.Start(start);
                reading = new Thread(() => {
                    byte[] b = new byte[4096];
                    Stream o = recorder.StandardOutput.BaseStream;
                    int n, odd = -1;
                    while ((n = o.Read(b, 0, b.Length)) > 0) {
                        for (int i = 0; i < n; i++) {
                            if (odd < 0) {
                                odd = b[i];
                                continue;
                            }
                            if (heardCount < heard.Length) {
                                heard[heardCount++] = (short) (odd | b[i] << 8);
                            }
                            odd = -1;
                        }
                    }
                });
                reading.IsBackground = true;
                reading.Start();
                Thread.Sleep(300);
            }

            // The stream, in real time: 256 samples at 48 kHz every 5.333 ms
            using (CngAesGcm aead = new CngAesGcm(Swmc.AudioKey(pairKey, deviceId, clientRandom, sessionId))) {
                byte[] output = new byte[2048];
                ulong hash = 14695981039346656037UL;
                byte[] last = null;
                Stopwatch clock = Stopwatch.StartNew();
                for (int i = 0; i < packets; i++) {
                    byte[] vban = Vban(i, amplitude);
                    for (int j = Vban_Header; j < vban.Length; j++) {
                        hash = (hash ^ vban[j]) * 1099511628211UL;
                    }
                    int n = Swmc.Audio(aead, deviceId, sessionId, i, vban, vban.Length, output);
                    last = Swmc.Slice(output, 0, n);
                    s.SendTo(output, 0, n, SocketFlags.None, receiver);
                    long due = (long) (i + 1) * 256 * 1000000 / 48000;
                    while (clock.Elapsed.Ticks / 10 < due) {
                        Thread.Sleep(1);
                    }
                }
                // The last packet again: a replay, refused
                s.SendTo(last, receiver);
                Console.WriteLine("SENT played=" + packets + " replayed=1 pcm=" + hash.ToString("x16"));
            }

            if (recorder != null) {
                Thread.Sleep(300);
                try {
                    recorder.Kill();
                }
                catch (InvalidOperationException) {
                }
                recorder.WaitForExit(2000);
                reading.Join(2000);
                // How loud 1 kHz is in the recording (Goertzel), against everything else
                int peak = 0;
                double tone = 0;
                for (int start = 0; start + 4800 <= heardCount; start += 4800) {
                    double s0 = 0, s1 = 0, s2 = 0, c = 2 * Math.Cos(2 * Math.PI * 1000 / 48000.0);
                    for (int i = start; i < start + 4800; i++) {
                        s0 = heard[i] + c * s1 - s2;
                        s2 = s1;
                        s1 = s0;
                        peak = Math.Max(peak, Math.Abs((int) heard[i]));
                    }
                    tone = Math.Max(tone, Math.Sqrt(Math.Max(0, s1 * s1 + s2 * s2 - c * s1 * s2)) * 2 / 4800);
                }
                Console.WriteLine("HEARD samples=" + heardCount + " peak=" + peak + " tone=" + Math.Round(tone));
                Check("the PipeWire microphone played the tone (1 kHz at about " + amplitude + ")",
                    tone > amplitude * 0.8 && tone < amplitude * 1.2);
            }
        }
        Console.WriteLine(failures == 0 ? "FakePhone: all checks passed" : "FakePhone: " + failures + " checks FAILED");
        return failures == 0 ? 0 : 1;
    }
}
