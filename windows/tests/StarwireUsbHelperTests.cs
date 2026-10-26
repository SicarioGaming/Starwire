// Tests for StarwireUsbHelper.cs, the USB helper's driver side (GPL-3.0, part of Starwire): the
// driver's command layouts, the version check, attach, and detaching only the helper's own
// devices, against a fake driver that answers the way usbip-win2 0.9.8.1's driver does
// (drivers/ude/vhci_ioctl.cpp). Deterministic: no driver, no network, no timed waits, fixed
// random bytes. Its files go in a folder in TEMP named after the test's process, and its driver
// lock is named the same way, so two runs at once don't meet.
// run-usb-helper-tests.ps1 builds it with the C# compiler that comes with Windows and runs it.
//
//   StarwireUsbHelperTests.exe     runs every test; exits with 1 if any fails

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;

static class StarwireUsbHelperTests
{
    const string Laptop = "192.168.1.50";
    const string Vibepollo = "127.0.0.1";
    const string VibepolloSerial = "VP0123456789AB0";
    // The port the helper detaches as a wait for the driver's queued detaches
    const int NoSuchPort = int.MaxValue;
    // The first line of the helper's list, written out here so a change to it shows: lists written
    // by an earlier helper must still be read
    const string ListHeading = "# Starwire USB helper: the USB devices it attached. It only ever detaches these.";

    static int passed, failed;
    static string temp;
    static string lockName;

    static void Check(string what, bool ok)
    {
        if (ok) {
            passed++;
            Console.WriteLine("PASS " + what);
        }
        else {
            failed++;
            Console.WriteLine("FAIL " + what);
        }
    }

    static int Main(string[] args)
    {
        int pid = Process.GetCurrentProcess().Id;
        temp = Path.Combine(Path.GetTempPath(), "StarwireUsbHelperTests-" + pid);
        lockName = @"Local\StarwireUsbHelperTests." + pid;
        if (Directory.Exists(temp)) {
            Directory.Delete(temp, true);
        }
        Directory.CreateDirectory(temp);

        Layouts();
        Errors();
        HostsAndBusIds();
        Serials();
        VersionCheck();
        LayoutCheck();
        Probe();
        Attach();
        AttachFailures();
        OnlyADualSense();
        BadRequests();
        DetachOwnOnly();
        PortReusedByAnotherProcess();
        DropDuringDetach();
        NeverEveryDevice();
        OwnedList();
        NotTheHelpersList();
        ListGrows();
        CrashSafety();
        ShortReplies();
        DriverLock();
        TrayJobs();

        Directory.Delete(temp, true);
        Console.WriteLine();
        Console.WriteLine(failed == 0 ? "All " + passed + " checks passed." : failed + " of " + (passed + failed) + " checks failed.");
        return failed == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------- the fake driver
    sealed class Call
    {
        public uint Code;
        public byte[] Input;
        public int Port { get { return Code == UsbipAbi.PlugoutHardware ? UsbipAbi.GetInt(Input, 4) : 0; } }
    }

    // A USB/IP server the fake can reach, and what it answers
    sealed class Server
    {
        public readonly Dictionary<string, int[]> Devices = new Dictionary<string, int[]>();
        public int Error;
    }

    // What the driver does with each command, from vhci_ioctl.cpp at 0.9.8.1: the same size checks
    // and errors, port 0 or below on PLUGOUT detaches everything, and ports are reused lowest first.
    // Attach and detach go through one queue, in order (a detach the driver queued itself runs
    // before them); the list and the stop don't wait for it.
    sealed class FakeVhci : IVhciDriver
    {
        public DriverIdentity Id = KnownIdentity();
        public readonly ImportedDevice[] Ports = new ImportedDevice[8];
        public readonly List<Call> Calls = new List<Call>();
        public readonly Dictionary<string, Server> Servers = new Dictionary<string, Server>();
        public readonly Dictionary<string, int> PendingAttempts = new Dictionary<string, int>();
        // Locations whose detach after a dropped connection has freed the port, but hasn't yet
        // started the reconnect attempts
        public readonly List<string> HalfDetached = new List<string>();
        // Runs before each list is answered
        public Action OnList;
        public int BadListLength = -1;
        public bool DetachedAll;
        public bool StoppedAll;
        public bool Disposed;

        public DriverIdentity Identity { get { return Id; } }

        public void Dispose()
        {
            Disposed = true;
        }

        public Server AddServer(string host, string service)
        {
            Server s = new Server();
            Servers[host + "|" + service] = s;
            return s;
        }

        // Another program's device, attached on that port
        public void Place(int port, string host, string service, string busId, string serial)
        {
            ImportedDevice d = new ImportedDevice();
            d.Port = port;
            d.Host = host;
            d.Service = service;
            d.BusId = busId;
            d.Serial = serial;
            d.Vendor = 0x054c;
            d.Product = 0x0ce6;
            d.Speed = 2;
            Ports[port - 1] = d;
        }

        // The device's connection dropped: the driver frees its port and keeps trying to reconnect
        public void Drop(int port)
        {
            ImportedDevice d = Ports[port - 1];
            Ports[port - 1] = null;
            string key = d.Host + "|" + d.Service + "|" + d.BusId;
            int n;
            PendingAttempts.TryGetValue(key, out n);
            PendingAttempts[key] = n + 1;
        }

        // The same, caught halfway: device::detach has freed the port and not yet started the
        // attempts (device.cpp), which it does when its queue next moves
        public void DropHalfway(int port)
        {
            ImportedDevice d = Ports[port - 1];
            Ports[port - 1] = null;
            HalfDetached.Add(d.Host + "|" + d.Service + "|" + d.BusId);
        }

        // The driver's own queued detaches finish
        public void Settle()
        {
            foreach (string key in HalfDetached) {
                int n;
                PendingAttempts.TryGetValue(key, out n);
                PendingAttempts[key] = n + 1;
            }
            HalfDetached.Clear();
        }

        public int Count(uint code)
        {
            int n = 0;
            foreach (Call c in Calls) {
                if (c.Code == code) {
                    n++;
                }
            }
            return n;
        }

        // The ports the helper asked to detach, leaving out its waits (a port that can't exist)
        public List<int> PlugoutPorts()
        {
            List<int> ports = new List<int>();
            foreach (Call c in Calls) {
                if (c.Code == UsbipAbi.PlugoutHardware && c.Port != NoSuchPort) {
                    ports.Add(c.Port);
                }
            }
            return ports;
        }

        // Where in the calls each wait and each stop came
        public List<int> Positions(uint code, int port)
        {
            List<int> at = new List<int>();
            for (int i = 0; i < Calls.Count; i++) {
                if (Calls[i].Code == code && (code != UsbipAbi.PlugoutHardware || Calls[i].Port == port)) {
                    at.Add(i);
                }
            }
            return at;
        }

        public int Changes()
        {
            return Calls.Count - Count(UsbipAbi.GetImportedDevices);
        }

        public byte[] LastInput(uint code)
        {
            byte[] last = null;
            foreach (Call c in Calls) {
                if (c.Code == code) {
                    last = c.Input;
                }
            }
            return last;
        }

        public int Control(uint code, byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            Call call = new Call();
            call.Code = code;
            call.Input = input == null ? null : (byte[]) input.Clone();
            Calls.Add(call);
            if (code == UsbipAbi.GetImportedDevices) {
                if (OnList != null) {
                    OnList();
                }
                return List(input, output, out returned);
            }
            if (code == UsbipAbi.PluginHardwareOnce || code == UsbipAbi.PluginHardware) {
                Settle();
                return Plugin(input, output, out returned);
            }
            if (code == UsbipAbi.PlugoutHardware) {
                Settle();
                return Plugout(input);
            }
            if (code == UsbipAbi.StopAttachAttempts) {
                return Stop(input, output, out returned);
            }
            return 1; // ERROR_INVALID_FUNCTION
        }

        int List(byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            if (output == null || output.Length < UsbipAbi.ListSize) {
                return UsbipAbi.ErrorInsufficientBuffer;
            }
            if (input == null || input.Length < 4 || UsbipAbi.GetInt(input, 0) != UsbipAbi.ListSize) {
                return UsbipAbi.ErrorAbi;
            }
            int room = (output.Length - UsbipAbi.ListHeaderSize) / UsbipAbi.DeviceSize;
            int n = 0;
            foreach (ImportedDevice d in Ports) {
                if (d == null) {
                    continue;
                }
                if (n == room) {
                    return UsbipAbi.ErrorInsufficientBuffer;
                }
                UsbipAbi.PutDevice(output, UsbipAbi.ListHeaderSize + n * UsbipAbi.DeviceSize, d);
                n++;
            }
            returned = BadListLength >= 0 ? BadListLength : UsbipAbi.ListHeaderSize + n * UsbipAbi.DeviceSize;
            return 0;
        }

        int Plugin(byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            if (input == null || input.Length != UsbipAbi.PluginSize) {
                return 1784; // STATUS_INVALID_BUFFER_SIZE
            }
            if (UsbipAbi.GetInt(input, 0) != UsbipAbi.PluginSize) {
                return UsbipAbi.ErrorAbi;
            }
            string serial = UsbipAbi.GetString(input, UsbipAbi.PluginSerial, UsbipAbi.SerialSize);
            if (serial.Length >= UsbipAbi.SerialSize || !Alnum(serial)) {
                return UsbipAbi.ErrorSerialNumber;
            }
            string host, service, busId;
            UsbipAbi.GetLocation(input, UsbipAbi.PluginLocation, out host, out service, out busId);
            Server server;
            if (!Servers.TryGetValue(host + "|" + service, out server)) {
                return 1225; // nobody listening: connection refused
            }
            if (server.Error != 0) {
                return server.Error;
            }
            int[] ids;
            if (!server.Devices.TryGetValue(busId, out ids)) {
                return UsbipAbi.ErrorNoDevice;
            }
            int free = Array.IndexOf(Ports, null);
            if (free < 0) {
                return UsbipAbi.ErrorPortFull;
            }
            ImportedDevice d = new ImportedDevice();
            d.Port = free + 1;
            d.Host = host;
            d.Service = service;
            d.BusId = busId;
            d.Serial = serial;
            d.Vendor = ids[0];
            d.Product = ids[1];
            d.Speed = ids[2];
            d.WskEvents = input[UsbipAbi.PluginWskEvents] != 0;
            Ports[free] = d;
            UsbipAbi.PutInt(output, 0, UsbipAbi.PluginSize);
            UsbipAbi.PutInt(output, 4, d.Port);
            returned = UsbipAbi.AttachReplySize;
            return 0;
        }

        int Plugout(byte[] input)
        {
            if (input == null || input.Length != UsbipAbi.PlugoutSize) {
                return 1784;
            }
            if (UsbipAbi.GetInt(input, 0) != UsbipAbi.PlugoutSize) {
                return UsbipAbi.ErrorAbi;
            }
            int port = UsbipAbi.GetInt(input, 4);
            if (port <= 0) {
                DetachedAll = true;
                Array.Clear(Ports, 0, Ports.Length);
                return 0;
            }
            if (port > Ports.Length) {
                return 87; // STATUS_INVALID_PARAMETER
            }
            if (Ports[port - 1] == null) {
                return 1167; // STATUS_DEVICE_NOT_CONNECTED
            }
            Ports[port - 1] = null;
            return 0;
        }

        int Stop(byte[] input, byte[] output, out int returned)
        {
            returned = 0;
            if (input == null || input.Length != UsbipAbi.StopSize) {
                return 1784;
            }
            if (UsbipAbi.GetInt(input, 0) != UsbipAbi.StopSize) {
                return UsbipAbi.ErrorAbi;
            }
            string host, service, busId;
            UsbipAbi.GetLocation(input, 4, out host, out service, out busId);
            int given = (host.Length > 0 ? 1 : 0) + (service.Length > 0 ? 1 : 0) + (busId.Length > 0 ? 1 : 0);
            int count = 0;
            if (given == 0) {
                StoppedAll = true;
                foreach (int n in PendingAttempts.Values) {
                    count += n;
                }
                PendingAttempts.Clear();
            }
            else if (given != 3) {
                return 87;
            }
            else {
                string key = host + "|" + service + "|" + busId;
                PendingAttempts.TryGetValue(key, out count);
                PendingAttempts.Remove(key);
            }
            Array.Copy(input, output, UsbipAbi.StopSize);
            UsbipAbi.PutInt(output, 1104, count);
            returned = UsbipAbi.StopSize;
            return 0;
        }

        static bool Alnum(string s)
        {
            foreach (char c in s) {
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                    return false;
                }
            }
            return true;
        }
    }

    // What this PC's controller says in Device Manager (read on 26 Oct 2026)
    static DriverIdentity KnownIdentity()
    {
        DriverIdentity id = new DriverIdentity();
        id.InstanceId = @"ROOT\USB\0001";
        id.Description = "USBip 3.X Emulated Host Controller";
        id.Version = "12.1.30.403";
        id.Provider = "USBIP-WIN2";
        id.Service = "usbip2_ude";
        id.Date = new DateTime(2026, 9, 22, 0, 0, 0, DateTimeKind.Utc);
        id.ImagePath = @"C:\WINDOWS\System32\DriverStore\FileRepository\usbip2_ude.inf_amd64_bcfb5e9b79f800fa\usbip2_ude.sys";
        id.ImageSha256 = ReleaseSysSha256;
        return id;
    }

    // usbip2_ude.sys from usbip-win2 0.9.8.1, as Vibepollo's package.ps1 pins it
    const string ReleaseSysSha256 = "abd4fc43dce40e027e4acc884cbfe92433e026206f6565c5ee5284a39441169a";

    // A fake driver, a list file of its own, fixed random bytes, and every line the helper says
    sealed class Rig
    {
        public readonly FakeVhci Driver = new FakeVhci();
        public readonly OwnedDevices Owned;
        public readonly List<string> Lines = new List<string>();
        public readonly Random Seeded = new Random(7);
        public string OpenProblem;
        public int Opens;

        public Rig(string name)
        {
            Owned = new OwnedDevices(Path.Combine(temp, name + ".txt"));
        }

        // A run of the helper, like starting the exe again: same driver, same list file
        public UsbHelper Helper()
        {
            return Helper(Driver);
        }

        // The same, saying its lines somewhere else
        public UsbHelper HelperSaying(Action<string> say)
        {
            return new UsbHelper(Open, Owned, b => Seeded.NextBytes(b), lockName, say);
        }

        // The same, through a wrapper around the fake driver
        public UsbHelper Helper(IVhciDriver through)
        {
            Lines.Clear();
            return new UsbHelper((out string problem) => {
                IVhciDriver d = Open(out problem);
                return d == null ? null : through;
            }, Owned, b => Seeded.NextBytes(b), lockName, Lines.Add);
        }

        IVhciDriver Open(out string problem)
        {
            Opens++;
            if (OpenProblem != null) {
                problem = OpenProblem;
                return null;
            }
            problem = null;
            Driver.Disposed = false;
            return Driver;
        }

        public bool Said(string part)
        {
            foreach (string line in Lines) {
                if (line.IndexOf(part, StringComparison.Ordinal) >= 0) {
                    return true;
                }
            }
            return false;
        }

        public string FileText()
        {
            return File.Exists(Owned.FilePath) ? File.ReadAllText(Owned.FilePath) : "";
        }

        public void Dump()
        {
            foreach (string line in Lines) {
                Console.WriteLine("     | " + line);
            }
        }
    }

    // The laptop shares a DualSense as bus ID 2-3, and Vibepollo's virtual DualSense is on port 1
    static Rig LaptopRig(string name)
    {
        Rig rig = new Rig(name);
        rig.Driver.AddServer(Laptop, "3240").Devices["2-3"] = new int[] { 0x054c, 0x0ce6, 2 };
        rig.Driver.Place(1, Vibepollo, "50123", "1-1", VibepolloSerial);
        return rig;
    }

    // ---------------------------------------------------------------- the tests
    static void Layouts()
    {
        // CTL_CODE(0x22, function, 0, 3), worked out by hand
        Check("PLUGIN_HARDWARE is 0x22E000", UsbipAbi.PluginHardware == 0x22E000);
        Check("PLUGOUT_HARDWARE is 0x22E004", UsbipAbi.PlugoutHardware == 0x22E004);
        Check("GET_IMPORTED_DEVICES is 0x22E008", UsbipAbi.GetImportedDevices == 0x22E008);
        Check("STOP_ATTACH_ATTEMPTS is 0x22E014", UsbipAbi.StopAttachAttempts == 0x22E014);
        Check("PLUGIN_HARDWARE_ONCE is 0x22E018", UsbipAbi.PluginHardwareOnce == 0x22E018);
        Check("the interface GUID is usbip-win2's", UsbipAbi.Interface == new Guid("b4030c06-dc5f-4fcc-87eb-e5515a0935c0"));

        // The sizes vhci.h's layouts give (Vibepollo's usbip_win2_abi.h asserts the same numbers)
        Check("list request: size 1136, 4 bytes", Same(UsbipAbi.ListRequest(), new byte[] { 0x70, 0x04, 0, 0 }));
        Check("plugout request for port 2", Same(UsbipAbi.PlugoutRequest(2), new byte[] { 8, 0, 0, 0, 2, 0, 0, 0 }));

        byte[] p = UsbipAbi.PluginRequest("10.0.0.7", "3240", "2-3", "SW0123456789ABC", true);
        Check("plugin request is 1124 bytes with its size first", p.Length == 1124 && UsbipAbi.GetInt(p, 0) == 1124);
        Check("plugin request: port and hash left 0", UsbipAbi.GetInt(p, 4) == 0 && UsbipAbi.GetInt(p, 8) == 0);
        Check("plugin request: bus ID at 12", Ascii(p, 12, 32) == "2-3");
        Check("plugin request: service at 44", Ascii(p, 44, 32) == "3240");
        Check("plugin request: host at 76", Ascii(p, 76, 1025) == "10.0.0.7");
        Check("plugin request: serial at 1104", Ascii(p, 1104, 16) == "SW0123456789ABC");
        Check("plugin request: wsk_events at 1120, then padding", p[1120] == 1 && p[1121] == 0 && p[1122] == 0 && p[1123] == 0);
        // 1124 is 64 04 00 00, then 3 + 4 + 8 + 15 characters and the flag
        Check("plugin request: nothing else set", NonZero(p) == 2 + 3 + 4 + 8 + 15 + 1);

        byte[] s = UsbipAbi.StopRequest("10.0.0.7", "3240", "2-3");
        Check("stop request is 1108 bytes, fields where the plugin's are", s.Length == 1108 && UsbipAbi.GetInt(s, 0) == 1108
            && Ascii(s, 12, 32) == "2-3" && Ascii(s, 44, 32) == "3240" && Ascii(s, 76, 1025) == "10.0.0.7");

        // One imported_device written byte by byte at vhci.h's offsets, read back by the helper
        byte[] d = new byte[4 + 1132];
        int o = 4;
        PutInt(d, o + 0, 3);
        PutInt(d, o + 4, 0x11223344);
        PutAscii(d, o + 8, "2-3");
        PutAscii(d, o + 40, "3240");
        PutAscii(d, o + 72, "10.0.0.7");
        PutInt(d, o + 1100, 0x00020003);
        PutInt(d, o + 1104, 3);
        d[o + 1108] = 0x4c; d[o + 1109] = 0x05;
        d[o + 1110] = 0xe6; d[o + 1111] = 0x0c;
        PutAscii(d, o + 1112, "SW0123456789ABC");
        d[o + 1128] = 255;
        d[o + 1129] = 1;
        ImportedDevice dev = UsbipAbi.ParseDevice(d, o);
        Check("device: port, hash and location", dev.Port == 3 && dev.LocationHash == 0x11223344 && dev.BusId == "2-3"
            && dev.Service == "3240" && dev.Host == "10.0.0.7");
        Check("device: devid, speed, vendor, product", dev.DevId == 0x00020003 && dev.Speed == 3 && dev.Vendor == 0x054c && dev.Product == 0x0ce6);
        Check("device: serial, iSerial, wsk_events", dev.Serial == "SW0123456789ABC" && dev.ISerial == 255 && dev.WskEvents);
        byte[] again = new byte[4 + 1132];
        UsbipAbi.PutDevice(again, 4, dev);
        Check("device: written back the same", Same(again, d));

        bool threw = false;
        try {
            UsbipAbi.PluginRequest("10.0.0.7", "3240", new string('1', 32), "", false);
        }
        catch (ArgumentException) {
            threw = true;
        }
        Check("a string too long for its field is never cut short", threw);
    }

    static void Errors()
    {
        UsbipErrors.Target t = new UsbipErrors.Target();
        t.Host = Laptop;
        t.Service = "3240";
        t.BusId = "2-9";
        Check("the driver's codes are mc -c's numbers", UsbipAbi.ErrorNoDevice == unchecked((int) 0xE1020004)
            && UsbipAbi.ErrorAbi == unchecked((int) 0xE1000005) && UsbipAbi.ErrorSerialNumber == unchecked((int) 0xE1000006));
        Check("no such bus ID names it and usbipd bind", UsbipErrors.Explain(UsbipAbi.ErrorNoDevice, t).Contains("bus ID 2-9")
            && UsbipErrors.Explain(UsbipAbi.ErrorNoDevice, t).Contains("usbipd bind"));
        Check("a refused connection names the address and the firewall", UsbipErrors.Explain(1225, t).Contains(Laptop + ":3240")
            && UsbipErrors.Explain(1225, t).Contains("firewall"));
        Check("an unknown error gives its number", UsbipErrors.Explain(unchecked((int) 0xE1029999), t).Contains("0xE1029999"));
        Check("an IPv6 address gets brackets", Requests.Endpoint("fe80::1", "3240") == "[fe80::1]:3240");
    }

    static void HostsAndBusIds()
    {
        string[] good = { Laptop, "10.0.0.2", "172.16.5.4", "172.31.0.9", "100.100.1.2", "169.254.3.4", "127.0.0.1", "fe80::1", "fd12:3456::5", "::1" };
        foreach (string h in good) {
            string host, problem;
            Check("home address accepted: " + h, Requests.ParseHost(h, out host, out problem) && host == IPAddress.Parse(h).ToString());
        }
        string mapped, why;
        Check("an IPv4 address written as IPv6 is kept as IPv4", Requests.ParseHost("::ffff:192.168.1.5", out mapped, out why) && mapped == "192.168.1.5");
        string[] bad = { "", "laptop", "laptop.local", "8.8.8.8", "172.32.0.1", "100.128.0.1", "192.168.1", "010.0.0.1",
            "192.168.1.50 ", " 192.168.1.50", "192.168.1.50\n", "::ffff:8.8.8.8", "2001:db8::1", "192.168.1.256", null };
        foreach (string h in bad) {
            string host, problem;
            Check("refused as an address: " + (h == null ? "null" : "\"" + h.Replace("\n", "\\n") + "\""),
                !Requests.ParseHost(h, out host, out problem) && host == null && problem != null);
        }
        string[] goodIds = { "2-1", "3-1.4", "10-12", "1-1.2.3.4.5.6" };
        foreach (string b in goodIds) {
            string problem;
            Check("bus ID accepted: " + b, Requests.CheckBusId(b, out problem));
        }
    }

    static void Serials()
    {
        Check("serial: SW and 13 characters from fixed bytes",
            Requests.NewSerial(b => { for (int i = 0; i < b.Length; i++) b[i] = (byte) i; }) == "SW0123456789ABC");
        Check("serial: only the low 5 bits count", Requests.NewSerial(b => { for (int i = 0; i < b.Length; i++) b[i] = 0xFF; }) == "SWZZZZZZZZZZZZZ");
        string serial = Requests.NewSerial(b => new Random(1).NextBytes(b));
        Check("serial: 15 characters, which the driver allows", serial.Length == 15 && Requests.IsSerial(serial));
        Check("serial: not one of Vibepollo's", !Requests.IsSerial(VibepolloSerial));

        // An entry without one of the helper's serials never matches, even where everything else does
        FakeVhci fake = new FakeVhci();
        fake.Place(1, Vibepollo, "50123", "1-1", VibepolloSerial);
        fake.Place(2, Laptop, "3240", "2-3", "");
        Check("match: an entry with Vibepollo's serial isn't the helper's", !new OwnedDevice(1, Vibepollo, "50123", "1-1", VibepolloSerial).Matches(fake.Ports[0]));
        Check("match: an entry with no serial isn't the helper's", !new OwnedDevice(2, Laptop, "3240", "2-3", "").Matches(fake.Ports[1]));
    }

    static void VersionCheck()
    {
        string problem;
        Check("this PC's driver is known", KnownDriver.Recognize(KnownIdentity(), out problem) != null && problem == null);

        // Another version: refused with the reason, before any command
        Rig rig = LaptopRig("version");
        rig.Driver.Id.Version = "12.1.31.0";
        int code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("unknown version: attach refused", code == StarwireUsbHelper.ExitDriver);
        Check("unknown version: says which it found and which it knows", rig.Said("12.1.31.0") && rig.Said("12.1.30.403 (usbip-win2 0.9.8.1)"));
        Check("unknown version: no command sent at all", rig.Driver.Calls.Count == 0);
        code = rig.Helper().DebugDetach();
        Check("unknown version: detach refused too, nothing sent", code == StarwireUsbHelper.ExitDriver && rig.Driver.Calls.Count == 0);
        code = rig.Helper().Probe();
        Check("unknown version: probe says so", code == StarwireUsbHelper.ExitDriver && rig.Said("won't use this one"));

        DriverIdentity other = KnownIdentity();
        other.Service = "usbip_vhci";
        Check("right number, other driver service: refused", KnownDriver.Recognize(other, out problem) == null && problem.Contains("usbip_vhci"));
        other = KnownIdentity();
        other.Provider = "Someone Else";
        Check("right number, other provider: refused", KnownDriver.Recognize(other, out problem) == null && problem.Contains("Someone Else"));
        other = KnownIdentity();
        other.Version = null;
        Check("no version given: refused", KnownDriver.Recognize(other, out problem) == null && problem != null);
        other.Version = "12.1.30.4030";
        Check("a longer version isn't a match", KnownDriver.Recognize(other, out problem) == null);

        // The right version, provider and service, but not that release's file
        other = KnownIdentity();
        other.ImageSha256 = "0" + ReleaseSysSha256.Substring(1);
        Check("right number, another driver file: refused, naming the file and both hashes",
            KnownDriver.Recognize(other, out problem) == null && problem.Contains("isn't that release's usbip2_ude.sys")
            && problem.Contains(other.ImageSha256) && problem.Contains(ReleaseSysSha256) && problem.Contains(other.ImagePath));
        other = KnownIdentity();
        other.ImageSha256 = ReleaseSysSha256.ToUpperInvariant();
        Check("the file's hash in capitals is the same hash", KnownDriver.Recognize(other, out problem) != null);
        other = KnownIdentity();
        other.ImageSha256 = null;
        other.ImageProblem = "couldn't read its file: access denied";
        Check("driver file not readable: refused with the reason", KnownDriver.Recognize(other, out problem) == null
            && problem.Contains("access denied"));
        rig = LaptopRig("other-file");
        rig.Driver.Id.ImageSha256 = "0" + ReleaseSysSha256.Substring(1);
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("another driver file: attach refused before any command", code == StarwireUsbHelper.ExitDriver
            && rig.Driver.Calls.Count == 0 && rig.Said("isn't that release's usbip2_ude.sys"));

        // Where Windows says the driver's file is, as a path to open
        string win = @"C:\WINDOWS";
        Check("image path: \\SystemRoot\\ is the Windows folder", Win32Vhci.ResolveImagePath(
            @"\SystemRoot\System32\DriverStore\FileRepository\usbip2_ude.inf_amd64_x\usbip2_ude.sys", win, false)
            == @"C:\WINDOWS\System32\DriverStore\FileRepository\usbip2_ude.inf_amd64_x\usbip2_ude.sys");
        Check("image path: System32\\ is under the Windows folder", Win32Vhci.ResolveImagePath(@"System32\drivers\usbip2_ude.sys", win, false)
            == @"C:\WINDOWS\System32\drivers\usbip2_ude.sys");
        Check("image path: \\??\\ is dropped", Win32Vhci.ResolveImagePath(@"\??\D:\drivers\usbip2_ude.sys", win, false) == @"D:\drivers\usbip2_ude.sys");
        Check("image path: a 32-bit copy reads System32 through Sysnative", Win32Vhci.ResolveImagePath(@"System32\drivers\usbip2_ude.sys", win, true)
            == @"C:\WINDOWS\Sysnative\drivers\usbip2_ude.sys");
        Check("image path: missing, relative, network or odd ones are refused", Win32Vhci.ResolveImagePath(null, win, false) == null
            && Win32Vhci.ResolveImagePath("usbip2_ude.sys", win, false) == null
            && Win32Vhci.ResolveImagePath(@"\??\UNC\server\share\usbip2_ude.sys", win, false) == null
            && Win32Vhci.ResolveImagePath("\"C:\\x.sys\"", win, false) == null);

        rig = new Rig("missing");
        rig.OpenProblem = "This PC has no USB/IP driver running (usbip-win2's controller isn't there).";
        Check("no driver: attach says why", rig.Helper().DebugAttach(Laptop, "2-3", 3240, false) == StarwireUsbHelper.ExitDriver
            && rig.Said("no USB/IP driver running"));
    }

    static void LayoutCheck()
    {
        // A driver with the right version number that answers in another layout
        Rig rig = LaptopRig("layout");
        rig.Driver.BadListLength = 4 + 1000;
        int code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("odd list answer: attach refused", code == StarwireUsbHelper.ExitDriver && rig.Said("doesn't fit"));
        Check("odd list answer: only the list was sent", rig.Driver.Changes() == 0);

        // Ports start at 1: a device listed on port 0 means the layout isn't what the helper thinks
        rig = LaptopRig("port-zero");
        rig.Driver.Ports[0].Port = 0;
        code = rig.Helper().DebugDetach();
        Check("a device listed on port 0: the driver isn't used", code == StarwireUsbHelper.ExitDriver
            && rig.Said("ports start at 1") && rig.Driver.Changes() == 0);
    }

    static void Probe()
    {
        Rig rig = LaptopRig("probe");
        int code = rig.Helper().Probe();
        Check("probe: works and names the driver", code == StarwireUsbHelper.ExitOk && rig.Said("usbip-win2 0.9.8.1, which the helper knows"));
        Check("probe: lists Vibepollo's device as another program's", rig.Said("Port 1: USB device 054c:0ce6 (Sony DualSense)")
            && rig.Said("likely Vibepollo's virtual DualSense") && rig.Said("Another program's, left alone"));
        Check("probe: sends only the list command", rig.Driver.Calls.Count > 0 && rig.Driver.Changes() == 0);

        ReadOnlyVhci guard = new ReadOnlyVhci(rig.Driver);
        int error;
        int port = UsbipCommands.Attach(guard, Laptop, "3240", "2-3", "SW0123456789ABC", false, out error);
        string problem;
        bool detached = UsbipCommands.Detach(guard, 1, out problem);
        int stopped = UsbipCommands.StopAttempts(guard, Laptop, "3240", "2-3", out problem);
        Check("read-only guard: attach, detach and stop don't reach the driver", port == 0 && !detached && stopped < 0
            && guard.Blocked == 3 && rig.Driver.Changes() == 0);
    }

    static void Attach()
    {
        Rig rig = LaptopRig("attach");
        int code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("attach: works", code == StarwireUsbHelper.ExitOk);
        Check("attach: on the first free port, 2", rig.Driver.Ports[1] != null && rig.Said("Attached on port 2."));
        Check("attach: once only, no automatic retries if it can't connect",
            rig.Driver.Count(UsbipAbi.PluginHardwareOnce) == 1 && rig.Driver.Count(UsbipAbi.PluginHardware) == 0);
        byte[] request = rig.Driver.LastInput(UsbipAbi.PluginHardwareOnce) ?? new byte[UsbipAbi.PluginSize];
        string expected = Requests.NewSerial(b => new Random(7).NextBytes(b));
        Check("attach: asks for the laptop's bus ID 2-3 on TCP 3240", Ascii(request, 76, 1025) == Laptop
            && Ascii(request, 44, 32) == "3240" && Ascii(request, 12, 32) == "2-3");
        Check("attach: with the helper's own new serial", Ascii(request, 1104, 16) == expected
            && rig.Driver.Ports[1] != null && rig.Driver.Ports[1].Serial == expected);
        Check("attach: zero-copy unless asked", request[1120] == 0);
        Check("attach: says what it is", rig.Said("Sony DualSense") && rig.Said("full speed"));
        Check("attach: noted with its port in the list file", rig.FileText().Contains("2\t" + Laptop + "\t3240\t2-3\t" + expected + "\r\n"));
        Check("attach: Vibepollo's port untouched", rig.Driver.Ports[0].Serial == VibepolloSerial && rig.Driver.PlugoutPorts().Count == 0);

        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("attach again: already the helper's, nothing sent", code == StarwireUsbHelper.ExitOk
            && rig.Said("already attached it, on port 2") && rig.Driver.Count(UsbipAbi.PluginHardwareOnce) == 1);

        Rig low = LaptopRig("attach-low");
        low.Helper().DebugAttach(Laptop, "2-3", 3240, true);
        byte[] lowRequest = low.Driver.LastInput(UsbipAbi.PluginHardwareOnce);
        Check("attach --low-latency: sets wsk_events", lowRequest != null && lowRequest[1120] == 1 && low.Driver.Ports[1].WskEvents);

        Rig other = LaptopRig("attach-other");
        other.Driver.Place(2, Laptop, "3240", "2-3", "");
        code = other.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("attach: the same device attached by another program is left to it", code == StarwireUsbHelper.ExitAttach
            && other.Said("attached by another program") && other.Driver.Changes() == 0 && other.FileText() == "");
    }

    static void AttachFailures()
    {
        Rig rig = LaptopRig("nodev");
        int code = rig.Helper().DebugAttach(Laptop, "2-9", 3240, false);
        Check("no such bus ID: fails with the reason", code == StarwireUsbHelper.ExitAttach && rig.Said("shares no device with bus ID 2-9"));
        Check("no such bus ID: nothing left on the list", rig.FileText() == "" && !File.Exists(rig.Owned.FilePath));

        rig = LaptopRig("refused");
        code = rig.Helper().DebugAttach("192.168.1.51", "2-3", 3240, false);
        Check("nobody listening: says to check usbipd and the firewall", code == StarwireUsbHelper.ExitAttach
            && rig.Said("192.168.1.51:3240 refused the connection") && rig.Said("Private network"));

        rig = LaptopRig("busy");
        rig.Driver.Servers[Laptop + "|3240"].Error = UsbipAbi.ErrorBusy;
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("in use elsewhere: says so", code == StarwireUsbHelper.ExitAttach && rig.Said("already attached by another computer"));

        rig = LaptopRig("full");
        for (int port = 2; port <= rig.Driver.Ports.Length; port++) {
            rig.Driver.Place(port, Vibepollo, (50000 + port).ToString(), "1-1", "VP00000000000" + port % 10 + "0");
        }
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("no free port: says so, detaches nothing", code == StarwireUsbHelper.ExitAttach && rig.Said("no free port")
            && rig.Driver.PlugoutPorts().Count == 0);

        rig = LaptopRig("other-port");
        rig.Driver.AddServer(Laptop, "3241").Devices["2-3"] = new int[] { 0x054c, 0x0ce6, 2 };
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3241, false);
        Check("attach on another TCP port", code == StarwireUsbHelper.ExitOk && rig.Driver.Ports[1].Service == "3241");
    }

    static void OnlyADualSense()
    {
        // The laptop also shares a keyboard, as bus ID 2-4, and that's the one asked for
        Rig rig = LaptopRig("keyboard");
        rig.Driver.Servers[Laptop + "|3240"].Devices["2-4"] = new int[] { 0x046d, 0xc31c, 2 };
        int code = rig.Helper().DebugAttach(Laptop, "2-4", 3240, false);
        Check("not a DualSense: says what it says it is, and fails", code == StarwireUsbHelper.ExitAttach
            && rig.Said("It says it's USB device 046d:c31c, not a DualSense"));
        Check("not a DualSense: detached at once, on its own port only", Same(rig.Driver.PlugoutPorts(), 2)
            && rig.Driver.Ports[1] == null && rig.Driver.Ports[0] != null && rig.Driver.Ports[0].Serial == VibepolloSerial);
        Check("not a DualSense: the driver's reconnects for it are stopped too, and the list is gone",
            rig.Driver.Positions(UsbipAbi.StopAttachAttempts, 0).Count == 1 && !File.Exists(rig.Owned.FilePath));

        rig = LaptopRig("edge");
        rig.Driver.Servers[Laptop + "|3240"].Devices["2-3"] = new int[] { 0x054c, 0x0df2, 3 };
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("a DualSense Edge stays attached", code == StarwireUsbHelper.ExitOk && rig.Driver.Ports[1] != null
            && rig.Said("Sony DualSense Edge") && rig.Driver.PlugoutPorts().Count == 0);

        // The driver reconnected the helper's pad and the other PC now says it's something else
        rig = LaptopRig("changed");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.Ports[1].Vendor = 0x046d;
        rig.Driver.Ports[1].Product = 0xc31c;
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("already attached, now not a DualSense: detached", code == StarwireUsbHelper.ExitAttach
            && Same(rig.Driver.PlugoutPorts(), 2) && rig.Driver.Ports[1] == null && !File.Exists(rig.Owned.FilePath));

        // Its connection drops straight after the attach: the driver would keep reconnecting it
        rig = LaptopRig("dropped-at-once");
        int lists = 0;
        rig.Driver.OnList = () => {
            // Checking the driver, then before the attach, then after it
            if (++lists == 3) {
                rig.Driver.DropHalfway(2);
            }
        };
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.OnList = null;
        rig.Driver.Settle();
        Check("dropped straight after the attach: says so, stops the reconnects, clears the list", code == StarwireUsbHelper.ExitAttach
            && rig.Said("doesn't show it any more") && rig.Said("Stopped 1 attempt") && rig.Driver.PendingAttempts.Count == 0
            && rig.Driver.PlugoutPorts().Count == 0 && !File.Exists(rig.Owned.FilePath));
    }

    static void BadRequests()
    {
        string[] busIds = { "", "2", "2-", "-3", "2-3;calc", "2-3 ", " 2-3", "2-3\n", "1-1.2.3.4.5.6.7", "1234-1", "VID:054c",
            "２-３", "054c:0ce6", "2-3/../1-1", new string('1', 15) + "-" + new string('2', 16) };
        foreach (string b in busIds) {
            Rig rig = LaptopRig("busid");
            int code = rig.Helper().DebugAttach(Laptop, b, 3240, false);
            Check("bad bus ID \"" + b.Replace("\n", "\\n") + "\": refused before the driver is opened", code == StarwireUsbHelper.ExitUsage
                && rig.Opens == 0 && rig.Driver.Calls.Count == 0 && rig.Said("isn't a bus ID"));
        }
        string[] hosts = { "laptop.local", "8.8.8.8", "::ffff:8.8.8.8", "" };
        foreach (string h in hosts) {
            Rig rig = LaptopRig("host");
            int code = rig.Helper().DebugAttach(h, "2-3", 3240, false);
            Check("bad address \"" + h + "\": refused before the driver is opened", code == StarwireUsbHelper.ExitUsage && rig.Opens == 0);
        }
        int[] ports = { 0, -1, 65536 };
        foreach (int p in ports) {
            Rig rig = LaptopRig("port");
            int code = rig.Helper().DebugAttach(Laptop, "2-3", p, false);
            Check("bad TCP port " + p + ": refused before the driver is opened", code == StarwireUsbHelper.ExitUsage && rig.Opens == 0);
        }
    }

    static void DetachOwnOnly()
    {
        Rig rig = LaptopRig("detach");
        rig.Driver.Place(3, Laptop, "3240", "2-4", "");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        int ours = Array.IndexOf(rig.Driver.Ports, Find(rig.Driver, "2-3")) + 1;
        int code = rig.Helper().DebugDetach();
        Check("detach: works", code == StarwireUsbHelper.ExitOk && rig.Said("Done: the helper has nothing attached now."));
        Check("detach: only the helper's port", ours == 2 && Same(rig.Driver.PlugoutPorts(), 2));
        Check("detach: Vibepollo's device and the other laptop device stay", rig.Driver.Ports[0] != null
            && rig.Driver.Ports[0].Serial == VibepolloSerial && rig.Driver.Ports[2] != null && rig.Driver.Ports[1] == null);
        Check("detach: stops reconnect attempts for its own location only", rig.Driver.Count(UsbipAbi.StopAttachAttempts) == 1
            && Ascii(rig.Driver.LastInput(UsbipAbi.StopAttachAttempts), 12, 32) == "2-3" && !rig.Driver.StoppedAll);
        Check("detach: the list file is gone", !File.Exists(rig.Owned.FilePath));

        code = rig.Helper().DebugDetach();
        Check("detach with nothing attached: sends no detach", code == StarwireUsbHelper.ExitOk
            && rig.Said("nothing to detach") && Same(rig.Driver.PlugoutPorts(), 2));

        // The driver reconnected the helper's dropped device on another port, with its serial
        rig = LaptopRig("moved");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        ImportedDevice mine = rig.Driver.Ports[1];
        rig.Driver.Ports[1] = null;
        rig.Driver.Place(2, Vibepollo, "50999", "1-1", "VP0123456789AB1");
        rig.Driver.Ports[3] = mine;
        mine.Port = 4;
        code = rig.Helper().DebugDetach();
        Check("reconnected on port 4: that's the one detached", code == StarwireUsbHelper.ExitOk && Same(rig.Driver.PlugoutPorts(), 4)
            && rig.Driver.Ports[1] != null);

        // A detach the driver refuses keeps the device on the list
        rig = LaptopRig("stuck");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        code = rig.Helper(new StuckVhci(rig.Driver)).DebugDetach();
        Check("refused detach: says so and keeps the device on the list", code == StarwireUsbHelper.ExitDetach
            && rig.Said("Couldn't detach port 2") && rig.FileText().Contains("\t2-3\t"));
    }

    // The fake driver, except every detach fails
    sealed class StuckVhci : IVhciDriver
    {
        readonly FakeVhci inner;

        public StuckVhci(FakeVhci inner)
        {
            this.inner = inner;
        }

        public DriverIdentity Identity { get { return inner.Identity; } }

        public int Control(uint code, byte[] input, byte[] output, out int returned)
        {
            if (code == UsbipAbi.PlugoutHardware) {
                returned = 0;
                return 31; // ERROR_GEN_FAILURE
            }
            return inner.Control(code, input, output, out returned);
        }

        public void Dispose()
        {
        }
    }

    static void PortReusedByAnotherProcess()
    {
        // The helper attached on port 2; the laptop's device dropped, and another program's device
        // got port 2. The list file still says port 2.
        Rig rig = LaptopRig("reused");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.Drop(2);
        rig.Driver.Place(2, Vibepollo, "50999", "1-1", "VP0123456789AB1");
        Check("reused: the list file still says port 2", rig.FileText().StartsWith("#") && rig.FileText().Contains("\r\n2\t" + Laptop));
        int code = rig.Helper().DebugDetach();
        Check("reused: no detach sent at all", rig.Driver.PlugoutPorts().Count == 0 && !rig.Driver.DetachedAll);
        Check("reused: both other devices stay", rig.Driver.Ports[0] != null && rig.Driver.Ports[1] != null
            && rig.Driver.Ports[1].Serial == "VP0123456789AB1");
        Check("reused: says port 2 is another program's now", code == StarwireUsbHelper.ExitOk
            && rig.Said("Port 2 now holds another program's device") && rig.Said("leaves it alone"));
        Check("reused: stops the driver reconnecting the helper's device", rig.Said("Stopped 1 attempt by the driver")
            && rig.Driver.PendingAttempts.Count == 0);
        Check("reused: the list file is gone", !File.Exists(rig.Owned.FilePath));

        // Same laptop and bus ID, attached again by another program (another serial) on port 2
        rig = LaptopRig("same-place");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.Ports[1] = null;
        rig.Driver.Place(2, Laptop, "3240", "2-3", "");
        code = rig.Helper().DebugDetach();
        Check("same device, other program's attach: not detached", code == StarwireUsbHelper.ExitOk
            && rig.Driver.PlugoutPorts().Count == 0 && rig.Driver.Ports[1] != null);

        // The same again with a serial shaped like the helper's, just not the one it made
        rig = LaptopRig("other-serial");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.Ports[1] = null;
        rig.Driver.Place(2, Laptop, "3240", "2-3", "SWABCDEFGHJKMNP");
        code = rig.Helper().DebugDetach();
        Check("same device, an SW serial the helper didn't make: not detached", code == StarwireUsbHelper.ExitOk
            && rig.Driver.PlugoutPorts().Count == 0 && rig.Driver.Ports[1] != null && rig.Said("Port 2 now holds another program's device"));
    }

    static void DropDuringDetach()
    {
        // The laptop's connection drops just as the detach lists the devices. The driver has freed
        // the port but not yet started its reconnect attempts, so the list shows nothing and a
        // stop sent straight away would find no attempts to stop.
        Rig rig = LaptopRig("drop-race");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        int lists = 0;
        rig.Driver.OnList = () => {
            // The first list checks the driver; the second is the detach's own
            if (++lists == 2) {
                rig.Driver.DropHalfway(2);
            }
        };
        int code = rig.Helper().DebugDetach();
        rig.Driver.OnList = null;
        // Whatever the driver still had queued runs now
        rig.Driver.Settle();
        Check("drop during a detach: its reconnect attempts are stopped anyway", code == StarwireUsbHelper.ExitOk
            && rig.Said("Stopped 1 attempt") && rig.Driver.PendingAttempts.Count == 0);
        List<int> waits = rig.Driver.Positions(UsbipAbi.PlugoutHardware, NoSuchPort);
        List<int> stops = rig.Driver.Positions(UsbipAbi.StopAttachAttempts, 0);
        Check("drop during a detach: it waits for the driver's queued detaches, then stops",
            waits.Count == 1 && stops.Count == 1 && waits[0] < stops[0]);
        byte[] wait = rig.Driver.LastInput(UsbipAbi.PlugoutHardware);
        Check("drop during a detach: the wait is a detach of port 0x7FFFFFFF, never 0 or below",
            Same(wait, new byte[] { 8, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0x7F }) && !rig.Driver.DetachedAll);
        Check("drop during a detach: nothing else detached, the list is gone", rig.Driver.PlugoutPorts().Count == 0
            && rig.Driver.Ports[0] != null && !File.Exists(rig.Owned.FilePath));
    }

    static void NeverEveryDevice()
    {
        FakeVhci fake = new FakeVhci();
        fake.Place(1, Vibepollo, "50123", "1-1", VibepolloSerial);
        string problem;
        Check("detach port 0: refused, nothing sent", !UsbipCommands.Detach(fake, 0, out problem) && fake.Calls.Count == 0 && problem.Contains("every device"));
        Check("detach port -1: refused, nothing sent", !UsbipCommands.Detach(fake, -1, out problem) && fake.Calls.Count == 0);
        Check("detach port -2 (close all): refused", !UsbipCommands.Detach(fake, -2, out problem) && fake.Calls.Count == 0);
        Check("stop attempts with an empty field: refused, nothing sent",
            UsbipCommands.StopAttempts(fake, "", "3240", "2-3", out problem) < 0
            && UsbipCommands.StopAttempts(fake, Laptop, "", "2-3", out problem) < 0
            && UsbipCommands.StopAttempts(fake, Laptop, "3240", "", out problem) < 0 && fake.Calls.Count == 0);
        Check("Vibepollo's device survived", fake.Ports[0] != null && !fake.DetachedAll && !fake.StoppedAll);

        // A list file edited to point at port 0 or a device without a serial is ignored
        Rig rig = LaptopRig("edited");
        File.WriteAllText(rig.Owned.FilePath, ListHeading + "\r\n0\t127.0.0.1\t50123\t1-1\t" + VibepolloSerial
            + "\r\n0\t127.0.0.1\t50123\t1-1\t\r\n");
        int code = rig.Helper().DebugDetach();
        Check("edited list: Vibepollo's device isn't touched", rig.Driver.PlugoutPorts().Count == 0 && rig.Driver.Ports[0] != null
            && rig.Said("isn't understood") && code == StarwireUsbHelper.ExitOk);
    }

    static void OwnedList()
    {
        string path = Path.Combine(temp, "owned.txt");
        OwnedDevices a = new OwnedDevices(path);
        a.Devices.Add(new OwnedDevice(2, Laptop, "3240", "2-3", "SW0123456789ABC"));
        a.Devices.Add(new OwnedDevice(0, "fe80::1", "3240", "3-1.4", "SWZZZZZZZZZZZZZ"));
        string problem;
        Check("list: saves", a.Save(out problem));
        OwnedDevices b = new OwnedDevices(path);
        b.Load();
        Check("list: reads back the same", b.Devices.Count == 2 && b.Problems.Count == 0 && b.Devices[0].Port == 2
            && b.Devices[0].Host == Laptop && b.Devices[1].Host == "fe80::1" && b.Devices[1].BusId == "3-1.4" && b.Devices[1].Port == 0);
        Check("list: no file left half-written", !File.Exists(path + ".new"));

        File.WriteAllText(path, ListHeading + "\r\n# comment\r\n"
            + "2\t" + Laptop + "\t3240\t2-3\tSW0123456789ABC\r\n"
            + "2\t" + Laptop + "\t3240\t2-3\r\n"
            + "x\t" + Laptop + "\t3240\t2-3\tSW0123456789ABC\r\n"
            + "2\t8.8.8.8\t3240\t2-3\tSW0123456789ABC\r\n"
            + "2\t::FFFF:192.168.1.50\t3240\t2-3\tSW0123456789ABC\r\n"
            + "2\t" + Laptop + "\t03240\t2-3\tSW0123456789ABC\r\n"
            + "2\t" + Laptop + "\t3240\t2-3;x\tSW0123456789ABC\r\n"
            + "2\t" + Laptop + "\t3240\t2-3\tSWI23456789ABCD\r\n"
            + "2\t" + Laptop + "\t3240\t2-3\tsw0123456789abc\r\n");
        b.Load();
        Check("list: bad lines ignored, each named", b.Devices.Count == 1 && b.Problems.Count == 8 && b.Problems[0].StartsWith("line 4 "));
        b.Devices.Clear();
        Check("list: lines it doesn't understand, and comments, are kept when it writes the list again",
            b.Save(out problem) && TextOf(path).Contains("\r\n# comment\r\n")
            && TextOf(path).Contains("\r\n2\t8.8.8.8\t3240\t2-3\tSW0123456789ABC\r\n")
            && TextOf(path).StartsWith(ListHeading + "\r\n"));

        string big = ListHeading + "\r\n" + new string('#', 70 * 1024);
        File.WriteAllText(path, big);
        b.Load();
        Check("list: an oversized file is ignored", b.Devices.Count == 0 && b.Problems.Count == 1);
        Check("list: and left as it is", !b.Save(out problem) && TextOf(path) == big);

        File.WriteAllText(path, ListHeading + "\r\n2\t" + Laptop + "\t3240\t2-3\tSW0123456789ABC\r\n");
        b.Load();
        b.Devices.Clear();
        Check("list: an empty list removes the file", b.Save(out problem) && !File.Exists(path));
    }

    static void NotTheHelpersList()
    {
        // A file there that the helper didn't write: it never replaces or removes it
        Rig rig = LaptopRig("notes");
        string notes = "my own notes, not a list\r\n";
        File.WriteAllText(rig.Owned.FilePath, notes);
        int code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("someone else's file: attach refused before the driver is asked", code == StarwireUsbHelper.ExitAttach
            && rig.Driver.Count(UsbipAbi.PluginHardwareOnce) == 0 && rig.Said("isn't the helper's list"));
        Check("someone else's file: left as it was", rig.FileText() == notes);
        code = rig.Helper().DebugDetach();
        Check("someone else's file: detach finds nothing of the helper's in it, changes nothing",
            code == StarwireUsbHelper.ExitOk && rig.Driver.PlugoutPorts().Count == 0 && rig.FileText() == notes);

        // Even a file whose lines look like the helper's, without its heading
        File.WriteAllText(rig.Owned.FilePath, "2\t" + Laptop + "\t3240\t2-3\tSW0123456789ABC\r\n");
        OwnedDevices o = new OwnedDevices(rig.Owned.FilePath);
        o.Load();
        string problem;
        Check("no heading: nothing read from it, and an empty list doesn't remove it", o.Devices.Count == 0
            && !o.Save(out problem) && File.Exists(rig.Owned.FilePath));

        // A line from a newer helper, with a sixth field, survives an attach and a detach
        rig = LaptopRig("newer");
        string newer = "2\t192.168.1.60\t3240\t2-1\tSW0123456789ABC\tfuture-field";
        File.WriteAllText(rig.Owned.FilePath, ListHeading + "\r\n" + newer + "\r\n");
        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("a line it doesn't understand: kept after an attach", code == StarwireUsbHelper.ExitOk
            && rig.FileText().Contains("\r\n" + newer + "\r\n") && rig.FileText().Contains("\t2-3\tSW"));
        code = rig.Helper().DebugDetach();
        Check("a line it doesn't understand: kept after the detach, the helper's own line gone", code == StarwireUsbHelper.ExitOk
            && rig.FileText().Contains("\r\n" + newer + "\r\n") && !rig.FileText().Contains("\t2-3\tSW"));
    }

    static void ListGrows()
    {
        FakeVhci fake = new FakeVhci();
        for (int port = 1; port <= 6; port++) {
            fake.Place(port, Vibepollo, (50000 + port).ToString(), "1-1", "VP0000000000000");
        }
        string problem;
        List<ImportedDevice> devices = UsbipCommands.List(fake, out problem);
        Check("list of 6: asks again with room for 8", devices != null && devices.Count == 6 && fake.Count(UsbipAbi.GetImportedDevices) == 2);
        Check("list of 6: in port order", devices != null && devices[0].Port == 1 && devices[5].Port == 6 && devices[5].Service == "50006");
    }

    // The fake driver with one thing gone wrong, for the paths that keep the helper safe when a
    // run stops halfway or the driver's answer is odd
    sealed class OddVhci : IVhciDriver
    {
        readonly FakeVhci inner;
        // The helper's process is killed just after the driver attached the device
        public bool KilledAfterAttach;
        // How many bytes the driver says it wrote back to an attach or a stop (-1: the real number)
        public int AttachReplyLength = -1;
        public int StopReplyLength = -1;
        // Every list fails once a detach has worked
        public bool ListFailsAfterDetach;
        bool detached;

        public OddVhci(FakeVhci inner)
        {
            this.inner = inner;
        }

        public DriverIdentity Identity { get { return inner.Identity; } }

        public int Control(uint code, byte[] input, byte[] output, out int returned)
        {
            if (code == UsbipAbi.GetImportedDevices && ListFailsAfterDetach && detached) {
                returned = 0;
                return 31; // ERROR_GEN_FAILURE
            }
            int error = inner.Control(code, input, output, out returned);
            if (code == UsbipAbi.PlugoutHardware && error == 0) {
                detached = true;
            }
            if (code == UsbipAbi.PluginHardwareOnce) {
                if (KilledAfterAttach) {
                    throw new ProcessKilled();
                }
                if (AttachReplyLength >= 0) {
                    returned = AttachReplyLength;
                }
            }
            if (code == UsbipAbi.StopAttachAttempts && StopReplyLength >= 0) {
                returned = StopReplyLength;
            }
            return error;
        }

        public void Dispose()
        {
        }
    }

    sealed class ProcessKilled : Exception
    {
    }

    static void CrashSafety()
    {
        // Killed after the driver attached it and before the helper could note the port
        Rig rig = LaptopRig("killed");
        OddVhci odd = new OddVhci(rig.Driver);
        odd.KilledAfterAttach = true;
        bool killed = false;
        try {
            rig.Helper(odd).DebugAttach(Laptop, "2-3", 3240, false);
        }
        catch (ProcessKilled) {
            killed = true;
        }
        string serial = Requests.NewSerial(b => new Random(7).NextBytes(b));
        Check("killed after the attach: the device is on port 2", killed && rig.Driver.Ports[1] != null
            && rig.Driver.Ports[1].Serial == serial);
        Check("killed after the attach: it was on the list before the driver saw it, port not known yet",
            rig.FileText().Contains("\r\n0\t" + Laptop + "\t3240\t2-3\t" + serial + "\r\n"));
        int code = rig.Helper().DebugDetach();
        Check("killed after the attach: the next detach finds it by its serial, on port 2 only",
            code == StarwireUsbHelper.ExitOk && Same(rig.Driver.PlugoutPorts(), 2) && rig.Driver.Ports[1] == null);
        Check("killed after the attach: Vibepollo's device stays and the list is gone", rig.Driver.Ports[0] != null
            && rig.Driver.Ports[0].Serial == VibepolloSerial && !File.Exists(rig.Owned.FilePath));
    }

    static void ShortReplies()
    {
        // The driver attached it, but says it wrote back too few bytes to hold the port
        Rig rig = LaptopRig("short-attach");
        OddVhci odd = new OddVhci(rig.Driver);
        odd.AttachReplyLength = 4;
        int code = rig.Helper(odd).DebugAttach(Laptop, "2-3", 3240, false);
        Check("short attach answer: not taken as a port", code == StarwireUsbHelper.ExitAttach
            && rig.Said("didn't say which port") && !rig.Said("Attached on port"));
        Check("short attach answer: kept on the list in case it attached", rig.FileText().Contains("\r\n0\t" + Laptop + "\t3240\t2-3\tSW"));
        code = rig.Helper().DebugDetach();
        Check("short attach answer: the next detach removes it, and only it", code == StarwireUsbHelper.ExitOk
            && Same(rig.Driver.PlugoutPorts(), 2) && rig.Driver.Ports[0] != null && !File.Exists(rig.Owned.FilePath));

        // The stop's answer is too short to hold its count
        rig = LaptopRig("short-stop");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        rig.Driver.Drop(2);
        odd = new OddVhci(rig.Driver);
        odd.StopReplyLength = 8;
        rig.Helper(odd).DebugDetach();
        Check("short stop answer: says it couldn't stop the attempts, doesn't make up a count",
            rig.Said("Couldn't stop the driver's attempts") && !rig.Said("Stopped 1 attempt"));

        // The list fails after the detach: what's left can't be checked, so the list stays
        rig = LaptopRig("after-list");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        odd = new OddVhci(rig.Driver);
        odd.ListFailsAfterDetach = true;
        code = rig.Helper(odd).DebugDetach();
        Check("list fails after the detach: the helper keeps its list", code == StarwireUsbHelper.ExitDetach
            && rig.Said("keeps its list") && rig.FileText().Contains("\t2-3\tSW"));
        code = rig.Helper().DebugDetach();
        Check("list fails after the detach: the next detach clears it without detaching again", code == StarwireUsbHelper.ExitOk
            && Same(rig.Driver.PlugoutPorts(), 2) && !File.Exists(rig.Owned.FilePath));
    }

    // Runs work while another thread, standing in for another copy of the helper, holds the lock
    static int WhileAnotherCopyHoldsTheLock(Func<int> work)
    {
        using (ManualResetEvent held = new ManualResetEvent(false))
        using (ManualResetEvent release = new ManualResetEvent(false)) {
            Thread other = new Thread(() => {
                using (Mutex m = new Mutex(false, lockName)) {
                    m.WaitOne();
                    held.Set();
                    release.WaitOne();
                    m.ReleaseMutex();
                }
            });
            other.Start();
            held.WaitOne();
            try {
                return work();
            }
            finally {
                release.Set();
                other.Join();
            }
        }
    }

    static void DriverLock()
    {
        Rig rig = LaptopRig("lock");
        int code = WhileAnotherCopyHoldsTheLock(() => {
            UsbHelper h = rig.Helper();
            h.LockWaitMs = 0;
            return h.DebugAttach(Laptop, "2-3", 3240, false);
        });
        Check("lock held by another copy: attach gives up, says why, sends no attach", code == StarwireUsbHelper.ExitDriver
            && rig.Said("Another copy of the helper is busy") && rig.Driver.Count(UsbipAbi.PluginHardwareOnce) == 0
            && !File.Exists(rig.Owned.FilePath));

        code = rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        Check("lock free again: attach works", code == StarwireUsbHelper.ExitOk && rig.Driver.Ports[1] != null);
        code = WhileAnotherCopyHoldsTheLock(() => {
            UsbHelper h = rig.Helper();
            h.LockWaitMs = 0;
            return h.DebugDetach();
        });
        Check("lock held by another copy: detach gives up and sends no detach", code == StarwireUsbHelper.ExitDriver
            && rig.Driver.PlugoutPorts().Count == 0 && rig.Driver.Ports[1] != null && rig.FileText().Contains("\t2-3\tSW"));

        // A copy that died holding the lock doesn't block the next one. The lock stays open here,
        // so the helper meets it abandoned rather than making a new one.
        Mutex left = null;
        Thread dead = new Thread(() => {
            left = new Mutex(false, lockName);
            left.WaitOne();
        });
        dead.Start();
        dead.Join();
        UsbHelper after = rig.Helper();
        after.LockWaitMs = 0;
        code = after.DebugDetach();
        left.Dispose();
        Check("lock left by a copy that died: the next detach goes ahead", code == StarwireUsbHelper.ExitOk
            && Same(rig.Driver.PlugoutPorts(), 2));
    }

    static void TrayJobs()
    {
        // The icon's jobs run away from its thread, one at a time, and answer on its thread
        List<Action> away = new List<Action>();
        List<Action> back = new List<Action>();
        OneJobAtATime jobs = new OneJobAtATime(away.Add, back.Add);
        int ran = 0;
        string got = null;
        Exception threw = null;
        bool first = jobs.Start(() => { ran++; return "done"; }, (a, e) => { got = a; threw = e; });
        bool second = jobs.Start(() => { ran++; return "again"; }, (a, e) => { got = a; });
        Check("tray: a job doesn't run on the icon's thread", first && ran == 0 && away.Count == 1 && jobs.Busy);
        Check("tray: a second job while one runs isn't started", !second && away.Count == 1);
        away[0]();
        Check("tray: the answer waits for the icon's thread", ran == 1 && got == null && back.Count == 1 && jobs.Busy);
        back[0]();
        Check("tray: then it's shown, and the next job can start", got == "done" && threw == null && !jobs.Busy);
        away.Clear();
        back.Clear();
        jobs.Start<string>(() => { throw new InvalidOperationException("driver went away"); }, (a, e) => { threw = e; });
        away[0]();
        back[0]();
        Check("tray: a job that throws hands back the error and frees the runner", threw != null
            && threw.Message == "driver went away" && !jobs.Busy);

        // The jobs themselves, on the fake driver
        Rig rig = LaptopRig("tray");
        TrayAnswer answer = TrayWork.CheckDriver(say => rig.HelperSaying(say));
        Check("tray: the driver is ready", answer.Ok && answer.Status == "USB/IP driver ready (usbip-win2 0.9.8.1)");
        rig.Helper().DebugAttach(Laptop, "2-3", 3240, false);
        answer = TrayWork.Detach(say => rig.HelperSaying(say));
        Check("tray: detach detaches only the helper's port and says it's done", answer.Ok
            && answer.Balloon == "Done: the helper has nothing attached now." && Same(rig.Driver.PlugoutPorts(), 2)
            && answer.Status == "USB/IP driver ready (usbip-win2 0.9.8.1)");
        rig.Driver.Id.Version = "12.1.31.0";
        answer = TrayWork.CheckDriver(say => rig.HelperSaying(say));
        Check("tray: another driver version shows why", !answer.Ok && answer.Status.StartsWith("USB/IP driver: ")
            && answer.Status.Contains("won't use this one"));
    }

    // ---------------------------------------------------------------- helpers
    // A file's text, or "" when there's no such file
    static string TextOf(string path)
    {
        return File.Exists(path) ? File.ReadAllText(path) : "";
    }

    static ImportedDevice Find(FakeVhci fake, string busId)
    {
        foreach (ImportedDevice d in fake.Ports) {
            if (d != null && d.BusId == busId) {
                return d;
            }
        }
        return null;
    }

    static bool Same(byte[] a, byte[] b)
    {
        if (a == null || b == null || a.Length != b.Length) {
            return false;
        }
        for (int i = 0; i < a.Length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    static bool Same(List<int> list, params int[] want)
    {
        if (list.Count != want.Length) {
            return false;
        }
        for (int i = 0; i < want.Length; i++) {
            if (list[i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    static int NonZero(byte[] b)
    {
        int n = 0;
        foreach (byte x in b) {
            if (x != 0) {
                n++;
            }
        }
        return n;
    }

    static string Ascii(byte[] b, int offset, int size)
    {
        if (b == null) {
            return null;
        }
        int n = 0;
        while (n < size && b[offset + n] != 0) {
            n++;
        }
        return Encoding.ASCII.GetString(b, offset, n);
    }

    static void PutAscii(byte[] b, int offset, string s)
    {
        byte[] t = Encoding.ASCII.GetBytes(s);
        Array.Copy(t, 0, b, offset, t.Length);
    }

    static void PutInt(byte[] b, int offset, int v)
    {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >> 8);
        b[offset + 2] = (byte) (v >> 16);
        b[offset + 3] = (byte) (v >> 24);
    }
}
