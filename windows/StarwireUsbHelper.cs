// Starwire USB helper for Windows (GPL-3.0, part of Starwire).
//
// Will let a phone running Starwire lend a USB device to this PC, so Windows sees it as if it were
// plugged in here. It uses usbip-win2's USB/IP driver, the one Vibepollo's DualSense haptics use,
// and talks to the driver directly, without usbip.exe. So far this is the driver side: find the
// driver, check it's a version the helper knows, attach a device a USB/IP server shares, and
// detach only the devices the helper attached itself. The phone's side and its encrypted tunnel
// come next. Until then the only way to attach is the debug command, for a test with a PC that
// shares a device with usbipd (USB-LAPTOP-TEST.md).
//
//   StarwireUsbHelper.exe              runs in the notification area. For now its icon only shows
//                                      the driver's state and can detach the helper's devices
//   StarwireUsbHelper.exe --probe      says which USB/IP driver this PC has and what's attached to
//                                      it. Changes nothing: it can only send the driver's
//                                      read-only command
//   StarwireUsbHelper.exe --debug-attach <ip> <bus-id> [--port 3240] [--low-latency]
//                                      attaches the device that the PC at that address shares
//                                      with usbipd under that bus ID, and prints each step.
//                                      --low-latency asks the driver for its low-latency receive
//                                      mode instead of its default, zero-copy
//   StarwireUsbHelper.exe --debug-detach
//                                      detaches the devices the helper attached, and nothing else
//   --state-file <path>                keeps the list of attached devices there instead of
//                                      %LOCALAPPDATA%\Starwire\usb-attached.txt (for testing)
//
// From PowerShell, pipe the output of the commands (for example `| Out-Host`): a program without a
// window isn't waited for otherwise.
//
// Ports. The driver numbers the devices it holds by port, and frees a port for reuse as soon as
// its device goes. Vibepollo attaches its virtual DualSense through the same driver, and other
// programs can too. So the helper keeps a list of what it attached (address, TCP port, bus ID and
// a serial number it makes up for each attach), and before a detach it finds the port that holds
// exactly that. It never sends port 0 or below, which the driver takes as "every device". Before
// it stops the driver's reconnect attempts, it waits for the detaches the driver has queued. This
// follows Vibepollo (src/platform/windows/dualsense_usbip_transport.cpp, GPL-3.0, by Nonary).
//
// There's no version command in the driver, so the helper reads the version Windows keeps for the
// driver's controller in Device Manager, and then checks that the driver answers its list command
// the way that version's code does.
//
// The driver's commands, their layouts and its error codes are adapted from usbip-win2 0.9.8.1
// (include/usbip/vhci.h, include/usbip/consts.h and userspace/resources/messages.mc at 55e1fa7),
// under this notice:
//
//   BSD 2-Clause License
//
//   Copyright (c) 2021-2026, Vadym Hrynchyshyn <vadimgrn@gmail.com>
//
//   Redistribution and use in source and binary forms, with or without
//   modification, are permitted provided that the following conditions are met:
//
//   1. Redistributions of source code must retain the above copyright notice, this
//      list of conditions and the following disclaimer.
//
//   2. Redistributions in binary form must reproduce the above copyright notice,
//      this list of conditions and the following disclaimer in the documentation
//      and/or other materials provided with the distribution.
//
//   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
//   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
//   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
//   DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
//   FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
//   DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
//   SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
//   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
//   OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
//   OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Built with the C# compiler that comes with Windows, like the microphone receiver, so it's C# 5.

using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32.SafeHandles;

static class StarwireUsbHelper
{
    public const int ExitOk = 0;
    public const int ExitUsage = 1;
    // No driver, one the helper doesn't know, or one it can't read
    public const int ExitDriver = 2;
    public const int ExitAttach = 3;
    // Something the helper attached is still attached
    public const int ExitDetach = 4;

    // Held while a copy of the helper attaches or detaches, so two copies never pick ports at once
    public const string DriverMutex = @"Global\StarwireUsbHelper.Driver";

    static readonly RandomNumberGenerator rng = RandomNumberGenerator.Create();

    [DllImport("kernel32.dll")] static extern bool AttachConsole(int processId);

    [STAThread]
    static int Main(string[] args)
    {
        string command = null;
        string host = null;
        string busId = null;
        int tcpPort = UsbipAbi.DefaultTcpPort;
        bool lowLatency = false;
        string stateFile = null;
        string problem = null;
        for (int i = 0; i < args.Length && problem == null; i++) {
            string a = args[i].ToLowerInvariant();
            if (a == "--probe" || a == "--debug-detach") {
                command = a;
            }
            else if (a == "--debug-attach") {
                command = a;
                if (i + 2 >= args.Length) {
                    problem = "--debug-attach needs the other PC's IP address and the bus ID.";
                }
                else {
                    host = args[++i];
                    busId = args[++i];
                }
            }
            else if (a == "--port") {
                if (i + 1 >= args.Length || !int.TryParse(args[i + 1], NumberStyles.None, CultureInfo.InvariantCulture, out tcpPort)) {
                    problem = "--port needs a TCP port number, like 3240.";
                }
                i++;
            }
            else if (a == "--low-latency") {
                lowLatency = true;
            }
            else if (a == "--state-file") {
                if (i + 1 >= args.Length) {
                    problem = "--state-file needs a path.";
                }
                else {
                    stateFile = args[++i];
                }
            }
            else {
                problem = "Unknown option: " + args[i];
            }
        }

        // Built without a window of its own: the commands print to the terminal they were run from
        if ((command != null || problem != null) && !Console.IsOutputRedirected) {
            AttachConsole(-1);
        }
        if (problem != null) {
            Console.WriteLine(problem);
            Console.WriteLine("Usage: StarwireUsbHelper.exe [--probe | --debug-attach <ip> <bus-id> [--port 3240] [--low-latency] | --debug-detach] [--state-file <path>]");
            return ExitUsage;
        }

        OwnedDevices owned = new OwnedDevices(stateFile != null ? Path.GetFullPath(stateFile) : OwnedDevices.DefaultPath());
        if (command == "--probe") {
            return new UsbHelper(Win32Vhci.Open, owned, Random, DriverMutex, Console.WriteLine).Probe();
        }
        if (command == "--debug-attach") {
            return new UsbHelper(Win32Vhci.Open, owned, Random, DriverMutex, Console.WriteLine).DebugAttach(host, busId, tcpPort, lowLatency);
        }
        if (command == "--debug-detach") {
            return new UsbHelper(Win32Vhci.Open, owned, Random, DriverMutex, Console.WriteLine).DebugDetach();
        }

        // One icon per list of attached devices at a time
        bool first;
        Mutex single = new Mutex(true, InstanceName(owned.FilePath), out first);
        if (!first) {
            MessageBox.Show("Starwire's USB helper is already running. Its icon is in the notification area, by the clock.",
                "Starwire USB", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return ExitOk;
        }
        Application.EnableVisualStyles();
        UsbTray tray = new UsbTray(owned, Random);
        Application.Run();
        GC.KeepAlive(tray);
        GC.KeepAlive(single);
        return ExitOk;
    }

    static string InstanceName(string stateFile)
    {
        using (SHA256 sha = SHA256.Create()) {
            byte[] path = Encoding.UTF8.GetBytes(stateFile.ToLowerInvariant());
            byte[] hash = sha.ComputeHash(path);
            return "StarwireUsbHelper." + BitConverter.ToString(hash, 0, 8).Replace("-", "");
        }
    }

    static void Random(byte[] bytes)
    {
        lock (rng) {
            rng.GetBytes(bytes);
        }
    }
}

// ---------------------------------------------------------------- the driver's commands
// usbip-win2 0.9.8.1's user-mode interface (include/usbip/vhci.h). Every structure has a size
// field the driver checks against its own sizeof, and the layouts have no pointers, so they're the
// same for 32-bit and 64-bit programs. Strings are UTF-8 and end with a 0 byte.
static class UsbipAbi
{
    public const int DefaultTcpPort = 3240;

    // GUID_DEVINTERFACE_USBIP_VHCI
    public static readonly Guid Interface = new Guid(0xB4030C06, 0xDC5F, 0x4FCC, 0x87, 0xEB, 0xE5, 0x51, 0x5A, 0x09, 0x35, 0xC0);

    // CTL_CODE(FILE_DEVICE_UNKNOWN, function, METHOD_BUFFERED, FILE_READ_DATA | FILE_WRITE_DATA)
    public static uint ControlCode(int function)
    {
        return (0x22u << 16) | (3u << 14) | ((uint) function << 2);
    }

    public static readonly uint PluginHardware = ControlCode(0x800);
    public static readonly uint PlugoutHardware = ControlCode(0x801);
    public static readonly uint GetImportedDevices = ControlCode(0x802);
    public static readonly uint StopAttachAttempts = ControlCode(0x805);
    public static readonly uint PluginHardwareOnce = ControlCode(0x806);

    public const int BusIdSize = 32;
    public const int ServiceSize = 32;
    public const int HostSize = 1025;
    public const int SerialSize = 16;

    // sizeof each structure, as the driver checks it
    public const int LocationSize = 1100;    // imported_device_location
    public const int DeviceSize = 1132;      // imported_device
    public const int PluginSize = 1124;      // plugin_hardware
    public const int PlugoutSize = 8;        // plugout_hardware
    public const int StopSize = 1108;        // stop_attach_attempts
    public const int ListHeaderSize = 4;     // get_imported_devices up to its devices
    public const int ListSize = ListHeaderSize + DeviceSize;
    // What PLUGIN_HARDWARE writes back: the size field and the port
    public const int AttachReplySize = 8;

    // imported_device_location, from its start
    const int LocPort = 0;
    const int LocHash = 4;
    const int LocBusId = 8;
    const int LocService = 40;
    const int LocHost = 72;
    // imported_device_properties, from the start of imported_device
    const int DevDevId = 1100;
    const int DevSpeed = 1104;
    const int DevVendor = 1108;
    const int DevProduct = 1110;
    const int DevSerial = 1112;
    const int DevISerial = 1128;
    const int DevWskEvents = 1129;
    // plugin_hardware: size, imported_device_location, serial, wsk_events
    public const int PluginLocation = 4;
    public const int PluginSerial = 1104;
    public const int PluginWskEvents = 1120;
    // stop_attach_attempts: size, imported_device_location, count
    const int StopLocation = 4;
    const int StopCount = 1104;

    // The driver's errors (messages.mc, compiled with mc -c, so Windows hands them on unchanged)
    public const int ErrorGeneral = unchecked((int) 0xE1000001);
    public const int ErrorVersion = unchecked((int) 0xE1000002);
    public const int ErrorProtocol = unchecked((int) 0xE1000003);
    public const int ErrorPortFull = unchecked((int) 0xE1000004);
    public const int ErrorAbi = unchecked((int) 0xE1000005);
    public const int ErrorSerialNumber = unchecked((int) 0xE1000006);
    public const int ErrorNotAvailable = unchecked((int) 0xE1020001);
    public const int ErrorBusy = unchecked((int) 0xE1020002);
    public const int ErrorDeviceError = unchecked((int) 0xE1020003);
    public const int ErrorNoDevice = unchecked((int) 0xE1020004);
    public const int ErrorUnexpected = unchecked((int) 0xE1020005);

    public const int ErrorAccessDenied = 5;
    public const int ErrorInvalidParameter = 87;
    public const int ErrorInsufficientBuffer = 122;

    // A port the driver can't have: it has at most 254 for USB 2 and 254 for USB 3 (vhci.cpp)
    public const int NoSuchPort = int.MaxValue;

    public static byte[] ListRequest()
    {
        byte[] b = new byte[4];
        PutInt(b, 0, ListSize);
        return b;
    }

    public static byte[] PluginRequest(string host, string service, string busId, string serial, bool wskEvents)
    {
        byte[] b = new byte[PluginSize];
        PutInt(b, 0, PluginSize);
        PutLocation(b, PluginLocation, host, service, busId);
        PutString(b, PluginSerial, SerialSize, serial);
        b[PluginWskEvents] = (byte) (wskEvents ? 1 : 0);
        return b;
    }

    public static byte[] PlugoutRequest(int port)
    {
        byte[] b = new byte[PlugoutSize];
        PutInt(b, 0, PlugoutSize);
        PutInt(b, 4, port);
        return b;
    }

    public static byte[] StopRequest(string host, string service, string busId)
    {
        byte[] b = new byte[StopSize];
        PutInt(b, 0, StopSize);
        PutLocation(b, StopLocation, host, service, busId);
        return b;
    }

    public static int StopReplyCount(byte[] b)
    {
        return GetInt(b, StopCount);
    }

    public static ImportedDevice ParseDevice(byte[] b, int offset)
    {
        ImportedDevice d = new ImportedDevice();
        d.Port = GetInt(b, offset + LocPort);
        d.LocationHash = (uint) GetInt(b, offset + LocHash);
        d.BusId = GetString(b, offset + LocBusId, BusIdSize);
        d.Service = GetString(b, offset + LocService, ServiceSize);
        d.Host = GetString(b, offset + LocHost, HostSize);
        d.DevId = (uint) GetInt(b, offset + DevDevId);
        d.Speed = GetInt(b, offset + DevSpeed);
        d.Vendor = b[offset + DevVendor] | (b[offset + DevVendor + 1] << 8);
        d.Product = b[offset + DevProduct] | (b[offset + DevProduct + 1] << 8);
        d.Serial = GetString(b, offset + DevSerial, SerialSize);
        d.ISerial = b[offset + DevISerial];
        d.WskEvents = b[offset + DevWskEvents] != 0;
        return d;
    }

    // For the tests' fake driver: one imported_device as the driver writes it
    public static void PutDevice(byte[] b, int offset, ImportedDevice d)
    {
        PutInt(b, offset + LocPort, d.Port);
        PutInt(b, offset + LocHash, (int) d.LocationHash);
        PutString(b, offset + LocBusId, BusIdSize, d.BusId);
        PutString(b, offset + LocService, ServiceSize, d.Service);
        PutString(b, offset + LocHost, HostSize, d.Host);
        PutInt(b, offset + DevDevId, (int) d.DevId);
        PutInt(b, offset + DevSpeed, d.Speed);
        b[offset + DevVendor] = (byte) d.Vendor;
        b[offset + DevVendor + 1] = (byte) (d.Vendor >> 8);
        b[offset + DevProduct] = (byte) d.Product;
        b[offset + DevProduct + 1] = (byte) (d.Product >> 8);
        PutString(b, offset + DevSerial, SerialSize, d.Serial);
        b[offset + DevISerial] = (byte) d.ISerial;
        b[offset + DevWskEvents] = (byte) (d.WskEvents ? 1 : 0);
    }

    // The fields of an imported_device_location at offset: port and hash are the driver's to fill
    public static void PutLocation(byte[] b, int offset, string host, string service, string busId)
    {
        PutString(b, offset + LocBusId, BusIdSize, busId);
        PutString(b, offset + LocService, ServiceSize, service);
        PutString(b, offset + LocHost, HostSize, host);
    }

    public static void GetLocation(byte[] b, int offset, out string host, out string service, out string busId)
    {
        busId = GetString(b, offset + LocBusId, BusIdSize);
        service = GetString(b, offset + LocService, ServiceSize);
        host = GetString(b, offset + LocHost, HostSize);
    }

    public static void PutInt(byte[] b, int offset, int v)
    {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >> 8);
        b[offset + 2] = (byte) (v >> 16);
        b[offset + 3] = (byte) (v >> 24);
    }

    public static int GetInt(byte[] b, int offset)
    {
        return b[offset] | (b[offset + 1] << 8) | (b[offset + 2] << 16) | (b[offset + 3] << 24);
    }

    // The callers check lengths first; a string that doesn't fit with its 0 byte is a bug here
    public static void PutString(byte[] b, int offset, int size, string s)
    {
        byte[] text = Encoding.UTF8.GetBytes(s ?? "");
        if (text.Length >= size) {
            throw new ArgumentException("\"" + s + "\" is too long for a " + size + "-byte field");
        }
        Array.Copy(text, 0, b, offset, text.Length);
        Array.Clear(b, offset + text.Length, size - text.Length);
    }

    public static string GetString(byte[] b, int offset, int size)
    {
        int n = 0;
        while (n < size && b[offset + n] != 0) {
            n++;
        }
        return Encoding.UTF8.GetString(b, offset, n);
    }
}

// One device the driver holds, as GET_IMPORTED_DEVICES describes it
sealed class ImportedDevice
{
    public int Port;
    public uint LocationHash;
    public string BusId = "";
    public string Service = "";
    public string Host = "";
    public uint DevId;
    public int Speed;
    public int Vendor;
    public int Product;
    public string Serial = "";
    public int ISerial;
    public bool WskEvents;

    public bool SameLocation(string host, string service, string busId)
    {
        return string.Equals(Host, host, StringComparison.Ordinal)
            && string.Equals(Service, service, StringComparison.Ordinal)
            && string.Equals(BusId, busId, StringComparison.Ordinal);
    }

    public string Describe()
    {
        string s = "USB device " + Vendor.ToString("x4") + ":" + Product.ToString("x4");
        string name = KnownName(Vendor, Product);
        if (name != null) {
            s += " (" + name + ")";
        }
        s += ", bus ID " + BusId + " from " + Requests.Endpoint(Host, Service);
        if (Serial.Length > 0) {
            s += ", serial " + Serial;
        }
        // Vibepollo's virtual DualSense: serial "VP" and 13 hex digits, on this PC
        if (Host == "127.0.0.1" && Serial.StartsWith("VP", StringComparison.Ordinal)) {
            s += ", likely Vibepollo's virtual DualSense";
        }
        return s;
    }

    public static string KnownName(int vendor, int product)
    {
        if (vendor == 0x054c && product == 0x0ce6) {
            return "Sony DualSense";
        }
        if (vendor == 0x054c && product == 0x0df2) {
            return "Sony DualSense Edge";
        }
        return null;
    }

    public static string SpeedName(int speed)
    {
        switch (speed) {
            case 1: return "low speed";
            case 2: return "full speed";
            case 3: return "high speed (USB 2)";
            case 4: return "wireless USB";
            case 5: return "SuperSpeed (USB 3)";
            case 6: return "SuperSpeed+ (USB 3.1)";
            default: return "speed not known yet";
        }
    }
}

// ---------------------------------------------------------------- the driver itself
// Who the driver is, from its controller's entry in Device Manager
sealed class DriverIdentity
{
    public string InstanceId;
    public string Description;
    public string Version;
    public string Provider;
    public string Service;
    public DateTime? Date;
}

// The driver, behind an interface so the tests can use a fake one
interface IVhciDriver : IDisposable
{
    DriverIdentity Identity { get; }

    // Sends one command (DeviceIoControl). Returns 0 when it worked, otherwise the Windows error.
    int Control(uint code, byte[] input, byte[] output, out int returned);
}

// Lets only the driver's read-only command through. --probe and the icon's status use it, so
// they can't change anything even by mistake.
sealed class ReadOnlyVhci : IVhciDriver
{
    readonly IVhciDriver inner;
    public int Blocked;

    public ReadOnlyVhci(IVhciDriver inner)
    {
        this.inner = inner;
    }

    public DriverIdentity Identity { get { return inner.Identity; } }

    public int Control(uint code, byte[] input, byte[] output, out int returned)
    {
        returned = 0;
        if (code != UsbipAbi.GetImportedDevices) {
            Blocked++;
            return UsbipAbi.ErrorAccessDenied;
        }
        return inner.Control(code, input, output, out returned);
    }

    public void Dispose()
    {
        inner.Dispose();
    }
}

// The real driver, found through its device interface the way usbip-win2's own tool finds it
// (userspace/libusbip/src/vhci.cpp, get_path): exactly one controller, or none is used.
sealed class Win32Vhci : IVhciDriver
{
    const int CrSuccess = 0;
    const int CrBufferSmall = 0x1A;
    const int DevPropTypeString = 0x12;
    const int DevPropTypeFileTime = 0x10;

    [StructLayout(LayoutKind.Sequential)]
    struct DevPropKey
    {
        public Guid Category;
        public uint Id;

        public DevPropKey(string category, uint id)
        {
            Category = new Guid(category);
            Id = id;
        }
    }

    static readonly DevPropKey InstanceIdKey = new DevPropKey("78c34fc8-104a-4aca-9ea4-524d52996e57", 256);
    static readonly DevPropKey DeviceDescKey = new DevPropKey("a45c254e-df1c-4efd-8020-67d146a850e0", 2);
    static readonly DevPropKey ServiceKey = new DevPropKey("a45c254e-df1c-4efd-8020-67d146a850e0", 6);
    static readonly DevPropKey DriverDateKey = new DevPropKey("a8b865dd-2e3d-4094-ad97-e593a70c75d6", 2);
    static readonly DevPropKey DriverVersionKey = new DevPropKey("a8b865dd-2e3d-4094-ad97-e593a70c75d6", 3);
    static readonly DevPropKey DriverProviderKey = new DevPropKey("a8b865dd-2e3d-4094-ad97-e593a70c75d6", 9);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_Interface_List_SizeW(out int length, ref Guid interfaceClass, string deviceId, int flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_Interface_ListW(ref Guid interfaceClass, string deviceId, [Out] char[] buffer, int length, int flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_Interface_PropertyW(string path, ref DevPropKey key, out int type, [Out] byte[] buffer, ref int size, int flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Locate_DevNodeW(out int node, string deviceId, int flags);
    [DllImport("cfgmgr32.dll")]
    static extern int CM_Get_DevNode_PropertyW(int node, ref DevPropKey key, out int type, [Out] byte[] buffer, ref int size, int flags);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern SafeFileHandle CreateFileW(string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DeviceIoControl(SafeFileHandle device, uint code, byte[] input, int inputSize, [Out] byte[] output, int outputSize, out int returned, IntPtr overlapped);

    readonly SafeFileHandle handle;
    readonly DriverIdentity identity;

    Win32Vhci(SafeFileHandle handle, DriverIdentity identity)
    {
        this.handle = handle;
        this.identity = identity;
    }

    public DriverIdentity Identity { get { return identity; } }

    public static IVhciDriver Open(out string problem)
    {
        Guid guid = UsbipAbi.Interface;
        List<string> paths = null;
        // The list can change while it's being sized
        for (int attempt = 0; attempt < 4 && paths == null; attempt++) {
            int length;
            int cr = CM_Get_Device_Interface_List_SizeW(out length, ref guid, null, 0);
            if (cr != CrSuccess || length < 1 || length > 65536) {
                problem = "Windows couldn't list the USB/IP controllers (configuration manager error " + cr + ").";
                return null;
            }
            char[] buffer = new char[length];
            cr = CM_Get_Device_Interface_ListW(ref guid, null, buffer, length, 0);
            if (cr == CrBufferSmall) {
                continue;
            }
            if (cr != CrSuccess) {
                problem = "Windows couldn't list the USB/IP controllers (configuration manager error " + cr + ").";
                return null;
            }
            paths = new List<string>();
            int start = 0;
            for (int i = 0; i < buffer.Length; i++) {
                if (buffer[i] == '\0') {
                    if (i == start) {
                        break;
                    }
                    paths.Add(new string(buffer, start, i - start));
                    start = i + 1;
                }
            }
        }
        if (paths == null) {
            problem = "The list of USB/IP controllers kept changing. Try again in a moment.";
            return null;
        }
        if (paths.Count == 0) {
            problem = "This PC has no USB/IP driver running (usbip-win2's controller isn't there).";
            return null;
        }
        if (paths.Count > 1) {
            problem = "This PC has " + paths.Count + " USB/IP controllers. The helper expects one, so it won't guess which to use.";
            return null;
        }
        DriverIdentity identity = Identify(paths[0]);
        // GENERIC_READ | GENERIC_WRITE (its commands need both), shared, OPEN_EXISTING, not overlapped
        SafeFileHandle handle = CreateFileW(paths[0], 0xC0000000, 3, IntPtr.Zero, 3, 0x80, IntPtr.Zero);
        if (handle.IsInvalid) {
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            problem = "Windows didn't let the helper open the USB/IP driver: " + new Win32Exception(error).Message;
            return null;
        }
        problem = null;
        return new Win32Vhci(handle, identity);
    }

    static DriverIdentity Identify(string path)
    {
        DriverIdentity id = new DriverIdentity();
        DevPropKey key = InstanceIdKey;
        id.InstanceId = AsString(Read(delegate(byte[] buffer, ref int size, out int type) {
            return CM_Get_Device_Interface_PropertyW(path, ref key, out type, buffer, ref size, 0);
        }, DevPropTypeString));
        int node;
        if (id.InstanceId == null || CM_Locate_DevNodeW(out node, id.InstanceId, 0) != CrSuccess) {
            return id;
        }
        id.Description = NodeString(node, DeviceDescKey);
        id.Version = NodeString(node, DriverVersionKey);
        id.Provider = NodeString(node, DriverProviderKey);
        id.Service = NodeString(node, ServiceKey);
        key = DriverDateKey;
        byte[] date = Read(delegate(byte[] buffer, ref int size, out int type) {
            return CM_Get_DevNode_PropertyW(node, ref key, out type, buffer, ref size, 0);
        }, DevPropTypeFileTime);
        if (date != null && date.Length == 8) {
            id.Date = DateTime.FromFileTimeUtc(BitConverter.ToInt64(date, 0));
        }
        return id;
    }

    delegate int PropertyCall(byte[] buffer, ref int size, out int type);

    static string NodeString(int node, DevPropKey key)
    {
        return AsString(Read(delegate(byte[] buffer, ref int size, out int type) {
            return CM_Get_DevNode_PropertyW(node, ref key, out type, buffer, ref size, 0);
        }, DevPropTypeString));
    }

    // A property's bytes, or null if it's missing, of another type, or oddly big
    static byte[] Read(PropertyCall call, int wantType)
    {
        int size = 0;
        int type;
        int cr = call(null, ref size, out type);
        if (cr != CrBufferSmall || size <= 0 || size > 8192) {
            return null;
        }
        byte[] buffer = new byte[size];
        if (call(buffer, ref size, out type) != CrSuccess || type != wantType || size > buffer.Length) {
            return null;
        }
        if (size < buffer.Length) {
            Array.Resize(ref buffer, size);
        }
        return buffer;
    }

    static string AsString(byte[] b)
    {
        return b == null ? null : Encoding.Unicode.GetString(b).TrimEnd('\0');
    }

    public int Control(uint code, byte[] input, byte[] output, out int returned)
    {
        returned = 0;
        int got;
        if (!DeviceIoControl(handle, code, input, input == null ? 0 : input.Length, output, output == null ? 0 : output.Length, out got, IntPtr.Zero)) {
            return Marshal.GetLastWin32Error();
        }
        returned = got;
        return 0;
    }

    public void Dispose()
    {
        handle.Dispose();
    }
}

// ---------------------------------------------------------------- versions the helper knows
sealed class KnownDriver
{
    public readonly string Version;
    public readonly string Provider;
    public readonly string Service;
    public readonly string Name;

    public KnownDriver(string version, string provider, string service, string name)
    {
        Version = version;
        Provider = provider;
        Service = service;
        Name = name;
    }

    // Only versions whose command layouts were checked against their source. 12.1.30.403 dated
    // 22 Sep 2026 is the driver in usbip-win2's 0.9.8.1 release (USBip-0.9.8.1-x64.exe): its
    // usbip2_ude.inf, .sys and .cat have the SHA-256s Vibepollo pins for that release
    // (packaging/windows/usbip_transport/package.ps1).
    public static readonly KnownDriver[] All = {
        new KnownDriver("12.1.30.403", "USBIP-WIN2", "usbip2_ude", "usbip-win2 0.9.8.1"),
    };

    public static KnownDriver Recognize(DriverIdentity id, out string problem)
    {
        if (id == null || string.IsNullOrEmpty(id.Version)) {
            problem = "Windows doesn't say which version the USB/IP driver is, so the helper won't use it.";
            return null;
        }
        List<string> known = new List<string>();
        foreach (KnownDriver k in All) {
            if (!string.Equals(id.Version, k.Version, StringComparison.Ordinal)) {
                known.Add(k.Version + " (" + k.Name + ")");
                continue;
            }
            if (!string.Equals(id.Provider, k.Provider, StringComparison.OrdinalIgnoreCase)
                    || !string.Equals(id.Service, k.Service, StringComparison.OrdinalIgnoreCase)) {
                problem = "Its version number is " + k.Name + "'s, but it comes from " + (id.Provider ?? "an unnamed provider")
                    + " with the driver service " + (id.Service ?? "(none)") + ", not " + k.Provider + " with " + k.Service
                    + ". So the helper won't use it.";
                return null;
            }
            problem = null;
            return k;
        }
        problem = "The helper knows only version " + string.Join(", ", known.ToArray())
            + ". Another version may lay out its commands differently, so the helper won't use this one.";
        return null;
    }
}

// ---------------------------------------------------------------- one command each
static class UsbipCommands
{
    // Everything the driver holds. Null (with the reason) if the driver's answer doesn't fit the
    // layout: that's also the check that the driver is the version it says it is.
    public static List<ImportedDevice> List(IVhciDriver driver, out string problem)
    {
        // As usbip-win2's tool does: start with room for 4 and double while the driver says it's short
        for (int capacity = 4; capacity <= 1024; capacity *= 2) {
            byte[] output = new byte[UsbipAbi.ListHeaderSize + capacity * UsbipAbi.DeviceSize];
            int returned;
            int error = driver.Control(UsbipAbi.GetImportedDevices, UsbipAbi.ListRequest(), output, out returned);
            if (error == UsbipAbi.ErrorInsufficientBuffer) {
                continue;
            }
            if (error != 0) {
                problem = "its list of devices failed: " + UsbipErrors.Explain(error, null);
                return null;
            }
            if (returned < UsbipAbi.ListHeaderSize || returned > output.Length
                    || (returned - UsbipAbi.ListHeaderSize) % UsbipAbi.DeviceSize != 0) {
                problem = "its list of devices was " + returned + " bytes, which doesn't fit 4 bytes and "
                    + UsbipAbi.DeviceSize + " for each device";
                return null;
            }
            List<ImportedDevice> devices = new List<ImportedDevice>();
            for (int offset = UsbipAbi.ListHeaderSize; offset < returned; offset += UsbipAbi.DeviceSize) {
                ImportedDevice d = UsbipAbi.ParseDevice(output, offset);
                if (d.Port <= 0) {
                    problem = "its list has a device on port " + d.Port + ", and ports start at 1";
                    return null;
                }
                devices.Add(d);
            }
            problem = null;
            return devices;
        }
        problem = "it holds more than 1024 devices";
        return null;
    }

    // PLUGIN_HARDWARE_ONCE: connects to the server and imports the device, without the driver's
    // automatic retries if it can't connect. Returns the port, or 0 with the Windows error.
    // An error of 0 with no port means the driver's answer didn't fit: it may have attached it.
    public static int Attach(IVhciDriver driver, string host, string service, string busId, string serial, bool wskEvents, out int error)
    {
        byte[] request = UsbipAbi.PluginRequest(host, service, busId, serial, wskEvents);
        byte[] reply = new byte[UsbipAbi.AttachReplySize];
        int returned;
        error = driver.Control(UsbipAbi.PluginHardwareOnce, request, reply, out returned);
        if (error != 0) {
            return 0;
        }
        int port = UsbipAbi.GetInt(reply, 4);
        return returned == UsbipAbi.AttachReplySize && port > 0 ? port : 0;
    }

    // PLUGOUT_HARDWARE for one port. Port 0 or below means every device to the driver, so it's
    // refused here, whoever asks.
    public static bool Detach(IVhciDriver driver, int port, out string problem)
    {
        if (port <= 0) {
            problem = "port " + port + " means every device to the driver, so the helper never sends it";
            return false;
        }
        int returned;
        int error = driver.Control(UsbipAbi.PlugoutHardware, UsbipAbi.PlugoutRequest(port), null, out returned);
        problem = error == 0 ? null : UsbipErrors.Explain(error, null);
        return error == 0;
    }

    // PLUGOUT_HARDWARE for a port that can't exist, as a wait. The driver runs detaches one at a
    // time, in order, and refuses this one without touching any device once those queued before
    // it are done. That matters for a device whose connection just dropped: the driver detaches it
    // through that queue, freeing its port first and only then starting its reconnect attempts,
    // so a stop sent in between finds none to stop. Vibepollo waits the same way before its stop
    // (detach_barrier in dualsense_usbip_transport.cpp). True when the driver refused it as expected.
    public static bool WaitForQueuedDetaches(IVhciDriver driver, out string problem)
    {
        int returned;
        int error = driver.Control(UsbipAbi.PlugoutHardware, UsbipAbi.PlugoutRequest(UsbipAbi.NoSuchPort), null, out returned);
        if (error == UsbipAbi.ErrorInvalidParameter) {
            problem = null;
            return true;
        }
        problem = error == 0 ? "the driver says it detached port " + UsbipAbi.NoSuchPort + ", which can't exist"
            : UsbipErrors.Explain(error, null);
        return false;
    }

    // STOP_ATTACH_ATTEMPTS for one exact location: the driver keeps trying to reconnect a device
    // whose connection dropped. Empty fields would stop everyone's attempts, so they're refused.
    // Returns how many attempts it stopped, or -1.
    public static int StopAttempts(IVhciDriver driver, string host, string service, string busId, out string problem)
    {
        if (string.IsNullOrEmpty(host) || string.IsNullOrEmpty(service) || string.IsNullOrEmpty(busId)) {
            problem = "an empty address, TCP port or bus ID would stop every program's attempts, so the helper never sends one";
            return -1;
        }
        byte[] request = UsbipAbi.StopRequest(host, service, busId);
        byte[] reply = new byte[UsbipAbi.StopSize];
        int returned;
        int error = driver.Control(UsbipAbi.StopAttachAttempts, request, reply, out returned);
        if (error != 0) {
            problem = UsbipErrors.Explain(error, null);
            return -1;
        }
        if (returned != UsbipAbi.StopSize) {
            problem = "the driver's answer was " + returned + " bytes, not " + UsbipAbi.StopSize;
            return -1;
        }
        problem = null;
        return Math.Max(0, UsbipAbi.StopReplyCount(reply));
    }
}

// What an error from the driver means, in plain words
static class UsbipErrors
{
    // The device being attached, for the messages that name it
    public sealed class Target
    {
        public string Host;
        public string Service;
        public string BusId;
    }

    public static string Explain(int error, Target t)
    {
        string busId = t != null ? t.BusId : "?";
        string where = t != null ? Requests.Endpoint(t.Host, t.Service) : "the other PC";
        string tcp = t != null ? t.Service : "3240";
        switch (error) {
            case UsbipAbi.ErrorNoDevice:
                return "the other PC shares no device with bus ID " + busId + ". On it, usbipd list shows the bus IDs, "
                    + "and usbipd bind --busid <id> (as administrator) shares one.";
            case UsbipAbi.ErrorNotAvailable:
                return "the other PC says bus ID " + busId + " isn't shared. Run usbipd bind --busid " + busId + " there, as administrator.";
            case UsbipAbi.ErrorBusy:
                return "the other PC says bus ID " + busId + " is already attached by another computer.";
            case UsbipAbi.ErrorDeviceError:
                return "the other PC says the device is in an error state. Unplug it, plug it back in and bind it again.";
            case UsbipAbi.ErrorUnexpected:
                return "the other PC gave an unexpected answer.";
            case UsbipAbi.ErrorVersion:
                return "the USB/IP server at " + where + " speaks another version of the protocol.";
            case UsbipAbi.ErrorProtocol:
                return "the answer from " + where + " broke the USB/IP protocol.";
            case UsbipAbi.ErrorPortFull:
                return "the driver has no free port. Detach a device first.";
            case UsbipAbi.ErrorAbi:
                return "the driver says a command had the wrong size, so it isn't the version the helper expects.";
            case UsbipAbi.ErrorSerialNumber:
                return "the driver refused the serial number.";
            case UsbipAbi.ErrorGeneral:
                return "the driver says the command failed.";
            case 1225: // ERROR_CONNECTION_REFUSED
                return where + " refused the connection. Check that usbipd is running there and that its firewall lets TCP "
                    + tcp + " in on the Private network.";
            case 121:  // ERROR_SEM_TIMEOUT, the driver's connect timing out
            case 1460: // ERROR_TIMEOUT
                return "no answer from " + where + " in time. Check the address, that both PCs are on the same network, "
                    + "and the other PC's firewall.";
            case 1168: // ERROR_NOT_FOUND, from the driver's address lookup
            case 1169: // ERROR_NO_MATCH
                return "the driver couldn't look up the address " + (t != null ? t.Host : "") + ".";
            case 1231: // ERROR_NETWORK_UNREACHABLE
            case 1232: // ERROR_HOST_UNREACHABLE
                return "this PC can't reach " + (t != null ? t.Host : "the other PC") + ".";
            case 64:   // ERROR_NETNAME_DELETED
            case 1236: // ERROR_CONNECTION_ABORTED
                return "the connection to " + where + " closed during the attach.";
            case UsbipAbi.ErrorAccessDenied:
                return "Windows refused the command (access denied).";
        }
        return new Win32Exception(error).Message.TrimEnd('.', ' ') + " (error 0x" + error.ToString("X8") + ").";
    }
}

// ---------------------------------------------------------------- checking what's asked for
static class Requests
{
    // usbipd's bus IDs: hub and port, then ports of hubs behind it, like 2-1 or 3-1.4
    static readonly Regex BusIdPattern = new Regex(@"^[0-9]{1,3}-[0-9]{1,3}(\.[0-9]{1,3}){0,5}\z");
    // Dotted IPv4, no leading zeros (Windows reads those as octal)
    static readonly Regex Ipv4Pattern = new Regex(@"^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}\z");
    static readonly Regex Ipv6Pattern = new Regex(@"^[0-9A-Fa-f:.]+(%[0-9]{1,10})?\z");
    static readonly Regex SerialPattern = new Regex(@"^SW[0-9A-HJKMNP-TV-Z]{13}\z");
    // Crockford's base 32: no I, L, O or U to misread
    const string SerialAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    // An IP address the helper will connect to, written the way it's kept and compared. Names are
    // refused (the driver would look them up as SYSTEM, and compare as typed), and so are addresses
    // outside home networks: usbipd has no password. That's a filter, not a security boundary.
    public static bool ParseHost(string text, out string host, out string problem)
    {
        host = null;
        IPAddress address = null;
        bool shaped = text != null && (text.IndexOf(':') >= 0 ? Ipv6Pattern.IsMatch(text) : Ipv4Pattern.IsMatch(text));
        if (!shaped || !IPAddress.TryParse(text, out address)) {
            problem = "\"" + text + "\" isn't an IP address. Give the other PC's address as ipconfig shows it there "
                + "(IPv4 Address), not its name.";
            return false;
        }
        if (address.AddressFamily == AddressFamily.InterNetworkV6 && address.IsIPv4MappedToIPv6) {
            address = address.MapToIPv4();
        }
        if (!IsHomeAddress(address)) {
            problem = address + " isn't a home-network address. usbipd shares the device with anyone who can reach it, "
                + "so the helper only connects to private, link-local, VPN (100.64.0.0/10) and loopback addresses.";
            return false;
        }
        host = address.ToString();
        problem = null;
        return true;
    }

    public static bool IsHomeAddress(IPAddress a)
    {
        byte[] b = a.GetAddressBytes();
        if (b.Length == 4) {
            return b[0] == 10
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 169 && b[1] == 254)
                || (b[0] == 100 && b[1] >= 64 && b[1] <= 127)
                || b[0] == 127;
        }
        return IPAddress.IsLoopback(a) || a.IsIPv6LinkLocal || (b[0] & 0xFE) == 0xFC;
    }

    public static bool CheckBusId(string busId, out string problem)
    {
        if (busId == null || busId.Length >= UsbipAbi.BusIdSize || !BusIdPattern.IsMatch(busId)) {
            problem = "\"" + busId + "\" isn't a bus ID. On the other PC, usbipd list shows them in its first column, like 2-1.";
            return false;
        }
        problem = null;
        return true;
    }

    public static bool CheckTcpPort(int port, out string problem)
    {
        if (port < 1 || port > 65535) {
            problem = port + " isn't a TCP port number. usbipd uses 3240.";
            return false;
        }
        problem = null;
        return true;
    }

    public static bool IsSerial(string s)
    {
        return s != null && SerialPattern.IsMatch(s);
    }

    // A new serial number for an attach: SW and 13 random characters (65 bits). The driver takes
    // up to 15 letters and digits, and Windows sees the device with this serial.
    public static string NewSerial(Action<byte[]> random)
    {
        byte[] bytes = new byte[13];
        random(bytes);
        StringBuilder s = new StringBuilder("SW");
        foreach (byte b in bytes) {
            s.Append(SerialAlphabet[b & 31]);
        }
        return s.ToString();
    }

    public static string Endpoint(string host, string service)
    {
        return (host.IndexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + service;
    }
}

// ---------------------------------------------------------------- what the helper attached
// One attach the helper made: where the device came from and the serial it gave it. Port is
// where the driver put it, 0 until the driver says. Only a device matching all five is the helper's.
sealed class OwnedDevice
{
    public int Port;
    public readonly string Host;
    public readonly string Service;
    public readonly string BusId;
    public readonly string Serial;

    public OwnedDevice(int port, string host, string service, string busId, string serial)
    {
        Port = port;
        Host = host;
        Service = service;
        BusId = busId;
        Serial = serial;
    }

    // The driver can reconnect a dropped device on a new port, with the same serial, so the port
    // isn't part of the match; it has to be a real one, though
    public bool Matches(ImportedDevice d)
    {
        return d != null && d.Port > 0 && Requests.IsSerial(Serial)
            && d.SameLocation(Host, Service, BusId)
            && string.Equals(d.Serial, Serial, StringComparison.Ordinal);
    }

    public string Describe()
    {
        return "bus ID " + BusId + " from " + Requests.Endpoint(Host, Service) + " (serial " + Serial + ")";
    }
}

// The list of attached devices, in a small text file so a later run (or the icon) can detach them
sealed class OwnedDevices
{
    const string Header = "# Starwire USB helper: the USB devices it attached. It only ever detaches these.";
    const string Columns = "# port\taddress\tTCP port\tbus ID\tserial";
    const int MaxFileBytes = 64 * 1024;

    public readonly string FilePath;
    public readonly List<OwnedDevice> Devices = new List<OwnedDevice>();
    public readonly List<string> Problems = new List<string>();

    public OwnedDevices(string filePath)
    {
        FilePath = filePath;
    }

    public static string DefaultPath()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Starwire", "usb-attached.txt");
    }

    public void Load()
    {
        Devices.Clear();
        Problems.Clear();
        string[] lines;
        try {
            if (!File.Exists(FilePath)) {
                return;
            }
            if (new FileInfo(FilePath).Length > MaxFileBytes) {
                Problems.Add("the list is over " + MaxFileBytes / 1024 + " KB, so it was ignored");
                return;
            }
            lines = File.ReadAllLines(FilePath, Encoding.UTF8);
        }
        catch (Exception e) {
            Problems.Add("couldn't read it: " + e.Message);
            return;
        }
        for (int i = 0; i < lines.Length; i++) {
            string line = lines[i];
            if (line.Length == 0 || line[0] == '#') {
                continue;
            }
            OwnedDevice d = Parse(line);
            if (d == null) {
                Problems.Add("line " + (i + 1) + " isn't understood, so it was ignored");
            }
            else {
                Devices.Add(d);
            }
        }
    }

    static OwnedDevice Parse(string line)
    {
        string[] f = line.Split('\t');
        int port, service;
        string host, problem;
        if (f.Length != 5
                || !int.TryParse(f[0], NumberStyles.None, CultureInfo.InvariantCulture, out port) || port > 65535
                || !Requests.ParseHost(f[1], out host, out problem) || host != f[1]
                || !int.TryParse(f[2], NumberStyles.None, CultureInfo.InvariantCulture, out service)
                || service.ToString(CultureInfo.InvariantCulture) != f[2] || !Requests.CheckTcpPort(service, out problem)
                || !Requests.CheckBusId(f[3], out problem)
                || !Requests.IsSerial(f[4])) {
            return null;
        }
        return new OwnedDevice(port, host, f[2], f[3], f[4]);
    }

    public OwnedDevice Find(ImportedDevice d)
    {
        foreach (OwnedDevice o in Devices) {
            if (o.Matches(d)) {
                return o;
            }
        }
        return null;
    }

    // Written whole to a new file and moved over the old one, so a crash leaves one or the other.
    // An empty list removes the file.
    public bool Save(out string problem)
    {
        try {
            if (Devices.Count == 0) {
                if (File.Exists(FilePath)) {
                    File.Delete(FilePath);
                }
                problem = null;
                return true;
            }
            string folder = Path.GetDirectoryName(FilePath);
            if (!string.IsNullOrEmpty(folder)) {
                Directory.CreateDirectory(folder);
            }
            StringBuilder s = new StringBuilder();
            s.Append(Header).Append("\r\n").Append(Columns).Append("\r\n");
            foreach (OwnedDevice d in Devices) {
                s.Append(d.Port.ToString(CultureInfo.InvariantCulture)).Append('\t').Append(d.Host).Append('\t')
                    .Append(d.Service).Append('\t').Append(d.BusId).Append('\t').Append(d.Serial).Append("\r\n");
            }
            string next = FilePath + ".new";
            File.WriteAllText(next, s.ToString(), new UTF8Encoding(false));
            if (File.Exists(FilePath)) {
                File.Replace(next, FilePath, null);
            }
            else {
                File.Move(next, FilePath);
            }
            problem = null;
            return true;
        }
        catch (Exception e) {
            problem = e.Message;
            return false;
        }
    }
}

// ---------------------------------------------------------------- the commands
// --probe, --debug-attach and --debug-detach, with the driver, the list of attached devices,
// the random bytes and the lock passed in, so the tests run them against a fake driver
sealed class UsbHelper
{
    public delegate IVhciDriver Opener(out string problem);

    readonly Opener open;
    readonly OwnedDevices owned;
    readonly Action<byte[]> random;
    readonly string mutexName;
    readonly Action<string> say;
    int step;

    // The driver's version, once a check has passed
    public KnownDriver Driver;
    // How long to wait for another copy of the helper to finish with the driver
    public int LockWaitMs = 10000;

    public UsbHelper(Opener open, OwnedDevices owned, Action<byte[]> random, string mutexName, Action<string> say)
    {
        this.open = open;
        this.owned = owned;
        this.random = random;
        this.mutexName = mutexName;
        this.say = say;
    }

    void Step(string text)
    {
        step++;
        say(step + ". " + text);
    }

    void Note(string text)
    {
        say("   " + text);
    }

    public int Probe()
    {
        say("Starwire USB helper: the USB/IP driver and what's attached (changes nothing)");
        IVhciDriver driver = Open();
        if (driver == null) {
            return StarwireUsbHelper.ExitDriver;
        }
        using (IVhciDriver readOnly = new ReadOnlyVhci(driver)) {
            List<ImportedDevice> devices = CheckDriver(readOnly);
            if (devices == null) {
                return StarwireUsbHelper.ExitDriver;
            }
            LoadOwned();
            ShowDevices(devices);
        }
        return StarwireUsbHelper.ExitOk;
    }

    public int DebugAttach(string hostText, string busId, int tcpPort, bool lowLatency)
    {
        say("Starwire USB helper: attach a shared USB device (debug)");
        // What's asked for is checked before the driver is touched at all
        string host, problem;
        if (!Requests.ParseHost(hostText, out host, out problem) || !Requests.CheckBusId(busId, out problem)
                || !Requests.CheckTcpPort(tcpPort, out problem)) {
            say(problem);
            return StarwireUsbHelper.ExitUsage;
        }
        string service = tcpPort.ToString(CultureInfo.InvariantCulture);
        Step("Device to attach: bus ID " + busId + " from " + Requests.Endpoint(host, service)
            + (lowLatency ? ", in the driver's low-latency receive mode." : "."));
        IVhciDriver driver = Open();
        if (driver == null) {
            return StarwireUsbHelper.ExitDriver;
        }
        using (driver) {
            if (CheckDriver(driver) == null) {
                return StarwireUsbHelper.ExitDriver;
            }
            return WithDriverLock(() => AttachLocked(driver, host, service, busId, lowLatency));
        }
    }

    int AttachLocked(IVhciDriver driver, string host, string service, string busId, bool lowLatency)
    {
        LoadOwned();
        string problem;
        List<ImportedDevice> devices = UsbipCommands.List(driver, out problem);
        if (devices == null) {
            Step("The driver's list of devices failed: " + problem);
            return StarwireUsbHelper.ExitDriver;
        }
        ShowDevices(devices);
        foreach (ImportedDevice d in devices) {
            if (!d.SameLocation(host, service, busId)) {
                continue;
            }
            if (owned.Find(d) != null) {
                Step("The helper already attached it, on port " + d.Port + ". Nothing to do.");
                return StarwireUsbHelper.ExitOk;
            }
            Step("Port " + d.Port + " already holds it, attached by another program, so the helper leaves it alone.");
            return StarwireUsbHelper.ExitAttach;
        }

        // On the list before the driver sees it: if this stops halfway, a detach still finds it
        OwnedDevice mine = new OwnedDevice(0, host, service, busId, Requests.NewSerial(random));
        owned.Devices.Add(mine);
        if (!owned.Save(out problem)) {
            owned.Devices.Remove(mine);
            Step("Couldn't write the list of attached devices (" + problem + "), so the helper won't attach "
                + "something it couldn't find again to detach.");
            return StarwireUsbHelper.ExitAttach;
        }
        Step("Connecting to " + Requests.Endpoint(host, service) + " and asking for bus ID " + busId
            + ", with serial " + mine.Serial + ". The driver does this, and with a wrong address it can take a while to give up.");
        int error;
        int port = UsbipCommands.Attach(driver, host, service, busId, mine.Serial, lowLatency, out error);
        if (port <= 0 && error == 0) {
            Note("The driver didn't say which port it used, so it may have attached it. It stays on the helper's "
                + "list: run --debug-detach to be sure it's gone.");
            return StarwireUsbHelper.ExitAttach;
        }
        if (port <= 0) {
            owned.Devices.Remove(mine);
            owned.Save(out problem);
            UsbipErrors.Target t = new UsbipErrors.Target();
            t.Host = host;
            t.Service = service;
            t.BusId = busId;
            Note("Couldn't attach it: " + UsbipErrors.Explain(error, t));
            return StarwireUsbHelper.ExitAttach;
        }
        mine.Port = port;
        if (!owned.Save(out problem)) {
            Note("Couldn't note the port in the list of attached devices (" + problem + "). A detach still finds it by its serial.");
        }
        Step("Attached on port " + port + ".");
        devices = UsbipCommands.List(driver, out problem);
        ImportedDevice now = null;
        if (devices != null) {
            foreach (ImportedDevice d in devices) {
                if (mine.Matches(d)) {
                    now = d;
                }
            }
        }
        if (now != null) {
            Note("It's " + now.Describe() + ", " + ImportedDevice.SpeedName(now.Speed) + ".");
        }
        Note("Windows sets it up next: it should show up in Device Manager within a few seconds.");
        Note("To give it back: StarwireUsbHelper.exe --debug-detach");
        return StarwireUsbHelper.ExitOk;
    }

    public int DebugDetach()
    {
        say("Starwire USB helper: detach the helper's USB devices (debug)");
        IVhciDriver driver = Open();
        if (driver == null) {
            return StarwireUsbHelper.ExitDriver;
        }
        using (driver) {
            if (CheckDriver(driver) == null) {
                return StarwireUsbHelper.ExitDriver;
            }
            return WithDriverLock(() => DetachLocked(driver));
        }
    }

    int DetachLocked(IVhciDriver driver)
    {
        LoadOwned();
        string problem;
        List<ImportedDevice> devices = UsbipCommands.List(driver, out problem);
        if (devices == null) {
            Step("The driver's list of devices failed: " + problem);
            return StarwireUsbHelper.ExitDriver;
        }
        ShowDevices(devices);
        if (owned.Devices.Count == 0) {
            Step("The helper hasn't attached anything, so there's nothing to detach.");
            return StarwireUsbHelper.ExitOk;
        }
        foreach (OwnedDevice o in owned.Devices) {
            Step("Detaching " + o.Describe() + ".");
            bool found = false;
            foreach (ImportedDevice d in devices) {
                if (!o.Matches(d)) {
                    continue;
                }
                found = true;
                if (UsbipCommands.Detach(driver, d.Port, out problem)) {
                    Note("Detached port " + d.Port + ".");
                }
                else {
                    Note("Couldn't detach port " + d.Port + ": " + problem);
                }
            }
            if (!found) {
                ImportedDevice other = null;
                foreach (ImportedDevice d in devices) {
                    if (o.Port > 0 && d.Port == o.Port) {
                        other = d;
                    }
                }
                Note(other == null
                    ? "It isn't attached any more."
                    : "It isn't attached any more. Port " + o.Port + " now holds another program's device ("
                        + other.Describe() + "), so the helper leaves it alone.");
            }
            // If its connection dropped, the driver keeps trying to reconnect it: stop that, once
            // any detach the driver has queued is done
            if (!UsbipCommands.WaitForQueuedDetaches(driver, out problem)) {
                Note("The driver didn't answer the wait for its queued detaches as expected (" + problem
                    + "). Stopping its attempts to reconnect anyway.");
            }
            int stopped = UsbipCommands.StopAttempts(driver, o.Host, o.Service, o.BusId, out problem);
            if (stopped > 0) {
                Note("Stopped " + stopped + (stopped == 1 ? " attempt" : " attempts") + " by the driver to reconnect it.");
            }
            else if (stopped < 0) {
                Note("Couldn't stop the driver's attempts to reconnect it: " + problem);
            }
        }

        // Only what's really gone leaves the list
        List<ImportedDevice> after = UsbipCommands.List(driver, out problem);
        if (after == null) {
            Step("Couldn't check what's left, so the helper keeps its list: " + problem);
            return StarwireUsbHelper.ExitDetach;
        }
        List<OwnedDevice> left = new List<OwnedDevice>();
        foreach (OwnedDevice o in owned.Devices) {
            foreach (ImportedDevice d in after) {
                if (o.Matches(d) && !left.Contains(o)) {
                    left.Add(o);
                }
            }
        }
        owned.Devices.Clear();
        owned.Devices.AddRange(left);
        if (!owned.Save(out problem)) {
            Note("Couldn't update the list of attached devices: " + problem);
        }
        if (left.Count > 0) {
            Step(left.Count + (left.Count == 1 ? " device is" : " devices are") + " still attached. Run --debug-detach again.");
            return StarwireUsbHelper.ExitDetach;
        }
        Step("Done: the helper has nothing attached now.");
        return StarwireUsbHelper.ExitOk;
    }

    IVhciDriver Open()
    {
        Step("Looking for the USB/IP driver.");
        string problem;
        IVhciDriver driver = open(out problem);
        if (driver == null) {
            Note(problem);
            return null;
        }
        DriverIdentity id = driver.Identity;
        Note("Found " + (id != null && id.Description != null ? id.Description : "a USB/IP controller")
            + (id != null && id.InstanceId != null ? " (" + id.InstanceId + ")" : "") + ".");
        return driver;
    }

    // The version, then the list command as the layout check. The list, or null if the helper
    // won't use this driver.
    List<ImportedDevice> CheckDriver(IVhciDriver driver)
    {
        DriverIdentity id = driver.Identity;
        string problem;
        Step("Driver version " + (id != null && id.Version != null ? id.Version : "(not given)")
            + (id != null && id.Date.HasValue ? ", dated " + id.Date.Value.ToString("d MMM yyyy", CultureInfo.InvariantCulture) : "")
            + (id != null && id.Provider != null ? ", from " + id.Provider : "")
            + (id != null && id.Service != null ? ", service " + id.Service : "") + ".");
        KnownDriver known = KnownDriver.Recognize(id, out problem);
        if (known == null) {
            Note(problem);
            return null;
        }
        Note("That's " + known.Name + ", which the helper knows.");
        List<ImportedDevice> devices = UsbipCommands.List(driver, out problem);
        if (devices == null) {
            Note("But " + problem + ". That isn't how " + known.Name + " answers, so the helper won't use it.");
            return null;
        }
        Driver = known;
        return devices;
    }

    void LoadOwned()
    {
        owned.Load();
        foreach (string p in owned.Problems) {
            Note("The list of attached devices (" + owned.FilePath + "): " + p + ".");
        }
    }

    void ShowDevices(List<ImportedDevice> devices)
    {
        if (devices.Count == 0) {
            Step("Attached to the driver now: nothing.");
            return;
        }
        Step("Attached to the driver now: " + devices.Count + (devices.Count == 1 ? " device." : " devices."));
        foreach (ImportedDevice d in devices) {
            Note("Port " + d.Port + ": " + d.Describe() + ". "
                + (owned.Find(d) != null ? "The helper's." : "Another program's, left alone."));
        }
    }

    int WithDriverLock(Func<int> work)
    {
        Mutex m;
        try {
            m = new Mutex(false, mutexName);
        }
        catch (UnauthorizedAccessException) {
            Step("Another user's copy of the helper is using the driver, so this one won't.");
            return StarwireUsbHelper.ExitDriver;
        }
        using (m) {
            bool held = false;
            try {
                try {
                    held = m.WaitOne(LockWaitMs);
                }
                catch (AbandonedMutexException) {
                    held = true;
                }
                if (!held) {
                    Step("Another copy of the helper is busy with the driver. Try again in a moment.");
                    return StarwireUsbHelper.ExitDriver;
                }
                return work();
            }
            finally {
                if (held) {
                    m.ReleaseMutex();
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the icon
// For now: the driver's state, a way to detach the helper's devices, and Quit. Sharing a device
// from the phone comes with the tunnel.
sealed class UsbTray
{
    readonly NotifyIcon icon = new NotifyIcon();
    readonly ToolStripMenuItem status = new ToolStripMenuItem();
    readonly OwnedDevices owned;
    readonly Action<byte[]> random;

    public UsbTray(OwnedDevices owned, Action<byte[]> random)
    {
        this.owned = owned;
        this.random = random;
        status.Enabled = false;
        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add(status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Detach the devices the helper attached", null, delegate { Detach(); });
        menu.Items.Add("Check the driver again", null, delegate { Refresh(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, delegate { icon.Visible = false; Environment.Exit(0); });
        icon.ContextMenuStrip = menu;
        icon.Icon = LoadIcon();
        Refresh();
        icon.Visible = true;
    }

    void Refresh()
    {
        List<string> lines = new List<string>();
        UsbHelper helper = new UsbHelper(Win32Vhci.Open, owned, random, StarwireUsbHelper.DriverMutex, lines.Add);
        int code = helper.Probe();
        string text = code == StarwireUsbHelper.ExitOk
            ? "USB/IP driver ready (" + helper.Driver.Name + ")"
            : "USB/IP driver: " + Last(lines);
        status.Text = text;
        icon.Text = text.Length > 63 ? text.Substring(0, 60) + "..." : text;
    }

    void Detach()
    {
        List<string> lines = new List<string>();
        int code = new UsbHelper(Win32Vhci.Open, owned, random, StarwireUsbHelper.DriverMutex, lines.Add).DebugDetach();
        icon.ShowBalloonTip(6000, "Starwire USB", Last(lines),
            code == StarwireUsbHelper.ExitOk ? ToolTipIcon.Info : ToolTipIcon.Warning);
        Refresh();
    }

    static string Last(List<string> lines)
    {
        if (lines.Count == 0) {
            return "no answer";
        }
        string s = lines[lines.Count - 1].Trim();
        int dot = s.IndexOf(". ");
        // Drop the step number
        return dot > 0 && dot < 4 ? s.Substring(dot + 2) : s;
    }

    static Icon LoadIcon()
    {
        try {
            string ico = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "starwire-usb.ico");
            if (File.Exists(ico)) {
                return new Icon(ico, SystemInformation.SmallIconSize);
            }
            return Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch (Exception) {
            return SystemIcons.Application;
        }
    }
}
