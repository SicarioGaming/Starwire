// Starwire microphone receiver for Linux: the commands (GPL-3.0, part of Starwire).
//
// The receiver runs as a systemd user service with no window. The commands (starwire-mic pair,
// devices, unpair, status, tone, check, watch) and the Starwire microphone app (app/) talk to it
// over a Unix socket in $XDG_RUNTIME_DIR/starwire-mic, a folder only this user can open; the
// receiver also checks that whoever connects runs as this user. ControlClient.cs is their side. One
// line asks, lines answer:
//
//   status                      what it's doing, as lines to print
//   devices                     one line per paired device: id, name, paired, last used (tabs)
//   unpair <id or name>         "ok <name>" or "error <why>"
//   tone <ms> <hz>              plays a tone into the microphone: "ok", or "busy" while a device sends
//   node                        the microphone's PipeWire node name
//   pair                        "code <code>", then "open <seconds left>" four times a second, until
//                               "paired <name> <address> <id>" (tabs), "expired", "toomany",
//                               "usedtwice", "replaced" or "cancelled". Closing the connection
//                               before then cancels the code, like closing the Windows window.
//   watch                       what the app shows, as it changes, for as long as the connection
//                               stays open: "port <n>", "unencrypted <stream name>" (only if it's
//                               on), then "status <text>", "mic ready" or "mic missing", and
//                               "devices <count> <signature>" (the app asks "devices" again when it
//                               changes), each when it changes, and "beat" every 2 seconds. Tabs
//                               between the words.
//
// Anyone who sees the code before the user has typed it can pair first, so `starwire-mic pair`
// only shows it once asked to, and then says where the pairing came from and how to undo it.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

static class Control
{
    const UnixFileMode Folder = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute;
    const UnixFileMode Private = UnixFileMode.UserRead | UnixFileMode.UserWrite;
    const int SolSocket = 1, SoPeerCred = 17;
    public const int WatchMs = 250, BeatMs = 2000;

    [DllImport("libc", SetLastError = true)] static extern uint geteuid();

    static Socket listener;
    static int pairingRound;

    static void MakeFolder(string folder)
    {
        if (!Directory.Exists(folder)) {
            Directory.CreateDirectory(folder, Folder);
        }
        if ((File.GetUnixFileMode(folder) & (UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute
                | UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute)) != 0) {
            File.SetUnixFileMode(folder, Folder);
        }
    }

    // Held for as long as the receiver runs: a second receiver for the same pairings file gets null
    public static FileStream TakeLock(string pairingsFile)
    {
        string folder = ControlClient.ControlFolder(pairingsFile);
        MakeFolder(folder);
        try {
            FileStreamOptions options = new FileStreamOptions();
            options.Mode = FileMode.OpenOrCreate;
            options.Access = FileAccess.ReadWrite;
            options.Share = FileShare.None;
            options.UnixCreateMode = Private;
            return new FileStream(Path.Combine(folder, "receiver" + ControlClient.Suffix(pairingsFile) + ".lock"), options);
        }
        catch (IOException) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the receiver's side
    // Starts answering commands; returns why it couldn't, or null
    public static string Serve(string path)
    {
        try {
            MakeFolder(Path.GetDirectoryName(path));
            // Only the receiver holding the lock gets here, so a socket left there is an old one's
            if (File.Exists(path)) {
                File.Delete(path);
            }
            listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            listener.Bind(new UnixDomainSocketEndPoint(path));
            File.SetUnixFileMode(path, Private);
            listener.Listen(8);
        }
        catch (Exception e) {
            if (!(e is SocketException || e is IOException || e is UnauthorizedAccessException)) {
                throw;
            }
            return e.Message;
        }
        Thread accept = new Thread(() => {
            while (true) {
                Socket client;
                try {
                    client = listener.Accept();
                }
                catch (SocketException) {
                    continue;
                }
                catch (ObjectDisposedException) {
                    return;
                }
                Thread t = new Thread(() => Answer(client));
                t.IsBackground = true;
                t.Name = "command";
                t.Start();
            }
        });
        accept.IsBackground = true;
        accept.Name = "commands";
        accept.Start();
        return null;
    }

    public static void Stop(string path)
    {
        if (listener != null) {
            listener.Dispose();
            try {
                File.Delete(path);
            }
            catch (IOException) {
            }
        }
    }

    // The user id of whoever is on the other end (SO_PEERCRED: pid, uid, gid)
    static long PeerUid(Socket s)
    {
        byte[] cred = new byte[12];
        try {
            if (s.GetRawSocketOption(SolSocket, SoPeerCred, cred) != 12) {
                return -1;
            }
        }
        catch (SocketException) {
            return -1;
        }
        return BitConverter.ToUInt32(cred, 4);
    }

    static void Answer(Socket client)
    {
        using (client)
        using (NetworkStream stream = new NetworkStream(client, false)) {
            try {
                if (PeerUid(client) != geteuid()) {
                    return;
                }
                client.ReceiveTimeout = 5000;
                StreamReader reader = new StreamReader(stream, new UTF8Encoding(false));
                StreamWriter writer = new StreamWriter(stream, new UTF8Encoding(false));
                writer.NewLine = "\n";
                writer.AutoFlush = true;
                string line = reader.ReadLine();
                if (line == null || line.Length > 200) {
                    return;
                }
                string[] words = line.Split(' ', 2);
                string arg = words.Length > 1 ? words[1].Trim() : "";
                switch (words[0]) {
                    case "status": AnswerStatus(writer); break;
                    case "devices": AnswerDevices(writer); break;
                    case "unpair": AnswerUnpair(writer, arg); break;
                    case "tone": AnswerTone(writer, arg); break;
                    case "node": writer.WriteLine(StarwireMicReceiver.Output.NodeName); break;
                    case "pair": AnswerPair(writer); break;
                    case "watch": AnswerWatch(writer); break;
                    default: writer.WriteLine("error unknown command"); break;
                }
            }
            catch (Exception e) {
                // The command went away (IOException, SocketException). Anything else is a bug,
                // but it mustn't stop the receiver: an unhandled exception here would.
                if (!(e is IOException || e is SocketException || e is ObjectDisposedException)) {
                    Console.WriteLine("A command failed: " + e);
                }
            }
        }
    }

    static void AnswerStatus(StreamWriter w)
    {
        SecureMicEndpoint e = StarwireMicReceiver.Endpoint;
        MicOutput o = StarwireMicReceiver.Output;
        w.WriteLine("Starwire microphone receiver: running");
        w.WriteLine("  " + StarwireMicReceiver.Status);
        w.WriteLine("  Microphone: \"Starwire microphone\" (PipeWire node " + o.NodeName + "), " + (o.Ready ? "ready" : "not there right now"));
        w.WriteLine("  Paired devices: " + e.PairedCount + (e.PairedCount == 0 ? " (starwire-mic pair)" : " (starwire-mic devices)"));
        w.WriteLine("  This PC: " + ControlClient.ThisPc() + ", UDP port " + StarwireMicReceiver.Port);
        if (StarwireMicReceiver.AllowUnencrypted) {
            w.WriteLine("  Not safe: unencrypted VBAN named \"" + StarwireMicReceiver.StreamName + "\" plays too (--allow-unencrypted)");
        }
        w.WriteLine("  Packets: " + e.Counts());
        w.WriteLine("  Audio: " + o.Stats());
    }

    static string Day(DateTime utc)
    {
        if (utc == DateTime.MinValue) {
            return "-";
        }
        return utc.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    }

    static List<string> DeviceLines()
    {
        List<string> lines = new List<string>();
        foreach (MicPairing d in StarwireMicReceiver.Endpoint.Devices()) {
            lines.Add(d.Id + "\t" + d.Name + "\t" + Day(d.PairedAt) + "\t" + Day(d.LastSeen));
        }
        return lines;
    }

    static void AnswerDevices(StreamWriter w)
    {
        foreach (string line in DeviceLines()) {
            w.WriteLine(line);
        }
    }

    static void AnswerUnpair(StreamWriter w, string which)
    {
        List<MicPairing> matches = new List<MicPairing>();
        foreach (MicPairing d in StarwireMicReceiver.Endpoint.Devices()) {
            if (string.Equals(d.Id, which, StringComparison.OrdinalIgnoreCase) || string.Equals(d.Name, which, StringComparison.OrdinalIgnoreCase)) {
                matches.Add(d);
            }
        }
        if (matches.Count == 0) {
            w.WriteLine("error No paired device has that id or name. starwire-mic devices lists them.");
            return;
        }
        if (matches.Count > 1) {
            w.WriteLine("error More than one paired device is called that. Use its id from starwire-mic devices.");
            return;
        }
        try {
            StarwireMicReceiver.Endpoint.Unpair(matches[0].DeviceId);
            // In the log, since a device that's unpaired can't send its microphone any more
            Console.WriteLine("Unpaired " + matches[0].Name + " (id " + matches[0].Id + "), as the app or starwire-mic unpair asked.");
            w.WriteLine("ok " + matches[0].Name);
        }
        catch (Exception e) {
            if (!(e is IOException || e is UnauthorizedAccessException)) {
                throw;
            }
            w.WriteLine("error Couldn't save the paired devices: " + e.Message);
        }
    }

    static void AnswerTone(StreamWriter w, string arg)
    {
        string[] f = arg.Split(' ');
        int ms, hz;
        if (f.Length != 2 || !int.TryParse(f[0], out ms) || !int.TryParse(f[1], out hz) || ms < 1 || ms > 60000 || hz < 20 || hz > 20000) {
            w.WriteLine("error tone <ms> <hz>");
            return;
        }
        w.WriteLine(StarwireMicReceiver.PlayTone(ms, hz) ? "ok" : "busy");
    }

    static void AnswerPair(StreamWriter w)
    {
        int round = Interlocked.Increment(ref pairingRound);
        SecureMicEndpoint e = StarwireMicReceiver.Endpoint;
        w.WriteLine("code " + e.BeginPairing());
        try {
            while (true) {
                if (Volatile.Read(ref pairingRound) != round) {
                    // Another starwire-mic pair, or the app, made a new code
                    w.WriteLine("replaced");
                    return;
                }
                PairingStatus s = e.Pairing;
                switch (s.State) {
                    case PairingState.Open:
                        w.WriteLine("open " + (s.RemainingMs + 999) / 1000);
                        break;
                    case PairingState.Paired:
                        w.WriteLine("paired\t" + s.DeviceName + "\t" + s.Address + "\t" + Convert.ToHexString(s.DeviceId).ToLowerInvariant());
                        return;
                    case PairingState.Expired: w.WriteLine("expired"); return;
                    case PairingState.TooManyTries: w.WriteLine("toomany"); return;
                    case PairingState.UsedTwice: w.WriteLine("usedtwice"); return;
                    default: w.WriteLine("cancelled"); return;
                }
                Thread.Sleep(250);
            }
        }
        catch (Exception) {
            // The command went away (Ctrl+C, or the window closed) while the code still worked: it
            // stops working, as closing the window does on Windows. After pairing this changes
            // nothing.
            if (Volatile.Read(ref pairingRound) == round) {
                e.CancelPairing();
            }
            throw;
        }
    }

    // What the app shows, each time it changes, until the app goes away (a write fails)
    static void AnswerWatch(StreamWriter w)
    {
        w.WriteLine("port\t" + StarwireMicReceiver.Port);
        if (StarwireMicReceiver.AllowUnencrypted) {
            w.WriteLine("unencrypted\t" + StarwireMicReceiver.StreamName);
        }
        string status = null, mic = null, devices = null;
        Stopwatch clock = Stopwatch.StartNew();
        long beat = 0;
        while (true) {
            string s = "status\t" + StarwireMicReceiver.Status;
            if (s != status) {
                w.WriteLine(s);
                status = s;
            }
            string m = "mic\t" + (StarwireMicReceiver.Output.Ready ? "ready" : "missing");
            if (m != mic) {
                w.WriteLine(m);
                mic = m;
            }
            List<string> lines = DeviceLines();
            string d = "devices\t" + lines.Count + "\t" + Signature(lines);
            if (d != devices) {
                w.WriteLine(d);
                devices = d;
            }
            if (clock.ElapsedMilliseconds - beat >= BeatMs) {
                beat = clock.ElapsedMilliseconds;
                w.WriteLine("beat");
            }
            Thread.Sleep(WatchMs);
        }
    }

    // Changes when a device is paired, unpaired, renamed or used on another day
    static string Signature(List<string> lines)
    {
        ulong h = 14695981039346656037UL;
        foreach (byte b in Encoding.UTF8.GetBytes(string.Join("\n", lines))) {
            h = (h ^ b) * 1099511628211UL;
        }
        return h.ToString("x16");
    }

    // ---------------------------------------------------------------- the commands' side
    public static int Client(string path, string command, string argument, int seconds)
    {
        if (command == "unpair" && string.IsNullOrEmpty(argument)) {
            Console.Error.WriteLine("Say which device: starwire-mic unpair <id or name>. starwire-mic devices lists them.");
            return 2;
        }
        Socket s = ControlClient.Connect(path);
        if (s == null) {
            Console.Error.WriteLine("Starwire's microphone receiver isn't running. Start it with:");
            Console.Error.WriteLine("  systemctl --user start starwire-mic");
            Console.Error.WriteLine("and see what it says with: journalctl --user -u starwire-mic");
            return 3;
        }
        using (s) {
            switch (command) {
                case "status":
                    foreach (string line in ControlClient.Ask(s, "status")) {
                        Console.WriteLine(line);
                    }
                    return 0;
                case "devices":
                    return Devices(s);
                case "unpair":
                    return Unpair(s, argument);
                case "tone":
                    return Tone(s, seconds > 0 ? seconds : 3);
                case "check":
                    return ControlClient.Check(path, Console.WriteLine) ? 0 : 1;
                case "pair":
                    return Pair(s);
                case "watch":
                    StreamReader r = ControlClient.Send(s, "watch");
                    string l;
                    while ((l = r.ReadLine()) != null) {
                        Console.WriteLine(l);
                    }
                    return 0;
            }
        }
        return 2;
    }

    static int Devices(Socket s)
    {
        List<ControlClient.Device> devices = ControlClient.ParseDevices(ControlClient.Ask(s, "devices"));
        if (devices.Count == 0) {
            Console.WriteLine("No devices paired yet. To pair one: starwire-mic pair");
            return 0;
        }
        Console.WriteLine("Paired devices:");
        foreach (ControlClient.Device d in devices) {
            Console.WriteLine("  " + d.Name + ": " + d.When(DateTime.UtcNow) + " (id " + d.Id + ")");
        }
        Console.WriteLine("To unpair one: starwire-mic unpair <id or name>");
        return 0;
    }

    static int Unpair(Socket s, string which)
    {
        List<string> lines = ControlClient.Ask(s, "unpair " + which);
        string answer = lines.Count > 0 ? lines[0] : "error no answer";
        if (answer.StartsWith("ok ")) {
            Console.WriteLine("Unpaired " + answer.Substring(3) + ". It can't send its microphone to this PC until you pair it again.");
            return 0;
        }
        Console.Error.WriteLine(answer.StartsWith("error ") ? answer.Substring(6) : answer);
        return 1;
    }

    static int Tone(Socket s, int seconds)
    {
        Console.WriteLine("Playing a " + seconds + " second tone into the Starwire microphone...");
        List<string> lines = ControlClient.Ask(s, "tone " + Math.Min(seconds, 60) * 1000 + " 440");
        if (lines.Count > 0 && lines[0] == "ok") {
            Console.WriteLine("Done.");
            return 0;
        }
        Console.Error.WriteLine(lines.Count > 0 && lines[0] == "busy"
            ? "A device is sending its microphone right now. Try again when it stops."
            : "The receiver didn't play it: " + (lines.Count > 0 ? lines[0] : "no answer"));
        return 1;
    }

    static int Pair(Socket s)
    {
        Console.WriteLine(PairingModel.Instructions);
        Console.WriteLine("This PC: " + ControlClient.ThisPc());
        bool terminal = !Console.IsInputRedirected && !Console.IsOutputRedirected;
        if (terminal) {
            Console.WriteLine();
            Console.WriteLine(PairingModel.Caution);
            Console.Write("Press Enter to show the code (Ctrl+C to stop): ");
            if (Console.ReadLine() == null) {
                return 1;
            }
        }
        PairingModel pairing = new PairingModel();
        StreamReader r = ControlClient.Send(s, "pair");
        string line;
        bool counting = false;
        while ((line = r.ReadLine()) != null) {
            bool hadCode = pairing.Code != null;
            pairing.Feed(line);
            if (pairing.Code != null && !hadCode) {
                Console.WriteLine();
                Console.WriteLine("    " + pairing.Code);
                Console.WriteLine();
            }
            if (pairing.Step == PairingStep.Open) {
                if (line.StartsWith("open ")) {
                    if (terminal) {
                        Console.Write("\r" + pairing.TimeLeft + "  ");
                    }
                    else if (!counting) {
                        Console.WriteLine(pairing.TimeLeft);
                    }
                    counting = true;
                }
                continue;
            }
            break;
        }
        if (line == null) {
            pairing.Closed();
        }
        if (counting && terminal) {
            Console.WriteLine();
        }
        Console.WriteLine(pairing.Title);
        Console.WriteLine(pairing.Detail("Run starwire-mic pair again"));
        if (pairing.Step == PairingStep.Paired) {
            Console.WriteLine(PairingModel.NotMine);
            Console.WriteLine("  starwire-mic unpair " + pairing.DeviceId);
            return 0;
        }
        return 1;
    }
}
