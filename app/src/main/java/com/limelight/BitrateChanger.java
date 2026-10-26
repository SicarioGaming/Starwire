package com.limelight;

import java.util.concurrent.Executor;

/**
 * Changes the bitrate without reconnecting (Moonlight V+ feature, Vibepollo's /bitrate), away
 * from the UI thread. The host may clamp the choice to its own maximum, so it answers with the
 * bitrate it applied, and that's the one shown.
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

    private final Host host;
    private final Executor executor;
    private final Listener listener;

    // The stream's bitrate now, in kbps, as far as Starwire knows
    private volatile int currentKbps;

    public BitrateChanger(int startKbps, Host host, Executor executor, Listener listener) {
        this.currentKbps = startKbps;
        this.host = host;
        this.executor = executor;
        this.listener = listener;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    /** Asks the host for this bitrate, in kbps. */
    public void request(int kbps) {
        executor.execute(() -> {
            try {
                int applied = host.setStreamBitrate(kbps);
                currentKbps = applied;
                listener.onChanged(applied);
            } catch (Exception e) {
                listener.onFailed(e);
            }
        });
    }
}
