// Starwire microphone app: the main window (GPL-3.0, part of Starwire). What the receiver is doing,
// the paired devices, and buttons for what the Windows receiver's icon menu has. Closing it leaves
// the receiver running, and the tray icon.

using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;

sealed class MainWindow : Window
{
    readonly App app;
    readonly StackPanel body = new StackPanel();

    public MainWindow(App app)
    {
        this.app = app;
        Title = "Starwire microphone";
        Icon = Ui.WindowIcon();
        Width = 600;
        SizeToContent = SizeToContent.Height;
        CanResize = false;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        StackPanel page = new StackPanel();
        page.Margin = new Thickness(24);
        page.Spacing = 18;

        // The icon and the name
        Image icon = new Image();
        icon.Source = Ui.IconBitmap();
        icon.Width = 52;
        icon.Height = 52;
        StackPanel name = new StackPanel();
        name.VerticalAlignment = VerticalAlignment.Center;
        name.Children.Add(Ui.Text("Starwire microphone", 22, true));
        name.Children.Add(Ui.Muted("Your Android device's microphone, on this PC"));
        StackPanel header = Ui.Row(icon, name);
        header.Spacing = 14;
        page.Children.Add(header);

        body.Spacing = 18;
        page.Children.Add(body);
        Content = page;

        Refresh(app);
        app.Changed += Changed;
        Closed += delegate { app.Changed -= Changed; };
    }

    void Changed() { Refresh(app); }

    void Refresh(App app)
    {
        TrayModel m = app.Model;
        body.Children.Clear();

        // What it's doing
        StackPanel status = new StackPanel();
        status.Spacing = 6;
        TextBlock headline = Ui.Text(m.Headline, 17, true);
        if (m.Receiving) {
            headline.Foreground = new SolidColorBrush(Color.FromRgb(0x2E, 0xA0, 0x43));
        }
        status.Children.Add(headline);
        status.Children.Add(Ui.Text(m.Hint));
        if (m.Connected) {
            status.Children.Add(Ui.Muted("Microphone: " + (m.MicReady ? "\"Starwire microphone\", ready" : "not there right now")));
            status.Children.Add(Ui.Muted("This PC: " + ControlClient.ThisPc() + (m.PortLine.Length > 0 ? ", " + m.PortLine : "")));
        }
        body.Children.Add(Ui.Card(status));
        if (m.Warning != null) {
            body.Children.Add(Ui.Warning(m.Warning));
        }

        if (!m.Connected) {
            body.Children.Add(Ui.Row(Ui.Button("Start the receiver", app.StartReceiver, true), Ui.Button("Show the log", app.ShowLog)));
            body.Children.Add(Ui.Muted("If it doesn't start, the log says why. Run the setup again if the receiver was removed."));
            return;
        }

        // The paired devices
        StackPanel devices = new StackPanel();
        devices.Spacing = 10;
        devices.Children.Add(Ui.Text("Paired devices", 15, true));
        if (app.Devices.Count == 0) {
            devices.Children.Add(Ui.Muted("No devices paired yet."));
        }
        foreach (ControlClient.Device d in app.Devices) {
            ControlClient.Device chosen = d;
            Grid row = new Grid();
            row.ColumnDefinitions = new ColumnDefinitions("*,Auto");
            StackPanel who = new StackPanel();
            who.VerticalAlignment = VerticalAlignment.Center;
            who.Children.Add(Ui.Text(d.Name, 0, true, 360));
            who.Children.Add(Ui.Muted(d.When(DateTime.UtcNow), 360));
            Button unpair = Ui.Button("Unpair", () => app.Unpair(chosen, this));
            unpair.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(unpair, 1);
            row.Children.Add(who);
            row.Children.Add(unpair);
            devices.Children.Add(row);
        }
        body.Children.Add(devices);

        // What the Windows icon's menu has
        WrapPanel actions = new WrapPanel();
        actions.ItemSpacing = 8;
        actions.LineSpacing = 8;
        actions.Children.Add(Ui.Button("Pair a device...", app.OpenPairing, true));
        actions.Children.Add(Ui.Button("Check the microphone", () => app.Check(this)));
        actions.Children.Add(Ui.Button("Play a test tone", () => app.Tone(this)));
        actions.Children.Add(Ui.Button("Show the log", app.ShowLog));
        body.Children.Add(actions);

        StackPanel footer = new StackPanel();
        footer.Spacing = 6;
        footer.Children.Add(Ui.Muted("Closing this window keeps the microphone working. The Starwire icon in the system tray has the same menu."));
        Button guide = Ui.Button("Setup guide", app.OpenGuide);
        footer.Children.Add(guide);
        body.Children.Add(footer);
    }
}
