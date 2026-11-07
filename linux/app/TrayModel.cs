// Starwire microphone app: what the receiver is doing, as the app shows it (GPL-3.0, part of
// Starwire).
//
// The app asks the receiver to "watch" (Control.cs lists what that sends) and feeds each line here.
// This has no Avalonia in it, so tests/LinuxTests.cs checks it without a screen.

using System;

sealed class TrayModel
{
    public bool Connected;
    public string Status = "";
    public bool MicReady;
    public int PairedCount;
    public string DevicesSignature;
    public int Port;
    public string Unencrypted;   // the stream name, if --allow-unencrypted is on

    // Set when the paired devices changed: the app asks for the list again
    public bool DevicesChanged;

    // Takes one line of "watch"; true if anything the app shows changed
    public bool Feed(string line)
    {
        string[] f = line.Split('\t');
        bool was = Connected;
        Connected = true;
        switch (f[0]) {
            case "status":
                if (f.Length < 2 || f[1] == Status) {
                    return !was;
                }
                Status = f[1];
                return true;
            case "mic":
                bool ready = f.Length > 1 && f[1] == "ready";
                if (ready == MicReady && was) {
                    return false;
                }
                MicReady = ready;
                return true;
            case "devices":
                int count;
                if (f.Length < 3 || !int.TryParse(f[1], out count) || f[2] == DevicesSignature) {
                    return !was;
                }
                PairedCount = count;
                DevicesSignature = f[2];
                DevicesChanged = true;
                return true;
            case "port":
                int port;
                if (f.Length > 1 && int.TryParse(f[1], out port)) {
                    Port = port;
                }
                return true;
            case "unencrypted":
                Unencrypted = f.Length > 1 ? f[1] : "";
                return true;
            default:
                // "beat", or something a later receiver sends
                return !was;
        }
    }

    // The connection to the receiver went away (it stopped, or isn't running yet)
    public void Lost()
    {
        Connected = false;
        Status = "";
        MicReady = false;
        DevicesSignature = null;
        Unencrypted = null;
    }

    public bool Receiving { get { return Connected && Status.StartsWith("Receiving"); } }

    // The status in a few words, for the main window's heading and the tray's menu
    public string Headline
    {
        get {
            if (!Connected) {
                return "The receiver isn't running";
            }
            if (Receiving) {
                // "Receiving the microphone from Pixel 8 (encrypted)"
                return "Receiving from " + Sender;
            }
            if (PairedCount == 0 && Unencrypted == null) {
                return "No device paired yet";
            }
            return "Waiting for a paired device";
        }
    }

    // Who's sending, from "Receiving the microphone from <name> (encrypted)"
    public string Sender
    {
        get {
            const string from = "Receiving the microphone from ";
            if (!Status.StartsWith(from)) {
                return "";
            }
            string s = Status.Substring(from.Length);
            int bracket = s.LastIndexOf(" (");
            return bracket > 0 ? s.Substring(0, bracket) : s;
        }
    }

    // The line under the heading: what to do next
    public string Hint
    {
        get {
            if (!Connected) {
                return "Start it to use the Starwire microphone. It starts by itself when you sign in.";
            }
            if (!MicReady) {
                return "The Starwire microphone isn't there right now. Is PipeWire running?";
            }
            if (PairedCount == 0 && Unencrypted == null) {
                return "Pair your Android device: choose Pair a device, and type the code it shows into Starwire.";
            }
            if (Receiving) {
                return "Apps that use the Starwire microphone hear your device now.";
            }
            return "Start a stream in Starwire with Send my microphone on. Choose \"Starwire microphone\" in Discord or the game.";
        }
    }

    // The tray icon's hover text
    public string Tooltip
    {
        get {
            if (!Connected) {
                return "Starwire microphone: the receiver isn't running";
            }
            if (!MicReady) {
                return "Starwire microphone: not there right now (is PipeWire running?)";
            }
            return "Starwire microphone: " + Headline;
        }
    }

    public string PortLine
    {
        get { return Connected && Port > 0 ? "UDP port " + Port : ""; }
    }

    public string Warning
    {
        get {
            return Unencrypted == null ? null
                : "Not safe: unencrypted VBAN named \"" + Unencrypted + "\" plays too (--allow-unencrypted). Anyone on this network "
                    + "can send sound into this microphone or listen to it.";
        }
    }
}
