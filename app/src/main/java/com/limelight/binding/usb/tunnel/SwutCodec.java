package com.limelight.binding.usb.tunnel;

import com.limelight.binding.audio.securemic.SwmcCodec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The USB tunnel's own frames, "SWUT" version 1, exactly as docs/dev/usb-protocol.md describes
 * them: the key that comes from the microphone's pairing, and the BIND and BIND_ACK MACs that tie
 * the TLS session's two certificates to that pairing. The PC's USB helper is to implement the
 * same, and both are tested against app/src/test/resources/swut-v1-vectors.txt.
 *
 * No TLS, sockets or USB/IP here: the tunnel is built on top of this. Frames are whole byte
 * arrays, header included, since the MACs cover whole frames. Parsing never throws on what
 * arrives from the network: it returns -1, false or null instead.
 */
public final class SwutCodec {
    public static final int VERSION = 1;
    // The versions this side speaks
    public static final int LOWEST_VERSION = 1;
    public static final int HIGHEST_VERSION = 1;

    public static final int TYPE_HELLO = 1;
    public static final int TYPE_BIND = 2;
    public static final int TYPE_BIND_ACK = 3;
    public static final int TYPE_REFUSE = 4;
    public static final int TYPE_SHARE = 5;
    public static final int TYPE_SHARE_ACK = 6;
    public static final int TYPE_DATA = 7;
    public static final int TYPE_KEEPALIVE = 8;
    public static final int TYPE_END = 9;

    // Type 1, reserved 1, payload length u16
    public static final int FRAME_HEADER = 4;
    public static final int MAX_PAYLOAD = 16384;
    public static final int ID_LENGTH = 8;
    public static final int NONCE_LENGTH = 16;
    public static final int MAC_LENGTH = SwmcCodec.MAC_LENGTH;
    public static final int KEY_LENGTH = 32;
    public static final int FINGERPRINT_LENGTH = 32;
    public static final int HELLO_LENGTH = FRAME_HEADER + 24;                  // 28
    public static final int BIND_LENGTH = FRAME_HEADER + 4 + ID_LENGTH + NONCE_LENGTH + MAC_LENGTH;   // 48
    public static final int BIND_ACK_LENGTH = FRAME_HEADER + MAC_LENGTH;       // 20
    public static final int SHORT_LENGTH = FRAME_HEADER + 4;                   // REFUSE, SHARE_ACK, END
    public static final int KEEPALIVE_LENGTH = FRAME_HEADER;

    // Where fields are, in whole frames
    static final int HELLO_LOWEST_AT = 8;
    static final int HELLO_HIGHEST_AT = 9;
    static final int HELLO_NONCE_AT = 12;
    static final int BIND_VERSION_AT = 4;
    static final int BIND_DEVICE_ID_AT = 8;
    static final int BIND_NONCE_AT = 16;
    static final int BIND_MAC_AT = 32;

    public static final int REFUSE_NOT_PAIRED = 1;
    public static final int REFUSE_NO_VERSION = 2;
    public static final int REFUSE_MALFORMED = 3;

    public static final int SHARE_ATTACHING = 0;
    public static final int SHARE_BUSY = 1;
    public static final int SHARE_REFUSED = 2;
    public static final int SHARE_NO_DRIVER = 3;

    public static final int END_STOPPED = 1;
    public static final int END_UNPLUGGED = 2;
    public static final int END_STREAM_ENDED = 3;
    public static final int END_USB_OFF = 4;
    public static final int END_UNPAIRED = 5;
    public static final int END_RULES = 6;
    public static final int END_ATTACH_FAILED = 7;
    public static final int END_CLOSING = 8;
    public static final int END_PROTOCOL = 9;

    // The device rules (docs/dev/usb-protocol.md), as SHARE_ACK's and END's detail
    public static final int RULE_HUB = 1;
    public static final int RULE_STORAGE = 2;
    public static final int RULE_NETWORK = 3;
    public static final int RULE_UNLISTED_VENDOR = 4;
    public static final int RULE_UNCONFIRMED_INPUT = 5;
    public static final int RULE_CONFIGURATIONS = 6;
    public static final int RULE_UNREADABLE = 7;
    public static final int RULE_OTHER_CLASS = 8;
    public static final int RULE_MISMATCH = 9;

    // SHARE
    public static final int FLAG_CONFIRMED_INPUT = 1;
    public static final int MAX_BUS_ID = 31;
    public static final int MAX_NAME_BYTES = SwmcCodec.MAX_NAME_BYTES;
    public static final int DEVICE_DESCRIPTOR = 18;
    public static final int MIN_CONFIGURATION = 9;
    public static final int MAX_CONFIGURATION = 4096;
    public static final int MAX_REPORTS = 16;
    public static final int MAX_REPORT = 4096;

    private static final byte[] MAGIC = ascii("SWUT");
    private static final byte[] LABEL_AUTH = ascii("starwire-usb/1 auth");
    private static final byte[] LABEL_BIND = ascii("starwire-usb/1 bind");
    private static final byte[] LABEL_BIND_ACK = ascii("starwire-usb/1 bind ack");

    private SwutCodec() {
    }

    // ---------------------------------------------------------------- bytes
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    static int getU16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static void putU16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    private static byte[] frame(int type, int payloadLength) {
        byte[] f = new byte[FRAME_HEADER + payloadLength];
        f[0] = (byte) type;
        putU16(f, 2, payloadLength);
        return f;
    }

    // ---------------------------------------------------------------- keys
    /** K_usb = HKDF(no salt, K_pair, "starwire-usb/1 auth" || deviceId). It isn't the mic's K_auth. */
    public static byte[] usbKey(byte[] pairKey, byte[] deviceId) throws GeneralSecurityException {
        if (pairKey == null || pairKey.length != KEY_LENGTH || deviceId == null || deviceId.length != ID_LENGTH) {
            throw new IllegalArgumentException("a 32-byte pairing key and an 8-byte device id");
        }
        return SwmcCodec.hkdf(null, pairKey, concat(LABEL_AUTH, deviceId), KEY_LENGTH);
    }

    /** A certificate's fingerprint: SHA-256 of its DER bytes, as TLS sent them. */
    public static byte[] fingerprint(byte[] certificate) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(certificate);
    }

    // ---------------------------------------------------------------- frames
    /**
     * The frame's type if its header is a version 1 frame header that matches the frame's length
     * (a known type, a zero reserved byte, a payload of at most 16384 bytes), else -1.
     */
    public static int typeOf(byte[] f) {
        if (f == null || f.length < FRAME_HEADER) {
            return -1;
        }
        int length = frameLength(f, 0);
        return length == f.length ? f[0] & 0xFF : -1;
    }

    /**
     * The length of the whole frame that starts with these 4 header bytes, for reading frames from
     * a stream, or -1 if they can't start a version 1 frame. The payload's own checks come later.
     */
    public static int frameLength(byte[] header, int at) {
        if (header == null || at < 0 || header.length - at < FRAME_HEADER) {
            return -1;
        }
        int type = header[at] & 0xFF;
        int length = getU16(header, at + 2);
        if (type < TYPE_HELLO || type > TYPE_END || header[at + 1] != 0 || length > MAX_PAYLOAD) {
            return -1;
        }
        return FRAME_HEADER + length;
    }

    private static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- HELLO
    public static byte[] hello(byte[] helperNonce, int lowest, int highest) {
        if (helperNonce.length != NONCE_LENGTH || lowest < 1 || lowest > highest || highest > 255) {
            throw new IllegalArgumentException("a 16-byte nonce and versions 1 to 255, lowest first");
        }
        byte[] f = frame(TYPE_HELLO, HELLO_LENGTH - FRAME_HEADER);
        System.arraycopy(MAGIC, 0, f, FRAME_HEADER, MAGIC.length);
        f[HELLO_LOWEST_AT] = (byte) lowest;
        f[HELLO_HIGHEST_AT] = (byte) highest;
        System.arraycopy(helperNonce, 0, f, HELLO_NONCE_AT, NONCE_LENGTH);
        return f;
    }

    /** Whether this is a well-formed HELLO: 28 bytes, the magic, versions 1 to 255 lowest first. */
    public static boolean isHello(byte[] f) {
        return typeOf(f) == TYPE_HELLO && f.length == HELLO_LENGTH
                && SwmcCodec.constantTimeEquals(f, FRAME_HEADER, MAGIC, 0, MAGIC.length)
                && f[HELLO_LOWEST_AT] != 0 && (f[HELLO_LOWEST_AT] & 0xFF) <= (f[HELLO_HIGHEST_AT] & 0xFF)
                && zero(f, HELLO_HIGHEST_AT + 1, HELLO_NONCE_AT);
    }

    /**
     * The highest version both this side and the HELLO's sender speak; 0 if they share none, -1 if
     * it isn't a well-formed HELLO.
     */
    public static int chooseVersion(byte[] hello) {
        if (!isHello(hello)) {
            return -1;
        }
        int highest = Math.min(HIGHEST_VERSION, hello[HELLO_HIGHEST_AT] & 0xFF);
        return highest >= Math.max(LOWEST_VERSION, hello[HELLO_LOWEST_AT] & 0xFF) ? highest : 0;
    }

    private static boolean offers(byte[] hello, int version) {
        return version >= (hello[HELLO_LOWEST_AT] & 0xFF) && version <= (hello[HELLO_HIGHEST_AT] & 0xFF);
    }

    // ---------------------------------------------------------------- BIND and BIND_ACK
    private static boolean fingerprintsOk(byte[] phoneFingerprint, byte[] helperFingerprint) {
        return phoneFingerprint != null && phoneFingerprint.length == FINGERPRINT_LENGTH
                && helperFingerprint != null && helperFingerprint.length == FINGERPRINT_LENGTH;
    }

    private static byte[] bindMac(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                                  byte[] bind) throws GeneralSecurityException {
        byte[] input = concat(LABEL_BIND, hello, phoneFingerprint, helperFingerprint, Arrays.copyOf(bind, BIND_MAC_AT));
        return SwmcCodec.mac16(usbKey, input, 0, input.length);
    }

    private static byte[] ackMac(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                                 byte[] bind, byte[] ack) throws GeneralSecurityException {
        byte[] input = concat(LABEL_BIND_ACK, hello, phoneFingerprint, helperFingerprint, bind,
                Arrays.copyOf(ack, FRAME_HEADER));
        return SwmcCodec.mac16(usbKey, input, 0, input.length);
    }

    /**
     * Starwire's BIND for this HELLO and these two certificates (each fingerprint as this side saw
     * it in its finished TLS handshake: Starwire's own first, then the helper's).
     */
    public static byte[] bind(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                              int version, byte[] deviceId, byte[] phoneNonce) throws GeneralSecurityException {
        if (!isHello(hello) || !offers(hello, version) || !fingerprintsOk(phoneFingerprint, helperFingerprint)
                || deviceId.length != ID_LENGTH || phoneNonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException("a HELLO that offers the version, 32-byte fingerprints, an 8-byte id, a 16-byte nonce");
        }
        byte[] f = frame(TYPE_BIND, BIND_LENGTH - FRAME_HEADER);
        f[BIND_VERSION_AT] = (byte) version;
        System.arraycopy(deviceId, 0, f, BIND_DEVICE_ID_AT, ID_LENGTH);
        System.arraycopy(phoneNonce, 0, f, BIND_NONCE_AT, NONCE_LENGTH);
        System.arraycopy(bindMac(usbKey, hello, phoneFingerprint, helperFingerprint, f), 0, f, BIND_MAC_AT, MAC_LENGTH);
        return f;
    }

    private static boolean isBind(byte[] f) {
        return typeOf(f) == TYPE_BIND && f.length == BIND_LENGTH && f[BIND_VERSION_AT] != 0
                && zero(f, BIND_VERSION_AT + 1, BIND_DEVICE_ID_AT);
    }

    /**
     * The version a BIND frame asks for: its byte 4, where every version keeps it, whatever the
     * rest of the frame looks like. -1 if it isn't a BIND frame with a payload. The helper reads
     * it before it knows BIND's layout (see bindRefusal).
     */
    public static int bindVersion(byte[] bind) {
        return typeOf(bind) == TYPE_BIND && bind.length > BIND_VERSION_AT ? bind[BIND_VERSION_AT] & 0xFF : -1;
    }

    /**
     * The helper's answer to the frame that came where BIND should, before it looks for a pairing:
     * REFUSE_NO_VERSION if it's a BIND for a version this HELLO didn't offer, whatever its layout;
     * else REFUSE_MALFORMED if it isn't a well-formed BIND (another type of frame included); else 0,
     * and the pairing and the MAC come next (bindChecksOut, REFUSE_NOT_PAIRED).
     */
    public static int bindRefusal(byte[] hello, byte[] frame) {
        if (!isHello(hello)) {
            throw new IllegalArgumentException("the helper's own HELLO");
        }
        int version = bindVersion(frame);
        if (version < 0) {
            return REFUSE_MALFORMED;
        }
        if (!offers(hello, version)) {
            return REFUSE_NO_VERSION;
        }
        return isBind(frame) ? 0 : REFUSE_MALFORMED;
    }

    /** The device id in a well-formed BIND, to find its pairing, or null. */
    public static byte[] bindDeviceId(byte[] bind) {
        return isBind(bind) ? Arrays.copyOfRange(bind, BIND_DEVICE_ID_AT, BIND_DEVICE_ID_AT + ID_LENGTH) : null;
    }

    /**
     * Whether a BIND is well formed, asks for a version this HELLO offered, and carries the MAC that
     * K_usb gives over this HELLO and these two fingerprints. The helper's check.
     */
    public static boolean bindChecksOut(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                                        byte[] bind) throws GeneralSecurityException {
        if (!isBind(bind) || !isHello(hello) || !offers(hello, bind[BIND_VERSION_AT] & 0xFF)
                || !fingerprintsOk(phoneFingerprint, helperFingerprint)) {
            return false;
        }
        byte[] mac = bindMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind);
        return SwmcCodec.constantTimeEquals(mac, 0, bind, BIND_MAC_AT, MAC_LENGTH);
    }

    /** The helper's BIND_ACK for a BIND that checked out. */
    public static byte[] bindAck(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                                 byte[] bind) throws GeneralSecurityException {
        if (!isBind(bind) || !isHello(hello) || !fingerprintsOk(phoneFingerprint, helperFingerprint)) {
            throw new IllegalArgumentException("a well-formed HELLO and BIND and 32-byte fingerprints");
        }
        byte[] f = frame(TYPE_BIND_ACK, MAC_LENGTH);
        System.arraycopy(ackMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind, f), 0, f, FRAME_HEADER, MAC_LENGTH);
        return f;
    }

    /** Whether a BIND_ACK answers exactly this BIND, in this session, from someone who has K_usb. Starwire's check. */
    public static boolean bindAckChecksOut(byte[] usbKey, byte[] hello, byte[] phoneFingerprint, byte[] helperFingerprint,
                                           byte[] bind, byte[] ack) throws GeneralSecurityException {
        if (typeOf(ack) != TYPE_BIND_ACK || ack.length != BIND_ACK_LENGTH || !isBind(bind) || !isHello(hello)
                || !fingerprintsOk(phoneFingerprint, helperFingerprint)) {
            return false;
        }
        byte[] mac = ackMac(usbKey, hello, phoneFingerprint, helperFingerprint, bind, ack);
        return SwmcCodec.constantTimeEquals(mac, 0, ack, FRAME_HEADER, MAC_LENGTH);
    }

    // ---------------------------------------------------------------- REFUSE, SHARE_ACK, END, KEEPALIVE, DATA
    private static byte[] shortFrame(int type, int code, int detail) {
        if (code < 0 || code > 255 || detail < 0 || detail > 255) {
            throw new IllegalArgumentException("a code and a detail of one byte each");
        }
        byte[] f = frame(type, SHORT_LENGTH - FRAME_HEADER);
        f[FRAME_HEADER] = (byte) code;
        f[FRAME_HEADER + 1] = (byte) detail;
        return f;
    }

    public static byte[] refuse(int reason) {
        return shortFrame(TYPE_REFUSE, reason, 0);
    }

    public static byte[] shareAck(int status, int detail) {
        return shortFrame(TYPE_SHARE_ACK, status, detail);
    }

    public static byte[] end(int reason, int detail) {
        return shortFrame(TYPE_END, reason, detail);
    }

    /** The reason or status in a well-formed REFUSE, SHARE_ACK or END of this type, or -1. */
    public static int code(byte[] f, int type) {
        return isShort(f, type) ? f[FRAME_HEADER] & 0xFF : -1;
    }

    /** The detail (the rule, for a refused device) in a well-formed SHARE_ACK or END of this type, or -1. */
    public static int detail(byte[] f, int type) {
        return isShort(f, type) ? f[FRAME_HEADER + 1] & 0xFF : -1;
    }

    private static boolean isShort(byte[] f, int type) {
        return (type == TYPE_REFUSE || type == TYPE_SHARE_ACK || type == TYPE_END) && typeOf(f) == type
                && f.length == SHORT_LENGTH && zero(f, FRAME_HEADER + 2, SHORT_LENGTH)
                && (type != TYPE_REFUSE || f[FRAME_HEADER + 1] == 0);
    }

    public static byte[] keepalive() {
        return frame(TYPE_KEEPALIVE, 0);
    }

    /** A DATA frame carrying the next 1 to 16384 bytes of the USB/IP stream. */
    public static byte[] data(byte[] b, int at, int length) {
        if (length < 1 || length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("1 to 16384 bytes");
        }
        byte[] f = frame(TYPE_DATA, length);
        System.arraycopy(b, at, f, FRAME_HEADER, length);
        return f;
    }

    // ---------------------------------------------------------------- SHARE
    /** What SHARE says about the device Starwire wants to share. */
    public static final class Share {
        public final int flags;
        public final String busId;
        public final byte[] name;                 // UTF-8, at most 64 bytes; clean it before showing it
        public final byte[] deviceDescriptor;     // 18 bytes
        public final byte[] configuration;        // the whole configuration descriptor
        public final int[] reportInterfaces;      // the interface each HID report descriptor is for
        public final byte[][] reports;

        public Share(int flags, String busId, byte[] name, byte[] deviceDescriptor, byte[] configuration,
                     int[] reportInterfaces, byte[][] reports) {
            this.flags = flags;
            this.busId = busId;
            this.name = name;
            this.deviceDescriptor = deviceDescriptor;
            this.configuration = configuration;
            this.reportInterfaces = reportInterfaces;
            this.reports = reports;
        }

        /** Shown to the person at the PC, after SwmcCodec.cleanName. */
        public String nameText() {
            return new String(name, StandardCharsets.UTF_8);
        }
    }

    // Digits, letters and . _ : - only, as in usbipdcpp's 1-1.2 or 1-3:1. The bus id comes from a
    // phone that may be hostile, and the helper may hand it to a command, where " & | % ^ < > and
    // the like mean something.
    private static boolean busIdOk(byte[] b, int at, int length) {
        if (length < 1 || length > MAX_BUS_ID) {
            return false;
        }
        for (int i = at; i < at + length; i++) {
            int c = b[i];
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '.' || c == '_' || c == ':' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean descriptorsOk(byte[] device, int deviceAt, byte[] config, int configAt, int configLength) {
        return (device[deviceAt] & 0xFF) == DEVICE_DESCRIPTOR && device[deviceAt + 1] == 1
                && configLength >= MIN_CONFIGURATION && configLength <= MAX_CONFIGURATION
                && (config[configAt] & 0xFF) == MIN_CONFIGURATION && config[configAt + 1] == 2
                && getU16(config, configAt + 2) == configLength;
    }

    /** Starwire's SHARE. The name is cut to 64 bytes of UTF-8 without splitting a character. */
    public static byte[] share(int flags, String busId, String name, byte[] deviceDescriptor, byte[] configuration,
                               int[] reportInterfaces, byte[][] reports) {
        byte[] bus = busId == null ? new byte[0] : busId.getBytes(StandardCharsets.US_ASCII);
        byte[] nameBytes = SwmcCodec.nameBytes(name);
        if ((flags & ~FLAG_CONFIRMED_INPUT) != 0 || !busIdOk(bus, 0, bus.length) || !busId.equals(new String(bus, StandardCharsets.US_ASCII))
                || deviceDescriptor.length != DEVICE_DESCRIPTOR || reportInterfaces.length != reports.length
                || reports.length > MAX_REPORTS
                || !descriptorsOk(deviceDescriptor, 0, configuration, 0, configuration.length)) {
            throw new IllegalArgumentException("not a device SHARE can describe");
        }
        int length = 2 + bus.length + 1 + nameBytes.length + DEVICE_DESCRIPTOR + 2 + configuration.length + 1;
        for (int i = 0; i < reports.length; i++) {
            if (reportInterfaces[i] < 0 || reportInterfaces[i] > 255 || reports[i].length < 1 || reports[i].length > MAX_REPORT) {
                throw new IllegalArgumentException("a report descriptor of 1 to 4096 bytes for an interface 0 to 255");
            }
            for (int j = 0; j < i; j++) {
                if (reportInterfaces[j] == reportInterfaces[i]) {
                    throw new IllegalArgumentException("one report descriptor per interface");
                }
            }
            length += 3 + reports[i].length;
        }
        if (length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("the descriptors don't fit in one SHARE");
        }
        byte[] f = frame(TYPE_SHARE, length);
        int at = FRAME_HEADER;
        f[at++] = (byte) flags;
        f[at++] = (byte) bus.length;
        System.arraycopy(bus, 0, f, at, bus.length);
        at += bus.length;
        f[at++] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, f, at, nameBytes.length);
        at += nameBytes.length;
        System.arraycopy(deviceDescriptor, 0, f, at, DEVICE_DESCRIPTOR);
        at += DEVICE_DESCRIPTOR;
        putU16(f, at, configuration.length);
        at += 2;
        System.arraycopy(configuration, 0, f, at, configuration.length);
        at += configuration.length;
        f[at++] = (byte) reports.length;
        for (int i = 0; i < reports.length; i++) {
            f[at++] = (byte) reportInterfaces[i];
            putU16(f, at, reports[i].length);
            at += 2;
            System.arraycopy(reports[i], 0, f, at, reports[i].length);
            at += reports[i].length;
        }
        return f;
    }

    /**
     * A well-formed SHARE, or null: every length within its limits and adding up to exactly the
     * frame, a bus id of digits, letters and . _ : - only, an 18-byte device descriptor, a
     * configuration descriptor whose wTotalLength is its length, and at most one report descriptor
     * per interface. Whether the device is allowed is the device rules' job, not this.
     */
    public static Share parseShare(byte[] f) {
        if (typeOf(f) != TYPE_SHARE) {
            return null;
        }
        int end = f.length;
        int at = FRAME_HEADER;
        if (end - at < 2) {
            return null;
        }
        int flags = f[at++] & 0xFF;
        int busLength = f[at++] & 0xFF;
        if ((flags & ~FLAG_CONFIRMED_INPUT) != 0 || end - at < busLength + 1 || !busIdOk(f, at, busLength)) {
            return null;
        }
        String busId = new String(f, at, busLength, StandardCharsets.US_ASCII);
        at += busLength;
        int nameLength = f[at++] & 0xFF;
        if (nameLength > MAX_NAME_BYTES || end - at < nameLength + DEVICE_DESCRIPTOR + 2) {
            return null;
        }
        byte[] name = Arrays.copyOfRange(f, at, at + nameLength);
        at += nameLength;
        int deviceAt = at;
        at += DEVICE_DESCRIPTOR;
        int configLength = getU16(f, at);
        at += 2;
        if (end - at < configLength + 1 || configLength < MIN_CONFIGURATION
                || !descriptorsOk(f, deviceAt, f, at, configLength)) {
            return null;
        }
        byte[] configuration = Arrays.copyOfRange(f, at, at + configLength);
        at += configLength;
        int count = f[at++] & 0xFF;
        if (count > MAX_REPORTS) {
            return null;
        }
        int[] interfaces = new int[count];
        byte[][] reports = new byte[count][];
        for (int i = 0; i < count; i++) {
            if (end - at < 3) {
                return null;
            }
            interfaces[i] = f[at++] & 0xFF;
            int length = getU16(f, at);
            at += 2;
            if (length < 1 || length > MAX_REPORT || end - at < length) {
                return null;
            }
            for (int j = 0; j < i; j++) {
                if (interfaces[j] == interfaces[i]) {
                    return null;
                }
            }
            reports[i] = Arrays.copyOfRange(f, at, at + length);
            at += length;
        }
        if (at != end) {
            return null;
        }
        return new Share(flags, busId, name, Arrays.copyOfRange(f, deviceAt, deviceAt + DEVICE_DESCRIPTOR),
                configuration, interfaces, reports);
    }
}
