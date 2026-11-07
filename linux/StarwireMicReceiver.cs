// Starwire microphone receiver for Linux (GPL-3.0, part of Starwire).
//
// Receives the microphone Starwire sends (VBAN inside Starwire's encrypted SWMC format, from
// ../windows/SecureMic.cs, shared with the Windows receiver) and plays it into a virtual microphone
// that PipeWire shows to every app as "Starwire microphone". Works whatever streaming host the PC
// runs. Only devices paired with this PC can send it audio, and the audio is encrypted on the way.
// Plain VBAN is refused unless it runs with --allow-unencrypted.
//
// It runs as a systemd user service with no window. The same program, run with a command, talks
// to that service over a socket only this user can open: to pair a device, list or unpair the
// paired ones, play a test tone or check the microphone. Problems show as desktop notifications.
//
//   starwire-mic                       runs the receiver (the service runs this)
//   starwire-mic pair                  shows a pairing code to type into Starwire
//   starwire-mic devices               lists the paired devices
//   starwire-mic unpair <id or name>   unpairs one
//   starwire-mic status                what the receiver is doing
//   starwire-mic tone [seconds]        plays a tone into the microphone (for Discord's mic test)
//   starwire-mic check                 plays a tone and records it back, to show the microphone works
//   starwire-mic watch                 prints what the app shows, as it changes (for tests)
//   starwire-mic test-tone [seconds]   sends a tone as plain VBAN to a receiver on this PC; it only
//                                      plays with --allow-unencrypted
//
// Options for the receiver:
//   --port 6980                        the UDP port (Starwire's is 6980 unless changed)
//   --pair                             shows a pairing code as it starts (printed)
//   --allow-unencrypted [--name Stream1]
//                                      also plays plain VBAN with that stream name from
//                                      home-network addresses. Not safe: anyone on the network can
//                                      then send sound into the microphone or listen to it.
//   --stats                            prints the audio buffer's state every few seconds
//   --loopback                         listens on this PC only (for testing)
//   --pairings-file <path>             keeps the paired devices there instead of
//                                      ~/.local/share/Starwire/mic-pairings.dat (for testing: a
//                                      receiver with its own file can run next to the usual one).
//                                      The commands take it too, to talk to that receiver.
//   --node-name starwire-mic           the microphone's PipeWire node name (for testing)
//   --exit-after-idle <secs>           once packets have come, stops when none have for that long
//                                      and prints what it played and refused (for tests)
//   --no-notify                        prints only, no desktop notifications
//   --console                          accepted for the Windows receiver's sake; it always prints

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

static class StarwireMicReceiver
{
    const int HeaderSize = Vban.HeaderSize;
    public const string Guide = "https://github.com/SicarioGaming/Starwire/tree/main/linux#readme";
    const int StatsMs = 2000;

    static readonly RandomNumberGenerator rng = RandomNumberGenerator.Create();
    static readonly Stopwatch uptime = Stopwatch.StartNew();
    static long lastPacket = long.MinValue / 2;
    static bool stats;
    static Notifier notifier;
    public static MicOutput Output;
    public static SecureMicEndpoint Endpoint;
    public static int Port;
    public static string StreamName;
    public static bool AllowUnencrypted;
    static volatile string status = "Waiting";

    // No packets for 3 seconds: the phone stopped sending (stream ended, or the mic was turned off)
    public static bool Idle { get { return uptime.ElapsedMilliseconds - Interlocked.Read(ref lastPacket) > 3000; } }

    public static string Status { get { return status; } }

    static int Main(string[] args)
    {
        string command = null;
        string argument = null;
        string streamName = "Stream1";
        int port = 6980;
        bool loopback = false;
        bool allowUnencrypted = false;
        bool pairNow = false;
        bool notify = true;
        string pairingsFile = null;
        string nodeName = "starwire-mic";
        int exitAfterIdle = 0;
        int seconds = 0;
        try {
            for (int i = 0; i < args.Length; i++) {
                string a = args[i];
                if (a == "-h" || a == "--help" || a == "help") { command = "help"; continue; }
                if (a == "--console") { continue; }
                if (a == "--loopback") { loopback = true; continue; }
                if (a == "--stats") { stats = true; continue; }
                if (a == "--allow-unencrypted") { allowUnencrypted = true; continue; }
                if (a == "--pair") { pairNow = true; continue; }
                if (a == "--no-notify") { notify = false; continue; }
                if (a == "--test-tone") { command = "test-tone"; continue; }
                if (a == "--port") { port = int.Parse(args[++i]); continue; }
                if (a == "--name") { streamName = args[++i]; continue; }
                if (a == "--pairings-file") { pairingsFile = Path.GetFullPath(args[++i]); continue; }
                if (a == "--node-name") { nodeName = args[++i]; continue; }
                if (a == "--exit-after-idle") { exitAfterIdle = int.Parse(args[++i]); continue; }
                if (a.StartsWith("-")) {
                    Console.Error.WriteLine("Unknown option " + a + ". starwire-mic --help lists them.");
                    return 2;
                }
                if (command == null) {
                    command = a;
                }
                else if (argument == null) {
                    argument = a;
                }
                else {
                    Console.Error.WriteLine("Too many arguments. starwire-mic --help lists them.");
                    return 2;
                }
            }
        }
        catch (Exception e) {
            if (!(e is IndexOutOfRangeException || e is FormatException || e is OverflowException)) {
                throw;
            }
            Console.Error.WriteLine("An option is missing its value, or its value isn't a number. starwire-mic --help lists them.");
            return 2;
        }
        if (port < 1 || port > 65535) {
            Console.Error.WriteLine("The port must be between 1 and 65535.");
            return 2;
        }
        if ((command == "tone" || command == "test-tone") && argument != null && !int.TryParse(argument, out seconds)) {
            Console.Error.WriteLine("Say how many seconds as a number, like: starwire-mic " + command + " 3");
            return 2;
        }

        switch (command) {
            case null:
                break;
            case "help":
                Help();
                return 0;
            case "test-tone":
                SendTestTone(port, streamName, seconds > 0 ? seconds : 3);
                return 0;
            case "pair":
            case "devices":
            case "unpair":
            case "status":
            case "tone":
            case "check":
            case "watch":
                return Control.Client(ControlClient.SocketPath(pairingsFile), command, argument, seconds);
            default:
                Console.Error.WriteLine("Unknown command " + command + ". starwire-mic --help lists them.");
                return 2;
        }
        return Run(port, streamName, loopback, allowUnencrypted, pairNow, notify, pairingsFile, nodeName, exitAfterIdle);
    }

    static void Help()
    {
        Console.WriteLine("Starwire microphone receiver: plays Starwire's microphone into a virtual microphone,");
        Console.WriteLine("\"Starwire microphone\", that apps on this PC can use.");
        Console.WriteLine();
        Console.WriteLine("  starwire-mic pair               pair a device: shows a code to type into Starwire");
        Console.WriteLine("  starwire-mic devices            the paired devices");
        Console.WriteLine("  starwire-mic unpair <id|name>   unpair one");
        Console.WriteLine("  starwire-mic status             what the receiver is doing");
        Console.WriteLine("  starwire-mic tone [seconds]     plays a tone into the microphone, for Discord's mic test");
        Console.WriteLine("  starwire-mic check              plays a tone and records it back from the microphone");
        Console.WriteLine("  starwire-mic test-tone [secs]   sends a tone as plain VBAN to this PC (only plays with");
        Console.WriteLine("                                  --allow-unencrypted)");
        Console.WriteLine("  starwire-mic                    runs the receiver; the systemd user service does this:");
        Console.WriteLine("                                  systemctl --user status starwire-mic");
        Console.WriteLine();
        Console.WriteLine("Receiver options: --port 6980, --pair, --stats, --loopback, --no-notify,");
        Console.WriteLine("  --allow-unencrypted [--name Stream1] (not safe: anyone on the network can then send");
        Console.WriteLine("  sound into the microphone or listen to it), --pairings-file <path>, --node-name <name>,");
        Console.WriteLine("  --exit-after-idle <secs>. The commands take --pairings-file too.");
        Console.WriteLine();
        Console.WriteLine("Guide: " + Guide);
    }

    static int Run(int port, string streamName, bool loopback, bool allowUnencrypted, bool pairNow, bool notify,
        string pairingsFile, string nodeName, int exitAfterIdle)
    {
        Port = port;
        StreamName = streamName;
        AllowUnencrypted = allowUnencrypted;
        // Lines go to the journal as they're printed
        Console.Out.Flush();
        TextWriter stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        ((StreamWriter) stdout).AutoFlush = true;
        Console.SetOut(TextWriter.Synchronized(stdout));

        MicPairingStore store = new MicPairingStore(pairingsFile != null ? pairingsFile : MicPairingStore.DefaultPath());
        // One receiver per pairings file at a time (a second one would only find the port taken,
        // and both would write the file)
        FileStream single = Control.TakeLock(pairingsFile);
        if (single == null) {
            Console.WriteLine("Starwire's microphone receiver is already running. See: systemctl --user status starwire-mic");
            return 0;
        }
        store.Load(Random);

        Socket socket;
        try {
            if (loopback) {
                socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                socket.Bind(new IPEndPoint(IPAddress.Loopback, port));
            }
            else {
                socket = new Socket(AddressFamily.InterNetworkV6, SocketType.Dgram, ProtocolType.Udp);
                socket.DualMode = true;
                socket.Bind(new IPEndPoint(IPAddress.IPv6Any, port));
            }
        }
        catch (SocketException e) {
            string why = e.SocketErrorCode == SocketError.AddressAlreadyInUse
                ? "UDP port " + port + " is already in use by another program, often a VBAN app. Close it, or run the setup with --port <another> and set the same port in Starwire."
                : "Couldn't listen on UDP port " + port + ": " + e.Message;
            Console.WriteLine(why);
            if (notify) {
                Notifier.Send(why);
            }
            // The service stops rather than restarting again and again
            return 4;
        }

        if (notify) {
            notifier = new Notifier();
        }
        Endpoint = new SecureMicEndpoint(store, () => uptime.ElapsedMilliseconds, Random,
            (data, count, to) => SendQuietly(socket, data, count, to), EndpointSays);
        Endpoint.AllowUnencrypted = allowUnencrypted;
        Endpoint.StreamName = Vban.StreamNameBytes(streamName);
        Endpoint.PcName = Environment.MachineName;
        Endpoint.HowToPair = "run starwire-mic pair";
        Endpoint.HowToPairAgain = "Run starwire-mic pair again";
        Endpoint.HowToUnpair = "with starwire-mic unpair";
        Output = new MicOutput(nodeName, "Starwire microphone", ReportOutput);
        // For HELLO_ACK's "no output" status; asked at most every 2 seconds
        Endpoint.OutputReady = () => Output.Ready;

        Console.WriteLine("Starwire microphone receiver");
        Console.WriteLine("  Listening on UDP port " + port + (loopback ? " on this PC only" : "") + " for paired devices (encrypted)");
        if (allowUnencrypted) {
            Console.WriteLine("  WARNING: --allow-unencrypted is on, so it also plays plain VBAN");
            Console.WriteLine("  named \"" + streamName + "\" from home-network addresses. Anyone on");
            Console.WriteLine("  the network can then send sound into this PC's microphone or");
            Console.WriteLine("  listen to it. Leave it off unless another VBAN app needs it.");
            Report("Unencrypted VBAN is allowed (--allow-unencrypted): anyone on this network can send sound into this PC's microphone or listen to it.", true);
        }
        List<string> names = new List<string>();
        foreach (MicPairing p in Endpoint.Devices()) {
            names.Add(p.Name);
        }
        Console.WriteLine(names.Count == 0 ? "  No devices paired yet: starwire-mic pair" : "  Paired: " + string.Join(", ", names));
        foreach (string problem in store.Problems) {
            Console.WriteLine("  Paired devices file: " + problem);
        }
        if (store.Problems.Count > 0) {
            Report("Some paired devices couldn't be read (" + store.Problems[0] + "). If one stops working, pair it again.", true);
        }
        // The microphone is there from the start, so apps can be set to use it before any audio comes
        Output.Start(48000);
        string socketPath = ControlClient.SocketPath(pairingsFile);
        string controlProblem = Control.Serve(socketPath);
        if (controlProblem != null) {
            Console.WriteLine("  Commands won't work: " + controlProblem);
        }
        if (pairNow) {
            Console.WriteLine("  Pairing code: " + Endpoint.BeginPairing());
            Console.WriteLine("  In Starwire: Settings > Sound & vibration > Send my microphone >");
            Console.WriteLine("  Pair with the PC, and type it. It works once, for 5 minutes.");
        }
        else {
            Console.WriteLine("  To pair a device: starwire-mic" + (pairingsFile != null ? " --pairings-file " + pairingsFile : "") + " pair");
        }
        SetWaiting();

        // Stops cleanly on Ctrl+C and when systemd stops the service, so the microphone goes away
        // at once rather than when PipeWire notices
        ManualResetEventSlim stopping = new ManualResetEventSlim(false);
        using (PosixSignalRegistration term = PosixSignalRegistration.Create(PosixSignal.SIGTERM, c => { c.Cancel = true; stopping.Set(); }))
        using (PosixSignalRegistration intr = PosixSignalRegistration.Create(PosixSignal.SIGINT, c => { c.Cancel = true; stopping.Set(); })) {
            Thread receiver = new Thread(() => {
                Receive(socket, Endpoint, loopback, exitAfterIdle * 1000);
                stopping.Set();
            });
            receiver.IsBackground = true;
            receiver.Name = "receive";
            receiver.Start();
            Thread watcher = new Thread(() => Watch(stopping));
            watcher.IsBackground = true;
            watcher.Name = "watch";
            watcher.Start();
            stopping.Wait();
        }
        Console.WriteLine("Stopping.");
        Control.Stop(socketPath);
        Output.Close();
        // A receiver with its own pairings file (a test's) takes its lock file with it; the usual one
        // keeps its name for next time
        string lockPath = single.Name;
        single.Dispose();
        if (pairingsFile != null) {
            try {
                File.Delete(lockPath);
            }
            catch (IOException) {
            }
        }
        return 0;
    }

    // Once a second: back to "waiting" a few seconds after the device stops sending, the microphone
    // made again if PipeWire restarted, and the notifications that waited their turn
    static void Watch(ManualResetEventSlim stopping)
    {
        while (!stopping.Wait(1000)) {
            // Kept up to date while waiting too, since devices get paired and unpaired
            if (!status.StartsWith("Receiving") || Idle) {
                SetWaiting();
            }
            Output.Keep();
            if (notifier != null) {
                notifier.Tick();
            }
        }
    }

    static void SetWaiting()
    {
        if (AllowUnencrypted) {
            status = "Waiting for Starwire (" + StreamName + ", UDP " + Port + ")";
        }
        else if (Endpoint.PairedCount == 0) {
            status = "No device paired yet: run starwire-mic pair";
        }
        else {
            status = "Waiting for a paired device (UDP " + Port + ")";
        }
    }

    public static void Random(byte[] bytes)
    {
        lock (rng) {
            rng.GetBytes(bytes);
        }
    }

    // Answers to devices (HELLO_ACK, REHELLO, pairing); a device that has gone away isn't a problem
    static void SendQuietly(Socket socket, byte[] data, int count, EndPoint to)
    {
        try {
            socket.SendTo(data, 0, count, SocketFlags.None, to);
        }
        catch (SocketException) {
        }
        catch (ObjectDisposedException) {
        }
    }

    // What the encrypted side has to say: everything is printed (the journal keeps it), and the
    // important things are a notification too
    static void EndpointSays(string text, bool important)
    {
        Console.WriteLine(text);
        if (important && notifier != null) {
            notifier.Add(text);
        }
    }

    // What the receiver has to say: printed, the important ones as a notification too, the others
    // as its status
    public static void Report(string text, bool important)
    {
        Console.WriteLine(text);
        if (important) {
            if (notifier != null) {
                notifier.Add(text);
            }
        }
        else {
            status = text;
        }
    }

    public static void ReportOutput(string text)
    {
        Console.WriteLine("  Output: " + text);
    }

    // The endpoint (SecureMic.cs) decides what plays: audio from a paired device, decrypted, or
    // with --allow-unencrypted plain VBAN too. exitAfterIdleMs is --exit-after-idle's, for tests.
    static void Receive(Socket socket, SecureMicEndpoint endpoint, bool loopback, int exitAfterIdleMs)
    {
        byte[] buffer = new byte[2048];
        byte[] vban = new byte[2048];
        string currentSender = null;
        bool warnedFormat = false;
        long lastStats = 0;
        long lastArrival = 0;
        bool arrived = false;
        // FNV-1a of the audio played, which --exit-after-idle prints so tests can compare it
        ulong pcmHash = 14695981039346656037UL;
        EndPoint from = new IPEndPoint(loopback ? IPAddress.Any : IPAddress.IPv6Any, 0);
        if (exitAfterIdleMs > 0) {
            socket.ReceiveTimeout = 250;
        }

        while (true) {
            int length;
            try {
                length = socket.ReceiveFrom(buffer, ref from);
            }
            catch (SocketException) {
                // --exit-after-idle's wait ran out, or a sender went away; keep listening
                if (exitAfterIdleMs > 0 && arrived && uptime.ElapsedMilliseconds - lastArrival > exitAfterIdleMs) {
                    Console.WriteLine("Summary: " + endpoint.Counts() + ", PCM fnv1a64 " + pcmHash.ToString("x16"));
                    return;
                }
                continue;
            }
            arrived = true;
            lastArrival = uptime.ElapsedMilliseconds;

            int vbanLength;
            string sender;
            bool encrypted;
            if (!endpoint.Handle(buffer, length, from, vban, out vbanLength, out sender, out encrypted)) {
                continue;
            }
            int samples, channels, rate;
            if (!Vban.ParsePcm(vban, vbanLength, out samples, out channels, out rate)) {
                if (!warnedFormat) {
                    warnedFormat = true;
                    Report("Ignoring a VBAN stream that isn't 16-bit PCM in one or two channels.", true);
                }
                continue;
            }

            if (sender != currentSender || Idle) {
                currentSender = sender;
                Report("Receiving the microphone from " + sender + (encrypted ? " (encrypted)" : " (not encrypted)"), false);
            }
            long now = uptime.ElapsedMilliseconds;
            Interlocked.Exchange(ref lastPacket, now);
            if (exitAfterIdleMs > 0) {
                for (int i = HeaderSize; i < vbanLength; i++) {
                    pcmHash = (pcmHash ^ vban[i]) * 1099511628211UL;
                }
            }
            Output.Play(vban, HeaderSize, samples, channels, rate);
            if (stats && now - lastStats >= StatsMs) {
                lastStats = now;
                Console.WriteLine("  " + DateTime.Now.ToString("HH:mm:ss") + " " + Output.Stats());
            }
        }
    }

    // A tone straight into the microphone, in 10 ms blocks in real time, the way packets come.
    // False if a device is sending its microphone right now.
    public static bool PlayTone(int milliseconds, int frequency)
    {
        if (!Idle) {
            return false;
        }
        const int rate = 48000;
        byte[] packet = new byte[HeaderSize + 480 * 2];
        double phase = 0;
        Stopwatch clock = Stopwatch.StartNew();
        for (int block = 0; block * 10 < milliseconds; block++) {
            while (clock.ElapsedMilliseconds < block * 10L) {
                Thread.Sleep(1);
            }
            for (int i = 0; i < 480; i++) {
                short value = (short) (Math.Sin(phase) * 8000);
                packet[HeaderSize + i * 2] = (byte) value;
                packet[HeaderSize + i * 2 + 1] = (byte) (value >> 8);
                phase += 2 * Math.PI * frequency / rate;
            }
            Output.Play(packet, HeaderSize, 480, 1, rate);
        }
        return true;
    }

    // A 440 Hz tone as plain VBAN, the way Starwire 0.2.0 sent the microphone. Starwire's receiver
    // refuses it now unless it runs with --allow-unencrypted.
    static void SendTestTone(int port, string streamName, int seconds)
    {
        const int samples = 256, rate = 48000;
        byte[] packet = new byte[HeaderSize + samples * 2];
        packet[0] = (byte) 'V'; packet[1] = (byte) 'B'; packet[2] = (byte) 'A'; packet[3] = (byte) 'N';
        packet[4] = 3;                  // 48000 Hz
        packet[5] = samples - 1;
        packet[6] = 0;                  // mono
        packet[7] = 1;                  // 16-bit PCM
        Array.Copy(Vban.StreamNameBytes(streamName), 0, packet, 8, 16);

        using (Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)) {
            IPEndPoint target = new IPEndPoint(IPAddress.Loopback, port);
            Console.WriteLine("Sending a " + seconds + " second test tone, as plain VBAN, to UDP port " + port + " on this PC...");
            double phase = 0;
            int total = seconds * rate / samples;
            Stopwatch clock = Stopwatch.StartNew();
            for (int frame = 0; frame < total; frame++) {
                while (clock.ElapsedMilliseconds < (long) frame * samples * 1000 / rate) {
                    Thread.Sleep(1);
                }
                BitConverter.GetBytes(frame).CopyTo(packet, 24);
                for (int i = 0; i < samples; i++) {
                    short value = (short) (Math.Sin(phase) * 8000);
                    packet[HeaderSize + i * 2] = (byte) value;
                    packet[HeaderSize + i * 2 + 1] = (byte) (value >> 8);
                    phase += 2 * Math.PI * 440 / rate;
                }
                socket.SendTo(packet, target);
            }
        }
        Console.WriteLine("Done. Starwire's receiver only plays it if it runs with --allow-unencrypted;");
        Console.WriteLine("otherwise it refuses it, as it should. starwire-mic tone checks the microphone");
        Console.WriteLine("without the network.");
    }
}

// Plays into the virtual microphone, keeping it there: made again when PipeWire restarts, and
// again at another rate if the device sends one. Its methods are called from the receive thread,
// the watch thread and a command's tone, so they take turns.
sealed class MicOutput
{
    const int RetryMs = 1000;

    readonly object gate = new object();
    readonly string nodeName;
    readonly string description;
    readonly Action<string> sayOutput;
    readonly Stopwatch clock = Stopwatch.StartNew();
    VirtualMic mic;
    int rate = 48000;
    long nextOpenAttempt;
    bool missing;
    // What the log has said about this microphone's gaps and trims
    int gapsSeen, trimsSeen;

    public MicOutput(string nodeName, string description, Action<string> sayOutput)
    {
        this.nodeName = nodeName;
        this.description = description;
        this.sayOutput = sayOutput;
    }

    public string NodeName { get { return nodeName; } }

    public bool Ready { get { lock (gate) { return mic != null && !mic.Failed; } } }

    public void Start(int rate)
    {
        lock (gate) {
            this.rate = rate;
            OpenLocked(true);
        }
    }

    // From the watch thread, every second: a microphone that went away comes back, and the log
    // says when the audio had a gap or was trimmed
    public void Keep()
    {
        lock (gate) {
            if (mic == null || mic.Failed) {
                OpenLocked(false);
            }
            if (mic == null) {
                return;
            }
            JitterRing r = mic.Ring;
            int newGaps, newTrims, stallMs;
            r.TakeNews(ref gapsSeen, ref trimsSeen, out newGaps, out newTrims, out stallMs);
            if (newGaps > 0) {
                Console.WriteLine("  Gap in the audio" + (newGaps > 1 ? " (" + newGaps + " this second)" : "") + ": no packet came for "
                    + stallMs + " ms, longer than the buffer rides out. " + r.Gaps + " so far, at a quantum of " + r.Quantum + " samples.");
            }
            if (newTrims > 0) {
                Console.WriteLine("  Skipped " + newTrims * JitterRing.SkipMs + " ms of audio to keep the delay down (it had built up). "
                    + r.Trims + " so far.");
            }
        }
    }

    public void Play(byte[] packet, int offset, int samples, int channels, int rate)
    {
        lock (gate) {
            if (mic != null && (mic.Failed || mic.Rate != rate)) {
                this.rate = rate;
                OpenLocked(true);
            }
            if (mic == null) {
                OpenLocked(false);
            }
            if (mic != null) {
                mic.Write(packet, offset, samples, channels);
            }
        }
    }

    void OpenLocked(bool now)
    {
        long time = clock.ElapsedMilliseconds;
        if (!now && time < nextOpenAttempt) {
            return;
        }
        nextOpenAttempt = time + RetryMs;
        if (mic != null) {
            if (mic.Failed) {
                StarwireMicReceiver.Report("The microphone was disconnected from PipeWire (" + mic.Failure + "); making it again.", false);
            }
            mic.Dispose();
            mic = null;
        }
        string problem;
        mic = VirtualMic.Open(nodeName, description, rate, out problem);
        gapsSeen = trimsSeen = 0;
        if (mic == null) {
            if (!missing) {
                missing = true;
                StarwireMicReceiver.Report("Couldn't make the Starwire microphone: " + problem + ". Trying again every second.", true);
            }
            return;
        }
        if (missing) {
            missing = false;
            StarwireMicReceiver.Report("The Starwire microphone is back.", true);
        }
        sayOutput("PipeWire source \"" + description + "\" (node " + nodeName + ", id " + mic.NodeId + "), " + rate + " Hz mono");
    }

    public string Stats()
    {
        lock (gate) {
            return mic != null ? mic.Stats() : "no microphone";
        }
    }

    public void Close()
    {
        lock (gate) {
            if (mic != null) {
                mic.Dispose();
                mic = null;
            }
        }
    }
}

// Desktop notifications through notify-send, at most one every few seconds and the same text not
// twice in a row while it waits (ReportQueue, shared with the Windows receiver's icon)
sealed class Notifier
{
    readonly ReportQueue reports = new ReportQueue();
    readonly Stopwatch clock = Stopwatch.StartNew();
    readonly object gate = new object();

    // From any thread. Only queued: notify-send runs on the watch thread, so the receive thread
    // never waits for it.
    public void Add(string text)
    {
        reports.Add(text, true);
    }

    // From the watch thread, every second
    public void Tick()
    {
        string note;
        lock (gate) {
            reports.TakeStatus();
            note = reports.TakeNote(clock.ElapsedMilliseconds);
        }
        if (note != null) {
            Send(note);
        }
    }

    // KDE shows a notification's body as simple markup, so names and addresses are escaped
    public static void Send(string text)
    {
        try {
            ProcessStartInfo start = new ProcessStartInfo("notify-send");
            start.ArgumentList.Add("--app-name=Starwire microphone");
            start.ArgumentList.Add("--icon=audio-input-microphone");
            start.ArgumentList.Add("Starwire microphone");
            start.ArgumentList.Add(text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;"));
            start.UseShellExecute = false;
            start.RedirectStandardOutput = true;
            start.RedirectStandardError = true;
            using (Process p = Process.Start(start)) {
                p.WaitForExit(5000);
            }
        }
        catch (Exception e) {
            if (!(e is System.ComponentModel.Win32Exception || e is InvalidOperationException)) {
                throw;
            }
            // No notify-send (libnotify isn't installed): the journal still has it
        }
    }
}
