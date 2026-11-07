// Starwire microphone receiver for Linux: the jitter buffer (GPL-3.0, part of Starwire).
//
// The same rules as the Windows receiver's WasapiPlayer, with the clock passed in so the tests can
// run it without waiting. The receive thread writes packets as they come; PipeWire's data thread
// takes a quantum at a time. It builds up StartMs before playing (again after running dry) and
// never holds more than MaxMs.
//
// The phone's audio clock never quite matches the PC's (60 ppm apart in testing on Windows, about
// 3.6 ms a minute), so a phone running fast slowly fills the buffer. The lowest it gets in the
// first TrimMs of playing is where it settled for this network; when it hasn't dipped within
// SkipMs of that for TrimMs, 10 ms is skipped, so the delay stays near where it started.
//
// PipeWire takes a whole quantum at once (1024 samples, 21 ms, is common; some setups use 2048),
// where WASAPI takes 2.7 ms at a time. Starting on StartMs, as the Windows receiver does, rides
// out about as long a wait for packets as it does (about 40 ms with a 1024-sample quantum). On the
// first test with a real phone (a Galaxy Z Fold7 on home Wi-Fi) that left 4 gaps in 96 seconds. So
// here the levels are StartMs and MaxMs on top of a quantum: a quantum more in hand (about 64 ms
// of waiting ridden out), for a quantum more delay.
//
// A gap is counted when the buffer runs dry and audio comes again: running dry because the phone
// stopped sending isn't one. For each, it notes how long no packet came, for the log. It also
// counts every wait for packets of 20, 40, 60 and 100 ms or more, so a stream shows how much
// buffer that network needs.
//
// PipeWire only takes audio while an app records from the microphone (VB-Cable on Windows always
// does). While none does, only the newest StartAt is kept, so an app that starts recording mid-stream
// gets the audio as it is now. Before, the buffer filled to MaxMs with old audio, the packets after
// that were dropped, and once an app recorded it was trimmed back 10 ms at a time, each one a skip
// (the second test with a real phone: 6578 packets dropped and 5 trims in a row).

using System;

sealed class JitterRing
{
    public const int StartMs = 40;       // audio to build up before playback (re)starts
    public const int MaxMs = 120;        // never more than this behind (unless a quantum needs more)
    public const int TrimMs = 2000;
    public const int SkipMs = 10;
    public const int TrimFloorMs = 20;   // never skip while less than this stays buffered
    public const int IdleMs = 3000;      // nothing written for this long: forget what's left
    public const int AbsentMs = 250;     // nothing taken for this long: no app is recording

    public readonly int Rate;
    readonly object gate = new object();
    readonly short[] ring;
    int readAt, count;
    bool starting = true;
    int lowWater = int.MaxValue;   // the fewest samples left in this TrimMs window
    int settled = -1;              // the fewest in the first window after playback (re)started
    long windowStart;
    long lastData = long.MinValue / 2;
    int lastWanted;
    int ranDry, trims, dropped;
    long lastTake = long.MinValue / 2;
    int unheard;                   // packets that came while no app was recording
    // Ran dry and waiting to see whether audio comes again (a gap) or not (the stream ended)
    bool dryWaiting;
    int gaps, longestStall;
    long previousData;
    // Waits between packets of a stream of at least WaitMs[i]
    public static readonly int[] WaitMs = { 20, 40, 60, 100 };
    readonly int[] waits = new int[4];
    int longestWait;

    public JitterRing(int rate)
    {
        Rate = rate;
        // A second: room for the largest quantum PipeWire uses (8192 samples) twice over
        ring = new short[Math.Max(rate, 16384)];
    }

    int Samples(int ms) { return (int) ((long) Rate * ms / 1000); }

    // What has to be there before playing: StartMs on top of a quantum
    int StartAt() { return Samples(StartMs) + lastWanted; }

    int Most() { return Math.Min(ring.Length, Samples(MaxMs) + lastWanted); }

    // Adds a packet of 16-bit PCM (one or two channels, two made one). False if it was dropped
    // because the buffer is full.
    public bool Write(byte[] packet, int offset, int samples, int channels, long now)
    {
        lock (gate) {
            if (now - lastData > IdleMs) {
                // A new stream: whatever was left (too little to start playing) goes
                count = 0;
                starting = true;
                dryWaiting = false;
            }
            else {
                int wait = (int) (now - previousData);
                for (int i = 0; i < WaitMs.Length; i++) {
                    if (wait >= WaitMs[i]) {
                        waits[i]++;
                    }
                }
                longestWait = Math.Max(longestWait, wait);
            }
            if (dryWaiting) {
                // Audio again after running dry: a gap, as long as no packet came
                dryWaiting = false;
                gaps++;
                longestStall = Math.Max(longestStall, (int) (now - previousData));
            }
            previousData = now;
            lastData = now;
            bool absent = now - lastTake > AbsentMs;
            if (absent) {
                // Nobody is listening: keep only the newest StartAt (below), older audio goes
                unheard++;
                if (samples > ring.Length / 2) {
                    return false;
                }
            }
            else if (count + samples > Most()) {
                dropped++;
                return false;
            }
            int at = (readAt + count) % ring.Length;
            for (int i = 0; i < samples; i++) {
                int p = offset + i * channels * 2;
                int value = (short) (packet[p] | packet[p + 1] << 8);
                if (channels == 2) {
                    value = (value + (short) (packet[p + 2] | packet[p + 3] << 8)) / 2;
                }
                ring[at] = (short) value;
                at = at + 1 == ring.Length ? 0 : at + 1;
            }
            count += samples;
            if (absent && count > StartAt()) {
                int old = count - StartAt();
                readAt = (readAt + old) % ring.Length;
                count -= old;
            }
            return true;
        }
    }

    // Fills `into` from the front with up to its length of samples and returns how many; fewer (or
    // none) while building up. The caller fills the rest with silence.
    public int Take(Span<short> into, long now)
    {
        int wanted = into.Length;
        lock (gate) {
            lastWanted = wanted;
            if (now - lastTake > AbsentMs) {
                // An app has just started recording: start from what's there now, as a new stream
                starting = true;
            }
            lastTake = now;
            if (now - lastData > IdleMs) {
                // The phone stopped sending
                count = 0;
                starting = true;
                dryWaiting = false;
                return 0;
            }
            if (starting) {
                if (count < StartAt()) {
                    return 0;
                }
                starting = false;
                lowWater = int.MaxValue;
                settled = -1;
                windowStart = now;
            }
            int got = Math.Min(count, wanted);
            for (int i = 0; i < got; i++) {
                into[i] = ring[readAt];
                readAt = readAt + 1 == ring.Length ? 0 : readAt + 1;
            }
            count -= got;
            if (got < wanted) {
                // Ran dry (the phone paused, or Wi-Fi hiccuped): build up a little audio again first
                starting = true;
                ranDry++;
                dryWaiting = true;
                return got;
            }
            lowWater = Math.Min(lowWater, count);
            if (now - windowStart >= TrimMs) {
                int skip = Samples(SkipMs);
                if (settled < 0) {
                    // No higher than the start level, so a backlog that came in one go at the
                    // start is trimmed away rather than kept
                    settled = Math.Min(lowWater, Samples(StartMs));
                }
                else if (lowWater >= Math.Max(settled + skip, Samples(TrimFloorMs))) {
                    // Clock drift has built the buffer up: skip 10 ms
                    readAt = (readAt + skip) % ring.Length;
                    count -= skip;
                    trims++;
                }
                lowWater = int.MaxValue;
                windowStart = now;
            }
            return got;
        }
    }

    public int Buffered { get { lock (gate) { return count; } } }
    public int RanDry { get { lock (gate) { return ranDry; } } }
    public int Trims { get { lock (gate) { return trims; } } }
    public int Dropped { get { lock (gate) { return dropped; } } }
    public int Gaps { get { lock (gate) { return gaps; } } }
    public int Unheard { get { lock (gate) { return unheard; } } }

    // How many waits for packets were at least WaitMs[i] long
    public int Waits(int i) { lock (gate) { return waits[i]; } }

    // For the log, from the watch thread: the gaps and trims since it last asked, and the longest
    // time without a packet among those gaps
    public void TakeNews(ref int gapsSeen, ref int trimsSeen, out int newGaps, out int newTrims, out int stallMs)
    {
        lock (gate) {
            newGaps = gaps - gapsSeen;
            newTrims = trims - trimsSeen;
            gapsSeen = gaps;
            trimsSeen = trims;
            stallMs = longestStall;
            longestStall = 0;
        }
    }

    public int Quantum { get { lock (gate) { return lastWanted; } } }

    public string Stats()
    {
        lock (gate) {
            return count * 1000 / Rate + " ms buffered (settled at " + Math.Max(settled, 0) * 1000 / Rate + " ms), quantum "
                + lastWanted + " samples, gaps " + gaps + ", ran dry " + ranDry + ", trims " + trims + ", dropped " + dropped + " packets"
                + ", " + unheard + " packets with no app recording"
                + ", waits for packets of 20+ ms " + waits[0] + ", 40+ " + waits[1] + ", 60+ " + waits[2] + ", 100+ " + waits[3]
                + " (longest " + longestWait + " ms)";
        }
    }
}
