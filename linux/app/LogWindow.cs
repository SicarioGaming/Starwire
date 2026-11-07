// Starwire microphone app: the receiver's log (GPL-3.0, part of Starwire), from the systemd journal,
// as `journalctl --user -u starwire-mic` shows it. What the Windows receiver prints with --console.

using System;
using System.Diagnostics;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;

sealed class LogWindow : Window
{
    const int Lines = 400;
    readonly TextBox text = new TextBox();

    public LogWindow()
    {
        Title = "Log - Starwire microphone";
        Icon = Ui.WindowIcon();
        Width = 960;
        Height = 560;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        text.IsReadOnly = true;
        text.AcceptsReturn = true;
        text.TextWrapping = TextWrapping.NoWrap;
        text.FontFamily = Ui.Mono;
        text.FontSize = 12;

        StackPanel buttons = Ui.Row(Ui.Button("Refresh", Load), Ui.Button("Close", Close));
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        DockPanel page = new DockPanel();
        page.Margin = new Thickness(16);
        TextBlock about = Ui.Muted("The receiver's last " + Lines + " lines, from journalctl --user -u starwire-mic. "
            + "Paste them into a bug report as they are: they have no keys or codes in them.", 900);
        about.Margin = new Thickness(0, 0, 0, 10);
        DockPanel.SetDock(about, Dock.Top);
        buttons.Margin = new Thickness(0, 10, 0, 0);
        DockPanel.SetDock(buttons, Dock.Bottom);
        page.Children.Add(about);
        page.Children.Add(buttons);
        page.Children.Add(text);
        Content = page;
        Load();
    }

    async void Load()
    {
        string log = await Task.Run(() => {
            try {
                ProcessStartInfo start = new ProcessStartInfo("journalctl");
                foreach (string a in new[] { "--user", "-u", "starwire-mic", "-n", Lines.ToString(), "--no-pager", "-o", "short-iso" }) {
                    start.ArgumentList.Add(a);
                }
                start.UseShellExecute = false;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;
                using (Process p = Process.Start(start)) {
                    string output = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(5000);
                    return output.Length > 0 ? output : "The log is empty.";
                }
            }
            catch (System.ComponentModel.Win32Exception) {
                return "journalctl isn't there, so the log can't be shown.";
            }
        });
        text.Text = log;
        text.CaretIndex = log.Length;
    }
}
