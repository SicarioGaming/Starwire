package com.limelight.binding.usb.share;

import java.io.IOException;

/**
 * What the share flow needs from the tunnel client (lane U-A): one session with the PC's USB
 * helper, as docs/dev/usb-protocol.md describes it. TLS, HELLO and BIND first; then the share
 * flow starts the exporter; then SHARE, SHARE_ACK and the relay, until either side ends it.
 *
 * The numbers below are the contract's own (SwutCodec's REFUSE_*, SHARE_* and END_*).
 */
public interface UsbTunnel {
    // REFUSE reasons
    int REFUSE_NOT_PAIRED = 1;
    int REFUSE_NO_VERSION = 2;
    int REFUSE_MALFORMED = 3;
    int REFUSE_NOT_ALLOWED = 4;

    // SHARE_ACK statuses other than 0
    int SHARE_BUSY = 1;
    int SHARE_REFUSED = 2;
    int SHARE_NO_DRIVER = 3;

    // END reasons
    int END_STOPPED = 1;
    int END_UNPLUGGED = 2;
    int END_STREAM_ENDED = 3;
    int END_USB_OFF = 4;
    int END_UNPAIRED = 5;
    int END_RULES = 6;
    int END_ATTACH_FAILED = 7;
    int END_CLOSING = 8;
    int END_PROTOCOL = 9;

    // Problems Starwire finds itself, before anything is shared (Closed.FAILED)
    int FAILED_NO_PAIRING = 1;      // no microphone pairing with this PC on the phone
    int FAILED_NOT_HOME = 2;        // the PC's address isn't a home-network or private VPN one
    int FAILED_NO_ANSWER = 3;       // no connection, or no HELLO or BIND_ACK in time
    int FAILED_NOT_PROVEN = 4;      // the TLS handshake failed, or the BIND_ACK didn't check out
    int FAILED_APP_TOO_OLD = 5;     // no version in common: the helper only speaks newer ones
    int FAILED_HELPER_TOO_OLD = 6;  // no version in common: the helper only speaks older ones
    int FAILED_EXPORTER = 7;        // the exporter couldn't start (onBound threw)

    /** Which PC to share with: its address, and its id for the microphone's pairing store. */
    final class Target {
        public final String host;
        public final String computerUuid;

        public Target(String host, String computerUuid) {
            this.host = host;
            this.computerUuid = computerUuid;
        }
    }

    /** Why a session ended, as the contract numbers it. */
    final class Closed {
        /** The helper sent REFUSE: code is its reason. Only a hint, since anyone can send one. */
        public static final int REFUSED = 1;
        /** SHARE_ACK with a status other than 0: code is the status, detail the rule for 2. */
        public static final int NOT_SHARED = 2;
        /** The helper sent END: code is its reason, detail the rule for 6. */
        public static final int ENDED = 3;
        /** Nothing heard for 8 s, or the connection dropped without an END: a Wi-Fi drop. */
        public static final int LOST = 4;
        /** Starwire's own problem before anything was shared: code is a FAILED_* value. */
        public static final int FAILED = 5;

        public final int kind;
        public final int code;
        public final int detail;

        public Closed(int kind, int code, int detail) {
            this.kind = kind;
            this.code = code;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "Closed(" + kind + ", " + code + ", " + detail + ")";
        }
    }

    /** Called on the tunnel's own threads. */
    interface Listener {
        /**
         * The helper proved it holds the pairing (a BIND_ACK that checks out), so the exporter may
         * start now. Returns what SHARE describes. Throwing ends the session with END 8 and then
         * onClosed(FAILED, FAILED_EXPORTER).
         */
        UsbExporter.Export onBound() throws IOException;

        /** SHARE_ACK with status 0: the helper is attaching the device. */
        void onSharing();

        /** The session is over, whoever ended it, unless Starwire ended it with close(). Once. */
        void onClosed(Closed why);
    }

    /**
     * Starts a session in the background and returns at once. confirmedInput sets SHARE's flag:
     * you said yes to sharing this keyboard or mouse just now.
     */
    void connect(Target target, boolean confirmedInput, Listener listener);

    /**
     * Ends the session from Starwire's side: END with this reason if BIND_ACK has come, then TLS
     * is closed properly. No onClosed follows. Safe to call more than once, and before connect
     * has got anywhere.
     */
    void close(int endReason);
}
