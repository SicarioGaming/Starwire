package com.limelight;

import java.util.concurrent.Executor;

/**
 * Changes the bitrate without reconnecting (Moonlight V+ feature, Vibepollo's /bitrate), away
 * from the UI thread. The host may clamp the choice to its own maximum, so it answers with the
 * bitrate it applied, and that's the one shown.
 *
 * The latest pick wins. Picks go to the host one at a time, in the order they were made, so the
 * host ends on the latest one. A pick made while another is on its way waits, and replaces any
 * pick already waiting. Only the answer to the latest pick is shown, so an older answer that
 * comes back later can't overwrite a newer choice. (The idea is from Moonlight V+.)
 */
public class BitrateChanger {
    /** Asks the host for a bitrate. Returns the one it applied, in kbps. */
    public interface Host {
        int setStreamBitrate(int kbps) throws Exception;
    }

    /** What to tell the user. Called on the thread that asked the host. */
    public interface Listener {
        void onChanged(int appliedKbps);
        void onFailed(Exception e);
    }

    // No pick waiting. Every choice in the picker is above 0.
    private static final int NONE = 0;

    private final Host host;
    private final Executor executor;
    private final Listener listener;

    // The stream's bitrate now, in kbps: what the host last applied
    private volatile int currentKbps;

    private final Object lock = new Object();
    // The latest pick that hasn't gone to the host yet, or NONE
    private int waitingKbps = NONE;
    // Whether a thread is sending picks to the host
    private boolean sending;

    public BitrateChanger(int startKbps, Host host, Executor executor, Listener listener) {
        this.currentKbps = startKbps;
        this.host = host;
        this.executor = executor;
        this.listener = listener;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    /** Asks the host for this bitrate, in kbps, once any request on its way is answered. */
    public void request(int kbps) {
        synchronized (lock) {
            waitingKbps = kbps;
            if (sending) {
                // The thread that's sending takes it when the host answers
                return;
            }
            sending = true;
        }
        executor.execute(this::sendWaiting);
    }

    // Sends the waiting pick, then the one that came in meanwhile if any, until none is left
    private void sendWaiting() {
        try {
            int kbps;
            while ((kbps = takeWaiting()) != NONE) {
                send(kbps);
            }
        } catch (RuntimeException | Error e) {
            // So the next pick starts a new thread instead of waiting for this one forever
            synchronized (lock) {
                sending = false;
            }
            throw e;
        }
    }

    // The waiting pick, or NONE when there's nothing left to send and this thread is done
    private int takeWaiting() {
        synchronized (lock) {
            int kbps = waitingKbps;
            waitingKbps = NONE;
            if (kbps == NONE) {
                sending = false;
            }
            return kbps;
        }
    }

    private void send(int kbps) {
        int applied = 0;
        Exception failure = null;
        try {
            applied = host.setStreamBitrate(kbps);
            // One request at a time, so this is what the host streams at now
            currentKbps = applied;
        } catch (Exception e) {
            failure = e;
        }

        synchronized (lock) {
            if (waitingKbps != NONE) {
                // A newer pick goes next; only its answer is worth showing
                return;
            }
        }
        if (failure == null) {
            listener.onChanged(applied);
        } else {
            listener.onFailed(failure);
        }
    }
}
