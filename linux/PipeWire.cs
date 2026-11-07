// Starwire microphone receiver for Linux: the virtual microphone, on PipeWire (GPL-3.0, part of
// Starwire).
//
// On Windows the receiver plays into VB-Cable, a driver whose other end is a microphone. PipeWire
// needs no driver for that: a stream whose media.class is Audio/Source is itself a microphone that
// any app can choose (Discord, games, the browser, KDE's sound settings). So the receiver opens one
// such stream for as long as it runs, called "Starwire microphone", and PipeWire's data thread takes
// a quantum of audio from the jitter buffer each time an app records from it. With nothing playing
// it gives silence, like a microphone nobody is talking into.
//
// This calls libpipewire-0.3 directly, with no library in between. The few structs it reads
// (pw_buffer, spa_buffer, spa_data, spa_chunk) and the events table it fills are PipeWire's stable
// ABI; the offsets below were checked against PipeWire 1.6's headers, and the format it asks for is
// checked byte for byte against what spa_format_audio_raw_build makes (tests/LinuxTests.cs).

using System;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;

static unsafe class Pw
{
    const string Lib = "libpipewire-0.3.so.0";

    [DllImport(Lib)] public static extern void pw_init(IntPtr argc, IntPtr argv);
    [DllImport(Lib)] public static extern IntPtr pw_thread_loop_new(string name, IntPtr props);
    [DllImport(Lib)] public static extern int pw_thread_loop_start(IntPtr loop);
    [DllImport(Lib)] public static extern void pw_thread_loop_stop(IntPtr loop);
    [DllImport(Lib)] public static extern void pw_thread_loop_destroy(IntPtr loop);
    [DllImport(Lib)] public static extern void pw_thread_loop_lock(IntPtr loop);
    [DllImport(Lib)] public static extern void pw_thread_loop_unlock(IntPtr loop);
    [DllImport(Lib)] public static extern IntPtr pw_thread_loop_get_loop(IntPtr loop);
    [DllImport(Lib)] public static extern IntPtr pw_properties_new_string(string args);
    [DllImport(Lib)] public static extern int pw_properties_set(IntPtr props, string key, string value);
    [DllImport(Lib)] public static extern IntPtr pw_stream_new_simple(IntPtr loop, string name, IntPtr props, IntPtr events, IntPtr data);
    [DllImport(Lib)] public static extern int pw_stream_connect(IntPtr stream, int direction, uint targetId, int flags, IntPtr* parameters, uint count);
    [DllImport(Lib)] public static extern IntPtr pw_stream_dequeue_buffer(IntPtr stream);
    [DllImport(Lib)] public static extern int pw_stream_queue_buffer(IntPtr stream, IntPtr buffer);
    [DllImport(Lib)] public static extern void pw_stream_destroy(IntPtr stream);
    [DllImport(Lib)] public static extern uint pw_stream_get_node_id(IntPtr stream);

    // enum pw_direction, pw_stream_flags and pw_stream_state
    public const int DirectionOutput = 1;
    public const uint IdAny = 0xffffffff;
    public const int FlagAutoconnect = 0x1, FlagMapBuffers = 0x4, FlagRtProcess = 0x10;
    public const int StateError = -1, StateUnconnected = 0, StateConnecting = 1, StatePaused = 2, StateStreaming = 3;

    // struct pw_stream_events (version 2): the version, then 11 function pointers
    public const int EventsVersion = 2, EventsSize = 96, EventStateChanged = 16, EventProcess = 64;
    // struct pw_buffer: buffer at 0, requested at 24. struct spa_buffer: datas at 16. struct
    // spa_data: maxsize at 20, data at 24, chunk at 32. struct spa_chunk: offset, size, stride, flags.
    public const int BufferRequested = 24, SpaBufferDatas = 16, DataMaxSize = 20, DataData = 24, DataChunk = 32;

    static int initialised;

    public static void Init()
    {
        if (Interlocked.Exchange(ref initialised, 1) == 0) {
            pw_init(IntPtr.Zero, IntPtr.Zero);
        }
    }

    public static string StateName(int state)
    {
        switch (state) {
            case StateError: return "error";
            case StateUnconnected: return "unconnected";
            case StateConnecting: return "connecting";
            case StatePaused: return "paused";
            case StateStreaming: return "streaming";
            default: return "state " + state;
        }
    }
}

// The EnumFormat parameter the stream offers: 16-bit little-endian PCM, mono, at one rate. This is
// the SPA "pod" that spa_format_audio_raw_build makes in C (a C macro, so it can't be called).
static class FormatPod
{
    const uint TypeId = 3, TypeInt = 4, TypeArray = 13, TypeObject = 15, ObjectFormat = 0x40003, ParamEnumFormat = 3;
    const uint MediaType = 1, MediaSubtype = 2, AudioFormat = 0x10001, AudioRate = 0x10003, AudioChannels = 0x10004, AudioPosition = 0x10005;
    const uint MediaAudio = 1, SubtypeRaw = 1, FormatS16Le = 0x103, ChannelMono = 2;

    public static byte[] MonoS16(int rate)
    {
        byte[] p = new byte[168];
        int at = 0;
        Action<uint> u32 = v => { BitConverter.TryWriteBytes(new Span<byte>(p, at, 4), v); at += 4; };
        u32(160); u32(TypeObject);                 // the object: its body's size and type
        u32(ObjectFormat); u32(ParamEnumFormat);   // a Format, for EnumFormat
        // Each property: key, flags, then a pod (size, type, value) padded to 8 bytes
        u32(MediaType); u32(0); u32(4); u32(TypeId); u32(MediaAudio); u32(0);
        u32(MediaSubtype); u32(0); u32(4); u32(TypeId); u32(SubtypeRaw); u32(0);
        u32(AudioFormat); u32(0); u32(4); u32(TypeId); u32(FormatS16Le); u32(0);
        u32(AudioRate); u32(0); u32(4); u32(TypeInt); u32((uint) rate); u32(0);
        u32(AudioChannels); u32(0); u32(4); u32(TypeInt); u32(1); u32(0);
        // An array of one Id: the array's size, its type, then its child's size and type, the value
        u32(AudioPosition); u32(0); u32(12); u32(TypeArray); u32(4); u32(TypeId); u32(ChannelMono); u32(0);
        Debug.Assert(at == p.Length);
        return p;
    }
}

// The "Starwire microphone" source. One per rate; Open makes it, Dispose takes it away. Write is
// called from the receive thread, the jitter buffer is taken from on PipeWire's data thread.
sealed unsafe class VirtualMic : IDisposable
{
    const int OpenWaitMs = 3000;

    public readonly int Rate;
    public readonly string NodeName;
    public readonly string Description;
    readonly JitterRing ring;
    readonly Stopwatch clock = Stopwatch.StartNew();
    IntPtr loop, stream, events;
    GCHandle self;
    volatile int state = Pw.StateUnconnected;
    volatile string error;
    readonly ManualResetEventSlim connected = new ManualResetEventSlim(false);
    long quanta;

    VirtualMic(string nodeName, string description, int rate)
    {
        NodeName = nodeName;
        Description = description;
        Rate = rate;
        ring = new JitterRing(rate);
    }

    // Makes the source, or returns null and why (PipeWire not running, say)
    public static VirtualMic Open(string nodeName, string description, int rate, out string problem)
    {
        VirtualMic mic = new VirtualMic(nodeName, description, rate);
        try {
            problem = mic.Start();
        }
        catch (DllNotFoundException) {
            problem = "PipeWire's library (libpipewire-0.3.so.0) isn't installed";
        }
        catch (EntryPointNotFoundException e) {
            problem = "this PipeWire is too old: " + e.Message;
        }
        if (problem != null) {
            mic.Dispose();
            return null;
        }
        return mic;
    }

    string Start()
    {
        Pw.Init();
        loop = Pw.pw_thread_loop_new("starwire-mic", IntPtr.Zero);
        if (loop == IntPtr.Zero) {
            return "couldn't make PipeWire's thread loop";
        }
        IntPtr props = Pw.pw_properties_new_string("");
        Pw.pw_properties_set(props, "media.type", "Audio");
        Pw.pw_properties_set(props, "media.class", "Audio/Source");
        Pw.pw_properties_set(props, "media.name", Description);
        Pw.pw_properties_set(props, "node.name", NodeName);
        Pw.pw_properties_set(props, "node.description", Description);
        Pw.pw_properties_set(props, "node.nick", Description);
        Pw.pw_properties_set(props, "device.icon-name", "audio-input-microphone");
        Pw.pw_properties_set(props, "application.name", "Starwire microphone receiver");
        Pw.pw_properties_set(props, "application.icon-name", "audio-input-microphone");

        events = (IntPtr) NativeMemory.AllocZeroed(Pw.EventsSize);
        *(uint*) events = Pw.EventsVersion;
        *(IntPtr*) ((byte*) events + Pw.EventStateChanged) = (IntPtr) (delegate* unmanaged[Cdecl]<IntPtr, int, int, IntPtr, void>) &OnStateChanged;
        *(IntPtr*) ((byte*) events + Pw.EventProcess) = (IntPtr) (delegate* unmanaged[Cdecl]<IntPtr, void>) &OnProcess;
        self = GCHandle.Alloc(this);

        Pw.pw_thread_loop_lock(loop);
        try {
            if (Pw.pw_thread_loop_start(loop) < 0) {
                return "couldn't start PipeWire's thread loop";
            }
            // The stream takes the properties over, even when it fails
            stream = Pw.pw_stream_new_simple(Pw.pw_thread_loop_get_loop(loop), Description, props, events, GCHandle.ToIntPtr(self));
            if (stream == IntPtr.Zero) {
                return "couldn't make the PipeWire stream";
            }
            byte[] pod = FormatPod.MonoS16(Rate);
            int result;
            fixed (byte* p = pod) {
                IntPtr param = (IntPtr) p;
                result = Pw.pw_stream_connect(stream, Pw.DirectionOutput, Pw.IdAny,
                    Pw.FlagAutoconnect | Pw.FlagMapBuffers | Pw.FlagRtProcess, &param, 1);
            }
            if (result < 0) {
                return "couldn't connect to PipeWire (error " + result + "). Is PipeWire running?";
            }
        }
        finally {
            Pw.pw_thread_loop_unlock(loop);
        }
        if (!connected.Wait(OpenWaitMs)) {
            return error != null ? error : "no answer from PipeWire. Is it running?";
        }
        if (state == Pw.StateError) {
            return "PipeWire refused the microphone: " + error;
        }
        return null;
    }

    // Unplugged from PipeWire: it restarted, or refused the stream
    public bool Failed { get { int s = state; return s == Pw.StateError || s == Pw.StateUnconnected && connected.IsSet; } }

    public string Failure { get { return error != null ? error : "PipeWire " + Pw.StateName(state); } }

    public uint NodeId
    {
        get {
            if (stream == IntPtr.Zero) {
                return Pw.IdAny;
            }
            Pw.pw_thread_loop_lock(loop);
            try {
                return Pw.pw_stream_get_node_id(stream);
            }
            finally {
                Pw.pw_thread_loop_unlock(loop);
            }
        }
    }

    public JitterRing Ring { get { return ring; } }

    public bool Write(byte[] packet, int offset, int samples, int channels)
    {
        return ring.Write(packet, offset, samples, channels, clock.ElapsedMilliseconds);
    }

    public string Stats()
    {
        return "PipeWire " + Pw.StateName(state) + ", " + ring.Stats() + ", quanta " + Interlocked.Read(ref quanta);
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    static void OnStateChanged(IntPtr data, int old, int now, IntPtr message)
    {
        try {
            VirtualMic mic = (VirtualMic) GCHandle.FromIntPtr(data).Target;
            if (now == Pw.StateError) {
                mic.error = message != IntPtr.Zero ? Marshal.PtrToStringUTF8(message) : "PipeWire stream error";
            }
            mic.state = now;
            if (now == Pw.StatePaused || now == Pw.StateStreaming || now == Pw.StateError) {
                mic.connected.Set();
            }
        }
        catch (Exception) {
            // Nothing may be thrown back into PipeWire
        }
    }

    // On PipeWire's data thread, once a quantum while an app records from the microphone. Nothing
    // here allocates.
    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    static void OnProcess(IntPtr data)
    {
        try {
            ((VirtualMic) GCHandle.FromIntPtr(data).Target).Process();
        }
        catch (Exception) {
        }
    }

    void Process()
    {
        IntPtr b = Pw.pw_stream_dequeue_buffer(stream);
        if (b == IntPtr.Zero) {
            return;
        }
        byte* buffer = (byte*) b;
        byte* spa = *(byte**) buffer;
        byte* d = *(byte**) (spa + Pw.SpaBufferDatas);
        short* samples = *(short**) (d + Pw.DataData);
        int* chunk = *(int**) (d + Pw.DataChunk);
        if (samples != null) {
            int frames = (int) (*(uint*) (d + Pw.DataMaxSize) / 2);
            ulong requested = *(ulong*) (buffer + Pw.BufferRequested);
            if (requested != 0 && requested < (ulong) frames) {
                frames = (int) requested;
            }
            Span<short> into = new Span<short>(samples, frames);
            int got = ring.Take(into, clock.ElapsedMilliseconds);
            into.Slice(got).Clear();
            chunk[0] = 0;              // offset
            chunk[1] = frames * 2;     // size
            chunk[2] = 2;              // stride: one 16-bit sample a frame
            chunk[3] = 0;              // flags
            Interlocked.Increment(ref quanta);
        }
        Pw.pw_stream_queue_buffer(stream, b);
    }

    public void Dispose()
    {
        if (loop != IntPtr.Zero) {
            // Stopping the loop first, so no callback is running or comes after
            Pw.pw_thread_loop_stop(loop);
            if (stream != IntPtr.Zero) {
                Pw.pw_stream_destroy(stream);
                stream = IntPtr.Zero;
            }
            Pw.pw_thread_loop_destroy(loop);
            loop = IntPtr.Zero;
        }
        if (self.IsAllocated) {
            self.Free();
        }
        if (events != IntPtr.Zero) {
            NativeMemory.Free((void*) events);
            events = IntPtr.Zero;
        }
    }
}
