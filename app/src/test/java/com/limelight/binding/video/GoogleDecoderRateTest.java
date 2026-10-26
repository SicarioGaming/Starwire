package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;

/**
 * Google's decoders (Pixel's Tensor chips, and AOSP's software ones) clock themselves to just meet
 * the operating rate they're given, so they're asked for the most they can do at the stream's
 * size, not the stream's frame rate (Moonlight X d88c6b3b). The decoders here are built from
 * profile levels, so Android's own rules say what they can do. No Pixel was tried.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class GoogleDecoderRateTest {
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;

    @Before
    public void readTheDevice() {
        // As Game does before a stream; no OpenGL renderer is known here
        MediaCodecHelper.initialize(RuntimeEnvironment.getApplication(), "");
    }

    private static MediaCodecInfo decoder(String name, int level) {
        CodecProfileLevel profileLevel = new CodecProfileLevel();
        profileLevel.profile = CodecProfileLevel.HEVCProfileMain;
        profileLevel.level = level;
        CodecCapabilities caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(HEVC, 3840, 2160))
                .setIsEncoder(false)
                .setProfileLevels(new CodecProfileLevel[] {profileLevel})
                .setColorFormats(new int[] {CodecCapabilities.COLOR_FormatYUV420Flexible})
                // As on a Pixel, so the first try stops after Android's low latency option
                .setRequiredFeatures(new String[] {CodecCapabilities.FEATURE_LowLatency})
                .build();
        return MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsSoftwareOnly(false)
                .setIsHardwareAccelerated(true)
                .setCapabilities(caps)
                .build();
    }

    private static MediaFormat stream(int width, int height, int fps) {
        MediaFormat format = MediaFormat.createVideoFormat(HEVC, width, height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        return format;
    }

    private static double mostAt(MediaCodecInfo decoder, int width, int height) {
        return decoder.getCapabilitiesForType(HEVC).getVideoCapabilities()
                .getSupportedFrameRatesFor(width, height).getUpper();
    }

    @Test
    public void aGoogleDecoderIsAskedForTheMostItCanDoAtTheStreamsSize() {
        // Level 5.2 manages a little over 120 FPS at 4K
        MediaCodecInfo pixel = decoder("c2.google.hevc.decoder", CodecProfileLevel.HEVCMainTierLevel52);
        double most = mostAt(pixel, 3840, 2160);
        assertTrue("this decoder's most at 4K: " + most, most > 121 && most < 960);

        MediaFormat format = stream(3840, 2160, 60);
        MediaCodecHelper.setDecoderLowLatencyOptions(format, pixel, false, 0);
        assertEquals((int) most, format.getInteger(MediaFormat.KEY_OPERATING_RATE));
        // Android 17 and later read Google's own key as well
        assertEquals((int) most, format.getInteger("vendor.google.frame-rate.value"));
    }

    @Test
    public void theRateStopsAt960() {
        // Level 6.2 at 1080p could go past Android's 960 FPS ceiling
        MediaCodecInfo pixel = decoder("c2.google.hevc.decoder", CodecProfileLevel.HEVCMainTierLevel62);
        MediaFormat format = stream(1920, 1080, 120);
        MediaCodecHelper.setDecoderLowLatencyOptions(format, pixel, false, 0);
        assertEquals(960, format.getInteger(MediaFormat.KEY_OPERATING_RATE));
    }

    @Test
    public void aSizeTheDecoderCantDoKeepsTheStreamsRate() {
        // Up to 4K
        MediaCodecInfo pixel = decoder("c2.google.hevc.decoder", CodecProfileLevel.HEVCMainTierLevel51);
        MediaFormat format = stream(7680, 4320, 60);
        MediaCodecHelper.setDecoderLowLatencyOptions(format, pixel, false, 0);
        // As before: the stream's rate, and never under 120
        assertEquals(120, format.getInteger(MediaFormat.KEY_OPERATING_RATE));
    }
}
