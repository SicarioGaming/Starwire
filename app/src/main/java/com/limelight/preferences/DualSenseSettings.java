package com.limelight.preferences;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.dualsense.BluetoothHidHostLink;
import com.limelight.binding.input.dualsense.DualSenseBluetooth;
import com.limelight.binding.input.dualsense.DualSenseChecks;
import com.limelight.binding.input.dualsense.DualSenseStatus;
import com.limelight.binding.input.dualsense.TriggerPreset;
import com.limelight.binding.input.dualsense.TriggerStrength;

/**
 * Settings > Controllers > DualSense (Experimental): the switch, which asks for the Nearby
 * devices permission, the status line, the Test button, the trigger preset and the strength.
 * While the switch is on and Settings is open, it looks for the pad, so the status line is live.
 * It never sends anything until Test is tapped.
 */
final class DualSenseSettings {
    static final int REQUEST_NEARBY_DEVICES = 0x6473;  // "ds"
    // How long Test holds its effect before turning it off
    static final long TEST_HOLD_MS = 10_000;

    /** Makes the checker: DualSenseBluetooth.forSettings, or one with a fake link in the JVM tests. */
    interface CheckerFactory {
        DualSenseBluetooth create(Activity activity, int strength, DualSenseBluetooth.StatusListener listener);
    }

    private final PreferenceFragment fragment;
    private final CheckerFactory checkers;
    private CheckBoxPreference toggle;
    private Preference status;
    private DualSenseBluetooth checker;
    private boolean testSent;

    DualSenseSettings(PreferenceFragment fragment) {
        this(fragment, DualSenseBluetooth::forSettings);
    }

    DualSenseSettings(PreferenceFragment fragment, CheckerFactory checkers) {
        this.fragment = fragment;
        this.checkers = checkers;
    }

    void attach() {
        toggle = (CheckBoxPreference) fragment.findPreference(PreferenceConfiguration.DUALSENSE_EFFECTS_PREF_STRING);
        status = fragment.findPreference("dualsense_status");
        Preference test = fragment.findPreference("dualsense_test");
        ListPreference strength = (ListPreference) fragment.findPreference(
                PreferenceConfiguration.DUALSENSE_TRIGGER_STRENGTH_PREF_STRING);
        if (toggle == null || status == null || test == null || strength == null) {
            return;
        }

        if (Build.VERSION.SDK_INT < DualSenseChecks.MIN_SDK) {
            toggle.setEnabled(false);
            toggle.setSummary(R.string.summary_checkbox_dualsense_effects_old_android);
            show(DualSenseStatus.NEEDS_ANDROID_12);
            return;
        }

        toggle.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = (Boolean) newValue;
            if (on && !BluetoothHidHostLink.hasPermission(fragment.getActivity())) {
                // Turned on once the permission is allowed
                requestNearbyDevices();
                return false;
            }
            if (on) {
                startChecker();
            }
            else {
                stopChecker();
                show(DualSenseStatus.OFF);
            }
            return true;
        });
        test.setOnPreferenceClickListener(preference -> {
            if (checker != null) {
                testSent = true;
                checker.test(TriggerPreset.GUN_CLICK.effect(), TEST_HOLD_MS);
            }
            return true;
        });
        strength.setOnPreferenceChangeListener((preference, newValue) -> {
            // The checker sends at the strength it started with
            if (checker != null) {
                stopChecker();
                startChecker(TriggerStrength.fromPref((String) newValue));
            }
            return true;
        });
        show(toggle.isChecked() ? DualSenseStatus.CHECKING : DualSenseStatus.OFF);
    }

    private void requestNearbyDevices() {
        if (Build.VERSION.SDK_INT >= DualSenseChecks.MIN_SDK) {
            fragment.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_NEARBY_DEVICES);
        }
    }

    void onResume() {
        if (toggle != null && toggle.isEnabled() && toggle.isChecked()) {
            startChecker();
        }
    }

    void onPause() {
        stopChecker();
    }

    /** True if the result was for the Nearby devices request. */
    boolean onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_NEARBY_DEVICES) {
            return false;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggle.setChecked(true);
            startChecker();
        }
        else {
            Toast.makeText(fragment.getActivity(), R.string.dualsense_permission_denied, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void onStatus(DualSenseBluetooth from, DualSenseStatus next) {
        if (from != checker) {
            // A late report from a checker that was stopped
            return;
        }
        if (next != DualSenseStatus.WORKING) {
            testSent = false;
        }
        show(next);
    }

    private void startChecker() {
        Activity activity = fragment.getActivity();
        startChecker(PreferenceConfiguration.readPreferences(activity).dualSenseTriggerStrength);
    }

    private void startChecker(int strength) {
        if (checker != null) {
            return;
        }
        testSent = false;
        DualSenseBluetooth[] started = new DualSenseBluetooth[1];
        started[0] = checkers.create(fragment.getActivity(), strength, next -> onStatus(started[0], next));
        checker = started[0];
        checker.start();
        show(DualSenseStatus.CHECKING);
    }

    private void stopChecker() {
        if (checker != null) {
            checker.stop();
            checker = null;
        }
    }

    private void show(DualSenseStatus now) {
        Activity activity = fragment.getActivity();
        if (activity == null || status == null) {
            return;
        }
        String text = now == DualSenseStatus.WORKING && testSent
                ? activity.getString(R.string.dualsense_test_sent) : activity.getString(now.message);
        if (now != DualSenseStatus.OFF) {
            DualSenseStatus last = DualSenseStatus.fromName(PreferenceManager.getDefaultSharedPreferences(activity)
                    .getString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, null));
            if (last != null && last.isFallback()) {
                text += "\n" + activity.getString(R.string.dualsense_status_last_stream, activity.getString(last.message));
            }
        }
        status.setSummary(text);
    }
}
