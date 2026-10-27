package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Async decode (from Artemide) against this device's real H.264 decoder: the adapter delivers
 * every frame, Async LFR keeps only the newest, and the codec recovery sequences Starwire uses
 * (flush then start, detach/stop/attach/configure) resume decoding.
 *
 * Some decoders keep a few frames back until more input comes, and these tests stop feeding at
 * the end of a run, so those frames never come out. The plain decoder is measured first, without
 * the adapter (see measureHeldBack). Each run then feeds that many frames more than it checks, to
 * push its own frames out, so every run checks all of its frames on every device.
 */
@RunWith(AndroidJUnit4.class)
public class AsyncDecodeTest {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FRAME_COUNT = 80;
    // The frames each recovery test checks before and after the recovery
    private static final int RUN = 20;
    // Two runs, each with the frames to push it out, have to fit in the stream
    private static final int MAX_HELD_BACK = FRAME_COUNT / 2 - RUN;
    private static final long TIMEOUT_MS = 5000;
    // How long the encoder may take for the whole stream: a limit, not a pause
    private static final long ENCODE_WAIT_MS = 20_000;
    // How long LFR's test waits for the decoder to finish: a limit, not a pause
    private static final long DECODE_WAIT_MS = 30_000;
    // A run of frames is over once nothing has gone in or come out for this long
    private static final long IDLE_MS = 1500;

    private static class EncodedFrame {
        final byte[] data;
        final long ptsUs;
        final int flags;

        EncodedFrame(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    // Codec config (SPS/PPS) first, then FRAME_COUNT key frames
    private static List<EncodedFrame> stream;

    // How many frames this device's decoder keeps back while no more input comes. On 2026-10-23
    // the emulators' software decoder kept 2 back on the Android 9 and 11 images (handheld-28 and
    // tv-30: 58 of 60 frames came out) and 17 on Android 6 (legacy-23: 43 of 60). The other images
    // that ran this test, Android 5 and 10 among them, kept none.
    private static int heldBack;

    // Counts frames the adapter drops itself (its output queue holds at most 8 waiting frames)
    private final java.util.concurrent.atomic.AtomicInteger releasedByAdapter = new java.util.concurrent.atomic.AtomicInteger();
    private final AsyncCodecAdapter.OutputRelease release = (codec, index) -> {
        releasedByAdapter.incrementAndGet();
        codec.releaseOutputBuffer(index, false);
    };

    @BeforeClass
    public static void encodeTestStream() throws Exception {
        stream = new ArrayList<>();

        MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0); // every frame a key frame

        MediaCodec encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int submitted = 0;
        boolean inputDone = false;
        long deadline = SystemClock.uptimeMillis() + ENCODE_WAIT_MS;

        while (SystemClock.uptimeMillis() < deadline) {
            if (!inputDone) {
                int in = encoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    if (submitted == FRAME_COUNT) {
                        encoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        Image image = encoder.getInputImage(in);
                        fillFrame(image, submitted);
                        encoder.queueInputBuffer(in, 0, WIDTH * HEIGHT * 3 / 2, submitted * 33_333L, 0);
                        submitted++;
                    }
                }
            }

            int out = encoder.dequeueOutputBuffer(info, 10_000);
            if (out >= 0) {
                if (info.size > 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(out);
                    byte[] data = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(data);
                    stream.add(new EncodedFrame(data, info.presentationTimeUs, info.flags));
                }
                encoder.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        encoder.stop();
        encoder.release();

        assertTrue("Encoder produced no codec config", (stream.get(0).flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);

        // A pause in the emulator while measuring can only make the count too high (frames still
        // on their way look kept back), so it's measured twice and the lower count is used
        heldBack = Math.min(measureHeldBack(), measureHeldBack());
        assertTrue("the decoder keeps " + heldBack + " frames back, more than these tests have room for ("
                + MAX_HELD_BACK + ")", heldBack <= MAX_HELD_BACK);
    }

    // Feeds the whole stream to the plain decoder (no adapter) and takes every frame it lets out
    // until it lets none out for IDLE_MS, as decode() does. The frames still missing then are the
    // ones it keeps back. Ending the stream lets them out: every frame has to come out in the
    // end, or the decoder itself lost some, and that isn't a hold-back to allow for.
    private static int measureHeldBack() throws Exception {
        MediaCodec decoder = MediaCodec.createDecoderByType(MIME);
        try {
            decoder.configure(MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT), null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int sent = 0;
            int frames = 0;
            int out = 0;
            long lastProgress = SystemClock.uptimeMillis();
            while (SystemClock.uptimeMillis() - lastProgress < IDLE_MS) {
                if (sent < stream.size()) {
                    int in = decoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        EncodedFrame f = stream.get(sent++);
                        ByteBuffer buf = decoder.getInputBuffer(in);
                        buf.clear();
                        buf.put(f.data);
                        decoder.queueInputBuffer(in, 0, f.data.length, f.ptsUs, f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                        if ((f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            frames++;
                        }
                        lastProgress = SystemClock.uptimeMillis();
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, 10_000);
                if (index >= 0) {
                    if (info.size > 0) {
                        out++;
                    }
                    decoder.releaseOutputBuffer(index, false);
                    lastProgress = SystemClock.uptimeMillis();
                }
            }
            assertEquals("frames fed to the plain decoder", FRAME_COUNT, frames);
            int beforeTheEnd = out;

            int in = decoder.dequeueInputBuffer(TIMEOUT_MS * 1000);
            assertTrue("no input buffer to end the stream with", in >= 0);
            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            boolean ended = false;
            long deadline = SystemClock.uptimeMillis() + DECODE_WAIT_MS;
            while (!ended && SystemClock.uptimeMillis() < deadline) {
                int index = decoder.dequeueOutputBuffer(info, 10_000);
                if (index >= 0) {
                    // The last frame can come in the same buffer as the end of the stream
                    if (info.size > 0) {
                        out++;
                    }
                    ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            assertTrue("the plain decoder never ended the stream", ended);
            assertEquals("frames out of the plain decoder once the stream ended", frames, out);
            return frames - beforeTheEnd;
        }
        finally {
            decoder.stop();
            decoder.release();
        }
    }

    // A moving gradient so each frame differs
    private static void fillFrame(Image image, int frame) {
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < planes.length; p++) {
            int w = p == 0 ? WIDTH : WIDTH / 2;
            int h = p == 0 ? HEIGHT : HEIGHT / 2;
            ByteBuffer buf = planes[p].getBuffer();
            int rowStride = planes[p].getRowStride();
            int pixelStride = planes[p].getPixelStride();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buf.put(y * rowStride + x * pixelStride, (byte) (p == 0 ? (x + y + frame * 4) : 128));
                }
            }
        }
    }

    private MediaCodec createDecoder(AsyncCodecAdapter adapter) throws Exception {
        MediaCodec decoder = MediaCodec.createDecoderByType(MIME);
        attachAndConfigure(decoder, adapter);
        return decoder;
    }

    // The order MediaCodecDecoderRenderer.configureAndStartDecoder() uses
    private void attachAndConfigure(MediaCodec decoder, AsyncCodecAdapter adapter) {
        adapter.attachIfNeeded(decoder, Process.THREAD_PRIORITY_DISPLAY, null, release);
        decoder.configure(MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT), null, null, 0);
        decoder.start();
    }

    /**
     * Feeds the codec config, frames [from, to) and then as many frames again as the decoder keeps
     * back (which push those out) through the adapter, releasing outputs as they come, until the
     * to - from frames are out or nothing has happened for IDLE_MS. Returns how many decoded frames
     * the adapter handed out.
     */
    private int decode(MediaCodec decoder, AsyncCodecAdapter adapter, int from, int to) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        List<EncodedFrame> toSend = new ArrayList<>();
        toSend.add(stream.get(0)); // SPS/PPS, needed after each flush or restart
        for (int i = from; i < to + heldBack; i++) {
            toSend.add(stream.get(1 + i));
        }

        int sent = 0;
        int outputs = 0;
        long lastProgress = SystemClock.uptimeMillis();

        while (SystemClock.uptimeMillis() - lastProgress < IDLE_MS) {
            adapter.drainNoRenderReleaseQueue(decoder, release);

            if (sent < toSend.size()) {
                int in = adapter.dequeueInputIndex(2000);
                if (in >= 0) {
                    EncodedFrame f = toSend.get(sent++);
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    buf.clear();
                    buf.put(f.data);
                    decoder.queueInputBuffer(in, 0, f.data.length, f.ptsUs, f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    lastProgress = SystemClock.uptimeMillis();
                }
            }

            int out = adapter.dequeueOutputIndex(decoder, info, 2000, release);
            if (out >= 0) {
                decoder.releaseOutputBuffer(out, false);
                outputs++;
                lastProgress = SystemClock.uptimeMillis();
            }

            if (sent == toSend.size() && outputs + releasedByAdapter.get() >= to - from) {
                break;
            }
        }
        adapter.drainNoRenderReleaseQueue(decoder, release);
        return outputs;
    }

    // Decodes frames [from, to) and checks every one came out of the decoder (handed out or
    // dropped by the adapter's bounded queue), with most handed out
    private void assertDecodes(MediaCodec decoder, AsyncCodecAdapter adapter, int from, int to) {
        releasedByAdapter.set(0);
        int outputs = decode(decoder, adapter, from, to);
        int expected = to - from;
        assertEquals("frames out of the decoder (" + heldBack + " more fed to push them out)",
                expected, outputs + releasedByAdapter.get());
        assertTrue("Only " + outputs + " of " + expected + " handed out", outputs >= expected * 8 / 10);
    }

    // Where the run after a recovery starts: past the frames that pushed the first run out
    private static int secondRun() {
        return RUN + heldBack;
    }

    @Test
    public void deliversEveryFrame() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertTrue(adapter.isActive());
            releasedByAdapter.set(0);
            // The whole stream goes in: the last heldBack frames push the others out
            int outputs = decode(decoder, adapter, 0, FRAME_COUNT - heldBack);

            // Every decoded frame was either handed out or released, none leaked; and when the
            // reader keeps up, nearly all are handed out
            int expected = FRAME_COUNT - heldBack;
            assertEquals("frames out of the decoder (" + heldBack + " kept back by the decoder)",
                    expected, outputs + releasedByAdapter.get());
            assertTrue("Only " + outputs + " of " + expected + " handed out", outputs >= expected * 9 / 10);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
        assertTrue("Callback thread still running after detach", !adapter.isActive());
    }

    @Test
    public void asyncLfrKeepsOnlyTheNewestWaitingFrame() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        adapter.setPreferLowerDelays(true);
        MediaCodec decoder = createDecoder(adapter);
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Feed everything without taking outputs, so decoded frames pile up
            int sent = 0;
            int frames = 0;
            List<EncodedFrame> all = stream;
            long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (sent < all.size() && SystemClock.uptimeMillis() < deadline) {
                adapter.drainNoRenderReleaseQueue(decoder, release);
                int in = adapter.dequeueInputIndex(5000);
                if (in >= 0) {
                    EncodedFrame f = all.get(sent++);
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    buf.clear();
                    buf.put(f.data);
                    decoder.queueInputBuffer(in, 0, f.data.length, f.ptsUs, f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    if ((f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        frames++;
                    }
                }
            }
            assertEquals("frames fed to the decoder", all.size(), sent);
            // Let decoding finish: every frame comes out, and all but the waiting one are dropped
            // by LFR. Release the dropped ones as they come, so the decoder never runs out of
            // output buffers (the renderer thread does this each loop). An emulator's software
            // decoder took longer than half a second for 60 frames, so wait for the frames
            // themselves, as long as it takes up to a generous limit.
            // (Less the frames the decoder keeps back, as no more input comes.)
            long decodeDeadline = SystemClock.uptimeMillis() + DECODE_WAIT_MS;
            while (releasedByAdapter.get() + adapter.getOutputQueueSize() < frames - heldBack
                    && SystemClock.uptimeMillis() < decodeDeadline) {
                adapter.drainNoRenderReleaseQueue(decoder, release);
                Thread.sleep(20);
            }
            adapter.drainNoRenderReleaseQueue(decoder, release);
            assertEquals("frames out of the decoder (dropped plus waiting, " + heldBack + " kept back by the decoder) after "
                    + DECODE_WAIT_MS + " ms", frames - heldBack, releasedByAdapter.get() + adapter.getOutputQueueSize());

            // With LFR, at most one frame is waiting, and it's a late one
            assertTrue("Output queue held " + adapter.getOutputQueueSize(), adapter.getOutputQueueSize() <= 1);
            int out = adapter.dequeueOutputIndex(decoder, info, 100_000, release);
            assertTrue("No frame waiting", out >= 0);
            assertTrue("Waiting frame is not one of the newest: pts " + info.presentationTimeUs,
                    info.presentationTimeUs >= (FRAME_COUNT - heldBack - 10) * 33_333L);
            decoder.releaseOutputBuffer(out, false);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterFlushRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, RUN);

            // MediaCodecDecoderRenderer's flush recovery in async mode. Without resumeAfterFlush(),
            // a buffer offered just before flush() was handed out after it once, and
            // getInputBuffer() threw "index 0 is not owned by client" (AsyncCodecFlushTest
            // plays that order every time).
            adapter.quiesceAndRelease(decoder, release);
            decoder.flush();
            adapter.resumeAfterFlush();
            decoder.start();

            assertDecodes(decoder, adapter, secondRun(), secondRun() + RUN);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterRestartRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, RUN);

            // MediaCodecDecoderRenderer's restart recovery in async mode
            adapter.quiesceAndRelease(decoder, release);
            adapter.detach(decoder, release);
            decoder.stop();
            attachAndConfigure(decoder, adapter);

            assertDecodes(decoder, adapter, secondRun(), secondRun() + RUN);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterResetRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, RUN);

            // MediaCodecDecoderRenderer's reset recovery in async mode
            adapter.quiesceAndRelease(decoder, release);
            adapter.detach(decoder, release);
            decoder.reset();
            attachAndConfigure(decoder, adapter);

            assertDecodes(decoder, adapter, secondRun(), secondRun() + RUN);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }
}
