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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sharing one wired USB device with the PC during a stream, for the stream menu's USB devices
 * page: the devices plugged in with what the device rules say about each, and the share flow.
 *
 * Choosing a device asks Android's permission, asks you first for a keyboard or mouse, then
 * connects the tunnel. Once the PC's helper has proved it holds the pairing, the tunnel starts
 * the exporter, which applies the device rules to all the device's descriptors and exports it
 * from that same read (docs/dev/usb-protocol.md, "Attacks"). Your yes goes to the exporter, and
 * only a yes given for this share counts. If the exporter finds a keyboard or mouse the list
 * didn't show, nothing is shared and it asks you, then connects again. Choosing the device again
 * stops. Unplugging it, the stream ending or the PC ending the session gives it back to the
 * phone; a lost connection does too, and then the device offers to be shared again.
 *
 * One device at a time, as the contract has it, and a new share only once the last one's tunnel
 * has said it's over. Everything here runs on the main thread except the tunnel's connect, on
 * `worker`, and what the tunnel and exporter call on their own threads, whose answers come back
 * through `main`. Every answer is for one share, and is dropped once that share is over.
 */
public final class UsbShareController {
    public enum State {
        /** Not shared; the verdict, or a note on how the last share ended, shows */
        IDLE,
        /** Android's permission dialog is up */
        PERMISSION,
        /** Waiting for your yes to share a keyboard or mouse */
        CONFIRM,
        /** TLS, BIND, the device rules and SHARE with the PC's helper */
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
        void confirmInput(Entry device, Answer answer);

        /** The question asked for is moot now (unplugged, stopped, the stream ended): take it away. */
        void cancelConfirm();

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
        public final int vendorId;
        public final int productId;
        private UsbVerdict verdict;
        private State state = State.IDLE;
        private String note;

        Entry(String key, String name, int vendorId, int productId, UsbVerdict verdict) {
            this.key = key;
            this.name = name;
            this.vendorId = vendorId;
            this.productId = productId;
            this.verdict = verdict;
        }

        /** The rules' decision: the preview until the exporter has checked it, then the exporter's */
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
        // Set on the main thread before the tunnel can use them
        UsbDevice device;
        boolean confirmed;
        UsbShareTunnel tunnel;
        UsbShareExporter exporter;
        // Main thread only
        boolean shared;
        State end = State.IDLE;
        String note;

        // Guarded by this: whether connect() is running or has returned, and the END to send
        // once it returns if Stop came while it ran
        boolean stopping;
        boolean connecting;
        boolean connected;
        int closeWhenConnected;

        // The exporter's side, on the tunnel's threads
        volatile UsbVerdict refusal;
        final AtomicBoolean exported = new AtomicBoolean();
        final AtomicBoolean released = new AtomicBoolean();

        Session(Entry entry) {
            this.entry = entry;
        }
    }

    private final Context context;
    private final UsbDevices devices;
    private final UsbShareBackends.Factory backends;
    private final UsbShareTunnel.Target target;
    private final Ui ui;
    private final Executor main;
    private final Executor worker;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Session session;
    private boolean closed;

    public UsbShareController(Context context, UsbDevices devices, UsbShareBackends.Factory backends,
                              UsbShareTunnel.Target target, Ui ui, Executor main, Executor worker) {
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
                ui.showMessage(context.getString(R.string.usb_share_one_at_a_time, session.entry.name));
            }
            else if (!session.stopping) {
                // Stop, or give up on one still on its way
                stop(session, State.IDLE, null, UsbShareTunnel.END_STOPPED);
            }
            return;
        }
        if (e.verdict.isRefused()) {
            ui.showMessage(UsbShareText.detail(context, e));
            return;
        }
        begin(e);
    }

    /** The phone's network went away: the tunnel cuts the connection now, not after 8 s of silence. */
    public void networkLost() {
        Session s = session;
        if (s == null || !live(s)) {
            return;
        }
        boolean connected;
        synchronized (s) {
            connected = s.connected;
        }
        if (connected) {
            s.tunnel.networkLost();
        }
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
            entries.put(d.key, new Entry(d.key, UsbShareText.name(context, d), d.vendorId, d.productId,
                    UsbSharePreview.judge(d)));
        }
    }

    private boolean live(Session s) {
        return session == s && !s.stopping && !closed;
    }

    private void set(Entry e, State state) {
        e.state = state;
        ui.onChanged();
    }

    private void begin(Entry e) {
        Session s = new Session(e);
        session = s;
        e.note = null;
        set(e, State.PERMISSION);
        devices.requestPermission(e.key, result -> permissionAnswered(s, result));
    }

    private void permissionAnswered(Session s, int result) {
        if (!live(s)) {
            return;
        }
        s.device = result == UsbDevices.GRANTED ? devices.device(s.entry.key) : null;
        if (s.device == null) {
            int note = result == UsbDevices.BLOCKED ? R.string.error_usb_prohibited : R.string.usb_share_permission_denied;
            finish(s, State.IDLE, context.getString(note));
        }
        else if (s.entry.verdict.asks()) {
            set(s.entry, State.CONFIRM);
            ui.confirmInput(s.entry, yes -> confirmed(s, yes));
        }
        else {
            connect(s);
        }
    }

    private void confirmed(Session s, boolean yes) {
        // Only the first answer to this share's question counts
        if (!live(s) || s.entry.state != State.CONFIRM) {
            return;
        }
        if (yes) {
            s.confirmed = true;
            connect(s);
        }
        else {
            finish(s, State.IDLE, null);
        }
    }

    private void connect(Session s) {
        set(s.entry, State.CONNECTING);
        s.exporter = backends.exporter(context, () -> main.execute(() -> deviceGone(s)));
        s.tunnel = backends.tunnel(context, new UsbShareTunnel.Exporter() {
            @Override
            public byte[] start() throws IOException {
                return startExporter(s);
            }

            @Override
            public void stop() {
                releaseExporter(s);
            }
        }, new UsbShareTunnel.Listener() {
            @Override
            public void onSharing() {
                main.execute(() -> sharing(s));
            }

            @Override
            public void onEnded(UsbShareTunnel.Ending ending) {
                // The tunnel has stopped the exporter by now; if it somehow hasn't, this does
                if (releaseExporter(s)) {
                    LimeLog.warning("USB share: the tunnel ended without giving the device back");
                }
                main.execute(() -> ended(s, ending));
            }
        });
        // connect() reads the pairing, so it's off the main thread
        worker.execute(() -> {
            synchronized (s) {
                if (s.stopping) {
                    return;
                }
                s.connecting = true;
            }
            RuntimeException failure = null;
            try {
                s.tunnel.connect(target);
            }
            catch (RuntimeException e) {
                failure = e;
            }
            int closeNow;
            synchronized (s) {
                s.connecting = false;
                s.connected = failure == null;
                closeNow = s.connected ? s.closeWhenConnected : 0;
            }
            if (closeNow != 0) {
                s.tunnel.close(closeNow);
            }
            if (failure != null) {
                RuntimeException why = failure;
                main.execute(() -> connectFailed(s, why));
            }
        });
    }

    // On the tunnel's thread, once the helper has proved the pairing
    private byte[] startExporter(Session s) throws IOException {
        synchronized (s) {
            if (s.stopping) {
                throw new IOException("the share was stopped");
            }
        }
        try {
            byte[] share = s.exporter.start(s.device, s.confirmed).shareFrame();
            s.exported.set(true);
            return share;
        }
        catch (UsbShareExporter.Refused e) {
            s.refusal = e.verdict;
            throw new IOException("the device rules: " + e.verdict, e);
        }
        catch (RuntimeException e) {
            throw new IOException("the exporter failed", e);
        }
    }

    // Gives the device back to Android, once; true if this call did it
    private boolean releaseExporter(Session s) {
        if (!s.exported.get() || !s.released.compareAndSet(false, true)) {
            return false;
        }
        try {
            s.exporter.stop();
        }
        catch (RuntimeException e) {
            LimeLog.warning("USB share: stopping the exporter failed: " + e);
        }
        return true;
    }

    private void sharing(Session s) {
        if (!live(s) || s.entry.state != State.CONNECTING) {
            return;
        }
        s.shared = true;
        if (!s.confirmed) {
            // The exporter's check of all its descriptors let it through without a question
            s.entry.verdict = UsbVerdict.ALLOW;
        }
        set(s.entry, State.SHARED);
        ui.showMessage(context.getString(R.string.usb_share_shared_toast, s.entry.name));
    }

    private void connectFailed(Session s, RuntimeException why) {
        if (session != s) {
            return;
        }
        if (s.stopping || closed) {
            finish(s, s.end, s.note);
            return;
        }
        // Another share's tunnel not quite over, say
        LimeLog.warning("USB share: the tunnel didn't connect: " + why);
        String note = context.getString(R.string.usb_share_connect_failed);
        finish(s, State.IDLE, note);
        ui.showMessage(note);
    }

    private void ended(Session s, UsbShareTunnel.Ending ending) {
        if (session != s) {
            return;
        }
        if (s.stopping || closed) {
            // The end that was asked for
            finish(s, s.end, s.note);
            return;
        }
        Entry e = s.entry;
        UsbVerdict refusal = s.refusal;
        if (refusal != null && refusal.asks() && !s.confirmed) {
            // The exporter found a keyboard or mouse behind what the list showed. Nothing went to
            // the PC; from now on it asks, starting now.
            e.verdict = UsbVerdict.ASK_FIRST;
            finish(s, State.IDLE, null);
            begin(e);
            return;
        }
        if (refusal != null && refusal.isRefused()) {
            e.verdict = refusal;
            finish(s, State.IDLE, null);
            ui.showMessage(UsbShareText.verdict(context, refusal));
            return;
        }
        if (ending.canShareAgain() && s.shared) {
            finish(s, State.LOST, null);
            ui.showMessage(context.getString(R.string.usb_share_lost_toast, e.name));
            return;
        }
        String note = UsbShareText.ended(context, ending);
        finish(s, State.IDLE, note);
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
        stop(s, State.IDLE, null, UsbShareTunnel.END_UNPLUGGED);
        if (wasShared) {
            ui.showMessage(context.getString(R.string.usb_share_unplugged_toast, s.entry.name));
        }
    }

    // Ends the share: at once if the tunnel hasn't connected, else by asking the tunnel to end with
    // END endReason, and waiting for it to say it's over. Then the device shows `end`.
    private void stop(Session s, State end, String note, int endReason) {
        boolean closeNow;
        boolean waitForTunnel;
        synchronized (s) {
            s.stopping = true;
            closeNow = s.connected;
            if (s.connecting) {
                // connect() is running: the worker sends it once connect() returns
                s.closeWhenConnected = endReason;
            }
            waitForTunnel = s.connected || s.connecting;
        }
        s.end = end;
        s.note = note;
        if (s.entry.state == State.CONFIRM) {
            ui.cancelConfirm();
        }
        if (!waitForTunnel) {
            // Nothing reached the PC, and the exporter never started
            finish(s, end, note);
            return;
        }
        set(s.entry, State.STOPPING);
        if (closeNow) {
            try {
                s.tunnel.close(endReason);
            }
            catch (RuntimeException e) {
                LimeLog.warning("USB share: closing the tunnel failed: " + e);
            }
        }
    }

    // The share is over and the device is back on the phone: another can be shared
    private void finish(Session s, State end, String note) {
        if (session == s) {
            session = null;
        }
        s.entry.note = note;
        s.entry.state = closed ? State.IDLE : end;
        ui.onChanged();
    }
}
