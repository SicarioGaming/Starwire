package com.limelight.binding.usb.share;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.audio.securemic.MicPairingStore;
import com.limelight.binding.usb.UsbDeviceRules;
import com.limelight.binding.usb.UsbExporter;
import com.limelight.binding.usb.tunnel.UsbTunnel;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;

/**
 * The share flow's backends in the app: the USB/IP exporter with Starwire's device rules
 * (UsbExporter) and Starwire's end of the tunnel (UsbTunnel), with the calls passed on. The share
 * flow's interfaces were shaped like these two classes, so nothing here decides anything of its
 * own: the rules stay the exporter's, and the protocol stays the tunnel's.
 *
 * The microphone's pairing with the PC is read for each connection and its key wiped once the
 * tunnel has worked out its own from a copy, as UsbTunnel.connect asks.
 *
 * One UsbExporter serves every share, so its "one device at a time" holds across shares as the
 * tunnel's does. Each share only gives back its own device, and only hears about its own device
 * going away.
 */
final class RealUsbShareBackends implements UsbShareBackends.Factory {
    /** What it's built on: Android's exporter and the helper's port in the app, loopback stand-ins in the tests. */
    interface Parts {
        boolean isSupported();

        UsbExporter exporter(Context app, UsbExporter.Listener listener);

        UsbTunnel tunnel(UsbTunnel.Exporter exporter, UsbTunnel.Listener listener);

        /** The USB helper's port on the PC */
        int helperPort();

        /** The microphone's pairing with this PC, a new copy each time, or null */
        MicPairing pairing(Context app, String computerUuid);
    }

    /** The app's own parts. Not private, so RealUsbShareBackendsTest can check them. */
    static final class AppParts implements Parts {
        @Override
        public boolean isSupported() {
            return UsbExporter.isSupported();
        }

        @Override
        public UsbExporter exporter(Context app, UsbExporter.Listener listener) {
            return new UsbExporter(app, listener);
        }

        @Override
        public UsbTunnel tunnel(UsbTunnel.Exporter exporter, UsbTunnel.Listener listener) {
            return new UsbTunnel(exporter, listener);
        }

        @Override
        public int helperPort() {
            return UsbTunnel.DEFAULT_PORT;
        }

        @Override
        public MicPairing pairing(Context app, String computerUuid) {
            return new MicPairingStore(app).forComputer(computerUuid);
        }
    }

    private static RealUsbShareBackends app;

    /** The app's own, made the first time it's asked for. */
    static synchronized RealUsbShareBackends app() {
        if (app == null) {
            app = new RealUsbShareBackends(new AppParts());
        }
        return app;
    }

    private final Parts parts;
    // Made on the first share, with the application's context
    private UsbExporter exporter;
    // Guards owner. Held while a share starts, so the share owns its export before the exporter
    // can say that export's device went away.
    private final Object owners = new Object();
    private Share owner;

    RealUsbShareBackends(Parts parts) {
        this.parts = parts;
    }

    @Override
    public boolean isSupported() {
        return parts.isSupported();
    }

    @Override
    public UsbShareExporter exporter(Context context, UsbShareExporter.Listener listener) {
        return new Share(exporter(context), listener);
    }

    private synchronized UsbExporter exporter(Context context) {
        if (exporter == null) {
            exporter = parts.exporter(context.getApplicationContext(), this::deviceGone);
        }
        return exporter;
    }

    // The exporter's unplug watch, on its own thread, once the device is released: tell the share
    // it belonged to, if that share still holds it
    private void deviceGone(UsbExporter.Export export) {
        UsbShareExporter.Listener listener = null;
        synchronized (owners) {
            if (owner != null && owner.export == export) {
                listener = owner.listener;
            }
        }
        if (listener != null) {
            listener.onDeviceGone();
        }
    }

    /** The share flow's verdict for the exporter's refusal: ask, or refused for the rule. */
    static UsbVerdict verdict(UsbDeviceRules.Decision decision) {
        return decision.refused() ? UsbVerdict.refused(decision.rule) : UsbVerdict.ASK_FIRST;
    }

    /** One share's exporter: the app's UsbExporter, for this share's device only. */
    private final class Share implements UsbShareExporter {
        final UsbExporter exporter;
        final UsbShareExporter.Listener listener;
        // Set once start() has exported the device
        volatile UsbExporter.Export export;

        Share(UsbExporter exporter, UsbShareExporter.Listener listener) {
            this.exporter = exporter;
            this.listener = listener;
        }

        @Override
        public Export start(UsbDevice device, boolean inputConfirmed) throws Refused, IOException {
            synchronized (owners) {
                UsbExporter.Export e;
                try {
                    e = exporter.start(device, inputConfirmed);
                }
                catch (UsbExporter.Refused r) {
                    throw new Refused(verdict(r.decision));
                }
                export = e;
                owner = this;
                return e::shareFrame;
            }
        }

        @Override
        public Socket connect(int timeoutMs) throws IOException {
            UsbExporter.Export e = export;
            if (e == null) {
                throw new IOException("nothing is exported");
            }
            return exporter.connect(e, timeoutMs);
        }

        @Override
        public void stop() {
            UsbExporter.Export e = export;
            if (e == null) {
                return;
            }
            // Only this share's device. If it was unplugged it's back already, and the tunnel
            // never lets another share start before this one's stop() has returned.
            if (exporter.current() == e) {
                exporter.stop();
            }
            synchronized (owners) {
                if (owner == this) {
                    owner = null;
                }
            }
        }
    }

    @Override
    public UsbShareTunnel tunnel(Context context, UsbShareTunnel.Exporter exporter, UsbShareTunnel.Listener listener) {
        Context app = context.getApplicationContext();
        UsbTunnel real = parts.tunnel(new UsbTunnel.Exporter() {
            @Override
            public byte[] start() throws IOException {
                return exporter.start();
            }

            @Override
            public Socket connect(int timeoutMs) throws IOException {
                return exporter.connect(timeoutMs);
            }

            @Override
            public void stop() {
                exporter.stop();
            }
        }, new UsbTunnel.Listener() {
            @Override
            public void onSharing() {
                listener.onSharing();
            }

            @Override
            public void onEnded(UsbTunnel.Ending ending) {
                listener.onEnded(ending(ending));
            }
        });
        return new UsbShareTunnel() {
            @Override
            public void connect(Target target) {
                MicPairing pairing = parts.pairing(app, target.computerUuid);
                try {
                    real.connect(target.host, parts.helperPort(), pairing);
                }
                finally {
                    // The tunnel works out K_usb from its own copy; this one isn't needed again
                    if (pairing != null) {
                        Arrays.fill(pairing.key, (byte) 0);
                    }
                }
            }

            @Override
            public void close(int endReason) {
                real.close(endReason);
            }

            @Override
            public void networkLost() {
                real.networkLost();
            }
        };
    }

    /** The tunnel's ending as the share flow takes it, with what to show. */
    static UsbShareTunnel.Ending ending(UsbTunnel.Ending e) {
        return new UsbShareTunnel.Ending(e.kind, e.code, e.rule, e.messageId(), e.ruleId());
    }
}
