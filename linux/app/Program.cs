// Starwire microphone app for Linux (GPL-3.0, part of Starwire).
//
// A window and a tray icon for Starwire's microphone receiver, which runs as a systemd user service
// (../StarwireMicReceiver.cs). The app only talks to it, over the receiver's socket, the way the
// starwire-mic commands do: it can be closed, crash or be left out, and the microphone goes on.
//
//   starwire-mic-app                    opens the window (from the app menu)
//   starwire-mic-app --tray             starts with only the tray icon (at sign-in)
//   starwire-mic-app --write-icon <png> writes the Starwire icon as a PNG, for the app menu
//   starwire-mic-app --render <folder>  draws the windows in each state to PNGs and exits (to check
//                                       how they look)
//   --pairings-file <path>              talks to the receiver that keeps its paired devices there
//
// One app runs at a time: starting it again shows the window of the one that's running.

using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using Avalonia;

static class Program
{
    public static bool StartInTray;
    public static string PairingsFile;
    public static string RenderFolder;

    [STAThread]
    static int Main(string[] args)
    {
        for (int i = 0; i < args.Length; i++) {
            string a = args[i];
            if (a == "--tray") {
                StartInTray = true;
            }
            else if (a == "--pairings-file" && i + 1 < args.Length) {
                PairingsFile = Path.GetFullPath(args[++i]);
            }
            else if (a == "--render" && i + 1 < args.Length) {
                RenderFolder = Path.GetFullPath(args[++i]);
            }
            else if (a == "--write-icon" && i + 1 < args.Length) {
                File.WriteAllBytes(args[++i], Ui.IconPng());
                return 0;
            }
            else {
                Console.Error.WriteLine("starwire-mic-app [--tray] [--pairings-file <path>] [--write-icon <png>] [--render <folder>]");
                return 2;
            }
        }
        if (RenderFolder == null && TellRunningApp(StartInTray ? "tray" : "show")) {
            return 0;
        }
        return AppBuilder.Configure<App>().UsePlatformDetect().LogToTrace()
            .StartWithClassicDesktopLifetime(args, Avalonia.Controls.ShutdownMode.OnExplicitShutdown);
    }

    // The app's own socket, next to the receiver's: a second start tells the first to show itself
    public static string AppSocketPath()
    {
        return Path.Combine(ControlClient.ControlFolder(PairingsFile), "app" + ControlClient.Suffix(PairingsFile));
    }

    static bool TellRunningApp(string what)
    {
        using (Socket s = ControlClient.Connect(AppSocketPath())) {
            if (s == null) {
                return false;
            }
            try {
                s.Send(Encoding.UTF8.GetBytes(what + "\n"));
            }
            catch (SocketException) {
                return false;
            }
            return true;
        }
    }
}
