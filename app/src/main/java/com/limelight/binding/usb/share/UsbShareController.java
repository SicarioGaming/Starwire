package com.limelight.binding.usb.share;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.limelight.LimeLog;
import com.limelight.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Sharing one wired USB device with the PC during a stream, for the stream menu's USB devices
 * page: the devices plugged in with what the device rules say about each, and the share flow.
 *
 * Choosing a device asks Android's permission, checks all its descriptors against the rules,
 * asks you for a keyboard or mouse, then connects the tunnel. The exporter starts only once the
 * PC's helper has proved it holds the pairing (docs/dev/usb-protocol.md, "Attacks"). Choosing it
 * again stops. Unplugging it, the stream ending or the PC ending the session gives it back to
 * the phone; a lost connection does too, and then the device offers to be shared again.
 *
 * One device at a time, as the contract has it. Everything here runs on the main thread except
 * the rules' check, the exporter and the tunnel's own threads, whose answers come back through
 * `main`. Every answer is for one share, and is dropped once that share is stopping or over.
 */
public final class UsbShareController {
    public enum State {
        /** Not shared; the verdict, or a note on how the last share ended, shows */
        IDLE,
        /** Android's permission dialog is up */
        PERMISSION,
        /** Reading its descriptors to apply the rules */
        CHECKING,
        /** Waiting for your yes to share a keyboard or mouse */
        CONFIRM,
        /** TLS, BIND and SHARE with the PC's helper */
        CONNECTING,
        /** The helper is attaching it, or has */
        SHARED,
        /** Ending the session and giving the device back */
        STOPPING,
        /** The connection was lost and the device is back on the phone; choosing it shares it again */
        LOST,
    }

    public interface Ui {
        /** The list or a device's state changed */
        void onChanged();

        /** Ask whether to share this keyboard or mouse; answer once, on the main thread. */
        void confirmInput(String name, Answer answer);

        /** Something worth saying while the menu may be closed */
        void showMessage(String message);
    }

    public interface Answer {
        void onAnswer(boolean yes);
    }

    /** One device plugged into the phone */
    public static final class Entry {
        public final String key;
        public final String name;
        private UsbVerdict verdict;
        private State state = State.IDLE;
        private String note;

        Entry(String key, String name, UsbVerdict verdict) {
            this.key = key;
            this.name = name;
            this.verdict = verdict;
        }

        /** The rules' decision: the preview until it's been checked, then the check's */
        public UsbVerdict verdict() {
            return verdict;
        }

        public State state() {
            return state;
        }

        /** How the last share ended, or null */
        public String note() {
            return note;
        }
    }

    // One share, from the permission request until the device is back on the phone
    private final class Session {
        final Entry entry;
        UsbDevice device;
        boolean confirmed;
        UsbTunnel tunnel;
        UsbExporter exporter;
        boolean shared;
        // Set on the main thread when it starts to stop: every answer for it is dropped from then
        boolean stopping;
        // Guarded by this. The exporter starts on the tunnel's thread and stops on the worker
        boolean exporterStarted;
        boolean over;

        Session(Entry entry) {
            this.entry = entry;
        }
    }

    private final Context context;
    private final UsbDevices devices;
    private final UsbShareBackends.Factory backends;
    private final UsbTunnel.Target target;
    private final Ui ui;
    private final Executor main;
    private final Executor worker;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private UsbDeviceRules rules;
    private Session session;
    private boolean closed;

    public UsbShareController(Context context, UsbDevices devices, UsbShareBackends.Factory backends,
                              UsbTunnel.Target target, Ui ui, Executor main, Executor worker) {
        this.context = context;
        this.devices = devices;
        this.backends = backends;
        this.target = target;
        this.ui = ui;
        this.main = main;
        this.worker = worker;
        for (UsbDeviceSummary d : devices.list()) {
            add(d);
        }
        devices.start(new UsbDevices.Listener() {
            @Override
            public void onAttached(UsbDeviceSummary device) {
                if (!closed) {
                    add(device);
                    ui.onChanged();
                }
            }

            @Override
            public void onDetached(String key) {
                unplugged(key);
            }
        });
    }

    /** The devices plugged in, in the order they came */
    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public Entry entry(String key) {
        return entries.get(key);
    }

    /** Whether a share is going on: from the permission request until the device is back */
    public boolean isBusy() {
        return session != null;
    }

    /** The device's tile was chosen: share it, stop it, or say why not. */
    public void activate(String key) {
        Entry e = entries.get(key);
        if (closed || e == null) {
            return;
        }
        if (session != null) {
            if (session.entry != e) {
                ui.showMessage(context.getString(R.string.usb_one_at_a_time, session.entry.name));
            }
            else if (!session.stopping) {
                // Stop, or give up on one still connecting
                stop(session, State.IDLE, null, UsbTunnel.END_STOPPED);
            }
            return;
        }
        if (e.verdict.isRefused()) {
            ui.showMessage(UsbShareText.detail(context, e));
            return;
        }
        Session s = new Session(e);
        session = s;
        e.note = null;
        set(e, State.PERMISSION);
        devices.requestPermission(key, result -> permissionAnswered(s, result));
    }

    /**
     * Gives the device back, if one is shared or on its way, and forgets any "Share again": the
     * stream ended. Stays usable for another stream.
     */
    public void stopAll(int endReason) {
        if (session != null && !session.stopping) {
            stop(session, State.IDLE, null, endReason);
        }
        for (Entry e : entries.values()) {
            if (e.state == State.LOST) {
                e.state = State.IDLE;
            }
        }
        ui.onChanged();
    }

    /** stopAll, then stops listening for devices. Nothing more happens after this. */
    public void close(int endReason) {
        if (closed) {
            return;
        }
        stopAll(endReason);
        closed = true;
        devices.stop();
    }

    private void add(UsbDeviceSummary d) {
        if (!entries.containsKey(d.key)) {
            entries.put(d.key, new Entry(d.key, UsbShareText.name(context, d), UsbSharePreview.judge(d)));
        }
    }

    private boolean live(Session s) {
        return session == s && !s.stopping && !closed;
    }

    private void set(Entry e, State state) {
        e.state = state;
        ui.onChanged();
    }

    private void permissionAnswered(Session s, int result) {
        if (!live(s)) {
            return;
        }
        s.device = result == UsbDevices.GRANTED ? devices.device(s.entry.key) : null;
        if (s.device == null) {
            int note = result == UsbDevices.BLOCKED ? R.string.error_usb_prohibited : R.string.usb_permission_denied;
            stop(s, State.IDLE, context.getString(note), 0);
            return;
        }
        set(s.entry, State.CHECKING);
        if (rules == null) {
            rules = backends.rules(context);
        }
        UsbDeviceRules check = rules;
        UsbDevice device = s.device;
        worker.execute(() -> {
            UsbVerdict verdict = null;
            try {
                verdict = check.check(device);
            }
            catch (IOException | RuntimeException e) {
                LimeLog.warning("USB share: couldn't read the device's descriptors: " + e);
            }
            UsbVerdict checked = verdict;
            main.execute(() -> checked(s, checked));
        });
    }

    private void checked(Session s, UsbVerdict verdict) {
        if (!live(s)) {
            return;
        }
        Entry e = s.entry;
        if (verdict == null) {
            stop(s, State.IDLE, context.getString(R.string.usb_read_failed), 0);
            return;
        }
        // The check's decision counts. A keyboard or mouse the preview saw still asks.
        e.verdict = e.verdict.asks() && !verdict.isRefused() ? UsbVerdict.ASK_FIRST : verdict;
        if (e.verdict.isRefused()) {
            ui.showMessage(UsbShareText.verdict(context, e.verdict));
            stop(s, State.IDLE, null, 0);
        }
        else if (e.verdict.asks()) {
            set(e, State.CONFIRM);
            ui.confirmInput(e.name, yes -> confirmed(s, yes));
        }
        else {
            connect(s);
        }
    }

    private void confirmed(Session s, boolean yes) {
        if (!live(s) || s.entry.state != State.CONFIRM) {
            return;
        }
        if (yes) {
            s.confirmed = true;
            connect(s);
        }
        else {
            stop(s, State.IDLE, null, 0);
        }
    }

    private void connect(Session s) {
        set(s.entry, State.CONNECTING);
        s.exporter = backends.exporter(context);
        s.tunnel = backends.tunnel(context);
        s.tunnel.connect(target, s.confirmed, new UsbTunnel.Listener() {
            @Override
            public UsbExporter.Export onBound() throws IOException {
                return startExporter(s);
            }

            @Override
            public void onSharing() {
                main.execute(() -> sharing(s));
            }

            @Override
            public void onClosed(UsbTunnel.Closed why) {
                main.execute(() -> tunnelClosed(s, why));
            }
        });
    }

    // On the tunnel's thread, once the helper has proved the pairing
    private UsbExporter.Export startExporter(Session s) throws IOException {
        synchronized (s) {
            if (s.over) {
                throw new IOException("the share was stopped");
            }
            UsbExporter.Export export = s.exporter.start(s.device, () -> main.execute(() -> deviceGone(s)));
            s.exporterStarted = true;
            return export;
        }
    }

    private void sharing(Session s) {
        if (!live(s) || s.entry.state != State.CONNECTING) {
            return;
        }
        s.shared = true;
        set(s.entry, State.SHARED);
        ui.showMessage(context.getString(R.string.usb_shared_toast, s.entry.name));
    }

    private void tunnelClosed(Session s, UsbTunnel.Closed why) {
        if (!live(s)) {
            return;
        }
        if (why.kind == UsbTunnel.Closed.LOST && s.shared) {
            stop(s, State.LOST, null, UsbTunnel.END_CLOSING);
            ui.showMessage(context.getString(R.string.usb_lost_toast, s.entry.name));
            return;
        }
        String note = UsbShareText.closed(context, why);
        stop(s, State.IDLE, note, UsbTunnel.END_CLOSING);
        ui.showMessage(note);
    }

    private void unplugged(String key) {
        if (closed) {
            return;
        }
        Entry e = entries.remove(key);
        if (e == null) {
            return;
        }
        if (session != null && session.entry == e) {
            deviceGone(session);
        }
        ui.onChanged();
    }

    // Unplugged, or the exporter lost it: Android has it back, or soon will
    private void deviceGone(Session s) {
        if (!live(s)) {
            return;
        }
        boolean wasShared = s.shared;
        stop(s, State.IDLE, null, UsbTunnel.END_UNPLUGGED);
        if (wasShared) {
            ui.showMessage(context.getString(R.string.usb_unplugged_toast, s.entry.name));
        }
    }

    // Ends the tunnel (with END endReason, if it got that far), stops the exporter and gives the
    // device back, off the main thread; then the device shows `end`
    private void stop(Session s, State end, String note, int endReason) {
        s.stopping = true;
        s.entry.note = note;
        set(s.entry, State.STOPPING);
        worker.execute(() -> {
            // Whatever either of them throws, the device is shown as back and another can be shared
            try {
                if (s.tunnel != null) {
                    s.tunnel.close(endReason != 0 ? endReason : UsbTunnel.END_CLOSING);
                }
            }
            catch (RuntimeException e) {
                LimeLog.warning("USB share: closing the tunnel failed: " + e);
            }
            synchronized (s) {
                s.over = true;
                try {
                    if (s.exporterStarted) {
                        s.exporter.stop();
                    }
                }
                catch (RuntimeException e) {
                    LimeLog.warning("USB share: stopping the exporter failed: " + e);
                }
            }
            main.execute(() -> {
                if (session == s) {
                    session = null;
                }
                s.entry.state = closed ? State.IDLE : end;
                ui.onChanged();
            });
        });
    }
}
