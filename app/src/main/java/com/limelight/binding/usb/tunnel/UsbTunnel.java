package com.limelight.binding.usb.tunnel;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.audio.VbanSender;
import com.limelight.binding.audio.securemic.MicPairing;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Starwire's end of the USB tunnel, SWUT version 1 (docs/dev/usb-protocol.md). It connects to the
 * PC's USB helper over TLS, proves the microphone's pairing with BIND and checks the helper's
 * BIND_ACK. Only then does it ask the exporter for the device and describe it with SHARE. After a
 * SHARE_ACK with status 0 it carries the USB/IP stream, in DATA frames, between the helper and the
 * exporter on 127.0.0.1, until either side ends it.
 *
 * One device is shared at a time, in the whole app: connect() refuses a second tunnel while one
 * is connecting or sharing. After it ends, the same tunnel can connect again ("share again").
 *
 * Four threads of its own for each connection, none of them the caller's: "UsbTunnel" connects,
 * does the handshake and then reads from the helper; "UsbTunnelSend" writes every frame after
 * BIND_ACK; "UsbTunnelExport" reads from the exporter; "UsbTunnelWatch" keeps the time limits,
 * sends KEEPALIVE and handles close(). close() and networkLost() only ask, so they're safe on the
 * main thread. The clock, random numbers and certificates can be passed in, so the tests drive it
 * on loopback without waiting.
 */
public final class UsbTunnel {
    public static final int DEFAULT_PORT = 6981;

    // The contract's limits: 10 s for HELLO (from the start) and for each answer after it, a
    // KEEPALIVE after 1 s of saying nothing, and a connection that's said nothing for 8 s is gone
    static final long ANSWER_MS = 10_000;
    static final long KEEPALIVE_MS = 1_000;
    static final long SILENCE_MS = 8_000;
    // How long a proper close (END, then TLS's close) may take before the connection is just cut
    static final long CLOSE_MS = 2_000;
    static final long TICK_MS = 100;
    private static final int CONNECT_MS = (int) ANSWER_MS;
    private static final int EXPORTER_CONNECT_MS = 5_000;
    // Frames waiting to go to the helper: up to 32 DATA frames, 512 KiB
    private static final int QUEUED_FRAMES = 32;
    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    /** The device's side: the USB/IP exporter in the app. */
    public interface Exporter {
        /**
         * BIND_ACK checked out: give the device to the exporter and return its SHARE frame
         * (SwutCodec.share), with the bus id the exporter gave it. The device rules, the speed
         * check and any yes from the person come before connect(), not here. Null shares nothing.
         * Called once for each connection, on the tunnel's thread.
         */
        byte[] start() throws IOException;

        /** Let in exactly one connection, the one from 127.0.0.1:sourcePort. Called after start(). */
        void authorize(int sourcePort) throws IOException;

        /** The connection is over: take the device back. Called once, after start(), however it ended. */
        void stop();
    }

    /** Told how it's going, on the tunnel's threads. */
    public interface Listener {
        /** The PC took the device, and its traffic now goes through the tunnel. */
        void onSharing();

        /** The connection ended, however it ended. Called once for each connect(). */
        void onEnded(Ending ending);
    }

    /** How a connection ended, and what to tell the person. */
    public static final class Ending {
        /** No microphone pairing with this PC. */
        public static final int NO_PAIRING = 1;
        /** The PC's address isn't a home-network or private VPN address. */
        public static final int NOT_HOME_NETWORK = 2;
        /** Nothing answered at the PC's address and port. */
        public static final int CANT_REACH = 3;
        /** The helper didn't answer in time, or closed before answering. */
        public static final int NO_ANSWER = 4;
        /** TLS failed, or the helper's answer didn't check out: nothing was shared. */
        public static final int NOT_VERIFIED = 5;
        /** No version in common: the helper only speaks newer ones. */
        public static final int UPDATE_STARWIRE = 6;
        /** No version in common: the helper only speaks older ones. */
        public static final int UPDATE_HELPER = 7;
        /** The helper sent REFUSE; code is its reason. Anyone in the way can send one: a hint only. */
        public static final int REFUSED = 8;
        /** SHARE_ACK with a status other than 0, in code; rule for status 2. */
        public static final int NOT_SHARED = 9;
        /** The helper sent END; code is its reason, rule for reason 6. */
        public static final int ENDED_BY_PC = 10;
        /** close() ended it; code is the END reason asked for, sent only if it got as far as BIND_ACK. */
        public static final int STOPPED = 11;
        /** The connection dropped: an error, 8 s of silence, or networkLost(). It can be shared again. */
        public static final int LOST = 12;
        /** The helper broke the protocol after BIND_ACK (Starwire sent END 9), or renegotiated TLS. */
        public static final int PROTOCOL_ERROR = 13;
        /** The exporter couldn't start or carry the device (Starwire sent END 2). */
        public static final int DEVICE_GONE = 14;

        public final int kind;
        /** REFUSE's reason, SHARE_ACK's status, or the END reason sent or received; else 0. */
        public final int code;
        /** The device rule, for SHARE_ACK status 2 and END reason 6; else 0. */
        public final int rule;

        Ending(int kind, int code, int rule) {
            this.kind = kind;
            this.code = code;
            this.rule = rule;
        }

        static Ending of(int kind) {
            return new Ending(kind, 0, 0);
        }

        /** Whether to offer "Share again": the connection dropped, nobody chose to end it. */
        public boolean canShareAgain() {
            return kind == LOST;
        }

        /** What to show, from strings.xml. When ruleId() isn't 0, its text is this string's %1$s. */
        public int messageId() {
            switch (kind) {
                case NO_PAIRING:
                    return R.string.usb_tunnel_no_pairing;
                case NOT_HOME_NETWORK:
                    return R.string.usb_tunnel_not_home;
                case CANT_REACH:
                    return R.string.usb_tunnel_cant_reach;
                case NO_ANSWER:
                    return R.string.usb_tunnel_no_answer;
                case UPDATE_STARWIRE:
                    return R.string.usb_tunnel_update_starwire;
                case UPDATE_HELPER:
                    return R.string.usb_tunnel_update_helper;
                case REFUSED:
                    switch (code) {
                        case SwutCodec.REFUSE_NOT_PAIRED:
                            return R.string.usb_tunnel_refused_not_paired;
                        case SwutCodec.REFUSE_NO_VERSION:
                            return R.string.usb_tunnel_refused_version;
                        case SwutCodec.REFUSE_NOT_ALLOWED:
                            return R.string.usb_tunnel_refused_not_allowed;
                        default:
                            return R.string.usb_tunnel_refused;
                    }
                case NOT_SHARED:
                    switch (code) {
                        case SwutCodec.SHARE_BUSY:
                            return R.string.usb_tunnel_busy;
                        case SwutCodec.SHARE_REFUSED:
                            return R.string.usb_tunnel_device_refused;
                        case SwutCodec.SHARE_NO_DRIVER:
                            return R.string.usb_tunnel_no_driver;
                        default:
                            return R.string.usb_tunnel_not_shared;
                    }
                case ENDED_BY_PC:
                    switch (code) {
                        case SwutCodec.END_STOPPED:
                            return R.string.usb_tunnel_stopped_on_pc;
                        case SwutCodec.END_USB_OFF:
                            return R.string.usb_tunnel_usb_off;
                        case SwutCodec.END_UNPAIRED:
                            return R.string.usb_tunnel_unpaired;
                        case SwutCodec.END_RULES:
                            return R.string.usb_tunnel_rules;
                        case SwutCodec.END_ATTACH_FAILED:
                            return R.string.usb_tunnel_attach_failed;
                        case SwutCodec.END_CLOSING:
                            return R.string.usb_tunnel_helper_closing;
                        default:
                            return R.string.usb_tunnel_ended_by_pc;
                    }
                case STOPPED:
                    return R.string.usb_tunnel_stopped;
                case LOST:
                    return R.string.usb_tunnel_lost;
                case PROTOCOL_ERROR:
                    return R.string.usb_tunnel_protocol;
                case DEVICE_GONE:
                    return R.string.usb_tunnel_device_gone;
                case NOT_VERIFIED:
                default:
                    return R.string.usb_tunnel_not_verified;
            }
        }

        /** The text of the rule that refused the device, for messageId()'s %1$s; 0 if there's none. */
        public int ruleId() {
            boolean named = (kind == NOT_SHARED && code == SwutCodec.SHARE_REFUSED)
                    || (kind == ENDED_BY_PC && code == SwutCodec.END_RULES);
            return named ? ruleText(rule) : 0;
        }

        @Override
        public String toString() {
            return "Ending(" + kind + ", " + code + ", " + rule + ")";
        }
    }

    /** What each device rule (docs/dev/usb-protocol.md, "The device rules") refuses, to show. */
    public static int ruleText(int rule) {
        switch (rule) {
            case SwutCodec.RULE_HUB:
                return R.string.usb_rule_hub;
            case SwutCodec.RULE_STORAGE:
                return R.string.usb_rule_storage;
            case SwutCodec.RULE_NETWORK:
                return R.string.usb_rule_network;
            case SwutCodec.RULE_UNLISTED_VENDOR:
                return R.string.usb_rule_unlisted_vendor;
            case SwutCodec.RULE_UNCONFIRMED_INPUT:
                return R.string.usb_rule_unconfirmed_input;
            case SwutCodec.RULE_CONFIGURATIONS:
                return R.string.usb_rule_configurations;
            case SwutCodec.RULE_UNREADABLE:
                return R.string.usb_rule_unreadable;
            case SwutCodec.RULE_OTHER_CLASS:
                return R.string.usb_rule_other_class;
            case SwutCodec.RULE_MISMATCH:
                return R.string.usb_rule_mismatch;
            case SwutCodec.RULE_TOO_FAST:
                return R.string.usb_rule_too_fast;
            default:
                return R.string.usb_rule_unknown;
        }
    }

    interface Clock {
        long nowMs();
    }

    /** This side's certificate for one connection. */
    interface Identities {
        SwutTls.Identity next(SecureRandom random) throws GeneralSecurityException;
    }

    // The connection that's connecting or sharing, in the whole app
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();

    private static final int CONNECTING = 0;
    private static final int BOUND = 1;     // BIND_ACK checked out
    private static final int SHARING = 2;   // SHARE_ACK 0, the exporter connected
    // Put in the send queue to have the sender close: the END it sends first, if any, is the session's
    private static final byte[] CLOSE = new byte[0];

    private final Exporter exporter;
    private final Listener listener;
    private final Clock clock;
    private final SecureRandom random;
    private final Identities identities;
    private final long tickMs;

    private final Object lock = new Object();
    // Guarded by lock: the connection now, or the last one
    private Session session;

    public UsbTunnel(Exporter exporter, Listener listener) {
        this(exporter, listener, () -> System.nanoTime() / 1_000_000, new SecureRandom(), SwutTls::newIdentity, TICK_MS);
    }

    UsbTunnel(Exporter exporter, Listener listener, Clock clock, SecureRandom random, Identities identities,
              long tickMs) {
        if (exporter == null || listener == null) {
            throw new IllegalArgumentException("an exporter and a listener");
        }
        this.exporter = exporter;
        this.listener = listener;
        this.clock = clock;
        this.random = random;
        this.identities = identities;
        this.tickMs = tickMs;
    }

    // One connect()'s state. Fields without a note are guarded by the tunnel's lock.
    private static final class Session {
        final String host;
        final int port;
        final int localPort;
        // The session thread's own
        byte[] usbKey;
        byte[] deviceId;
        boolean tcpConnected;

        int phase = CONNECTING;
        long deadline;
        long lastHeard;
        long lastSent;
        boolean keepaliveQueued;
        Ending ending;
        byte[] lastFrame;     // the END the sender sends before it closes, or null
        long cutAt;           // when a proper close gives up and cuts the connection; 0 for none
        int closeRequest;
        boolean lostRequest;
        boolean finished;
        Socket plain;
        SSLSocket tls;
        Socket local;
        Thread sender;
        Thread pump;
        final BlockingQueue<byte[]> out = new ArrayBlockingQueue<>(QUEUED_FRAMES);

        // Guarded by exporterLock, so stop() never runs while start() still is
        final Object exporterLock = new Object();
        boolean exporterStarted;
        // Guarded by callbackLock, so onSharing() never comes after onEnded()
        final Object callbackLock = new Object();
        boolean told;

        Session(String host, int port, int localPort) {
            this.host = host;
            this.port = port;
            this.localPort = localPort;
        }
    }

    // How the session thread leaves the handshake: an ending, and the END to send if it's past BIND_ACK
    private static final class Ended extends Exception {
        final Ending ending;
        final byte[] end;

        Ended(Ending ending, byte[] end) {
            super(ending != null ? ending.toString() : "already ending");
            this.ending = ending;
            this.end = end;
        }

        Ended(int kind) {
            this(Ending.of(kind), null);
        }
    }

    // ---------------------------------------------------------------- the caller's side
    /** Shares a device with the helper on its default port, 6981. See connect(String, int, MicPairing, int). */
    public void connect(String host, MicPairing pairing, int localPort) {
        connect(host, DEFAULT_PORT, pairing, localPort);
    }

    /**
     * Starts sharing a device with the USB helper at host. The pairing is the microphone's with
     * this PC, read from MicPairingStore for this connection: K_usb is worked out from it here and
     * the copy of its key is wiped (the pairing itself isn't changed). Null means not paired, and
     * the listener hears so. localPort is the exporter's port on 127.0.0.1, where it may already
     * listen, but it mustn't have the device before Exporter.start().
     *
     * @throws IllegalStateException while this or another tunnel is connecting or sharing
     */
    public void connect(String host, int port, MicPairing pairing, int localPort) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535 || localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("a host, and ports from 1 to 65535");
        }
        Session s = new Session(host, port, localPort);
        if (pairing != null) {
            byte[] pairKey = pairing.key.clone();
            try {
                s.usbKey = SwutCodec.usbKey(pairKey, pairing.deviceId);
            }
            catch (GeneralSecurityException e) {
                throw new IllegalStateException("no HMAC-SHA256 on this phone", e);
            }
            finally {
                Arrays.fill(pairKey, (byte) 0);
            }
            s.deviceId = pairing.deviceId.clone();
        }
        synchronized (lock) {
            if (session != null && !session.finished) {
                wipe(s.usbKey);
                throw new IllegalStateException("this tunnel is still connected");
            }
            if (!ACTIVE.compareAndSet(null, s)) {
                wipe(s.usbKey);
                throw new IllegalStateException("another device is being shared");
            }
            s.deadline = clock.nowMs() + ANSWER_MS;
            session = s;
        }
        new Thread(() -> run(s), "UsbTunnel").start();
        new Thread(() -> watch(s), "UsbTunnelWatch").start();
    }

    /** Stops sharing, as the person chose to (END 1). Safe on any thread; the listener hears when it's done. */
    public void close() {
        close(SwutCodec.END_STOPPED);
    }

    /**
     * Stops sharing with this END reason: SwutCodec.END_UNPLUGGED when the device was unplugged,
     * END_STREAM_ENDED when the stream ended, END_CLOSING when the app is closing. Before
     * BIND_ACK there's no END: the connection is just closed. Safe on any thread.
     */
    public void close(int reason) {
        if (reason < SwutCodec.END_STOPPED || reason > SwutCodec.END_PROTOCOL) {
            throw new IllegalArgumentException("an END reason, 1 to 9");
        }
        synchronized (lock) {
            if (session != null && !session.finished && session.closeRequest == 0) {
                session.closeRequest = reason;
                lock.notifyAll();
            }
        }
    }

    /**
     * The network to the PC went away (Wi-Fi lost): the connection is cut at once, without an END,
     * and the listener hears it can be shared again. Safe on any thread.
     */
    public void networkLost() {
        synchronized (lock) {
            if (session != null && !session.finished) {
                session.lostRequest = true;
                lock.notifyAll();
            }
        }
    }

    /** Whether a connect() hasn't ended yet. */
    public boolean isConnected() {
        synchronized (lock) {
            return session != null && !session.finished;
        }
    }

    // ---------------------------------------------------------------- the session thread
    private void run(Session s) {
        try {
            handshake(s);
            relay(s);
        }
        catch (Ended e) {
            if (e.ending != null) {
                endOrCut(s, e.end, e.ending);
            }
            else {
                // Another thread chose the ending, maybe before this one held its newest
                // socket. Close that too, unless a proper close is still sending its END.
                boolean closing;
                synchronized (lock) {
                    closing = s.cutAt != 0 && !s.finished;
                }
                if (!closing) {
                    cut(s);
                }
            }
        }
        catch (IOException | GeneralSecurityException | RuntimeException e) {
            int phase;
            boolean connected;
            synchronized (lock) {
                if (s.ending != null && s.cutAt != 0) {
                    // A proper close is under way, which is why the read failed: the sender
                    // finishes it, or the watchdog cuts it at cutAt
                    return;
                }
                phase = s.phase;
                connected = s.tcpConnected;
            }
            Ending why;
            if (phase >= BOUND) {
                why = Ending.of(Ending.LOST);
            }
            else if (!connected) {
                why = Ending.of(Ending.CANT_REACH);
            }
            else if (e instanceof SSLException || e instanceof GeneralSecurityException) {
                why = Ending.of(Ending.NOT_VERIFIED);
            }
            else {
                why = Ending.of(Ending.NO_ANSWER);
            }
            if (choose(s, why)) {
                LimeLog.warning("USB tunnel: " + e);
            }
            cut(s);
            finish(s);
        }
        finally {
            wipe(s.usbKey);
        }
    }

    private void handshake(Session s) throws IOException, GeneralSecurityException, Ended {
        if (s.usbKey == null) {
            throw new Ended(Ending.NO_PAIRING);
        }
        InetAddress address = InetAddress.getByName(s.host);
        if (!VbanSender.isHomeNetworkAddress(address)) {
            throw new Ended(Ending.NOT_HOME_NETWORK);
        }
        SwutTls.Identity me = identities.next(random);
        Socket plain = new Socket();
        synchronized (lock) {
            s.plain = plain;
            if (s.ending != null) {
                throw new Ended(null, null);
            }
        }
        plain.setTcpNoDelay(true);
        plain.connect(new InetSocketAddress(address, s.port), CONNECT_MS);
        synchronized (lock) {
            s.tcpConnected = true;
        }

        // The address as numbers, so no name goes out in the clear as SNI
        SSLSocket tls = (SSLSocket) SwutTls.clientContext(me).getSocketFactory()
                .createSocket(plain, address.getHostAddress(), s.port, true);
        synchronized (lock) {
            s.tls = tls;
            if (s.ending != null) {
                throw new Ended(null, null);
            }
        }
        SwutTls.restrict(tls);
        // Starwire refuses renegotiation: BIND tied the session to the first handshake's certificates
        AtomicInteger handshakes = new AtomicInteger();
        tls.addHandshakeCompletedListener(event -> {
            if (handshakes.incrementAndGet() > 1) {
                LimeLog.warning("USB tunnel: the helper started a second TLS handshake");
                cutWith(s, Ending.of(Ending.PROTOCOL_ERROR));
            }
        });
        tls.startHandshake();
        SSLSession tlsSession = tls.getSession();
        SwutTls.checkSession(tlsSession);
        Certificate[] mine = tlsSession.getLocalCertificates();
        Certificate[] theirs = tlsSession.getPeerCertificates();
        if (mine == null || mine.length != 1 || theirs == null || theirs.length != 1) {
            throw new SSLException("each side must show exactly one certificate");
        }
        // Each fingerprint as this side saw it in its finished handshake
        byte[] phoneFp = SwutCodec.fingerprint(mine[0].getEncoded());
        byte[] helperFp = SwutCodec.fingerprint(theirs[0].getEncoded());
        InputStream in = tls.getInputStream();
        OutputStream out = tls.getOutputStream();

        byte[] hello = readFrame(in);
        int version = SwutCodec.chooseVersion(hello);
        if (version < 0) {
            throw new Ended(Ending.NOT_VERIFIED);
        }
        if (version == 0) {
            boolean helperNewer = (hello[SwutCodec.HELLO_LOWEST_AT] & 0xFF) > SwutCodec.HIGHEST_VERSION;
            throw new Ended(helperNewer ? Ending.UPDATE_STARWIRE : Ending.UPDATE_HELPER);
        }
        byte[] phoneNonce = new byte[SwutCodec.NONCE_LENGTH];
        random.nextBytes(phoneNonce);
        byte[] bind = SwutCodec.bind(s.usbKey, hello, phoneFp, helperFp, version, s.deviceId, phoneNonce);
        synchronized (lock) {
            s.deadline = clock.nowMs() + ANSWER_MS;
        }
        out.write(bind);
        out.flush();

        byte[] answer = readFrame(in);
        if (SwutCodec.typeOf(answer) == SwutCodec.TYPE_REFUSE) {
            int reason = SwutCodec.code(answer, SwutCodec.TYPE_REFUSE);
            throw reason > 0 ? new Ended(new Ending(Ending.REFUSED, reason, 0), null) : new Ended(Ending.NOT_VERIFIED);
        }
        if (!SwutCodec.bindAckChecksOut(s.usbKey, hello, phoneFp, helperFp, bind, answer)) {
            throw new Ended(Ending.NOT_VERIFIED);
        }
        // From here TLS protects the session, and the key isn't needed again
        wipe(s.usbKey);

        Thread sender = new Thread(() -> send(s), "UsbTunnelSend");
        synchronized (lock) {
            if (s.ending != null) {
                throw new Ended(null, null);
            }
            s.phase = BOUND;
            s.deadline = Long.MAX_VALUE;
            s.sender = sender;
        }
        sender.start();

        byte[] share;
        synchronized (s.exporterLock) {
            synchronized (lock) {
                if (s.ending != null) {
                    throw new Ended(null, null);
                }
            }
            s.exporterStarted = true;
            try {
                share = exporter.start();
            }
            catch (IOException | RuntimeException e) {
                LimeLog.warning("USB tunnel: the exporter couldn't take the device: " + e);
                share = null;
            }
        }
        if (share == null || SwutCodec.parseShare(share) == null) {
            throw new Ended(Ending.of(Ending.DEVICE_GONE), SwutCodec.end(SwutCodec.END_UNPLUGGED, 0));
        }
        synchronized (lock) {
            if (s.ending != null) {
                throw new Ended(null, null);
            }
            s.out.add(share);
            s.deadline = clock.nowMs() + ANSWER_MS;
        }

        byte[] ack = readFrame(in);
        heard(s);
        int type = SwutCodec.typeOf(ack);
        if (type == SwutCodec.TYPE_END) {
            throw endedByPc(ack);
        }
        int status = SwutCodec.code(ack, SwutCodec.TYPE_SHARE_ACK);
        if (status < 0) {
            throw protocolError();
        }
        if (status != SwutCodec.SHARE_ATTACHING) {
            // The helper closes the session itself; Starwire closes TLS properly too, with no END
            throw new Ended(new Ending(Ending.NOT_SHARED, status, SwutCodec.detail(ack, SwutCodec.TYPE_SHARE_ACK)), null);
        }

        // Every app on the phone can reach 127.0.0.1, so the exporter takes only the connection
        // from the source port it was told, bound here first so no other app can take it. This
        // is how Moonlight V+'s USB backend does it (qiin2333/moonlight-vplus, usbip-backend).
        Socket local = new Socket();
        synchronized (lock) {
            s.local = local;
            if (s.ending != null) {
                throw new Ended(null, null);
            }
        }
        InetAddress loopback = InetAddress.getByAddress(LOOPBACK);
        try {
            local.setTcpNoDelay(true);
            local.bind(new InetSocketAddress(loopback, 0));
            exporter.authorize(local.getLocalPort());
            local.connect(new InetSocketAddress(loopback, s.localPort), EXPORTER_CONNECT_MS);
        }
        catch (IOException | RuntimeException e) {
            LimeLog.warning("USB tunnel: couldn't connect to the exporter: " + e);
            throw new Ended(Ending.of(Ending.DEVICE_GONE), SwutCodec.end(SwutCodec.END_UNPLUGGED, 0));
        }
        InputStream fromExporter = local.getInputStream();
        Thread pump = new Thread(() -> pump(s, fromExporter), "UsbTunnelExport");
        synchronized (lock) {
            if (s.ending != null) {
                throw new Ended(null, null);
            }
            s.phase = SHARING;
            s.lastHeard = s.lastSent = clock.nowMs();
            s.pump = pump;
        }
        pump.start();
        synchronized (s.callbackLock) {
            if (!s.told) {
                listener.onSharing();
            }
        }
    }

    // The helper's frames, to the exporter, until the session ends
    private void relay(Session s) throws IOException, Ended {
        InputStream in;
        OutputStream toExporter;
        synchronized (lock) {
            in = s.tls.getInputStream();
            toExporter = s.local.getOutputStream();
        }
        while (true) {
            byte[] f = readFrame(in);
            heard(s);
            int type = SwutCodec.typeOf(f);
            if (type == SwutCodec.TYPE_DATA && f.length > SwutCodec.FRAME_HEADER) {
                try {
                    toExporter.write(f, SwutCodec.FRAME_HEADER, f.length - SwutCodec.FRAME_HEADER);
                }
                catch (IOException e) {
                    throw new Ended(Ending.of(Ending.DEVICE_GONE), SwutCodec.end(SwutCodec.END_UNPLUGGED, 0));
                }
            }
            else if (type == SwutCodec.TYPE_KEEPALIVE && f.length == SwutCodec.KEEPALIVE_LENGTH) {
                // Only to say it's there
            }
            else if (type == SwutCodec.TYPE_END) {
                throw endedByPc(f);
            }
            else {
                throw protocolError();
            }
        }
    }

    private static Ended endedByPc(byte[] end) {
        int reason = SwutCodec.code(end, SwutCodec.TYPE_END);
        if (reason < 0) {
            return protocolError();
        }
        return new Ended(new Ending(Ending.ENDED_BY_PC, reason, SwutCodec.detail(end, SwutCodec.TYPE_END)), null);
    }

    private static Ended protocolError() {
        return new Ended(Ending.of(Ending.PROTOCOL_ERROR), SwutCodec.end(SwutCodec.END_PROTOCOL, 0));
    }

    /** The next whole frame, or null if its header can't start one. EOFException at the end. */
    static byte[] readFrame(InputStream in) throws IOException {
        byte[] header = new byte[SwutCodec.FRAME_HEADER];
        readFully(in, header, 0, header.length);
        int length = SwutCodec.frameLength(header, 0);
        if (length < 0) {
            return null;
        }
        byte[] f = Arrays.copyOf(header, length);
        readFully(in, f, SwutCodec.FRAME_HEADER, length - SwutCodec.FRAME_HEADER);
        return f;
    }

    private static void readFully(InputStream in, byte[] b, int at, int length) throws IOException {
        while (length > 0) {
            int n = in.read(b, at, length);
            if (n < 0) {
                throw new EOFException("the helper closed the connection");
            }
            at += n;
            length -= n;
        }
    }

    private void heard(Session s) {
        synchronized (lock) {
            s.lastHeard = clock.nowMs();
        }
    }

    // ---------------------------------------------------------------- the other threads
    // Every frame after BIND_ACK goes out here, so a write the network holds up never holds up
    // the time limits
    private void send(Session s) {
        try {
            OutputStream out;
            synchronized (lock) {
                out = s.tls.getOutputStream();
            }
            while (true) {
                byte[] f = s.out.take();
                if (f == CLOSE) {
                    byte[] last;
                    synchronized (lock) {
                        last = s.lastFrame;
                    }
                    if (last != null) {
                        out.write(last);
                        out.flush();
                    }
                    // TLS's close_notify, then the connection
                    s.tls.close();
                    break;
                }
                // Sent from the moment it's handed to TLS: the next KEEPALIVE is due 1 s after that
                synchronized (lock) {
                    s.lastSent = clock.nowMs();
                    if (f[0] == SwutCodec.TYPE_KEEPALIVE) {
                        s.keepaliveQueued = false;
                    }
                }
                out.write(f);
                out.flush();
            }
        }
        catch (IOException | RuntimeException e) {
            choose(s, Ending.of(Ending.LOST));
        }
        catch (InterruptedException e) {
            // finish() is ending it
        }
        cut(s);
        finish(s);
    }

    // The exporter's bytes, to the helper in DATA frames
    private void pump(Session s, InputStream fromExporter) {
        byte[] buffer = new byte[SwutCodec.MAX_PAYLOAD];
        try {
            for (int n; (n = fromExporter.read(buffer)) > 0; ) {
                s.out.put(SwutCodec.data(buffer, 0, n));
            }
        }
        catch (IOException e) {
            // As if the exporter had closed
        }
        catch (InterruptedException e) {
            return;
        }
        endOrCut(s, SwutCodec.end(SwutCodec.END_UNPLUGGED, 0), Ending.of(Ending.DEVICE_GONE));
    }

    // The time limits, KEEPALIVE, and what close() and networkLost() asked for
    private void watch(Session s) {
        while (true) {
            Runnable act = null;
            synchronized (lock) {
                if (s.finished) {
                    return;
                }
                long now = clock.nowMs();
                if (s.ending == null) {
                    if (s.lostRequest) {
                        act = () -> cutWith(s, Ending.of(Ending.LOST));
                    }
                    else if (s.closeRequest != 0) {
                        int reason = s.closeRequest;
                        act = () -> endOrCut(s, SwutCodec.end(reason, 0), new Ending(Ending.STOPPED, reason, 0));
                    }
                    else if (s.phase < SHARING && now >= s.deadline) {
                        act = () -> endOrCut(s, SwutCodec.end(SwutCodec.END_PROTOCOL, 0), Ending.of(Ending.NO_ANSWER));
                    }
                    else if (s.phase == SHARING && now - s.lastHeard >= SILENCE_MS) {
                        // The helper isn't answering: close without an END, as it would
                        act = () -> cutWith(s, Ending.of(Ending.LOST));
                    }
                    else if (s.phase == SHARING && now - s.lastSent >= KEEPALIVE_MS && !s.keepaliveQueued
                            && s.out.offer(SwutCodec.keepalive())) {
                        s.keepaliveQueued = true;
                    }
                }
                else if (s.cutAt != 0 && now >= s.cutAt) {
                    act = () -> {
                        cut(s);
                        finish(s);
                    };
                }
                if (act == null) {
                    try {
                        lock.wait(tickMs);
                    }
                    catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (act != null) {
                act.run();
            }
        }
    }

    // ---------------------------------------------------------------- ending
    // The first ending chosen is the one the listener hears. True if this was it.
    private boolean choose(Session s, Ending ending) {
        synchronized (lock) {
            if (s.ending != null) {
                return false;
            }
            s.ending = ending;
            lock.notifyAll();
            return true;
        }
    }

    // Ends properly once past BIND_ACK: the sender sends this END (if any) and closes TLS, and
    // the connection is cut if that takes longer than CLOSE_MS. Before BIND_ACK, just cut.
    private void endOrCut(Session s, byte[] end, Ending ending) {
        synchronized (lock) {
            if (s.ending != null) {
                return;
            }
            if (s.phase >= BOUND) {
                s.ending = ending;
                s.lastFrame = end;
                s.cutAt = clock.nowMs() + CLOSE_MS;
                s.out.clear();
                if (!s.out.offer(CLOSE)) {
                    // Only if the exporter's reader refilled the queue at once: cut at the next tick
                    s.cutAt = clock.nowMs();
                }
                lock.notifyAll();
                return;
            }
        }
        cutWith(s, ending);
    }

    // Ends at once, without an END
    private void cutWith(Session s, Ending ending) {
        choose(s, ending);
        cut(s);
        finish(s);
    }

    // Closes the connections without another word, so every thread blocked on one stops
    private void cut(Session s) {
        Socket plain;
        SSLSocket tls;
        Socket local;
        synchronized (lock) {
            plain = s.plain;
            tls = s.tls;
            local = s.local;
        }
        closeQuietly(plain);
        closeQuietly(local);
        // Its connection is already closed, so this only frees what TLS holds
        closeQuietly(tls);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException e) {
                // Closed either way
            }
        }
    }

    private void finish(Session s) {
        Ending ending;
        Thread sender;
        Thread pump;
        synchronized (lock) {
            if (s.finished) {
                return;
            }
            if (s.ending == null) {
                s.ending = Ending.of(Ending.LOST);
            }
            s.finished = true;
            ending = s.ending;
            sender = s.sender;
            pump = s.pump;
            ACTIVE.compareAndSet(s, null);
            lock.notifyAll();
        }
        Thread self = Thread.currentThread();
        if (sender != null && sender != self) {
            sender.interrupt();
        }
        if (pump != null && pump != self) {
            pump.interrupt();
        }
        synchronized (s.exporterLock) {
            if (s.exporterStarted) {
                try {
                    exporter.stop();
                }
                catch (RuntimeException e) {
                    LimeLog.warning("USB tunnel: the exporter didn't stop cleanly: " + e);
                }
            }
        }
        LimeLog.info("USB tunnel ended: " + ending);
        synchronized (s.callbackLock) {
            s.told = true;
            try {
                listener.onEnded(ending);
            }
            catch (RuntimeException e) {
                LimeLog.warning("USB tunnel: the listener failed: " + e);
            }
        }
    }

    private static void wipe(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }
}
