package com.limelight.preferences;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.audio.MicrophoneCaptureManager;
import com.limelight.binding.audio.VbanSender;
import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.audio.securemic.MicPairingStore;
import com.limelight.ui.CoverGlowSettings;
import com.limelight.ui.overlay.ImportedShortcuts;
import com.limelight.binding.video.CustomVulkanDriver;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StreamSettings extends Activity {
    private PreferenceConfiguration previousPrefs;
    private int previousDisplayPixelCount;
    // Keeps the "Predicted impact" panel's listener alive (preferences only hold it weakly)
    private SharedPreferences.OnSharedPreferenceChangeListener feelListener;

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

    void reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
            previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
        getFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new SettingsFragment()
        ).commitAllowingStateLoss();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        previousPrefs = PreferenceConfiguration.readPreferences(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_stream_settings);
        CoverGlowSettings.setUpHeader(this, null);
        feelListener = CoverGlowSettings.attachFeel(this);

        UiHelper.notifyNewRootView(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                displayCutoutP = insets.getDisplayCutout();
            }
        }

        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // The panel only fits on wide screens, so check again after rotating
        CoverGlowSettings.detachFeel(this, feelListener);
        feelListener = CoverGlowSettings.attachFeel(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
                reloadSettings();
            }
        }
    }

    @Override
    protected void onDestroy() {
        CoverGlowSettings.detachFeel(this, feelListener);
        feelListener = null;
        super.onDestroy();
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        finish();

        // Language changes are handled via configuration changes in Android 13+,
        // so manual activity relaunching is no longer required.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PreferenceConfiguration newPrefs = PreferenceConfiguration.readPreferences(this);
            if (!newPrefs.language.equals(previousPrefs.language)) {
                // Restart the PC view to apply UI changes
                Intent intent = new Intent(this, PcView.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, null);
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragment {
        private static final int REQUEST_CUSTOM_VULKAN_DRIVER = 0x6472;  // "dr"
        private static final int REQUEST_IMPORT_SHORTCUTS = 0x7363;  // "sc"
        private static final int REQUEST_RECORD_AUDIO = 0x6d63;  // "mc"
        private float maxSupportedFps;

        private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
            CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
            CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

            // Add the new option
            newEntries[newEntries.length - 1] = newEntryName;
            newValues[newValues.length - 1] = newEntryValue;

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait) {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            String newName;

            if (insetsRemoved) {
                newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
            }
            else {
                newName = getResources().getString(R.string.resolution_prefix_native);
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                if (portrait) {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
                }
                else {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
                }
            }

            newName += " ("+nativeWidth+"x"+nativeHeight+")";

            String newValue = nativeWidth+"x"+nativeHeight;

            // Check if the native resolution is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (newValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            appendPreferenceEntry(pref, newName, newValue);
        }

        private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true);
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false);
        }

        private void addNativeFrameRateEntry(float framerate) {
            int frameRateRounded = Math.round(framerate);
            if (frameRateRounded == 0) {
                return;
            }

            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            String fpsValue = Integer.toString(frameRateRounded);
            String fpsName = getResources().getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

            // Check if the native frame rate is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (fpsValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue);
        }

        private void removeValue(String preferenceKey, String value) {
            int matchingCount = 0;

            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            // Count the number of matching entries we'll be removing
            for (CharSequence seq : pref.getEntryValues()) {
                if (seq.toString().equalsIgnoreCase(value)) {
                    matchingCount++;
                }
            }

            // Create the new arrays
            CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
            CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
            int outIndex = 0;
            for (int i = 0; i < pref.getEntryValues().length; i++) {
                if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                    // Skip matching values
                    continue;
                }

                entries[outIndex] = pref.getEntries()[i];
                entryValues[outIndex] = pref.getEntryValues()[i];
                outIndex++;
            }

            // Update the preference with the new list
            pref.setEntries(entries);
            pref.setEntryValues(entryValues);
        }

        private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
            if (res == null) {
                res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
            }

            prefs.edit()
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            PreferenceConfiguration.getDefaultBitrate(res, fps))
                    .apply();
        }

        // Offers the frame rates the display supports. Others can still be entered
        // as a custom rate, which is kept even if the display doesn't support it.
        private void setUpFpsEntries() {
            FpsListPreference fpsPref = (FpsListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            fpsPref.resetEntries();

            // We give some extra room in case the FPS is rounded down
            if (maxSupportedFps < 118) {
                removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120");
            }
            if (maxSupportedFps < 88) {
                removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90");
            }
            // Never remove 30 FPS or 60 FPS

            addNativeFrameRateEntry(maxSupportedFps);
            fpsPref.addEntryForCustomValue();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);

            // Pad the list itself so it scrolls behind the navigation bar
            // but its last setting can still scroll clear of it
            View list = view.findViewById(android.R.id.list);
            if (list instanceof ViewGroup) {
                ((ViewGroup) list).setClipToPadding(false);
                UiHelper.applyStatusBarPadding(list);
            }
            else {
                UiHelper.applyStatusBarPadding(view);
            }
            return view;
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            CoverGlowSettings.attach(this, view);
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
            // Credit settings that came from another Moonlight version, under the panel
            CoverGlowSettings.showCredit(getActivity(), preference);
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.preferences);
            PreferenceScreen screen = getPreferenceScreen();
            initializeMicrophonePreferences();
            initializeAboutPreferences();

            // hide on-screen controls category on non touch screen devices
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_onscreen_controls");
                screen.removePreference(category);
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_input_settings");
                category.removePreference(findPreference("checkbox_absolute_mouse_mode"));
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors"));
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                    !getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback"));
            }

            // Hide USB driver options on devices without USB host support
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_gamepad_settings");
                category.removePreference(findPreference("checkbox_usb_bind_all"));
                category.removePreference(findPreference("checkbox_usb_driver"));
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    !getActivity().getPackageManager().hasSystemFeature("android.software.picture_in_picture") ||
                    getActivity().getPackageManager().hasSystemFeature("com.amazon.software.fireos")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_ui_settings");
                category.removePreference(findPreference("checkbox_enable_pip"));
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_help");
                screen.removePreference(category);
            }*/
            PreferenceCategory category_gamepad_settings =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            // Remove the vibration options if the device can't vibrate
            if (!((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasVibrator()) {
                category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"));
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
                // The entire OSC category may have already been removed by the touchscreen check above
                PreferenceCategory category = (PreferenceCategory) findPreference("category_onscreen_controls");
                if (category != null) {
                    category.removePreference(findPreference("checkbox_vibrate_osc"));
                }
            }


            // Start from the standard resolutions, since the saved value may have
            // been added as a custom one before this display's entries are known
            ResolutionListPreference resolutionPref =
                    (ResolutionListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);
            resolutionPref.resetEntries();

            Display display = getActivity().getWindowManager().getDefaultDisplay();
            maxSupportedFps = display.getRefreshRate();

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int maxSupportedResW = 0;

                // Add a native resolution with any insets included for users that don't want content
                // behind the notch of their display
                boolean hasInsets = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use the much nicer Display.getCutout() API on Android 10+
                        cutout = display.getCutout();
                    }
                    else {
                        // Android 9 only
                        cutout = displayCutoutP;
                    }

                    if (cutout != null) {
                        int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                        int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                        if (widthInsets != 0 || heightInsets != 0) {
                            DisplayMetrics metrics = new DisplayMetrics();
                            display.getRealMetrics(metrics);

                            int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                            int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                            addNativeResolutionEntries(width, height, false);
                            hasInsets = true;
                        }
                    }
                }

                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                // For example, a p201 device reports:
                // AVC Decoder: OMX.amlogic.avc.decoder.awesome
                // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
                // AVC supported width range: 64 - 384
                // HEVC supported width range: 64 - 544
                for (Display.Mode candidate : display.getSupportedModes()) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                    int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                            (width > 3840 || height > 2160)) {
                        addNativeResolutionEntries(width, height, hasInsets);
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560;
                    }
                    else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }

                    if (candidate.getRefreshRate() > maxSupportedFps) {
                        maxSupportedFps = candidate.getRefreshRate();
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(getContext()).glRenderer);

                MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
                MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

                if (avcDecoder != null) {
                    Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                if (hevcDecoder != null) {
                    Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                    LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                    // If 720p is not reported as supported, ignore all results from this API
                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840;
                        }
                        else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920;
                        }
                        else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280;
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K);
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P);
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P);
                    }
                    // Never remove 720p
                }
            }
            else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                int width = Math.max(metrics.widthPixels, metrics.heightPixels);
                int height = Math.min(metrics.widthPixels, metrics.heightPixels);
                addNativeResolutionEntries(width, height, false);
            }

            // A saved resolution that isn't offered, like one the decoder doesn't list
            // or one entered as a custom size, is kept and shown as a custom entry
            resolutionPref.addEntryForCustomValue();

            setUpFpsEntries();

            // The jitter buffer only applies to host frame timing pacing
            final Preference jitterBufferPref = findPreference("jitter_buffer");
            final ListPreference framePacingPref = (ListPreference) findPreference("frame_pacing");
            if (jitterBufferPref != null && framePacingPref != null) {
                jitterBufferPref.setEnabled("host-timed".equals(framePacingPref.getValue()));
                framePacingPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        jitterBufferPref.setEnabled("host-timed".equals(newValue));
                        return true;
                    }
                });
            }

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS");
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_advanced_settings");
                category.removePreference(findPreference("checkbox_enable_hdr"));
            }
            else {
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true;
                            break;
                        }
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    PreferenceCategory category =
                            (PreferenceCategory) findPreference("category_advanced_settings");
                    category.removePreference(findPreference("checkbox_enable_hdr"));
                }
                else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware");
                    PreferenceCategory category =
                            (PreferenceCategory) findPreference("category_advanced_settings");
                    CheckBoxPreference hdrPref = (CheckBoxPreference) category.findPreference("checkbox_enable_hdr");
                    hdrPref.setEnabled(false);
                    hdrPref.setChecked(false);
                    hdrPref.setSummary("Update the firmware on your NVIDIA SHIELD Android TV to enable HDR");
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, valueStr, null);

                    // Allow the original preference change to take place
                    return true;
                }
            });
            findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, null, valueStr);

                    // Allow the original preference change to take place
                    return true;
                }
            });

            // Setup custom commands preference click listener
            Preference customCommandsPref = findPreference("overlay_custom_commands");
            if (customCommandsPref != null) {
                customCommandsPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Intent intent = new Intent(getActivity(), CustomCommandsActivity.class);
                        startActivity(intent);
                        return true;
                    }
                });
            }

            // A custom driver is native code that runs inside Starwire, so the first time it's
            // turned on, say what that means and only turn it on if the user agrees
            Preference driverEnablePref = findPreference(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PREF_STRING);
            if (driverEnablePref != null) {
                driverEnablePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!(Boolean) newValue || PreferenceManager.getDefaultSharedPreferences(getActivity())
                            .getBoolean(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_WARNED_PREF_STRING, false)) {
                        return true;
                    }
                    showCustomDriverWarning((CheckBoxPreference) preference);
                    return false;
                });
            }

            // The custom Vulkan driver is a file chosen with the system document picker, then
            // checked and copied into app-private storage where libadrenotools can load it from
            Preference driverPref = findPreference(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING);
            if (driverPref != null) {
                driverPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        try {
                            startActivityForResult(intent, REQUEST_CUSTOM_VULKAN_DRIVER);
                        }
                        catch (ActivityNotFoundException e) {
                            Toast.makeText(getActivity(), R.string.custom_vulkan_driver_no_picker,
                                    Toast.LENGTH_LONG).show();
                        }
                        return true;
                    }
                });
                updateCustomDriverSummary();
            }
            Preference removeDriverPref = findPreference(PreferenceConfiguration.REMOVE_CUSTOM_VULKAN_DRIVER_PREF_STRING);
            if (removeDriverPref != null) {
                removeDriverPref.setOnPreferenceClickListener(preference -> {
                    CustomVulkanDriver.removeAll(CustomVulkanDriver.directory(getActivity()));
                    PreferenceManager.getDefaultSharedPreferences(getActivity()).edit()
                            .remove(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING)
                            .remove(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_SHA256_PREF_STRING)
                            .apply();
                    updateCustomDriverSummary();
                    Toast.makeText(getActivity(), R.string.custom_vulkan_driver_removed, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // Shortcuts imported from a file in Artemide's format, for the Shortcuts page
            Preference importShortcuts = findPreference("import_shortcuts_file");
            if (importShortcuts != null) {
                importShortcuts.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    // Shortcut files are often saved as .txt, so don't limit it to JSON
                    intent.setType("*/*");
                    try {
                        startActivityForResult(intent, REQUEST_IMPORT_SHORTCUTS);
                    }
                    catch (ActivityNotFoundException e) {
                        Toast.makeText(getActivity(), R.string.custom_vulkan_driver_no_picker,
                                Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            }
            Preference clearShortcuts = findPreference("clear_imported_shortcuts");
            if (clearShortcuts != null) {
                clearShortcuts.setOnPreferenceClickListener(preference -> {
                    ImportedShortcuts.clear(getActivity());
                    updateImportedShortcutsSummary();
                    Toast.makeText(getActivity(), R.string.imported_shortcuts_cleared, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            updateImportedShortcutsSummary();
        }

        private void updateImportedShortcutsSummary() {
            Preference importPref = findPreference("import_shortcuts_file");
            Preference clearPref = findPreference("clear_imported_shortcuts");
            if (importPref == null || getActivity() == null) {
                return;
            }
            List<ImportedShortcuts.Shortcut> shortcuts = ImportedShortcuts.load(getActivity());
            if (shortcuts.isEmpty()) {
                importPref.setSummary(R.string.summary_import_shortcuts);
            }
            else {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < shortcuts.size() && i < 4; i++) {
                    if (i > 0) {
                        names.append(", ");
                    }
                    names.append(shortcuts.get(i).name);
                }
                if (shortcuts.size() > 4) {
                    names.append(", …");
                }
                importPref.setSummary(getString(R.string.summary_imported_shortcuts, shortcuts.size(), names));
            }
            if (clearPref != null) {
                clearPref.setEnabled(!shortcuts.isEmpty());
            }
        }

        private void importShortcuts(Uri uri) {
            Context context = getActivity();
            if (context == null) {
                return;
            }
            String reason;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("could not open the file");
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > ImportedShortcuts.MAX_FILE_BYTES) {
                        throw new IllegalArgumentException(getString(R.string.imported_shortcuts_too_big));
                    }
                }
                int count = ImportedShortcuts.save(context, bytes.toString("UTF-8"));
                updateImportedShortcutsSummary();
                Toast.makeText(context, getString(R.string.imported_shortcuts_done, count), Toast.LENGTH_SHORT).show();
                return;
            }
            catch (IllegalArgumentException | IOException e) {
                reason = e.getMessage();
            }
            Toast.makeText(context, getString(R.string.imported_shortcuts_failed, reason), Toast.LENGTH_LONG).show();
        }

        private void updateCustomDriverSummary() {
            Preference pref = findPreference(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING);
            if (pref == null || getActivity() == null) {
                return;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
            String path = prefs.getString(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING, "");
            String sha256 = prefs.getString(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_SHA256_PREF_STRING, "");
            if (path == null || path.isEmpty()) {
                pref.setSummary(R.string.summary_custom_vulkan_driver_path);
            }
            else if (sha256 == null || sha256.isEmpty()) {
                // Imported by 0.2.0, which didn't record the SHA-256, so it isn't loaded
                pref.setSummary(getString(R.string.summary_custom_vulkan_driver_unchecked, new File(path).getName()));
            }
            else {
                pref.setSummary(getString(R.string.summary_custom_vulkan_driver_file, new File(path).getName(), sha256));
            }
        }

        private void showCustomDriverWarning(CheckBoxPreference pref) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.custom_vulkan_driver_warning_title)
                    .setMessage(R.string.custom_vulkan_driver_warning_message)
                    .setPositiveButton(R.string.custom_vulkan_driver_warning_accept, (dialog, which) -> {
                        PreferenceManager.getDefaultSharedPreferences(getActivity()).edit()
                                .putBoolean(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_WARNED_PREF_STRING, true)
                                .apply();
                        pref.setChecked(true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        // The About section: the version (a tap copies it for a bug report), the code for this
        // version, and the Credits and Open-source licences pages
        private void initializeAboutPreferences() {
            Preference versionPref = findPreference("about_version");
            Preference sourcePref = findPreference("about_source");
            Preference creditsPref = findPreference("about_credits");
            Preference licencesPref = findPreference("about_licences");
            if (versionPref == null || sourcePref == null || creditsPref == null || licencesPref == null) {
                return;
            }

            versionPref.setSummary(getString(R.string.summary_about_version,
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT));
            versionPref.setOnPreferenceClickListener(preference -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Starwire version", versionDetails()));
                // Android 13 and newer show their own "Copied" message
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(getActivity(), R.string.about_copied, Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            // The source offer GPL-3.0 asks for: the tagged code this release was built from
            sourcePref.setSummary(getString(R.string.summary_about_source, BuildConfig.VERSION_NAME));
            sourcePref.setOnPreferenceClickListener(preference -> {
                HelpLauncher.launchUrl(getActivity(),
                        "https://github.com/SicarioGaming/Starwire/tree/v" + BuildConfig.VERSION_NAME);
                return true;
            });

            creditsPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), OpenSourceActivity.class)
                        .putExtra(OpenSourceActivity.EXTRA_PAGE, OpenSourceActivity.PAGE_CREDITS));
                return true;
            });
            licencesPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), OpenSourceActivity.class)
                        .putExtra(OpenSourceActivity.EXTRA_PAGE, OpenSourceActivity.PAGE_LICENCES));
                return true;
            });
        }

        /** What a tap on Version copies: enough to tell builds and devices apart in a bug report. */
        static String versionDetails() {
            return "Starwire " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") "
                    + BuildConfig.FLAVOR + " " + BuildConfig.BUILD_TYPE
                    + ", commit " + BuildConfig.GIT_COMMIT
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + ", " + Build.MANUFACTURER + " " + Build.MODEL;
        }

        // Microphone streaming needs logabell's Apollo build on the PC; the summary says so.
        private void initializeMicrophonePreferences() {
            CheckBoxPreference enablePref =
                    (CheckBoxPreference) findPreference(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING);
            ListPreference devicePref =
                    (ListPreference) findPreference(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING);

            enablePref.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((Boolean) newValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !MicrophoneCaptureManager.hasRecordAudioPermission(getActivity())) {
                    // Turn it on once the permission is granted
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_RECORD_AUDIO);
                    return false;
                }
                return true;
            });

            initializeMicrophoneTargetPreferences();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                // No way to pick an input before Android 6, so always use the default
                ((PreferenceCategory) findPreference("category_audio_settings")).removePreference(devicePref);
                return;
            }

            List<MicrophoneCaptureManager.InputDeviceEntry> devices =
                    MicrophoneCaptureManager.getAvailableInputDevices(getActivity());
            CharSequence[] labels = new CharSequence[devices.size() + 1];
            CharSequence[] values = new CharSequence[devices.size() + 1];
            labels[0] = getString(R.string.microphone_device_default);
            values[0] = "0";
            for (int i = 0; i < devices.size(); i++) {
                labels[i + 1] = devices.get(i).label;
                values[i + 1] = Integer.toString(devices.get(i).id);
            }
            devicePref.setEntries(labels);
            devicePref.setEntryValues(values);
            if (devicePref.findIndexOfValue(devicePref.getValue()) < 0) {
                // The saved microphone is gone
                devicePref.setValue("0");
            }
        }

        // The rows that only apply to one microphone destination, kept while they're hidden
        private PreferenceCategory audioCategory;
        private Preference micHostInfoPref, vbanSetupPref, micPairingPref;
        private EditTextPreference vbanNamePref, vbanPortPref;

        // Only the rows for the chosen destination show: for Starwire's receiver the setup guide,
        // pairing and the port; for another VBAN receiver the guide with its warning, the stream
        // name and the port; for the host the Apollo link. (Disabled rows look like live ones in
        // this theme.)
        private void initializeMicrophoneTargetPreferences() {
            ListPreference targetPref =
                    (ListPreference) findPreference(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING);
            audioCategory = (PreferenceCategory) findPreference("category_audio_settings");
            micHostInfoPref = findPreference("microphone_host_info");
            vbanSetupPref = findPreference("vban_setup_info");
            micPairingPref = findPreference("mic_pairing");
            vbanNamePref = (EditTextPreference) findPreference(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING);
            vbanPortPref = (EditTextPreference) findPreference(PreferenceConfiguration.VBAN_PORT_PREF_STRING);
            if (targetPref == null || audioCategory == null || micHostInfoPref == null || vbanSetupPref == null
                    || micPairingPref == null || vbanNamePref == null || vbanPortPref == null) {
                return;
            }
            final EditTextPreference namePref = vbanNamePref;
            final EditTextPreference portPref = vbanPortPref;

            micPairingPref.setOnPreferenceClickListener(preference -> {
                MicPairingDialog.show(getActivity(), PreferenceConfiguration.readPreferences(getActivity()).vbanPort,
                        this::showMicrophonePairing);
                return true;
            });

            targetPref.setOnPreferenceChangeListener((preference, newValue) -> {
                showMicrophoneTarget((String) newValue, namePref.getText(), portPref.getText());
                return true;
            });
            namePref.setOnPreferenceChangeListener((preference, newValue) -> {
                showMicrophoneTarget(targetPref.getValue(), (String) newValue, portPref.getText());
                return true;
            });
            portPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String port = ((String) newValue).trim();
                try {
                    int value = Integer.parseInt(port);
                    if (value < 1 || value > 65535) {
                        return false;
                    }
                }
                catch (NumberFormatException e) {
                    return false;
                }
                showMicrophoneTarget(targetPref.getValue(), namePref.getText(), port);
                return true;
            });
            showMicrophoneTarget(targetPref.getValue(), namePref.getText(), portPref.getText());
        }

        private void showMicrophoneTarget(String target, String streamName, String port) {
            boolean vban = PreferenceConfiguration.MICROPHONE_TARGET_VBAN.equals(target);
            boolean host = PreferenceConfiguration.MICROPHONE_TARGET_HOST.equals(target);
            boolean starwire = !vban && !host;
            if (streamName == null || streamName.trim().isEmpty()) {
                streamName = VbanSender.DEFAULT_STREAM_NAME;
            }
            if (port == null || port.trim().isEmpty()) {
                port = Integer.toString(VbanSender.DEFAULT_PORT);
            }

            setShown(micHostInfoPref, host);
            setShown(vbanSetupPref, !host);
            setShown(micPairingPref, starwire);
            setShown(vbanNamePref, vban);
            setShown(vbanPortPref, !host);
            Preference setupPref = vbanSetupPref;
            Preference namePref = vbanNamePref;
            Preference portPref = vbanPortPref;

            if (vban) {
                String deviceAddress = localNetworkAddress();
                int portNumber;
                try {
                    portNumber = Integer.parseInt(port.trim());
                }
                catch (NumberFormatException e) {
                    portNumber = VbanSender.DEFAULT_PORT;
                }
                setupPref.setSummary(getString(R.string.summary_vban_setup,
                        deviceAddress != null ? deviceAddress : getString(R.string.vban_this_device),
                        streamName.trim(), portNumber));
            }
            else {
                setupPref.setSummary(R.string.summary_mic_setup_encrypted);
            }
            namePref.setSummary(getString(R.string.summary_vban_stream_name, streamName.trim()));
            portPref.setSummary(getString(R.string.summary_vban_port, port.trim()));
            showMicrophonePairing();
        }

        // Which PCs the microphone is paired with, or "Not paired yet"
        private void showMicrophonePairing() {
            if (micPairingPref == null || getActivity() == null) {
                return;
            }
            List<String> names = new ArrayList<>();
            for (MicPairing pairing : new MicPairingStore(getActivity()).all()) {
                names.add(pairing.displayName());
            }
            micPairingPref.setSummary(names.isEmpty() ? getString(R.string.summary_mic_pairing_none)
                    : getString(R.string.summary_mic_pairing_done, TextUtils.join(", ", names)));
        }

        // A removed row keeps its place (its order from the XML) when it's added back
        private void setShown(Preference preference, boolean shown) {
            boolean present = audioCategory.findPreference(preference.getKey()) != null;
            if (shown && !present) {
                audioCategory.addPreference(preference);
            }
            else if (!shown && present) {
                audioCategory.removePreference(preference);
            }
        }

        // This device's home-network IPv4 address, for the receiver on the PC to accept; Wi-Fi first
        private static String localNetworkAddress() {
            String fallback = null;
            try {
                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    String name = iface.getName();
                    // Mobile data interfaces can carry private-looking carrier addresses
                    if (!iface.isUp() || iface.isLoopback() || name.startsWith("rmnet")
                            || name.startsWith("ccmni") || name.startsWith("clat")) {
                        continue;
                    }
                    for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            if (iface.getName().startsWith("wlan")) {
                                return address.getHostAddress();
                            }
                            if (fallback == null) {
                                fallback = address.getHostAddress();
                            }
                        }
                    }
                }
            }
            catch (SocketException | NullPointerException e) {
                // No interfaces to look at; the setup text says "this device" instead
            }
            return fallback;
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode != REQUEST_RECORD_AUDIO) {
                return;
            }

            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                ((CheckBoxPreference) findPreference(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING))
                        .setChecked(true);
            }
            else {
                Toast.makeText(getActivity(), R.string.microphone_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);

            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            if (requestCode == REQUEST_CUSTOM_VULKAN_DRIVER) {
                importCustomDriver(data.getData());
            }
            else if (requestCode == REQUEST_IMPORT_SHORTCUTS) {
                importShortcuts(data.getData());
            }
        }

        private void importCustomDriver(Uri uri) {
            Context context = getActivity();
            if (context == null) {
                return;
            }

            // Checked as it's copied (name, size, 64-bit ARM library); a file that fails keeps
            // the driver imported before
            CustomVulkanDriver.Imported imported;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("could not open the file");
                }
                imported = CustomVulkanDriver.importDriver(CustomVulkanDriver.directory(context),
                        queryDisplayName(context, uri), in);
            }
            catch (Exception e) {
                Toast.makeText(context, context.getString(R.string.custom_vulkan_driver_import_failed) + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }

            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_PATH_PREF_STRING, imported.file.getAbsolutePath())
                    .putString(PreferenceConfiguration.CUSTOM_VULKAN_DRIVER_SHA256_PREF_STRING, imported.sha256)
                    .apply();
            updateCustomDriverSummary();
            Toast.makeText(context, context.getString(R.string.custom_vulkan_driver_imported, imported.file.getName()),
                    Toast.LENGTH_SHORT).show();
        }

        private String queryDisplayName(Context context, Uri uri) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        return cursor.getString(index);
                    }
                }
            }
            catch (Exception ignored) {
            }
            return null;
        }
    }
}
