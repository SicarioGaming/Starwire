// Starwire microphone receiver for Linux: talking to the receiver (GPL-3.0, part of Starwire).
//
// The receiver runs as a systemd user service and answers commands over a Unix socket (Control.cs
// has the receiver's side and the list of commands). This is the other side, shared by the
// starwire-mic commands and the Starwire microphone app (app/), so both find the receiver and ask
// it things the same way. It doesn't need SecureMic.cs.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

static class ControlClient
{
    // ---------------------------------------------------------------- where
    // The folder for the socket and the lock: in $XDG_RUNTIME_DIR, which only this user can open,
    // else next to the pairings file (in a folder Starwire keeps 0700)
    public static string ControlFolder(string pairingsFile)
    {
        string runtime = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
        if (!string.IsNullOrEmpty(runtime) && Directory.Exists(runtime)) {
            return Path.Combine(runtime, "starwire-mic");
        }
        return Path.GetDirectoryName(pairingsFile != null ? pairingsFile : DefaultPairingsFile());
    }

    // Where MicPairingStore.DefaultPath() keeps the paired devices
    public static string DefaultPairingsFile()
    {
        string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Starwire");
        return Path.Combine(folder, "mic-pairings.dat");
    }

    // The usual receiver's names, or ones made from its pairings file's path, so a test receiver
    // can run next to it
    public static string Suffix(string pairingsFile)
    {
        if (pairingsFile == null) {
            return "";
        }
        using (SHA256 sha = SHA256.Create()) {
            return "-" + Convert.ToHexString(sha.ComputeHash(Encoding.UTF8.GetBytes(pairingsFile)), 0, 8).ToLowerInvariant();
        }
    }

    public static string SocketPath(string pairingsFile)
    {
        return Path.Combine(ControlFolder(pairingsFile), "control" + Suffix(pairingsFile));
    }

    // ---------------------------------------------------------------- asking
    // A connection to the receiver, or null if it isn't running
    public static Socket Connect(string path)
    {
        Socket s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        try {
            s.Connect(new UnixDomainSocketEndPoint(path));
            return s;
        }
        catch (SocketException) {
            s.Dispose();
            return null;
        }
    }

    // Sends one line; the answer's lines come from the reader
    public static StreamReader Send(Socket s, string line)
    {
        NetworkStream stream = new NetworkStream(s, false);
        byte[] b = Encoding.UTF8.GetBytes(line + "\n");
        stream.Write(b, 0, b.Length);
        return new StreamReader(stream, new UTF8Encoding(false));
    }

    // Sends one line and reads every line of the answer
    public static List<string> Ask(Socket s, string line)
    {
        List<string> lines = new List<string>();
        StreamReader r = Send(s, line);
        string l;
        while ((l = r.ReadLine()) != null) {
            lines.Add(l);
        }
        return lines;
    }

    // One question on a connection of its own; null if the receiver isn't running
    public static List<string> Ask(string path, string line)
    {
        using (Socket s = Connect(path)) {
            return s == null ? null : Ask(s, line);
        }
    }

    // ---------------------------------------------------------------- answers
    public sealed class Device
    {
        public string Id;
        public string Name;
        public string Paired;     // yyyy-MM-dd
        public string LastUsed;   // yyyy-MM-dd, or null if it hasn't been used yet

        // "paired 2026-11-07, not used yet" or "last used today"
        public string When(DateTime todayUtc)
        {
            string today = todayUtc.ToString("yyyy-MM-dd");
            if (LastUsed == null) {
                return "paired " + (Paired == today ? "today" : Paired) + ", not used yet";
            }
            return "last used " + (LastUsed == today ? "today" : LastUsed);
        }
    }

    // The answer to "devices": id, name, paired, last used, with tabs between
    public static List<Device> ParseDevices(List<string> lines)
    {
        List<Device> devices = new List<Device>();
        foreach (string line in lines) {
            string[] f = line.Split('\t');
            if (f.Length != 4) {
                continue;
            }
            devices.Add(new Device { Id = f[0], Name = f[1], Paired = f[2], LastUsed = f[3] == "-" ? null : f[3] });
        }
        return devices;
    }

    // ---------------------------------------------------------------- checking the microphone
    // Records from the microphone with pw-record while the receiver plays a tone into it: a
    // microphone that works passes it straight through. Says how it went; true if it works.
    public static bool Check(string path, Action<string> say)
    {
        List<string> node = Ask(path, "node");
        if (node == null) {
            say("Starwire's microphone receiver isn't running.");
            return false;
        }
        if (node.Count != 1) {
            say("The receiver didn't say which microphone is its own.");
            return false;
        }
        say("Checking the Starwire microphone (PipeWire node " + node[0] + ")...");
        ProcessStartInfo start = new ProcessStartInfo("pw-record");
        foreach (string a in new[] { "--raw", "--target", node[0], "--rate", "48000", "--channels", "1", "--format", "s16", "-" }) {
            start.ArgumentList.Add(a);
        }
        start.UseShellExecute = false;
        start.RedirectStandardOutput = true;
        start.RedirectStandardError = true;
        Process recorder;
        try {
            recorder = Process.Start(start);
        }
        catch (System.ComponentModel.Win32Exception) {
            say("pw-record isn't installed (it comes with PipeWire).");
            return false;
        }
        int peak = 0;
        long samples = 0;
        Thread reading = new Thread(() => {
            byte[] b = new byte[4096];
            Stream o = recorder.StandardOutput.BaseStream;
            int n, odd = -1;
            while ((n = o.Read(b, 0, b.Length)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (odd < 0) {
                        odd = b[i];
                        continue;
                    }
                    int v = Math.Abs((int) (short) (odd | b[i] << 8));
                    odd = -1;
                    samples++;
                    if (v > peak) {
                        peak = v;
                    }
                }
            }
        });
        reading.IsBackground = true;
        reading.Start();
        Thread.Sleep(500);
        List<string> lines = Ask(path, "tone 1500 1000");
        string answer = lines != null && lines.Count > 0 ? lines[0] : "no answer";
        Thread.Sleep(300);
        try {
            recorder.Kill();
        }
        catch (InvalidOperationException) {
        }
        recorder.WaitForExit(2000);
        reading.Join(2000);
        if (answer == "busy") {
            say("A device is sending its microphone right now. Try again when it stops.");
            return false;
        }
        if (samples == 0) {
            say("pw-record didn't record anything from the microphone: " + recorder.StandardError.ReadToEnd().Trim());
            return false;
        }
        // The tone is 8000; anything over a tenth of that means it came through
        if (answer == "ok" && peak > 800) {
            say("Works: the microphone passed the test tone on (loudest sample " + peak + ").");
            return true;
        }
        say("No sound came through (loudest sample " + peak + ", receiver said " + answer + ").");
        return false;
    }

    // ---------------------------------------------------------------- this PC
    // This PC's IPv4 addresses, to tell it apart in Starwire's list of PCs
    public static string Addresses()
    {
        List<string> found = new List<string>();
        try {
            foreach (NetworkInterface n in NetworkInterface.GetAllNetworkInterfaces()) {
                if (n.OperationalStatus != OperationalStatus.Up && n.OperationalStatus != OperationalStatus.Unknown
                        || n.NetworkInterfaceType == NetworkInterfaceType.Loopback) {
                    continue;
                }
                foreach (UnicastIPAddressInformation a in n.GetIPProperties().UnicastAddresses) {
                    string text = a.Address.ToString();
                    if (a.Address.AddressFamily == AddressFamily.InterNetwork && !text.StartsWith("169.254.") && found.Count < 4) {
                        found.Add(text);
                    }
                }
            }
        }
        catch (NetworkInformationException) {
        }
        return string.Join(", ", found);
    }

    public static string ThisPc()
    {
        string addresses = Addresses();
        return Environment.MachineName + (addresses.Length > 0 ? ", " + addresses : "");
    }
}
