package com.limelight.binding.input.dualsense;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.InputDevice;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the DualSense's trigger effects over Bluetooth for a stream, or for the Test button in
 * Settings. It owns the Bluetooth link and one DualSenseSession at a time, and makes every
 * Bluetooth call on its own thread.
 *
 * In a stream, ControllerHandler tells it when a controller shows up or goes away and passes on
 * the host's trigger messages. Each DualSense that connects is checked again; anything that fails
 * leaves the pad on today's path, with the reason in the log and in Settings. One DualSense at a
 * time: with two, both stay on today's path. Rumble and the light bar never come here.
 *
 * The JVM tests run it with a fake link (LinkFactory) on the main looper, so its checks, timeouts
 * and fallbacks are tested without a device or a clock.
 */
public final class DualSenseBluetooth {
    /** The status, on the main thread. */
    public interface StatusListener {
        void onStatus(DualSenseStatus status);
    }

    /** Makes the Bluetooth link: Android's in the app, a fake in the JVM tests. */
    public interface LinkFactory {
        HidHostLink create(Context context);
    }

    private static final LinkFactory ANDROID_LINK = BluetoothHidHostLink::new;

    // How long to wait for Android's HID service, and for the pad to show in its list after
    // Android shows it as a controller (guesses; nobody has timed either with a pad)
    static final long LINK_TIMEOUT_MS = 5000;
    static final long PAD_RETRY_MS = 1500;

    private final Context context;
    private final boolean inStream;
    private final TriggerRules rules;
    private final int strength;
    private final StatusListener listener;
    private final LinkFactory linkFactory;
    // Null when the tests give a looper to run on
    private final HandlerThread thread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    // The DualSense controllers Android shows now, by device ID, with their address if known
    private final Map<Integer, String> pads = new LinkedHashMap<>();
    private final Runnable linkTimeout = this::onLinkTimeout;
    private final Runnable endTest = this::endTest;
    private final Runnable syncGame = this::syncGame;
    private boolean syncPosted;

    private HidHostLink link;
    private boolean linkReady;
    private DualSenseSession session;
    private int sessionDevice = -1;
    private boolean retried;
    private DualSenseStatus status = DualSenseStatus.CHECKING;
    // NO_PAD now only because the pad that was WORKING went away (turned off, out of range)
    private boolean workingPadLeft;
    private boolean stopped;

    private DualSenseBluetooth(Context context, boolean inStream, TriggerPreset preset, int strength,
                               StatusListener listener, LinkFactory linkFactory, Looper looper) {
        this.context = context.getApplicationContext();
        this.inStream = inStream;
        this.rules = new TriggerRules(preset);
        this.strength = strength;
        this.listener = listener;
        this.linkFactory = linkFactory;
        if (looper == null) {
            this.thread = new HandlerThread("DualSense");
            this.thread.start();
            looper = this.thread.getLooper();
        }
        else {
            this.thread = null;
        }
        this.worker = new Handler(looper);
    }

    /** For a stream, or null when the switch is off. Call start() next. */
    public static DualSenseBluetooth forStream(Context context, PreferenceConfiguration prefs) {
        return forStream(context, prefs, ANDROID_LINK, null);
    }

    /**
     * forStream with another link, run on the given looper (null: its own thread). For the JVM
     * tests.
     */
    public static DualSenseBluetooth forStream(Context context, PreferenceConfiguration prefs,
                                               LinkFactory links, Looper looper) {
        if (!prefs.dualSenseEffects) {
            return null;
        }
        return new DualSenseBluetooth(context, true, prefs.dualSenseTriggerPreset, prefs.dualSenseTriggerStrength,
                null, links, looper);
    }

    /** For Settings: finds the pad without sending anything, and sends only on test(). */
    public static DualSenseBluetooth forSettings(Context context, int strength, StatusListener listener) {
        return forSettings(context, strength, listener, ANDROID_LINK, null);
    }

    /** forSettings with another link, run on the given looper. For the JVM tests. */
    public static DualSenseBluetooth forSettings(Context context, int strength, StatusListener listener,
                                                 LinkFactory links, Looper looper) {
        return new DualSenseBluetooth(context, false, TriggerPreset.NONE, strength, listener, links, looper);
    }

    /** Where things stand. Read it on the worker's thread (the JVM tests give theirs). */
    public DualSenseStatus status() {
        return status;
    }

    /** The checks before the hidden calls, then the link. */
    public void start() {
        int sdk = Build.VERSION.SDK_INT;
        boolean modern = sdk >= DualSenseChecks.MIN_SDK;
        HidHostLink candidate = modern ? linkFactory.create(context) : null;
        DualSenseStatus before = DualSenseChecks.beforeConnect(true, sdk,
                modern && BluetoothHidHostLink.hasPermission(context),
                candidate != null && candidate.bluetoothOn());
        worker.post(() -> {
            if (stopped) {
                return;
            }
            if (before != DualSenseStatus.READY) {
                setStatus(before);
                return;
            }
            setStatus(DualSenseStatus.CHECKING);
            link = candidate;
            if (!link.open(() -> worker.post(this::onLinkReady))) {
                setStatus(DualSenseStatus.NO_HID_SERVICE);
                return;
            }
            worker.postDelayed(linkTimeout, LINK_TIMEOUT_MS);
        });
    }

    /**
     * A controller ControllerHandler now handles. Only a DualSense or an Edge with sticks counts:
     * a touchpad that Android shows apart from its pad doesn't.
     */
    public void onController(int deviceId, InputDevice device, boolean gamepad) {
        if (device == null) {
            return;
        }
        onController(deviceId, device.getVendorId(), device.getProductId(), safeToString(device),
                device.getDescriptor(), gamepad);
    }

    /** onController with what it reads from the InputDevice: its IDs, its text and descriptor. */
    void onController(int deviceId, int vendorId, int productId, String description, String descriptor,
                      boolean gamepad) {
        if (!gamepad || !PadMatcher.isDualSense(vendorId, productId)) {
            return;
        }
        String address = PadMatcher.addressFrom(description, descriptor);
        worker.post(() -> {
            if (stopped) {
                return;
            }
            pads.put(deviceId, address);
            retried = false;
            tryConnect();
        });
    }

    /** A controller went away. */
    public void onControllerRemoved(int deviceId) {
        worker.post(() -> {
            if (stopped || !pads.containsKey(deviceId)) {
                return;
            }
            pads.remove(deviceId);
            if (deviceId == sessionDevice) {
                boolean worked = session.status() == DualSenseStatus.WORKING;
                session.lost();
                endSession();
                setStatus(DualSenseStatus.NO_PAD);
                workingPadLeft = worked;
            }
            if (pads.size() == 1 && session == null) {
                // The one left is alone now
                retried = false;
                tryConnect();
            }
        });
    }

    /** The host's 0x5503 message, for the controller with this device ID. */
    public void onHostTriggers(int deviceId, int eventFlags, int typeLeft, int typeRight, byte[] left, byte[] right) {
        if (left == null || right == null || left.length < TriggerEffect.PARAMETER_SIZE
                || right.length < TriggerEffect.PARAMETER_SIZE) {
            return;
        }
        HostTriggerMessage message = HostTriggerMessage.fromCallback(0, eventFlags, typeLeft, typeRight,
                left.clone(), 0, right.clone(), 0);
        worker.post(() -> {
            if (stopped || !pads.containsKey(deviceId)) {
                return;
            }
            // Kept in the rules either way, for a session that starts later
            rules.onGameMessage(message);
            if (session != null && deviceId == sessionDevice && !syncPosted) {
                // One send for however many messages arrive before it runs
                syncPosted = true;
                worker.post(syncGame);
            }
        });
    }

    private void syncGame() {
        syncPosted = false;
        if (!stopped && session != null) {
            report(session.sync());
        }
    }

    /**
     * Settings' Test: one effect on both triggers of the one DualSense, then off again after
     * holdMs.
     */
    public void test(TriggerEffect effect, long holdMs) {
        worker.post(() -> {
            if (stopped) {
                return;
            }
            if (session == null) {
                // Look again: the pad may have connected since
                refreshNow();
                if (session == null) {
                    return;
                }
            }
            DualSenseStatus result = session.test(effect);
            report(result);
            // A second tap restarts the wait instead of ending the effect early
            worker.removeCallbacks(endTest);
            if (result == DualSenseStatus.WORKING) {
                worker.postDelayed(endTest, holdMs);
            }
        });
    }

    // Turns Test's effect off again
    private void endTest() {
        if (session != null && session.release() == DualSenseStatus.WORKING) {
            setStatus(DualSenseStatus.READY);
        }
    }

    /** Settings: look again for the pad. */
    public void refresh() {
        worker.post(this::refreshNow);
    }

    /** Turns the triggers off and lets go of everything. Safe to call twice. */
    public void stop() {
        worker.post(() -> {
            if (stopped) {
                return;
            }
            stopped = true;
            worker.removeCallbacksAndMessages(null);
            if (session != null) {
                session.release();
            }
            if (link != null) {
                link.close();
            }
            if (inStream) {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, lastStreamStatus().name())
                        .apply();
            }
            if (thread != null) {
                thread.quitSafely();
            }
        });
    }

    // What Settings shows for this stream: a pad that worked and then went away before the
    // stream ended worked, so that isn't "No DualSense connected"
    private DualSenseStatus lastStreamStatus() {
        return workingPadLeft && status == DualSenseStatus.NO_PAD ? DualSenseStatus.WORKING : status;
    }

    // Settings has no ControllerHandler, so it looks through Android's controllers itself
    private void refreshNow() {
        if (stopped) {
            return;
        }
        pads.clear();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && PadMatcher.isDualSense(device.getVendorId(), device.getProductId())
                    && (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                pads.put(id, PadMatcher.addressFrom(safeToString(device), device.getDescriptor()));
            }
        }
        endSession();
        retried = true;
        if (pads.isEmpty()) {
            if (linkReady) {
                setStatus(DualSenseStatus.NO_PAD);
            }
            return;
        }
        tryConnect();
    }

    private void onLinkReady() {
        if (stopped) {
            return;
        }
        worker.removeCallbacks(linkTimeout);
        linkReady = true;
        if (inStream) {
            tryConnect();
        }
        else {
            refreshNow();
        }
    }

    private void onLinkTimeout() {
        if (!linkReady) {
            LimeLog.warning("DualSense: no answer from Android's Bluetooth HID service");
            setStatus(DualSenseStatus.NO_HID_SERVICE);
        }
    }

    // On the worker thread. Starts a session for the one DualSense, if there's exactly one.
    private void tryConnect() {
        if (stopped || pads.isEmpty()) {
            return;
        }
        if (pads.size() > 1) {
            if (session != null) {
                session.release();
            }
            endSession();
            setStatus(DualSenseStatus.TWO_PADS);
            return;
        }
        if (session != null && pads.containsKey(sessionDevice)) {
            // Already running for this pad
            return;
        }
        if (!linkReady) {
            // Goes on once the link is ready, or the timeout says why not
            return;
        }
        Map.Entry<Integer, String> pad = pads.entrySet().iterator().next();
        DualSenseSession next = new DualSenseSession(link, rules, strength);
        DualSenseStatus result = inStream ? next.connect(pad.getValue()) : next.find(pad.getValue());
        if (result == DualSenseStatus.NO_PAD && !retried) {
            // Android may show the pad as a controller a moment before its HID service lists it
            retried = true;
            worker.postDelayed(this::tryConnect, PAD_RETRY_MS);
            return;
        }
        if (result == DualSenseStatus.WORKING || result == DualSenseStatus.READY) {
            session = next;
            sessionDevice = pad.getKey();
            LimeLog.info("DualSense: " + result + (inStream ? ", through " + next.sendForm() : "")
                    + (pad.getValue() != null ? ", found by its address" : ", found by its name"));
        }
        else if (next.lastError() != null) {
            LimeLog.warning("DualSense: " + result + ": " + next.lastError());
        }
        setStatus(result);
    }

    // A session's result: a failure ends that session, and the pad stays on today's path
    private void report(DualSenseStatus result) {
        if (result.isFallback() && session != null) {
            LimeLog.warning("DualSense: back to the usual path: " + result
                    + (session.lastError() != null ? " (" + session.lastError() + ")" : ""));
            endSession();
        }
        setStatus(result);
    }

    private void endSession() {
        session = null;
        sessionDevice = -1;
    }

    private void setStatus(DualSenseStatus next) {
        if (next != status) {
            LimeLog.info("DualSense: " + status + " -> " + next);
        }
        status = next;
        workingPadLeft = false;
        if (listener != null) {
            main.post(() -> listener.onStatus(next));
        }
    }

    private static String safeToString(InputDevice device) {
        try {
            return device.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
