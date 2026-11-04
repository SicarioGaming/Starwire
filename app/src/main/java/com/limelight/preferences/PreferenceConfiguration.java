package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.Display;

import com.limelight.binding.audio.VbanSender;
import com.limelight.nvstream.jni.MoonBridge;

public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
        FORCE_PYROWAVE,
    };

    public enum VideoRendererOption {
        AUTO,
        DIRECT,
        VULKAN,
    }

    public enum SpatialDitheringOption {
        OFF,
        LOW,
        HIGH,
    }

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    private static final String LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround";

    static final String RESOLUTION_PREF_STRING = "list_resolution";
    static final String FPS_PREF_STRING = "list_fps";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    private static final String ENABLE_ULTRA_LOW_LATENCY_PREF_STRING = "checkbox_ultra_low_latency";
    private static final String BITRATE_PREF_OLD_STRING = "seekbar_bitrate";
    private static final String STRETCH_PREF_STRING = "checkbox_stretch_video";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    private static final String BACK_AS_GUIDE_PREF_STRING = "checkbox_back_as_guide";
    static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    static final String ENABLE_MICROPHONE_PREF_STRING = "checkbox_enable_microphone";
    static final String MICROPHONE_DEVICE_PREF_STRING = "list_microphone_device";
    static final String MICROPHONE_TARGET_PREF_STRING = "list_microphone_target";
    static final String VBAN_STREAM_NAME_PREF_STRING = "text_vban_stream_name";
    static final String VBAN_PORT_PREF_STRING = "text_vban_port";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    private static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    private static final String ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls";
    private static final String ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3";
    private static final String SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button";
    private static final String LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop";
    private static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_PIP_PREF_STRING = "checkbox_enable_pip";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback";
    private static final String VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    private static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    private static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    private static final String ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx";
    private static final String REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate";
    private static final String ACTUAL_DISPLAY_REFRESH_RATE_PREF_STRING = "text_actual_display_refresh_rate";
    private static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback";
    private static final String OVERLAY_TRIGGER_BUTTON_PREF_STRING = "overlay_trigger_button";
    private static final String OVERLAY_HOLD_DURATION_PREF_STRING = "overlay_hold_duration";
    private static final String ENABLE_FLOATING_BUTTON_PREF_STRING = "checkbox_enable_floating_button";
    // Where the floating button was dragged to, one spot for landscape screens and one for
    // portrait (FloatingButton). Saved by the drag; they aren't on the settings screen.
    private static final String FLOATING_BUTTON_LANDSCAPE_X_PREF_STRING = "floating_button_landscape_x";
    private static final String FLOATING_BUTTON_LANDSCAPE_Y_PREF_STRING = "floating_button_landscape_y";
    private static final String FLOATING_BUTTON_PORTRAIT_X_PREF_STRING = "floating_button_portrait_x";
    private static final String FLOATING_BUTTON_PORTRAIT_Y_PREF_STRING = "floating_button_portrait_y";
    private static final String TOUCH_SENSITIVITY_PREF_STRING = "seekbar_touch_sensitivity";
    private static final String PERF_OVERLAY_LITE_PREF_STRING = "checkbox_enable_perf_overlay_lite";
    private static final String PREVENT_PACKET_LOSS_PREF_STRING = "checkbox_prevent_packet_loss";
    private static final String VIRTUAL_DISPLAY_MODE_PREF_STRING = "virtual_display_mode";
    private static final String VSYNC_MODE_PREF_STRING = "vsync_mode";
    private static final String SYNC_LFR_PREF_STRING = "checkbox_sync_lfr";
    private static final String ASYNC_DECODE_PREF_STRING = "checkbox_async_decode";
    private static final String ASYNC_LFR_PREF_STRING = "checkbox_async_lfr";
    private static final String KEYBOARD_VIBRATE_PREF_STRING = "checkbox_keyboard_vibrate";
    private static final String STICKY_MODIFIER_KEYS_PREF_STRING = "checkbox_sticky_modifier_keys";
    private static final String KEYBOARD_OPACITY_PREF_STRING = "seekbar_keyboard_opacity";
    private static final String SMART_CLIPBOARD_SYNC_PREF_STRING = "checkbox_smart_clipboard_sync";
    private static final String SMART_CLIPBOARD_SYNC_TOAST_PREF_STRING = "checkbox_smart_clipboard_sync_toast";
    private static final String HIDE_CLIPBOARD_CONTENT_PREF_STRING = "checkbox_hide_clipboard_content";

    // Artemide's V-Sync options for the direct renderer (one at a time, as in Artemide)
    public static final int VSYNC_OFF = 0;
    public static final int VSYNC_ON = 1;
    public static final int VSYNC_FAST = 2;
    private static final String AUTO_RESUME_STREAM_PREF_STRING = "checkbox_auto_resume_stream";
    private static final String VIDEO_RENDERER_PREF_STRING = "video_renderer";
    private static final String SPATIAL_DITHERING_PREF_STRING = "spatial_dithering";
    private static final String JITTER_BUFFER_PREF_STRING = "jitter_buffer";
    private static final String PYROWAVE_LATE_FRAMES_PREF_STRING = "pyrowave_late_frames";
    static final String CUSTOM_VULKAN_DRIVER_PREF_STRING = "checkbox_custom_vulkan_driver";
    static final String CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING = "text_custom_vulkan_driver_path";
    // Recorded when the driver is imported, and checked before it's loaded
    static final String CUSTOM_VULKAN_DRIVER_SHA256_PREF_STRING = "custom_vulkan_driver_sha256";
    // Set once the warning shown when the driver is first turned on has been accepted
    static final String CUSTOM_VULKAN_DRIVER_WARNED_PREF_STRING = "custom_vulkan_driver_warning_accepted";
    static final String REMOVE_CUSTOM_VULKAN_DRIVER_PREF_STRING = "remove_custom_vulkan_driver";
    private static final String CUSTOM_VULKAN_DRIVER_TOAST_PREF_STRING = "checkbox_custom_vulkan_driver_toast";

    static final String DEFAULT_RESOLUTION = "1280x720";
    static final String DEFAULT_FPS = "60";
    private static final boolean DEFAULT_ENABLE_ULTRA_LOW_LATENCY = false;
    private static final boolean DEFAULT_STRETCH = false;
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 7;
    private static final int DEFAULT_OPACITY = 90;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;
    private static final String DEFAULT_VIDEO_FORMAT = "auto";
    private static final String DEFAULT_VIDEO_RENDERER = "auto";
    private static final String DEFAULT_SPATIAL_DITHERING = "off";
    private static final String DEFAULT_JITTER_BUFFER = "balanced";
    private static final String DEFAULT_PYROWAVE_LATE_FRAMES = "balanced";
    private static final boolean DEFAULT_CUSTOM_VULKAN_DRIVER = false;
    private static final String DEFAULT_CUSTOM_VULKAN_DRIVER_PATH = "";
    private static final boolean DEFAULT_CUSTOM_VULKAN_DRIVER_TOAST = true;

    private static final boolean ONSCREEN_CONTROLLER_DEFAULT = false;
    private static final boolean ONLY_L3_R3_DEFAULT = false;
    private static final boolean SHOW_GUIDE_BUTTON_DEFAULT = true;
    private static final boolean DEFAULT_ENABLE_HDR = false;
    private static final boolean DEFAULT_ENABLE_PIP = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final boolean DEFAULT_VIBRATE_OSC = true;
    private static final boolean DEFAULT_VIBRATE_FALLBACK = false;
    private static final int DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    private static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    private static final boolean DEFAULT_ENABLE_MICROPHONE = false;
    private static final String DEFAULT_MICROPHONE_DEVICE = "0";
    // Where the microphone goes: Starwire's receiver on the PC, encrypted (works with every host);
    // another VBAN receiver, not encrypted; or over the stream (only logabell's Apollo build)
    public static final String MICROPHONE_TARGET_STARWIRE = "starwire";
    public static final String MICROPHONE_TARGET_VBAN = "vban";
    public static final String MICROPHONE_TARGET_HOST = "host";
    private static final String DEFAULT_MICROPHONE_TARGET = MICROPHONE_TARGET_STARWIRE;
    // Set once 0.2.1 has moved 0.2.0's "vban" (its default, unencrypted) to the encrypted target
    static final String MICROPHONE_TARGET_MIGRATED_PREF_STRING = "mic_target_migrated_021";
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    private static final String DEFAULT_FRAME_PACING = "balanced";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    private static final boolean DEFAULT_ENABLE_AUDIO_FX = false;
    private static final boolean DEFAULT_REDUCE_REFRESH_RATE = false;
    private static final String DEFAULT_ACTUAL_DISPLAY_REFRESH_RATE = "";
    private static final boolean DEFAULT_FULL_RANGE = false;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_GAMEPAD_MOTION_FALLBACK = false;
    private static final String DEFAULT_OVERLAY_TRIGGER_BUTTON = "select";
    private static final String DEFAULT_OVERLAY_HOLD_DURATION = "1500";
    private static final boolean DEFAULT_ENABLE_FLOATING_BUTTON = true;
    private static final int DEFAULT_TOUCH_SENSITIVITY = 100;
    private static final boolean DEFAULT_AUTO_RESUME_STREAM = false;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;
    public static final int FRAME_PACING_HOST_TIMED = 4;

    // Delay added to ride out network jitter in the host frame timing pacing mode
    public static final int JITTER_BUFFER_LOW_LATENCY = 0;
    public static final int JITTER_BUFFER_BALANCED = 1;
    public static final int JITTER_BUFFER_SMOOTH = 2;
    public static final int JITTER_BUFFER_LOWEST_LATENCY = 3;

    // What to do with a PyroWave frame still arriving when it should be ready to show: show it
    // late, or cut it short then and show it with what arrived, missing some fine detail. The
    // presets trade blur for fewer late frames (see VulkanRenderer's constructor).
    public static final int PYROWAVE_LATE_FRAMES_OFF = 0;
    public static final int PYROWAVE_LATE_FRAMES_SHARP = 1;
    public static final int PYROWAVE_LATE_FRAMES_BALANCED = 2;
    public static final int PYROWAVE_LATE_FRAMES_SMOOTH = 3;
    public static final int PYROWAVE_LATE_FRAMES_SMOOTHEST = 4;

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    public static final String RES_NATIVE = "Native";

    public int width, height, fps;
    public int bitrate;
    public boolean enableUltraLowLatency;
    public FormatOption videoFormat;
    public VideoRendererOption videoRenderer;
    public SpatialDitheringOption spatialDithering;
    public int deadzonePercentage;
    public int oscOpacity;
    public boolean stretchVideo, enableSops, playHostAudio, disableWarnings;
    public String language;
    public boolean smallIconMode, multiController, usbDriver, flipFaceButtons;
    public boolean backAsGuide;
    public boolean onscreenController;
    public boolean onlyL3R3;
    public boolean showGuideButton;
    public boolean enableHdr;
    public boolean enablePip;
    public boolean enablePerfOverlay;
    public boolean enableLatencyToast;
    public boolean bindAllUsb;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean vibrateOsc;
    public boolean vibrateFallbackToDevice;
    public int vibrateFallbackToDeviceStrength;
    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public boolean enableMicrophone;
    public int microphoneDeviceId;
    // MICROPHONE_TARGET_STARWIRE, _VBAN or _HOST
    public String microphoneTarget;
    public String vbanStreamName;
    public int vbanPort;
    public int framePacing;
    public int jitterBuffer;
    public int pyrowaveLateFrames;
    public boolean customVulkanDriver;
    public String customVulkanDriverPath;
    public String customVulkanDriverSha256;
    public boolean customVulkanDriverToast;
    public boolean absoluteMouseMode;
    public boolean enableAudioFx;
    public boolean reduceRefreshRate;
    public String actualDisplayRefreshRate;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean gamepadMotionSensorsFallbackToDevice;
    public String overlayTriggerButton;
    public int overlayHoldDurationMs;
    public boolean enableFloatingButton;
    public int touchSensitivity; // percent, applies to the trackpad touch modes
    public boolean enablePerfOverlayLite;
    public boolean preventPacketLoss;
    // "host" (send nothing), "virtual" or "primary": the virtualDisplay launch argument
    public String virtualDisplayMode;
    public int vsyncMode; // VSYNC_*
    public int warpFactor = 1; // Warp Drive 2, Warp 2 4, otherwise 1 (see warpFactorFor())
    public boolean longPressRightClick; // gaming trackpad: long press holds the right button
    public boolean pinchToZoom; // pinch-to-zoom anytime (Moonlit Artemis)
    // PC cursor in the video: HIDE_CURSOR_* (Vibepollo's Ctrl+Alt+Shift+N toggle)
    public int hideCursorMode;
    public static final int HIDE_CURSOR_NEVER = 0;
    public static final int HIDE_CURSOR_WHILE_TOUCHING = 1;
    public static final int HIDE_CURSOR_ALWAYS = 2;
    // Audio haptics: 0 = off, else AudioHaptics.OUTPUT_* bits
    public int audioHapticsOutputs;
    public int audioHapticsIntensity; // percent
    public boolean syncLfr;
    public boolean asyncDecode;
    public boolean asyncLfr; // only with asyncDecode
    // On-screen full keyboard (from Artemide)
    public boolean keyboardVibrate;
    public boolean stickyModifierKeys;
    public int keyboardOpacity; // percent
    // Automatic clipboard sync with Apollo/Vibepollo (from Artemide)
    public boolean smartClipboardSync;
    public boolean smartClipboardSyncToast;
    public boolean hideClipboardContent;
    public boolean autoResumeStream;

    public static boolean isNativeResolution(int width, int height) {
        // It's not a native resolution if it matches an existing resolution option
        if (width == 640 && height == 360) {
            return false;
        }
        else if (width == 854 && height == 480) {
            return false;
        }
        else if (width == 1280 && height == 720) {
            return false;
        }
        else if (width == 1920 && height == 1080) {
            return false;
        }
        else if (width == 2560 && height == 1440) {
            return false;
        }
        else if (width == 3840 && height == 2160) {
            return false;
        }

        return true;
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    public static boolean isSquarishScreen(Display display) {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            width = display.getMode().getPhysicalWidth();
            height = display.getMode().getPhysicalHeight();
        }
        else {
            width = display.getWidth();
            height = display.getHeight();
        }

        return isSquarishScreen(width, height);
    }

    private static String convertFromLegacyResolutionString(String resString) {
        if (resString.equalsIgnoreCase("360p")) {
            return RES_360P;
        }
        else if (resString.equalsIgnoreCase("480p")) {
            return RES_480P;
        }
        else if (resString.equalsIgnoreCase("720p")) {
            return RES_720P;
        }
        else if (resString.equalsIgnoreCase("1080p")) {
            return RES_1080P;
        }
        else if (resString.equalsIgnoreCase("1440p")) {
            return RES_1440P;
        }
        else if (resString.equalsIgnoreCase("4K")) {
            return RES_4K;
        }
        else {
            // Should be unreachable
            return RES_720P;
        }
    }

    private static int getWidthFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[0]);
    }

    private static int getHeightFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[1]);
    }

    private static String getResolutionString(int width, int height) {
        switch (height) {
            case 360:
                return RES_360P;
            case 480:
                return RES_480P;
            default:
            case 720:
                return RES_720P;
            case 1080:
                return RES_1080P;
            case 1440:
                return RES_1440P;
            case 2160:
                return RES_4K;
        }
    }

    public static int getDefaultBitrate(String resString, String fpsString) {
        int width = getWidthFromResolutionString(resString);
        int height = getHeightFromResolutionString(resString);
        int fps = Integer.parseInt(fpsString);

        // This logic is shamelessly stolen from Moonlight Qt:
        // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

        // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
        // bitrate increase for frame rate once we get to values that high.
        double frameRateFactor = (fps <= 60 ? fps : (Math.sqrt(fps / 60.f) * 60.f)) / 30.f;

        // TODO: Collect some empirical data to see if these defaults make sense.
        // We're just using the values that the Shield used, as we have for years.
        int[] pixelVals = {
            640 * 360,
            854 * 480,
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        };
        int[] factorVals = {
            1,
            2,
            5,
            10,
            20,
            40,
            -1
        };

        // Calculate the resolution factor by linear interpolation of the resolution table
        float resolutionFactor;
        int pixels = width * height;
        for (int i = 0; ; i++) {
            if (pixels == pixelVals[i]) {
                // We can bail immediately for exact matches
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixels < pixelVals[i]) {
                if (i == 0) {
                    // Never go below the lowest resolution entry
                    resolutionFactor = factorVals[i];
                }
                else {
                    // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                    resolutionFactor = ((float)(pixels - pixelVals[i-1]) / (pixelVals[i] - pixelVals[i-1])) * (factorVals[i] - factorVals[i-1]) + factorVals[i-1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                // Never go above the highest resolution entry
                resolutionFactor = factorVals[i-1];
                break;
            }
        }

        return (int)Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false;
                }
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 500;
    }

    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION),
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS));
    }

    private static FormatOption getVideoFormatValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        return formatOptionFor(prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT));
    }

    private static FormatOption formatOptionFor(String str) {
        if (str.equals("auto")) {
            return FormatOption.AUTO;
        }
        else if (str.equals("forceav1")) {
            return FormatOption.FORCE_AV1;
        }
        else if (str.equals("forceh265")) {
            return FormatOption.FORCE_HEVC;
        }
        else if (str.equals("neverh265")) {
            return FormatOption.FORCE_H264;
        }
        else if (str.equals("forcepyrowave")) {
            return FormatOption.FORCE_PYROWAVE;
        }
        else {
            // Should never get here
            return FormatOption.AUTO;
        }
    }

    private static VideoRendererOption getVideoRendererValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(VIDEO_RENDERER_PREF_STRING, DEFAULT_VIDEO_RENDERER);
        if (str.equals("direct")) {
            return VideoRendererOption.DIRECT;
        }
        else if (str.equals("vulkan")) {
            return VideoRendererOption.VULKAN;
        }
        else {
            return VideoRendererOption.AUTO;
        }
    }

    private static SpatialDitheringOption getSpatialDitheringValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(SPATIAL_DITHERING_PREF_STRING, DEFAULT_SPATIAL_DITHERING);
        if (str.equals("low")) {
            return SpatialDitheringOption.LOW;
        }
        else if (str.equals("high")) {
            return SpatialDitheringOption.HIGH;
        }
        else {
            return SpatialDitheringOption.OFF;
        }
    }

    private static int getPyrowaveLateFramesValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(PYROWAVE_LATE_FRAMES_PREF_STRING, DEFAULT_PYROWAVE_LATE_FRAMES);
        if (str.equals("off")) {
            return PYROWAVE_LATE_FRAMES_OFF;
        }
        else if (str.equals("sharp")) {
            return PYROWAVE_LATE_FRAMES_SHARP;
        }
        else if (str.equals("smooth")) {
            return PYROWAVE_LATE_FRAMES_SMOOTH;
        }
        else if (str.equals("smoothest")) {
            return PYROWAVE_LATE_FRAMES_SMOOTHEST;
        }
        else {
            return PYROWAVE_LATE_FRAMES_BALANCED;
        }
    }

    private static int getJitterBufferValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return parseJitterBuffer(prefs.getString(JITTER_BUFFER_PREF_STRING, DEFAULT_JITTER_BUFFER));
    }

    static int parseJitterBuffer(String str) {
        if (str.equals("low-latency")) {
            return JITTER_BUFFER_LOW_LATENCY;
        }
        else if (str.equals("lowest-latency")) {
            return JITTER_BUFFER_LOWEST_LATENCY;
        }
        else if (str.equals("smooth")) {
            return JITTER_BUFFER_SMOOTH;
        }
        else {
            return JITTER_BUFFER_BALANCED;
        }
    }

    private static int getFramePacingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Migrate legacy never drop frames option to the new location
        if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
            boolean legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false);
            prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, legacyNeverDropFrames ? "balanced" : "latency")
                    .apply();
        }

        String str = prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (str.equals("latency")) {
            return FRAME_PACING_MIN_LATENCY;
        }
        else if (str.equals("balanced")) {
            return FRAME_PACING_BALANCED;
        }
        else if (str.equals("cap-fps")) {
            return FRAME_PACING_CAP_FPS;
        }
        else if (str.equals("smoothness")) {
            return FRAME_PACING_MAX_SMOOTHNESS;
        }
        else if (str.equals("host-timed")) {
            return FRAME_PACING_HOST_TIMED;
        }
        else if (str.equals("warp") || str.equals("warp2")) {
            // Warp Drive and Warp 2 present like Prefer lowest latency; see warpFactorFor()
            return FRAME_PACING_MIN_LATENCY;
        }
        else {
            // Should never get here
            return FRAME_PACING_MIN_LATENCY;
        }
    }

    /**
     * Artemide's Warp Drive (2x) and Warp 2 (4x): how many times the chosen frame rate to ask the
     * host for. The newest frame is always shown, so frames are fresher and the decoder runs
     * faster. 1 for every other frame pacing setting.
     */
    public static int warpFactorFor(String framePacing) {
        if ("warp".equals(framePacing)) {
            return 2;
        }
        else if ("warp2".equals(framePacing)) {
            return 4;
        }
        return 1;
    }

    // Starwire options that App Settings can also set per game, by their global setting's key
    static final String HIDE_CURSOR_MODE_PREF_STRING = "hide_cursor_mode";
    static final String AUDIO_HAPTICS_PREF_STRING = "list_audio_haptics";
    static final String[] GAME_OPTION_KEYS = {
            VIDEO_FORMAT_PREF_STRING, VSYNC_MODE_PREF_STRING, SYNC_LFR_PREF_STRING,
            ASYNC_DECODE_PREF_STRING, ASYNC_LFR_PREF_STRING, VIRTUAL_DISPLAY_MODE_PREF_STRING,
            TOUCHSCREEN_TRACKPAD_PREF_STRING, HIDE_CURSOR_MODE_PREF_STRING, AUDIO_HAPTICS_PREF_STRING,
            JITTER_BUFFER_PREF_STRING,
    };

    /**
     * Sets one of the GAME_OPTION_KEYS options from its stored value: the global setting's value,
     * or a game's own from App Settings ("true"/"false" for the on/off ones).
     */
    void applyGameOption(String key, String value) {
        switch (key) {
            case VIDEO_FORMAT_PREF_STRING:
                videoFormat = formatOptionFor(value);
                break;
            case VSYNC_MODE_PREF_STRING:
                vsyncMode = "vsync".equals(value) ? VSYNC_ON : "fast".equals(value) ? VSYNC_FAST : VSYNC_OFF;
                break;
            case SYNC_LFR_PREF_STRING:
                syncLfr = Boolean.parseBoolean(value);
                break;
            case ASYNC_DECODE_PREF_STRING:
                asyncDecode = Boolean.parseBoolean(value);
                break;
            case ASYNC_LFR_PREF_STRING:
                // Only takes effect with async decode, which the caller checks after all options
                asyncLfr = Boolean.parseBoolean(value);
                break;
            case VIRTUAL_DISPLAY_MODE_PREF_STRING:
                virtualDisplayMode = value;
                break;
            case TOUCHSCREEN_TRACKPAD_PREF_STRING:
                touchscreenTrackpad = Boolean.parseBoolean(value);
                break;
            case HIDE_CURSOR_MODE_PREF_STRING:
                hideCursorMode = "touching".equals(value) ? HIDE_CURSOR_WHILE_TOUCHING :
                        "always".equals(value) ? HIDE_CURSOR_ALWAYS : HIDE_CURSOR_NEVER;
                break;
            case AUDIO_HAPTICS_PREF_STRING:
                // AudioHaptics.OUTPUT_* bits
                audioHapticsOutputs = "device".equals(value) ? 1 : "controller".equals(value) ? 2 :
                        "both".equals(value) ? 3 : 0;
                break;
            case JITTER_BUFFER_PREF_STRING:
                // Only used when frame pacing follows the host's frame timing
                jitterBuffer = parseJitterBuffer(value);
                break;
        }
    }

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING);
        if (str.equals("right")) {
            return AnalogStickForScrolling.RIGHT;
        }
        else if (str.equals("left")) {
            return AnalogStickForScrolling.LEFT;
        }
        else {
            return AnalogStickForScrolling.NONE;
        }
    }

    public static void resetStreamingSettings(Context context) {
        // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply();
    }

    public static void completeLanguagePreferenceMigration(Context context) {
        // Put our language option back to default which tells us that we've already migrated it
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply();
    }

    public static boolean isShieldAtvFirmwareWithBrokenHdr() {
        // This particular Shield TV firmware crashes when using HDR
        // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
        return Build.MANUFACTURER.equalsIgnoreCase("NVIDIA") &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395");
    }

    /**
     * Once, on the first start of 0.2.1: a saved "vban" (0.2.0's default, which wasn't encrypted)
     * becomes Starwire's encrypted receiver. Someone who really sends to Voicemeeter or another VBAN
     * app picks the "Not encrypted" option again, and that choice then stays.
     */
    static void migrateMicrophoneTarget(SharedPreferences prefs) {
        if (prefs.getBoolean(MICROPHONE_TARGET_MIGRATED_PREF_STRING, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit().putBoolean(MICROPHONE_TARGET_MIGRATED_PREF_STRING, true);
        if (MICROPHONE_TARGET_VBAN.equals(prefs.getString(MICROPHONE_TARGET_PREF_STRING, null))) {
            editor.putString(MICROPHONE_TARGET_PREF_STRING, MICROPHONE_TARGET_STARWIRE);
        }
        editor.apply();
    }

    /**
     * The floating menu button's saved spot for screens of this orientation, as {x, y}: each a
     * share (0 to 1) of the room the button moves in. A value that isn't saved, or was saved as
     * another type, comes back as NaN; FloatingButton checks both before using them.
     */
    public static float[] readFloatingButtonSpot(SharedPreferences prefs, boolean portrait) {
        return new float[] {
                readFloat(prefs, portrait ? FLOATING_BUTTON_PORTRAIT_X_PREF_STRING : FLOATING_BUTTON_LANDSCAPE_X_PREF_STRING),
                readFloat(prefs, portrait ? FLOATING_BUTTON_PORTRAIT_Y_PREF_STRING : FLOATING_BUTTON_LANDSCAPE_Y_PREF_STRING)
        };
    }

    public static void saveFloatingButtonSpot(SharedPreferences prefs, boolean portrait, float x, float y) {
        prefs.edit()
                .putFloat(portrait ? FLOATING_BUTTON_PORTRAIT_X_PREF_STRING : FLOATING_BUTTON_LANDSCAPE_X_PREF_STRING, x)
                .putFloat(portrait ? FLOATING_BUTTON_PORTRAIT_Y_PREF_STRING : FLOATING_BUTTON_LANDSCAPE_Y_PREF_STRING, y)
                .apply();
    }

    private static float readFloat(SharedPreferences prefs, String key) {
        try {
            return prefs.getFloat(key, Float.NaN);
        }
        catch (ClassCastException e) {
            // Saved as something other than a float
            return Float.NaN;
        }
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration config = new PreferenceConfiguration();

        // Migrate legacy preferences to the new locations
        if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
            if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply();
            }
        }

        String str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null);
        if (str != null) {
            if (str.equals("360p30")) {
                config.width = 640;
                config.height = 360;
                config.fps = 30;
            }
            else if (str.equals("360p60")) {
                config.width = 640;
                config.height = 360;
                config.fps = 60;
            }
            else if (str.equals("720p30")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 30;
            }
            else if (str.equals("720p60")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }
            else if (str.equals("1080p30")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 30;
            }
            else if (str.equals("1080p60")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 60;
            }
            else if (str.equals("4K30")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 30;
            }
            else if (str.equals("4K60")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 60;
            }
            else {
                // Should never get here
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }

            prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, ""+config.fps)
                    .apply();
        }
        else {
            // Use the new preference location
            String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

            // Convert legacy resolution strings to the new style
            if (!resStr.contains("x")) {
                resStr = PreferenceConfiguration.convertFromLegacyResolutionString(resStr);
                prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
            }

            config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
            config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
            config.fps = Integer.parseInt(prefs.getString(FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS));
        }

        if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
            // We need to write small icon mode's default to disk for the settings page to display
            // the current state of the option properly
            prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply();
        }

        if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
            // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
            // As a workaround, we will override the default value for the gamepad motion sensor
            // option to disabled on Android 12 to reduce the impact of this bug.
            // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
            prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated
        config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000);
        if (config.bitrate == 0) {
            config.bitrate = getDefaultBitrate(context);
        }

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        config.enableMicrophone = prefs.getBoolean(ENABLE_MICROPHONE_PREF_STRING, DEFAULT_ENABLE_MICROPHONE);
        try {
            config.microphoneDeviceId = Integer.parseInt(prefs.getString(MICROPHONE_DEVICE_PREF_STRING, DEFAULT_MICROPHONE_DEVICE));
        }
        catch (NumberFormatException e) {
            config.microphoneDeviceId = 0;
        }
        migrateMicrophoneTarget(prefs);
        config.microphoneTarget = prefs.getString(MICROPHONE_TARGET_PREF_STRING, DEFAULT_MICROPHONE_TARGET);
        if (!MICROPHONE_TARGET_VBAN.equals(config.microphoneTarget) && !MICROPHONE_TARGET_HOST.equals(config.microphoneTarget)) {
            config.microphoneTarget = MICROPHONE_TARGET_STARWIRE;
        }
        config.vbanStreamName = prefs.getString(VBAN_STREAM_NAME_PREF_STRING, VbanSender.DEFAULT_STREAM_NAME);
        try {
            config.vbanPort = Integer.parseInt(prefs.getString(VBAN_PORT_PREF_STRING,
                    Integer.toString(VbanSender.DEFAULT_PORT)).trim());
            if (config.vbanPort < 1 || config.vbanPort > 65535) {
                config.vbanPort = VbanSender.DEFAULT_PORT;
            }
        }
        catch (NumberFormatException e) {
            config.vbanPort = VbanSender.DEFAULT_PORT;
        }

        config.videoFormat = getVideoFormatValue(context);
        config.videoRenderer = getVideoRendererValue(context);
        config.spatialDithering = getSpatialDitheringValue(context);
        config.framePacing = getFramePacingValue(context);
        config.warpFactor = warpFactorFor(prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING));
        config.jitterBuffer = getJitterBufferValue(context);
        config.pyrowaveLateFrames = getPyrowaveLateFramesValue(context);
        config.customVulkanDriver = prefs.getBoolean(CUSTOM_VULKAN_DRIVER_PREF_STRING, DEFAULT_CUSTOM_VULKAN_DRIVER);
        config.customVulkanDriverPath = prefs.getString(CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING, DEFAULT_CUSTOM_VULKAN_DRIVER_PATH);
        config.customVulkanDriverSha256 = prefs.getString(CUSTOM_VULKAN_DRIVER_SHA256_PREF_STRING, null);
        config.customVulkanDriverToast = prefs.getBoolean(CUSTOM_VULKAN_DRIVER_TOAST_PREF_STRING, DEFAULT_CUSTOM_VULKAN_DRIVER_TOAST);

        config.analogStickForScrolling = getAnalogStickForScrollingValue(context);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.enableUltraLowLatency = prefs.getBoolean(ENABLE_ULTRA_LOW_LATENCY_PREF_STRING, DEFAULT_ENABLE_ULTRA_LOW_LATENCY);
        config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS);
        config.stretchVideo = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH);
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.backAsGuide = prefs.getBoolean(BACK_AS_GUIDE_PREF_STRING, false);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT);
        config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT);
        config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC);
        config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK);
        config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
        config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD);
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX);
        config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE);
        config.actualDisplayRefreshRate = prefs.getString(ACTUAL_DISPLAY_REFRESH_RATE_PREF_STRING, DEFAULT_ACTUAL_DISPLAY_REFRESH_RATE);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK);
        config.overlayTriggerButton = prefs.getString(OVERLAY_TRIGGER_BUTTON_PREF_STRING, DEFAULT_OVERLAY_TRIGGER_BUTTON);
        config.overlayHoldDurationMs = Integer.parseInt(prefs.getString(OVERLAY_HOLD_DURATION_PREF_STRING, DEFAULT_OVERLAY_HOLD_DURATION));
        config.enableFloatingButton = prefs.getBoolean(ENABLE_FLOATING_BUTTON_PREF_STRING, DEFAULT_ENABLE_FLOATING_BUTTON);
        config.touchSensitivity = prefs.getInt(TOUCH_SENSITIVITY_PREF_STRING, DEFAULT_TOUCH_SENSITIVITY);
        config.enablePerfOverlayLite = prefs.getBoolean(PERF_OVERLAY_LITE_PREF_STRING, false);
        config.preventPacketLoss = prefs.getBoolean(PREVENT_PACKET_LOSS_PREF_STRING, false);
        config.virtualDisplayMode = prefs.getString(VIRTUAL_DISPLAY_MODE_PREF_STRING, "host");
        config.applyGameOption(VSYNC_MODE_PREF_STRING, prefs.getString(VSYNC_MODE_PREF_STRING, "off"));
        config.syncLfr = prefs.getBoolean(SYNC_LFR_PREF_STRING, false);
        config.longPressRightClick = prefs.getBoolean("checkbox_long_press_right_click", true);
        config.pinchToZoom = prefs.getBoolean("checkbox_pinch_to_zoom", true);
        config.applyGameOption(HIDE_CURSOR_MODE_PREF_STRING, prefs.getString(HIDE_CURSOR_MODE_PREF_STRING, "never"));
        config.applyGameOption(AUDIO_HAPTICS_PREF_STRING, prefs.getString(AUDIO_HAPTICS_PREF_STRING, "off"));
        config.audioHapticsIntensity = prefs.getInt("seekbar_audio_haptics_intensity", 100);
        config.asyncDecode = prefs.getBoolean(ASYNC_DECODE_PREF_STRING, false);
        config.asyncLfr = config.asyncDecode && prefs.getBoolean(ASYNC_LFR_PREF_STRING, false);
        config.keyboardVibrate = prefs.getBoolean(KEYBOARD_VIBRATE_PREF_STRING, true);
        config.stickyModifierKeys = prefs.getBoolean(STICKY_MODIFIER_KEYS_PREF_STRING, true);
        config.keyboardOpacity = prefs.getInt(KEYBOARD_OPACITY_PREF_STRING, 90);
        config.smartClipboardSync = prefs.getBoolean(SMART_CLIPBOARD_SYNC_PREF_STRING, false);
        config.smartClipboardSyncToast = prefs.getBoolean(SMART_CLIPBOARD_SYNC_TOAST_PREF_STRING, false);
        config.hideClipboardContent = prefs.getBoolean(HIDE_CLIPBOARD_CONTENT_PREF_STRING, false);
        config.autoResumeStream = prefs.getBoolean(AUTO_RESUME_STREAM_PREF_STRING, DEFAULT_AUTO_RESUME_STREAM);

        return config;
    }
}
