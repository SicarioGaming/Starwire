package com.limelight.binding.usb.share;

import java.io.IOException;

/**
 * What the share flow needs from the tunnel client (lane U-A, com.limelight.binding.usb.tunnel's
 * UsbTunnel): one connection to the PC's USB helper, as docs/dev/usb-protocol.md describes it.
 * TLS, HELLO and BIND first; then, once the helper's BIND_ACK checks out, the tunnel asks its
 * Exporter for the device and sends the SHARE that start() returns; then SHARE_ACK and the relay,
 * until either side ends it.
 *
 * It's shaped like U-A's UsbTunnel, so the build's backend only passes calls on: the same
 * Exporter and Listener, the same endings, and close() and networkLost() that only ask. The PC's
 * address and the microphone's pairing for it are the backend's to read; the exporter's port on
 * 127.0.0.1 is between the tunnel and the exporter, so the share flow never sees it.
 *
 * The tunnel owns the exporter from connect() on: it calls Exporter.start() at most once, and
 * Exporter.stop() once after it, however the connection ends, before Listener.onEnded().
 */
public interface UsbShareTunnel {
    // END reasons the share flow asks for (docs/dev/usb-protocol.md, "DATA, KEEPALIVE and END";
    // SwutCodec's END_*)
    int END_STOPPED = 1;
    int END_UNPLUGGED = 2;
    int END_STREAM_ENDED = 3;
    int END_CLOSING = 8;

    /** Which PC to share with: its address, and its id for the microphone's pairing store. */
    final class Target {
        public final String host;
        public final String computerUuid;

        public Target(String host, String computerUuid) {
            this.host = host;
            this.computerUuid = computerUuid;
        }
    }

    /** The device's side, which the tunnel starts and stops. Called on the tunnel's threads. */
    interface Exporter {
        /**
         * BIND_ACK checked out: give the device to the exporter and return its SHARE frame. Throwing
         * shares nothing: the tunnel sends END 2 and ends with DEVICE_GONE.
         */
        byte[] start() throws IOException;

        /** The connection is over: take the device back. Once, after start(), however it ended. */
        void stop();
    }

    /** Told how it's going, on the tunnel's threads. */
    interface Listener {
        /** SHARE_ACK with status 0: the PC took the device, and its traffic goes through the tunnel. */
        void onSharing();

        /** The connection ended, however it ended, close() included. Once for each connect(). */
        void onEnded(Ending ending);
    }

    /** How a connection ended, numbered as U-A's UsbTunnel.Ending numbers it. */
    final class Ending {
        /** No microphone pairing with this PC on the phone */
        public static final int NO_PAIRING = 1;
        /** The PC's address isn't a home-network or private VPN one */
        public static final int NOT_HOME_NETWORK = 2;
        /** Nothing answered at the PC's address and port */
        public static final int CANT_REACH = 3;
        /** The helper didn't answer in time, or closed before answering */
        public static final int NO_ANSWER = 4;
        /** TLS failed, or the helper's answer didn't check out */
        public static final int NOT_VERIFIED = 5;
        /** No version in common: the helper only speaks newer ones */
        public static final int UPDATE_STARWIRE = 6;
        /** No version in common: the helper only speaks older ones */
        public static final int UPDATE_HELPER = 7;
        /** The helper sent REFUSE; code is its reason. Anyone in the middle can send one: a hint only */
        public static final int REFUSED = 8;
        /** SHARE_ACK with a status other than 0, in code; rule for status 2 */
        public static final int NOT_SHARED = 9;
        /** The helper sent END; code is its reason, rule for reason 6 */
        public static final int ENDED_BY_PC = 10;
        /** close() ended it; code is the END reason asked for */
        public static final int STOPPED = 11;
        /** The connection dropped: an error, 8 s of silence, or networkLost(). It can be shared again */
        public static final int LOST = 12;
        /** The helper broke the protocol after BIND_ACK, or renegotiated TLS */
        public static final int PROTOCOL_ERROR = 13;
        /** Exporter.start() threw, or the exporter couldn't carry the device (the tunnel sent END 2) */
        public static final int DEVICE_GONE = 14;

        public final int kind;
        public final int code;
        public final int rule;
        /** What to show, from strings.xml: U-A's Ending.messageId() */
        public final int messageId;
        /** 0, or the text of the rule that refused the device, for messageId's %1$s: U-A's ruleId() */
        public final int ruleId;

        public Ending(int kind, int code, int rule, int messageId, int ruleId) {
            this.kind = kind;
            this.code = code;
            this.rule = rule;
            this.messageId = messageId;
            this.ruleId = ruleId;
        }

        /** Whether to offer "Share again": the connection dropped, nobody chose to end it */
        public boolean canShareAgain() {
            return kind == LOST;
        }

        @Override
        public String toString() {
            return "Ending(" + kind + ", " + code + ", " + rule + ")";
        }
    }

    /**
     * Starts a connection in the background. Blocks only to read the pairing, so not on the main
     * thread.
     *
     * @throws IllegalStateException while this or another tunnel is still connecting or sharing
     */
    void connect(Target target);

    /** Ends it from Starwire's side with this END reason. Only asks, so safe on any thread. */
    void close(int endReason);

    /** The phone's network went away: cut the connection at once, as LOST. Only asks. */
    void networkLost();
}
