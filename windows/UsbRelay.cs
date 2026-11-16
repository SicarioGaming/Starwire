// The USB tunnel's relay, in the PC's USB helper (GPL-3.0, part of Starwire): for each attach it
// listens on a new port on 127.0.0.1 and takes one connection, the usbip-win2 driver's, only while
// its own attach is in progress. The driver and the phone's exporter then talk plain USB/IP through
// it, and the relay checks that stream in both directions as docs/dev/usb-protocol.md ("USB/IP
// inside the tunnel") says: the import, each transfer's length, the descriptors Windows reads, and
// the requests it answers itself so they never reach the device.
//
// USB/IP's messages are as the Linux kernel's USB/IP protocol document describes them: big-endian,
// OP_REQ_IMPORT and OP_REP_IMPORT first, then CMD_SUBMIT, CMD_UNLINK, RET_SUBMIT and RET_UNLINK
// with a 48-byte header.
//
// Built with the C# compiler that comes with Windows, like the rest of the helper, so it's C# 5.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// ---------------------------------------------------------------- bytes in order
// A byte queue for putting a stream back into whole messages, with a hard limit on what it holds
sealed class UsbByteQueue
{
    byte[] buffer = new byte[64 * 1024];
    int start, count;
    readonly int limit;

    public UsbByteQueue(int limit)
    {
        this.limit = limit;
    }

    public int Count { get { return count; } }

    // False, and nothing added, if it would hold more than its limit
    public bool Add(byte[] b, int at, int n)
    {
        if (n > limit - count) {
            return false;
        }
        if (start + count + n > buffer.Length) {
            if (count + n <= buffer.Length) {
                Array.Copy(buffer, start, buffer, 0, count);
            }
            else {
                byte[] bigger = new byte[Math.Min(limit, Math.Max(buffer.Length * 2, count + n))];
                Array.Copy(buffer, start, bigger, 0, count);
                buffer = bigger;
            }
            start = 0;
        }
        Array.Copy(b, at, buffer, start + count, n);
        count += n;
        return true;
    }

    public int Byte(int i)
    {
        return buffer[start + i];
    }

    public uint U32(int i)
    {
        return (uint) (Byte(i) << 24 | Byte(i + 1) << 16 | Byte(i + 2) << 8 | Byte(i + 3));
    }

    public int U16(int i)
    {
        return Byte(i) << 8 | Byte(i + 1);
    }

    public byte[] Take(int n)
    {
        byte[] r = new byte[n];
        Array.Copy(buffer, start, r, 0, n);
        start += n;
        count -= n;
        if (count == 0) {
            start = 0;
        }
        return r;
    }
}

// ---------------------------------------------------------------- the USB/IP checks
// The relay's checks of the USB/IP stream, without sockets, so the tests can feed it bytes. Each
// direction is put back into whole messages; only whole messages go on, so the relay's own answers
// reach the driver between two whole messages from the phone.
sealed class UsbipStreamCheck
{
    public const int Version = 0x0111;
    public const int OpReqImport = 0x8003;
    public const int OpRepImport = 0x0003;
    public const uint CmdSubmit = 1, CmdUnlink = 2, RetSubmit = 3, RetUnlink = 4;
    public const int Header = 48;
    public const int ImportRequestLength = 40;
    public const int ImportReplyLength = 320;
    public const int MaxTransfer = 1024 * 1024;
    public const int MaxIsoPackets = 1024;
    public const int MaxWaiting = 4096;
    public const int Epipe = -32;
    const uint NoIso = 0xFFFFFFFF;
    const int MaxMessage = Header + MaxTransfer + MaxIsoPackets * 16;
    // Enough for one whole message and the start of the next
    const int QueueLimit = MaxMessage + 2 * Swut.MaxPayload + 64 * 1024;

    // A transfer the driver asked for and the phone hasn't answered yet
    sealed class Transfer
    {
        public bool In;
        public uint Length;
        public uint IsoPackets;
        // For descriptor requests: what the reply must start with; Empty means no data at all
        public byte[] Expect;
        public bool Empty;
        public string What;
    }

    readonly SwutShare share;
    readonly object gate = new object();
    readonly UsbByteQueue fromDriver = new UsbByteQueue(QueueLimit);
    readonly UsbByteQueue fromPhone = new UsbByteQueue(QueueLimit);
    readonly Dictionary<uint, Transfer> waiting = new Dictionary<uint, Transfer>();
    readonly Dictionary<uint, uint> unlinks = new Dictionary<uint, uint>();
    // The requests the relay answered itself, for a CMD_UNLINK that names one
    readonly Queue<uint> answeredOrder = new Queue<uint>();
    readonly HashSet<uint> answered = new HashSet<uint>();
    bool requested, imported, stopped;

    public int EndReason, EndRule;
    public string Problem;
    // How many requests the relay answered itself, for the tests
    public int Stalled;

    public UsbipStreamCheck(SwutShare share)
    {
        this.share = share;
    }

    public bool Imported
    {
        get {
            lock (gate) {
                return imported;
            }
        }
    }

    bool Stop(int reason, int rule, string problem)
    {
        if (!stopped) {
            stopped = true;
            EndReason = reason;
            EndRule = rule;
            Problem = problem;
        }
        return false;
    }

    // Bytes from the driver. Whole messages for the phone go in toPhone, the relay's own answers in
    // toDriver. False when the session must end, with EndReason and EndRule.
    public bool FromDriver(byte[] b, int at, int n, List<byte[]> toPhone, List<byte[]> toDriver)
    {
        lock (gate) {
            if (stopped) {
                return false;
            }
            if (!fromDriver.Add(b, at, n)) {
                return Stop(Swut.EndProtocol, 0, "the driver sent more than a message can hold");
            }
            while (true) {
                if (!requested) {
                    if (fromDriver.Count < ImportRequestLength) {
                        return true;
                    }
                    byte[] m = fromDriver.Take(ImportRequestLength);
                    if (Be16(m, 0) != Version || Be16(m, 2) != OpReqImport || Be32(m, 4) != 0 || !SameBusId(m, 8)) {
                        return Stop(Swut.EndProtocol, 0, "the driver's first message isn't OP_REQ_IMPORT for bus ID " + share.BusId);
                    }
                    requested = true;
                    toPhone.Add(m);
                    continue;
                }
                if (!imported) {
                    if (fromDriver.Count > 0) {
                        return Stop(Swut.EndProtocol, 0, "the driver sent more before the device's import reply");
                    }
                    return true;
                }
                if (fromDriver.Count < Header) {
                    return true;
                }
                uint command = fromDriver.U32(0);
                uint seq = fromDriver.U32(4);
                uint direction = fromDriver.U32(12);
                uint ep = fromDriver.U32(16);
                if (command == CmdSubmit) {
                    uint length = fromDriver.U32(24);
                    uint packets = fromDriver.U32(32);
                    if (direction > 1) {
                        return Stop(Swut.EndProtocol, 0, "a CMD_SUBMIT with direction " + direction);
                    }
                    if (length > MaxTransfer) {
                        return Stop(Swut.EndProtocol, 0, "a transfer of " + length + " bytes, over 1 MiB");
                    }
                    uint iso = packets == NoIso ? 0 : packets;
                    if (iso > MaxIsoPackets) {
                        return Stop(Swut.EndProtocol, 0, "a transfer of " + iso + " isochronous packets, over 1024");
                    }
                    int total = Header + (direction == 0 ? (int) length : 0) + (int) iso * 16;
                    if (fromDriver.Count < total) {
                        return true;
                    }
                    byte[] m = fromDriver.Take(total);
                    if (waiting.ContainsKey(seq) || unlinks.ContainsKey(seq)) {
                        return Stop(Swut.EndProtocol, 0, "the driver used sequence number " + seq + " twice");
                    }
                    // A sequence number used again is a new transfer, not the one the relay answered
                    answered.Remove(seq);
                    Transfer t = new Transfer();
                    t.In = direction == 1;
                    t.Length = length;
                    t.IsoPackets = iso;
                    if (ep == 0) {
                        int setup = Header - 8;
                        int requestType = m[setup], request = m[setup + 1];
                        int value = m[setup + 2] | m[setup + 3] << 8;
                        int index = m[setup + 4] | m[setup + 5] << 8;
                        if (AnswersItself(requestType, request, value)) {
                            toDriver.Add(StallReply(seq, packets));
                            Answered(seq);
                            Stalled++;
                            continue;
                        }
                        Descriptor(t, requestType, request, value, index);
                    }
                    if (waiting.Count + unlinks.Count >= MaxWaiting) {
                        return Stop(Swut.EndProtocol, 0, "more than " + MaxWaiting + " transfers waiting");
                    }
                    waiting[seq] = t;
                    toPhone.Add(m);
                }
                else if (command == CmdUnlink) {
                    byte[] m = fromDriver.Take(Header);
                    uint target = Be32u(m, 20);
                    if (answered.Contains(target)) {
                        toDriver.Add(UnlinkReply(seq, 0));
                        continue;
                    }
                    if (waiting.ContainsKey(seq) || unlinks.ContainsKey(seq)) {
                        return Stop(Swut.EndProtocol, 0, "the driver used sequence number " + seq + " twice");
                    }
                    if (waiting.Count + unlinks.Count >= MaxWaiting) {
                        return Stop(Swut.EndProtocol, 0, "more than " + MaxWaiting + " transfers waiting");
                    }
                    unlinks[seq] = target;
                    toPhone.Add(m);
                }
                else {
                    return Stop(Swut.EndProtocol, 0, "the driver sent command " + command + ", which USB/IP doesn't have");
                }
            }
        }
    }

    // Bytes from the phone's exporter. Whole messages for the driver go in toDriver.
    public bool FromPhone(byte[] b, int at, int n, List<byte[]> toDriver)
    {
        lock (gate) {
            if (stopped) {
                return false;
            }
            if (!requested) {
                return Stop(Swut.EndProtocol, 0, "the phone sent USB/IP before the driver asked for the device");
            }
            if (!fromPhone.Add(b, at, n)) {
                return Stop(Swut.EndProtocol, 0, "the phone sent more than a message can hold");
            }
            while (true) {
                if (!imported) {
                    if (fromPhone.Count < 8) {
                        return true;
                    }
                    if (fromPhone.U16(0) != Version || fromPhone.U16(2) != OpRepImport || fromPhone.U32(4) != 0) {
                        return Stop(Swut.EndRules, Swut.RuleMismatch, "the device's first message isn't OP_REP_IMPORT with status 0");
                    }
                    if (fromPhone.Count < ImportReplyLength) {
                        return true;
                    }
                    byte[] m = fromPhone.Take(ImportReplyLength);
                    string problem = ImportMismatch(m);
                    if (problem != null) {
                        return Stop(Swut.EndRules, Swut.RuleMismatch, problem);
                    }
                    uint speed = Be32u(m, 8 + 256 + 32 + 8);
                    if (speed > 3) {
                        return Stop(Swut.EndRules, Swut.RuleTooFast, "the device is faster than high speed (USB/IP speed " + speed + ")");
                    }
                    if (speed < 1) {
                        return Stop(Swut.EndRules, Swut.RuleMismatch, "the device's speed isn't known");
                    }
                    imported = true;
                    toDriver.Add(m);
                    continue;
                }
                if (fromPhone.Count < Header) {
                    return true;
                }
                uint command = fromPhone.U32(0);
                uint seq = fromPhone.U32(4);
                if (command == RetSubmit) {
                    Transfer t;
                    if (!waiting.TryGetValue(seq, out t)) {
                        return Stop(Swut.EndProtocol, 0, "a RET_SUBMIT for transfer " + seq + ", which the driver isn't waiting for");
                    }
                    uint actual = fromPhone.U32(24);
                    uint packets = fromPhone.U32(32);
                    uint iso = packets == NoIso ? 0 : packets;
                    if (actual > t.Length) {
                        return Stop(Swut.EndProtocol, 0, "a reply with " + actual + " bytes for a transfer of " + t.Length);
                    }
                    if (iso != t.IsoPackets) {
                        return Stop(Swut.EndProtocol, 0, "a reply with " + iso + " isochronous packets for a transfer of " + t.IsoPackets);
                    }
                    int total = Header + (t.In ? (int) actual : 0) + (int) iso * 16;
                    if (fromPhone.Count < total) {
                        return true;
                    }
                    byte[] m = fromPhone.Take(total);
                    if (t.What != null && !ReplyFits(t, m, (int) actual)) {
                        return Stop(Swut.EndRules, Swut.RuleMismatch, "the device's " + t.What + " isn't the one SHARE described");
                    }
                    waiting.Remove(seq);
                    toDriver.Add(m);
                }
                else if (command == RetUnlink) {
                    uint target;
                    if (!unlinks.TryGetValue(seq, out target)) {
                        return Stop(Swut.EndProtocol, 0, "a RET_UNLINK for " + seq + ", which the driver didn't ask for");
                    }
                    byte[] m = fromPhone.Take(Header);
                    unlinks.Remove(seq);
                    // A transfer that was unlinked gets no RET_SUBMIT; status 0 means it had finished
                    if (Be32(m, 20) != 0) {
                        waiting.Remove(target);
                    }
                    toDriver.Add(m);
                }
                else {
                    return Stop(Swut.EndProtocol, 0, "the phone sent command " + command + ", which isn't a reply");
                }
            }
        }
    }

    // ---------------------------------------------------------------- the import
    // A 32-byte bus ID field that holds SHARE's bus ID, ended by a 0 byte
    bool SameBusId(byte[] m, int at)
    {
        byte[] want = Encoding.ASCII.GetBytes(share.BusId);
        if (want.Length >= 32) {
            return false;
        }
        for (int i = 0; i < want.Length; i++) {
            if (m[at + i] != want[i]) {
                return false;
            }
        }
        return m[at + want.Length] == 0;
    }

    // OP_REP_IMPORT's usbip_usb_device after its 8-byte header: path 256, busid 32, busnum, devnum,
    // speed, idVendor, idProduct, bcdDevice, class, subclass, protocol, configuration value, number
    // of configurations, number of interfaces. Null if it fits SHARE, else what doesn't.
    string ImportMismatch(byte[] m)
    {
        int at = 8 + 256;
        if (!SameBusId(m, at)) {
            return "the device's import reply is for another bus ID";
        }
        at += 32 + 12;
        byte[] d = share.DeviceDescriptor;
        byte[] c = share.Configuration;
        if (Be16(m, at) != Swut.GetU16(d, 8) || Be16(m, at + 2) != Swut.GetU16(d, 10) || Be16(m, at + 4) != Swut.GetU16(d, 12)) {
            return "the device's import reply has other vendor, product or version numbers than SHARE";
        }
        if (m[at + 6] != d[4] || m[at + 7] != d[5] || m[at + 8] != d[6]) {
            return "the device's import reply has other class codes than SHARE";
        }
        if (m[at + 9] != 0 && m[at + 9] != c[5]) {
            return "the device's import reply has another configuration value than SHARE";
        }
        if (m[at + 10] != d[17] || m[at + 11] != c[4]) {
            return "the device's import reply has another number of configurations or interfaces than SHARE";
        }
        return null;
    }

    // ---------------------------------------------------------------- endpoint 0
    // Requests the device never sees: Microsoft OS descriptors (string 0xEE, BOS), other-speed
    // configurations, every vendor request and the reserved type. They could let a device choose its
    // own Windows driver.
    static bool AnswersItself(int requestType, int request, int value)
    {
        int type = (requestType >> 5) & 3;
        if (type == 2 || type == 3) {
            return true;
        }
        if (type == 0 && request == 6 && (requestType & 0x80) != 0) {
            int descriptor = value >> 8;
            int index = value & 0xFF;
            return (descriptor == 3 && index == 0xEE) || descriptor == 0x0F || descriptor == 0x07;
        }
        return false;
    }

    // GET_DESCRIPTOR for the device, configuration and HID report descriptors: the reply must be
    // the start of what SHARE described
    void Descriptor(Transfer t, int requestType, int request, int value, int index)
    {
        int type = (requestType >> 5) & 3;
        if (type != 0 || request != 6 || (requestType & 0x80) == 0) {
            return;
        }
        int descriptor = value >> 8;
        if (descriptor == 1) {
            t.What = "device descriptor";
            t.Expect = share.DeviceDescriptor;
        }
        else if (descriptor == 2) {
            t.What = "configuration descriptor";
            if ((value & 0xFF) == 0) {
                t.Expect = share.Configuration;
            }
            else {
                t.Empty = true;
            }
        }
        else if (descriptor == 0x22) {
            t.What = "HID report descriptor";
            byte[] report = index <= 255 ? share.ReportFor(index) : null;
            if (report != null) {
                t.Expect = report;
            }
            else {
                t.Empty = true;
            }
        }
    }

    static bool ReplyFits(Transfer t, byte[] m, int actual)
    {
        if (!t.In || actual == 0) {
            return true;
        }
        if (t.Empty || actual > t.Expect.Length) {
            return false;
        }
        for (int i = 0; i < actual; i++) {
            if (m[Header + i] != t.Expect[i]) {
                return false;
            }
        }
        return true;
    }

    void Answered(uint seq)
    {
        if (answered.Add(seq)) {
            answeredOrder.Enqueue(seq);
            if (answeredOrder.Count > 256) {
                answered.Remove(answeredOrder.Dequeue());
            }
        }
    }

    // A stall, as a device without the request would answer: RET_SUBMIT, status -32 (EPIPE), no data
    public static byte[] StallReply(uint seq, uint packets)
    {
        byte[] m = new byte[Header];
        PutBe32(m, 0, RetSubmit);
        PutBe32(m, 4, seq);
        PutBe32(m, 20, unchecked((uint) Epipe));
        PutBe32(m, 32, packets);
        return m;
    }

    public static byte[] UnlinkReply(uint seq, int status)
    {
        byte[] m = new byte[Header];
        PutBe32(m, 0, RetUnlink);
        PutBe32(m, 4, seq);
        PutBe32(m, 20, unchecked((uint) status));
        return m;
    }

    public static int Be16(byte[] b, int at)
    {
        return b[at] << 8 | b[at + 1];
    }

    public static int Be32(byte[] b, int at)
    {
        return b[at] << 24 | b[at + 1] << 16 | b[at + 2] << 8 | b[at + 3];
    }

    public static uint Be32u(byte[] b, int at)
    {
        return unchecked((uint) Be32(b, at));
    }

    public static void PutBe32(byte[] b, int at, uint v)
    {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }

    public static void PutBe16(byte[] b, int at, int v)
    {
        b[at] = (byte) (v >> 8);
        b[at + 1] = (byte) v;
    }
}

// ---------------------------------------------------------------- the relay's socket
sealed class UsbRelay : IDisposable
{
    // Whether the connection from remote (the connecting side) to local (the relay) may be the
    // driver's. False, with the reason, for anyone else.
    public delegate bool PeerCheck(IPEndPoint local, IPEndPoint remote, out string why);

    public const int SystemProcess = 4;

    readonly PeerCheck peerOk;
    readonly Action<string> log;
    readonly TcpListener listener;
    readonly object writing = new object();
    readonly int sendTimeoutMs;
    public readonly int Port;
    volatile bool accepting = true;
    volatile bool closed;
    // Volatile, so CloseDriver can read it without the write lock: a SendToDriver blocked on a
    // driver that stopped reading holds that lock, and closing the socket is what unblocks it
    volatile Socket driver;
    Action<byte[], int> fromDriver;
    Action driverClosed;
    // True while a SendToDriver holds the write lock in Send: the tests watch it to know when the
    // relay is actually blocked writing to a driver that stopped reading
    public volatile bool Sending;

    public UsbRelay(PeerCheck peerOk, Action<string> log) : this(peerOk, log, 0)
    {
    }

    // sendTimeoutMs bounds each write to the driver (0: no bound, for the tests)
    public UsbRelay(PeerCheck peerOk, Action<string> log, int sendTimeoutMs)
    {
        this.peerOk = peerOk;
        this.log = log;
        this.sendTimeoutMs = sendTimeoutMs;
        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.ExclusiveAddressUse = true;
        listener.Start(1);
        Port = ((IPEndPoint) listener.LocalEndpoint).Port;
    }

    // The driver connects from the kernel, so its connection belongs to the System process. That
    // the driver's connection shows up that way is still to be confirmed on a real attach.
    public static bool SystemProcessOnly(IPEndPoint local, IPEndPoint remote, out string why)
    {
        int owner = TcpOwners.OwnerOf(remote, local);
        if (owner == SystemProcess) {
            why = null;
            return true;
        }
        why = owner < 0 ? "Windows doesn't list who owns the connection" : "it belongs to process " + owner + ", not the System process (4)";
        return false;
    }

    // Takes the first connection that passes the check, then reads it on its own thread
    public void Start(Action<byte[], int> fromDriver, Action driverClosed)
    {
        this.fromDriver = fromDriver;
        this.driverClosed = driverClosed;
        Thread t = new Thread(AcceptOne);
        t.IsBackground = true;
        t.Name = "USB relay: accept";
        t.Start();
    }

    // An exception that left the thread would end the whole helper
    void AcceptOne()
    {
        try {
            AcceptLoop();
        }
        catch (Exception e) {
            log("the relay: " + e.GetType().Name + ": " + e.Message);
            if (!closed) {
                driverClosed();
            }
        }
    }

    void AcceptLoop()
    {
        while (accepting && !closed) {
            Socket s;
            try {
                s = listener.AcceptSocket();
            }
            catch (Exception) {
                return;
            }
            string why = null;
            IPEndPoint local = (IPEndPoint) s.LocalEndPoint;
            IPEndPoint remote = (IPEndPoint) s.RemoteEndPoint;
            bool ok = false;
            try {
                ok = accepting && !closed && peerOk(local, remote, out why);
            }
            catch (Exception e) {
                why = e.Message;
            }
            if (!ok) {
                log("the relay closed a connection from " + remote + ": " + (why ?? "the attach is over"));
                Quietly(s.Close);
                continue;
            }
            s.NoDelay = true;
            // So no write blocks forever on a driver that stopped reading
            s.SendTimeout = sendTimeoutMs;
            lock (writing) {
                if (closed) {
                    Quietly(s.Close);
                    return;
                }
                driver = s;
            }
            StopAccepting();
            Read(s);
            return;
        }
    }

    void Read(Socket s)
    {
        byte[] buffer = new byte[64 * 1024];
        try {
            while (true) {
                int n = s.Receive(buffer);
                if (n <= 0) {
                    break;
                }
                fromDriver(buffer, n);
            }
        }
        catch (Exception) {
        }
        if (!closed) {
            driverClosed();
        }
    }

    public void StopAccepting()
    {
        accepting = false;
        Quietly(listener.Stop);
    }

    // Whole messages only, so what the relay answers itself lands between two of the phone's
    public void SendToDriver(byte[] m)
    {
        lock (writing) {
            if (driver == null) {
                throw new SocketException(10057);   // WSAENOTCONN
            }
            Sending = true;
            try {
                int at = 0;
                while (at < m.Length) {
                    at += driver.Send(m, at, m.Length - at, SocketFlags.None);
                }
            }
            finally {
                Sending = false;
            }
        }
    }

    // Drops the driver's connection, if there's one; the driver then gives up on the device. It
    // never takes the write lock, so it can't wait behind a write the driver isn't reading.
    public void CloseDriver()
    {
        Socket s = driver;
        if (s != null) {
            Quietly(delegate { s.Shutdown(SocketShutdown.Both); });
            Quietly(s.Close);
        }
        StopAccepting();
    }

    public void Dispose()
    {
        closed = true;
        StopAccepting();
        CloseDriver();
    }

    static void Quietly(Action a)
    {
        try {
            a();
        }
        catch (Exception) {
        }
    }
}

// Who owns a TCP connection on this PC, from Windows' connection table (GetExtendedTcpTable).
// Only reads.
static class TcpOwners
{
    [DllImport("iphlpapi.dll")]
    static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool sort, int family, int tableClass, int reserved);

    const int AfInet = 2;
    const int TcpTableOwnerPidConnections = 4;
    const uint ErrorInsufficientBuffer = 122;

    // The process that owns the IPv4 connection from local to remote, or -1
    public static int OwnerOf(IPEndPoint local, IPEndPoint remote)
    {
        if (local.AddressFamily != AddressFamily.InterNetwork || remote.AddressFamily != AddressFamily.InterNetwork) {
            return -1;
        }
        int size = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            IntPtr table = size > 0 ? Marshal.AllocHGlobal(size) : IntPtr.Zero;
            try {
                uint error = GetExtendedTcpTable(table, ref size, false, AfInet, TcpTableOwnerPidConnections, 0);
                if (error == ErrorInsufficientBuffer) {
                    continue;
                }
                if (error != 0 || table == IntPtr.Zero) {
                    return -1;
                }
                int rows = Marshal.ReadInt32(table);
                for (int i = 0; i < rows; i++) {
                    int at = 4 + i * 24;
                    uint localAddress = (uint) Marshal.ReadInt32(table, at + 4);
                    int localPort = Port(Marshal.ReadInt32(table, at + 8));
                    uint remoteAddress = (uint) Marshal.ReadInt32(table, at + 12);
                    int remotePort = Port(Marshal.ReadInt32(table, at + 16));
                    if (localPort == local.Port && remotePort == remote.Port
                            && new IPAddress(localAddress).Equals(local.Address) && new IPAddress(remoteAddress).Equals(remote.Address)) {
                        return Marshal.ReadInt32(table, at + 20);
                    }
                }
                return -1;
            }
            finally {
                if (table != IntPtr.Zero) {
                    Marshal.FreeHGlobal(table);
                }
            }
        }
        return -1;
    }

    // The table keeps ports in network order in the low 16 bits
    static int Port(int v)
    {
        return ((v & 0xFF) << 8) | ((v >> 8) & 0xFF);
    }

    public static int CurrentProcess()
    {
        return Process.GetCurrentProcess().Id;
    }
}
