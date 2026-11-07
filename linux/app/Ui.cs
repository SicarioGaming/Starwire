// Starwire microphone app: the pieces every window uses (GPL-3.0, part of Starwire): the icon,
// text, buttons, the small yes/no and message windows, and --render.
//
// The windows are made in code, as the Windows receiver's are, with Avalonia's Fluent theme, light
// or dark as the desktop is. User-facing text follows the repo's rules: plain English, short
// sentences, no em dashes.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;

static class Ui
{
    public const double TextWidth = 460;
    public static readonly FontFamily Mono = new FontFamily("Noto Sans Mono, DejaVu Sans Mono, Liberation Mono, monospace");

    // ---------------------------------------------------------------- the icon
    static byte[] iconPng;

    // The largest picture in windows/starwire-mic.ico (each one in it is a PNG), so the app needs no
    // icon file of its own
    public static byte[] IconPng()
    {
        if (iconPng != null) {
            return iconPng;
        }
        byte[] ico;
        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("starwire-mic.ico")) {
            MemoryStream m = new MemoryStream();
            s.CopyTo(m);
            ico = m.ToArray();
        }
        int count = BitConverter.ToUInt16(ico, 4);
        int best = -1, bestSize = 0;
        for (int i = 0; i < count; i++) {
            int at = 6 + 16 * i;
            int width = ico[at] == 0 ? 256 : ico[at];
            int offset = BitConverter.ToInt32(ico, at + 12);
            bool png = ico[offset] == 0x89 && ico[offset + 1] == (byte) 'P' && ico[offset + 2] == (byte) 'N' && ico[offset + 3] == (byte) 'G';
            if (png && width > bestSize) {
                best = i;
                bestSize = width;
            }
        }
        if (best < 0) {
            throw new InvalidDataException("starwire-mic.ico has no PNG picture in it");
        }
        int size = BitConverter.ToInt32(ico, 6 + 16 * best + 8);
        int start = BitConverter.ToInt32(ico, 6 + 16 * best + 12);
        iconPng = new byte[size];
        Array.Copy(ico, start, iconPng, 0, size);
        return iconPng;
    }

    public static Bitmap IconBitmap() { return new Bitmap(new MemoryStream(IconPng())); }

    public static WindowIcon WindowIcon() { return new WindowIcon(IconBitmap()); }

    // ---------------------------------------------------------------- text and buttons
    public static TextBlock Text(string text, double size = 0, bool bold = false, double maxWidth = TextWidth)
    {
        TextBlock t = new TextBlock();
        t.Text = text;
        t.TextWrapping = TextWrapping.Wrap;
        t.MaxWidth = maxWidth;
        t.HorizontalAlignment = HorizontalAlignment.Left;
        if (size > 0) {
            t.FontSize = size;
        }
        if (bold) {
            t.FontWeight = FontWeight.SemiBold;
        }
        return t;
    }

    // Grey: less important than the text around it
    public static TextBlock Muted(string text, double maxWidth = TextWidth)
    {
        TextBlock t = Text(text, 0, false, maxWidth);
        t.Opacity = 0.7;
        return t;
    }

    public static Button Button(string label, Action click, bool accent = false)
    {
        Button b = new Button();
        b.Content = label;
        b.Click += delegate { click(); };
        if (accent) {
            b.Classes.Add("accent");
        }
        return b;
    }

    // A device's name in a menu: long ones are cut
    public static string Short(string name)
    {
        return name.Length > 40 ? name.Substring(0, 39) + "..." : name;
    }

    public static StackPanel Row(params Control[] items)
    {
        StackPanel p = new StackPanel();
        p.Orientation = Orientation.Horizontal;
        p.Spacing = 8;
        foreach (Control c in items) {
            p.Children.Add(c);
        }
        return p;
    }

    // A box around part of a window, a little apart from the rest
    public static Border Card(Control content)
    {
        Border b = new Border();
        b.Child = content;
        b.Padding = new Thickness(16);
        b.CornerRadius = new CornerRadius(8);
        b.BorderThickness = new Thickness(1);
        b.BorderBrush = new SolidColorBrush(Color.FromArgb(0x40, 0x80, 0x80, 0x80));
        b.Background = new SolidColorBrush(Color.FromArgb(0x14, 0x80, 0x80, 0x80));
        return b;
    }

    public static Border Warning(string text)
    {
        TextBlock t = Text(text);
        Border b = Card(t);
        b.BorderBrush = new SolidColorBrush(Color.FromArgb(0xC0, 0xE0, 0x80, 0x10));
        b.Background = new SolidColorBrush(Color.FromArgb(0x30, 0xE0, 0x80, 0x10));
        return b;
    }

    public static void Open(string url)
    {
        try {
            ProcessStartInfo start = new ProcessStartInfo("xdg-open");
            start.ArgumentList.Add(url);
            start.UseShellExecute = false;
            Process.Start(start);
        }
        catch (System.ComponentModel.Win32Exception) {
        }
    }

    // ---------------------------------------------------------------- small windows
    static Window Small(string title, string text, out StackPanel buttons)
    {
        Window w = new Window();
        w.Title = "Starwire microphone";
        w.Icon = WindowIcon();
        w.SizeToContent = SizeToContent.WidthAndHeight;
        w.CanResize = false;
        w.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        StackPanel p = new StackPanel();
        p.Margin = new Thickness(24);
        p.Spacing = 12;
        p.Children.Add(Text(title, 16, true, 400));
        if (!string.IsNullOrEmpty(text)) {
            p.Children.Add(Text(text, 0, false, 400));
        }
        buttons = Row();
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        buttons.Margin = new Thickness(0, 8, 0, 0);
        p.Children.Add(buttons);
        w.Content = p;
        return w;
    }

    static void ShowSmall(Window w, Window owner)
    {
        if (owner != null && owner.IsVisible) {
            w.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            w.ShowDialog(owner);
        }
        else {
            w.Show();
            w.Activate();
        }
    }

    // Asks; true for yes. owner can be null (from the tray's menu).
    public static Task<bool> Confirm(Window owner, string title, string text, string yes)
    {
        TaskCompletionSource<bool> answer = new TaskCompletionSource<bool>();
        StackPanel buttons;
        Window w = Small(title, text, out buttons);
        buttons.Children.Add(Button(yes, () => { answer.TrySetResult(true); w.Close(); }, true));
        buttons.Children.Add(Button("Cancel", () => w.Close()));
        w.Closed += delegate { answer.TrySetResult(false); };
        ShowSmall(w, owner);
        return answer.Task;
    }

    public static Task Message(Window owner, string title, string text, bool warning)
    {
        TaskCompletionSource<bool> closed = new TaskCompletionSource<bool>();
        StackPanel buttons;
        Window w = Small(title, text, out buttons);
        buttons.Children.Add(Button("OK", () => w.Close(), true));
        w.Closed += delegate { closed.TrySetResult(true); };
        ShowSmall(w, owner);
        return closed.Task;
    }
}

// --render <folder>: each window in each state it can be in, drawn to PNGs, then the app exits. It
// talks to no receiver: the states are made up here.
static class Render
{
    public static async void All(string folder)
    {
        Directory.CreateDirectory(folder);
        App app = App.Instance;
        List<string> written = new List<string>();
        try {
            app.Model.Lost();
            await Save(new MainWindow(app), folder, "main-not-running", written);

            app.Model.Feed("port\t6980");
            app.Model.Feed("status\tNo device paired yet: run starwire-mic pair");
            app.Model.Feed("mic\tready");
            app.Model.Feed("devices\t0\t0000000000000000");
            await Save(new MainWindow(app), folder, "main-no-device", written);

            app.Model.Feed("devices\t2\t1111111111111111");
            app.Devices = ControlClient.ParseDevices(new List<string> {
                "85e9ec16e9493e67\tSamsung SM-F966U1\t2026-11-07\t" + DateTime.UtcNow.ToString("yyyy-MM-dd"),
                "a0a1a2a3a4a5a6a7\tPixel 8\t2026-11-01\t-",
            });
            app.Model.Feed("status\tWaiting for a paired device (UDP 6980)");
            await Save(new MainWindow(app), folder, "main-waiting", written);

            app.Model.Feed("status\tReceiving the microphone from Samsung SM-F966U1 (encrypted)");
            await Save(new MainWindow(app), folder, "main-receiving", written);

            app.Model.Feed("unencrypted\tStream1");
            await Save(new MainWindow(app), folder, "main-unencrypted", written);

            PairingModel m = new PairingModel();
            await Save(new PairingWindow(app, m, false), folder, "pair-start", written);
            m.Feed("code 2491-650N-2RBH-G690");
            m.Feed("open 287");
            await Save(new PairingWindow(app, m, true), folder, "pair-code", written);
            m.Feed("paired\tSamsung SM-F966U1\t192.0.2.23\t85e9ec16e9493e67");
            await Save(new PairingWindow(app, m, true), folder, "pair-paired", written);
            PairingModel expired = new PairingModel();
            expired.Feed("code 2491-650N-2RBH-G690");
            expired.Feed("expired");
            await Save(new PairingWindow(app, expired, true), folder, "pair-expired", written);
            Console.WriteLine("Wrote " + string.Join(", ", written));
        }
        catch (Exception e) {
            Console.Error.WriteLine("Render failed: " + e);
        }
        ((IClassicDesktopStyleApplicationLifetime) app.ApplicationLifetime).Shutdown();
    }

    static async Task Save(Window w, string folder, string name, List<string> written)
    {
        w.Position = new PixelPoint(-4000, -4000);
        w.ShowInTaskbar = false;
        w.Show();
        await Task.Delay(400);
        double scale = w.RenderScaling;
        PixelSize size = new PixelSize((int) Math.Ceiling(w.Bounds.Width * scale), (int) Math.Ceiling(w.Bounds.Height * scale));
        using (RenderTargetBitmap bitmap = new RenderTargetBitmap(size, new Vector(96 * scale, 96 * scale))) {
            bitmap.Render(w);
            string path = Path.Combine(folder, name + ".png");
            bitmap.Save(path);
            written.Add(name + ".png");
        }
        w.Close();
    }
}
