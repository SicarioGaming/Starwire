// Starwire microphone app: "Pair a device..." (GPL-3.0, part of Starwire), as the Windows
// receiver's pairing window has it. It shows a one-time code, once asked to, until a device pairs
// with it, it runs out, or the window is closed, which stops it working. Anyone who sees the code
// before the user has typed it can pair first, so the code only shows when asked, and the window
// then says where the pairing came from and can undo it. If another device uses the code too, the
// receiver undoes the pairing itself and a notification says so.

using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Threading;

sealed class PairingWindow : Window
{
    const string Again = "Choose Pair a device again";

    readonly App app;
    PairingModel model = new PairingModel();
    bool started;
    Socket socket;

    readonly TextBlock intro = Ui.Text(PairingModel.Instructions);
    readonly TextBlock caution = Ui.Muted(PairingModel.Caution);
    readonly Button showCode;
    readonly TextBlock code = Ui.Text("", 34, true);
    readonly TextBlock time = Ui.Text("");
    readonly TextBlock pc = Ui.Muted("");
    readonly TextBlock notMine = Ui.Text(PairingModel.NotMine);
    readonly Button unpair;
    readonly Button close;

    public PairingWindow(App app) : this(app, new PairingModel(), false)
    {
    }

    // With a model and whether the code was asked for: --render shows each state this way
    public PairingWindow(App app, PairingModel model, bool started)
    {
        this.app = app;
        this.model = model;
        this.started = started;
        Title = "Pair a device - Starwire microphone";
        Icon = Ui.WindowIcon();
        Width = 520;
        SizeToContent = SizeToContent.Height;
        CanResize = false;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        showCode = Ui.Button("Show the code", Begin, true);
        showCode.HorizontalAlignment = HorizontalAlignment.Center;
        showCode.Margin = new Thickness(0, 6, 0, 6);
        code.FontFamily = Ui.Mono;
        code.HorizontalAlignment = HorizontalAlignment.Center;
        code.Margin = new Thickness(0, 6, 0, 6);
        code.MaxWidth = double.PositiveInfinity;
        pc.Text = "This PC: " + ControlClient.ThisPc();
        unpair = Ui.Button("Unpair it", UnpairIt);
        close = Ui.Button("Cancel", Close);
        close.HorizontalAlignment = HorizontalAlignment.Right;

        StackPanel page = new StackPanel();
        page.Margin = new Thickness(24);
        page.Spacing = 12;
        foreach (Control c in new Control[] { intro, caution, showCode, code, time, pc, notMine, unpair, close }) {
            page.Children.Add(c);
        }
        Content = page;
        Closed += delegate {
            // While the code still works, closing the window stops it (the receiver sees the
            // connection go)
            Socket s = socket;
            socket = null;
            if (s != null) {
                s.Dispose();
            }
        };
        Refresh();
    }

    void Begin()
    {
        started = true;
        Socket s = ControlClient.Connect(app.ControlPath);
        if (s == null) {
            model.Closed();
            Refresh();
            return;
        }
        socket = s;
        Thread reader = new Thread(() => {
            try {
                StreamReader r = ControlClient.Send(s, "pair");
                string line;
                while ((line = r.ReadLine()) != null) {
                    string l = line;
                    Dispatcher.UIThread.Post(() => {
                        model.Feed(l);
                        Refresh();
                    });
                }
            }
            catch (IOException) {
            }
            catch (SocketException) {
            }
            catch (ObjectDisposedException) {
            }
            Dispatcher.UIThread.Post(() => {
                model.Closed();
                Refresh();
            });
        });
        reader.IsBackground = true;
        reader.Name = "pair";
        reader.Start();
        Refresh();
    }

    // What the window shows for where the pairing stands
    void Refresh()
    {
        bool open = started && !model.Finished;
        caution.IsVisible = !started;
        showCode.IsVisible = !started;
        code.IsVisible = open && model.Code != null;
        code.Text = model.Code ?? "";
        time.IsVisible = open || model.Finished;
        pc.IsVisible = !model.Finished;
        notMine.IsVisible = model.Step == PairingStep.Paired;
        unpair.IsVisible = model.Step == PairingStep.Paired;
        if (open) {
            intro.Text = PairingModel.Instructions;
            time.Text = model.Code == null ? "Asking the receiver for a code..." : model.TimeLeft;
            close.Content = "Cancel";
            return;
        }
        if (model.Finished) {
            // The outcome in big letters where the instructions were, then what it means
            intro.Text = model.Title;
            intro.FontSize = 18;
            intro.FontWeight = Avalonia.Media.FontWeight.SemiBold;
            time.Text = model.Step == PairingStep.Paired
                ? model.Detail(Again) + " To unpair it later, use Unpair in the Starwire microphone window."
                : model.Detail(Again);
            close.Content = "Close";
        }
    }

    // Someone else paired with the code: they go, like any unpaired device
    async void UnpairIt()
    {
        List<string> lines = await Task.Run(() => ControlClient.Ask(app.ControlPath, "unpair " + model.DeviceId));
        string answer = lines != null && lines.Count > 0 ? lines[0] : "error Starwire's microphone receiver isn't running.";
        if (!answer.StartsWith("ok ")) {
            await Ui.Message(this, "Couldn't unpair it", answer.StartsWith("error ") ? answer.Substring(6) : answer, true);
            return;
        }
        notMine.IsVisible = false;
        unpair.IsVisible = false;
        intro.Text = "Unpaired.";
        time.Text = "It can't send sound to this PC. Choose Pair a device again for a new code, and show it only where nobody "
            + "else can see it.";
    }
}
