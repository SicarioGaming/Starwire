// Starwire microphone app: the tray icon, the connection to the receiver, and what the windows and
// the menu do (GPL-3.0, part of Starwire).
//
// A background thread asks the receiver to "watch" and posts each line to the UI thread, where
// TrayModel keeps what it says. When the receiver isn't running (or stops), it tries again every two
// seconds, and the app says so and offers to start it.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;

sealed class App : Application
{
    public const string Guide = "https://github.com/SicarioGaming/Starwire/tree/main/linux#readme";
    const int RetryMs = 2000;
    // The receiver says "beat" every 2 seconds: nothing for this long means it has gone
    const int SilentMs = 6000;

    public readonly TrayModel Model = new TrayModel();
    public List<ControlClient.Device> Devices = new List<ControlClient.Device>();
    public string ControlPath;
    public event Action Changed;

    TrayIcon tray;
    MainWindow main;
    PairingWindow pairing;
    LogWindow log;
    string menuShown;
    volatile bool quitting;
    Socket appSocket;

    public static App Instance { get { return (App) Current; } }

    public override void Initialize()
    {
        Styles.Add(new FluentTheme());
        // Light or dark, as the desktop is
        RequestedThemeVariant = ThemeVariant.Default;
        Name = "Starwire microphone";
    }

    public override void OnFrameworkInitializationCompleted()
    {
        base.OnFrameworkInitializationCompleted();
        ControlPath = ControlClient.SocketPath(Program.PairingsFile);
        if (Program.RenderFolder != null) {
            Dispatcher.UIThread.Post(() => Render.All(Program.RenderFolder), DispatcherPriority.Background);
            return;
        }
        tray = new TrayIcon();
        tray.Icon = Ui.WindowIcon();
        tray.ToolTipText = "Starwire microphone";
        tray.Clicked += delegate { ShowMain(); };
        TrayIcons icons = new TrayIcons();
        icons.Add(tray);
        TrayIcon.SetIcons(this, icons);
        tray.IsVisible = true;
        RebuildMenu();

        Thread watcher = new Thread(Watch);
        watcher.IsBackground = true;
        watcher.Name = "watch";
        watcher.Start();
        ListenForOtherStarts();
        if (!Program.StartInTray) {
            ShowMain();
        }
    }

    // ---------------------------------------------------------------- the receiver
    void Watch()
    {
        while (!quitting) {
            Socket s = ControlClient.Connect(ControlPath);
            if (s != null) {
                using (s) {
                    try {
                        s.ReceiveTimeout = SilentMs;
                        StreamReader r = ControlClient.Send(s, "watch");
                        string line;
                        while ((line = r.ReadLine()) != null) {
                            string l = line;
                            Dispatcher.UIThread.Post(() => Feed(l));
                        }
                    }
                    catch (IOException) {
                    }
                    catch (SocketException) {
                    }
                }
            }
            Dispatcher.UIThread.Post(Lost);
            Thread.Sleep(RetryMs);
        }
    }

    void Feed(string line)
    {
        if (!Model.Feed(line)) {
            return;
        }
        if (Model.DevicesChanged) {
            Model.DevicesChanged = false;
            Task.Run(() => {
                List<string> lines = ControlClient.Ask(ControlPath, "devices");
                if (lines != null) {
                    List<ControlClient.Device> list = ControlClient.ParseDevices(lines);
                    Dispatcher.UIThread.Post(() => {
                        Devices = list;
                        Refresh();
                    });
                }
            });
        }
        Refresh();
    }

    void Lost()
    {
        if (!Model.Connected && Devices.Count == 0 && menuShown != null) {
            return;
        }
        Model.Lost();
        Devices = new List<ControlClient.Device>();
        Refresh();
    }

    void Refresh()
    {
        if (tray != null) {
            tray.ToolTipText = Model.Tooltip;
            RebuildMenu();
        }
        if (Changed != null) {
            Changed();
        }
    }

    // ---------------------------------------------------------------- the tray's menu
    // Made again only when what it shows changed
    void RebuildMenu()
    {
        string key = Model.Connected + "|" + Model.Headline + "|" + Model.MicReady + "|" + Model.Unencrypted + "|" + Model.DevicesSignature
            + "|" + Devices.Count;
        if (key == menuShown) {
            return;
        }
        menuShown = key;
        NativeMenu menu = new NativeMenu();
        NativeMenuItem status = new NativeMenuItem(Model.Headline);
        status.IsEnabled = false;
        menu.Items.Add(status);
        if (Model.Warning != null) {
            NativeMenuItem warning = new NativeMenuItem("Not safe: unencrypted VBAN plays too");
            warning.IsEnabled = false;
            menu.Items.Add(warning);
        }
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(Item("Open Starwire microphone", ShowMain));
        if (Model.Connected) {
            menu.Items.Add(Item("Pair a device...", OpenPairing));
            NativeMenuItem paired = new NativeMenuItem("Paired devices");
            NativeMenu list = new NativeMenu();
            if (Devices.Count == 0) {
                NativeMenuItem none = new NativeMenuItem("No devices paired yet");
                none.IsEnabled = false;
                list.Items.Add(none);
            }
            foreach (ControlClient.Device d in Devices) {
                ControlClient.Device chosen = d;
                NativeMenuItem item = new NativeMenuItem(Ui.Short(d.Name) + ", " + d.When(DateTime.UtcNow));
                NativeMenu sub = new NativeMenu();
                sub.Items.Add(Item("Unpair", () => Unpair(chosen, null)));
                item.Menu = sub;
                list.Items.Add(item);
            }
            paired.Menu = list;
            menu.Items.Add(paired);
            menu.Items.Add(new NativeMenuItemSeparator());
            menu.Items.Add(Item("Check the microphone", () => Check(null)));
            menu.Items.Add(Item("Play a test tone (for Discord's mic test)", () => Tone(null)));
        }
        else {
            menu.Items.Add(Item("Start the receiver", StartReceiver));
        }
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(Item("Show the log", ShowLog));
        menu.Items.Add(Item("Setup guide", OpenGuide));
        if (Model.Connected) {
            menu.Items.Add(Item("Stop the receiver...", () => StopReceiver(null)));
        }
        menu.Items.Add(Item("Quit (the receiver keeps running)", Quit));
        tray.Menu = menu;
    }

    static NativeMenuItem Item(string header, Action click)
    {
        NativeMenuItem item = new NativeMenuItem(header);
        item.Click += delegate { click(); };
        return item;
    }

    // ---------------------------------------------------------------- what the app does
    public void ShowMain()
    {
        if (main == null) {
            main = new MainWindow(this);
            main.Closed += delegate { main = null; };
        }
        main.Show();
        if (main.WindowState == WindowState.Minimized) {
            main.WindowState = WindowState.Normal;
        }
        main.Activate();
    }

    public void OpenPairing()
    {
        if (pairing != null) {
            pairing.Activate();
            return;
        }
        pairing = new PairingWindow(this);
        pairing.Closed += delegate { pairing = null; };
        pairing.Show();
        pairing.Activate();
    }

    public void ShowLog()
    {
        if (log != null) {
            log.Activate();
            return;
        }
        log = new LogWindow();
        log.Closed += delegate { log = null; };
        log.Show();
        log.Activate();
    }

    public async void Unpair(ControlClient.Device d, Window owner)
    {
        if (!await Ui.Confirm(owner, "Unpair " + d.Name + "?", "It can't send its microphone to this PC until you pair it again.", "Unpair")) {
            return;
        }
        List<string> lines = await Task.Run(() => ControlClient.Ask(ControlPath, "unpair " + d.Id));
        string answer = lines == null ? "error Starwire's microphone receiver isn't running." : lines.Count > 0 ? lines[0] : "error no answer";
        if (!answer.StartsWith("ok ")) {
            await Ui.Message(owner, "Couldn't unpair " + d.Name, answer.StartsWith("error ") ? answer.Substring(6) : answer, true);
        }
    }

    public async void Check(Window owner)
    {
        List<string> lines = new List<string>();
        bool works = await Task.Run(() => ControlClient.Check(ControlPath, lines.Add));
        if (lines.Count > 0 && lines[0].StartsWith("Checking")) {
            lines.RemoveAt(0);
        }
        await Ui.Message(owner, works ? "The microphone works" : "The microphone didn't pass the check", string.Join("\n", lines), !works);
    }

    public async void Tone(Window owner)
    {
        List<string> lines = await Task.Run(() => ControlClient.Ask(ControlPath, "tone 3000 440"));
        string answer = lines != null && lines.Count > 0 ? lines[0] : null;
        if (answer == "busy") {
            await Ui.Message(owner, "Not now", "A device is sending its microphone right now. Try again when it stops.", true);
        }
        else if (answer != "ok") {
            await Ui.Message(owner, "The tone didn't play", lines == null ? "Starwire's microphone receiver isn't running." : answer ?? "No answer.", true);
        }
    }

    public void StartReceiver()
    {
        Systemctl("start");
    }

    public async void StopReceiver(Window owner)
    {
        if (await Ui.Confirm(owner, "Stop the receiver?", "The Starwire microphone goes away until you start it again or sign in again.", "Stop")) {
            Systemctl("stop");
        }
    }

    static void Systemctl(string what)
    {
        Task.Run(() => {
            try {
                ProcessStartInfo start = new ProcessStartInfo("systemctl");
                start.ArgumentList.Add("--user");
                start.ArgumentList.Add(what);
                start.ArgumentList.Add("starwire-mic");
                start.UseShellExecute = false;
                using (Process p = Process.Start(start)) {
                    p.WaitForExit(10000);
                }
            }
            catch (System.ComponentModel.Win32Exception) {
            }
        });
    }

    public void OpenGuide()
    {
        Ui.Open(Guide);
    }

    public void Quit()
    {
        quitting = true;
        if (tray != null) {
            tray.IsVisible = false;
            tray.Dispose();
        }
        if (appSocket != null) {
            appSocket.Dispose();
            try {
                File.Delete(Program.AppSocketPath());
            }
            catch (IOException) {
            }
        }
        ((IClassicDesktopStyleApplicationLifetime) ApplicationLifetime).Shutdown();
    }

    // ---------------------------------------------------------------- one app at a time
    void ListenForOtherStarts()
    {
        string path = Program.AppSocketPath();
        try {
            string folder = Path.GetDirectoryName(path);
            if (!Directory.Exists(folder)) {
                Directory.CreateDirectory(folder, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }
            // Nothing answered there when this one started, so a socket left there is an old one's
            if (File.Exists(path)) {
                File.Delete(path);
            }
            appSocket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            appSocket.Bind(new UnixDomainSocketEndPoint(path));
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            appSocket.Listen(4);
        }
        catch (Exception e) {
            if (!(e is SocketException || e is IOException || e is UnauthorizedAccessException)) {
                throw;
            }
            return;
        }
        Thread t = new Thread(() => {
            byte[] b = new byte[16];
            while (!quitting) {
                try {
                    using (Socket c = appSocket.Accept()) {
                        c.ReceiveTimeout = 1000;
                        int n = c.Receive(b);
                        if (n >= 4 && b[0] == (byte) 's' && b[1] == (byte) 'h') {
                            Dispatcher.UIThread.Post(ShowMain);
                        }
                    }
                }
                catch (SocketException) {
                }
                catch (ObjectDisposedException) {
                    return;
                }
            }
        });
        t.IsBackground = true;
        t.Name = "other starts";
        t.Start();
    }
}
