package com.limelight.binding.usb.tunnel;

import static com.limelight.binding.usb.tunnel.SwutVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;
import com.limelight.binding.audio.securemic.SwmcCodec;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * SWUT v1, the USB tunnel's own frames (docs/dev/usb-protocol.md): every line of the vectors file
 * the PC's helper is to be tested against too, which openssl wrote apart from this codec, and
 * what must be refused: a BIND or BIND_ACK from another session, under another key, over other
 * certificates or an altered HELLO, any changed bit, malformed frames, and random junk (a seeded
 * fuzz).
 */
public class SwutCodecTest {

    private static byte[] usbKey() throws Exception {
        return SwutCodec.usbKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
    }

    private static Map<String, String> vectors() throws Exception {
        return SwutVectors.read();
    }

    private static byte[] v(String name) throws Exception {
        String line = vectors().get(name);
        assertNotNull(name + " isn't in the vectors file", line);
        return hex(line);
    }

    private static byte[] hello() {
        return SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 1);
    }

    private static byte[] flip(byte[] b, int bit) {
        byte[] c = b.clone();
        c[bit / 8] ^= (byte) (1 << (bit % 8));
        return c;
    }

    @Test
    public void theVectorsFileIsTheSharedOneByteForByte() throws Exception {
        byte[] ours = SwutVectors.fileBytes();
        assertEquals(SwutVectors.SHA256, hex(MessageDigest.getInstance("SHA-256").digest(ours)));
        byte[] helpers = Files.readAllBytes(RepoFiles.file("windows/tests/vectors/swut-v1-vectors.txt").toPath());
        assertArrayEquals("the app's and the helper's vectors differ", helpers, ours);
        assertEquals(SwutVectors.LINES, vectors().size());
        assertFalse("the vectors must keep LF line endings", new String(ours, "US-ASCII").contains("\r"));
    }

    @Test
    public void theProtocolDocGivesTheVectorsHash() throws Exception {
        String doc = RepoFiles.read("docs/dev/usb-protocol.md");
        assertTrue("usb-protocol.md doesn't give the vectors file's SHA-256",
                doc.contains(SwutVectors.SHA256.toUpperCase(Locale.ROOT)));
        assertTrue(doc.contains("K_usb   = HKDF(no salt, ikm = K_pair, info = \"starwire-usb/1 auth\" || deviceId)"));
    }

    // Each line made here from the fixed inputs, compared with the file; and no line goes unchecked
    @Test
    public void everyVectorLineIsReproduced() throws Exception {
        Map<String, String> want = vectors();
        Map<String, String> got = new LinkedHashMap<>();

        // Standards' known answers: HKDF RFC 5869 test case 1, HMAC-SHA256 truncated to 128 bits RFC 4231 test case 5
        got.put("kat.hkdf_rfc5869_1", hex(SwmcCodec.hkdf(hex("000102030405060708090a0b0c"),
                hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)));
        byte[] tc5 = SwutVectors.ascii("Test With Truncation");
        got.put("kat.mac16_rfc4231_5", hex(SwmcCodec.mac16(hex("0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c"), tc5, 0, tc5.length)));

        byte[] key = usbKey();
        got.put("key.usb", hex(key));
        // The certificates come from openssl as they are (theCertificates checks what's in them)
        got.put("cert.phone", want.get("cert.phone"));
        got.put("cert.helper", want.get("cert.helper"));
        byte[] phoneFp = SwutCodec.fingerprint(v("cert.phone"));
        byte[] helperFp = SwutCodec.fingerprint(v("cert.helper"));
        got.put("fp.phone", hex(phoneFp));
        got.put("fp.helper", hex(helperFp));

        byte[] hello = hello();
        byte[] bind = SwutCodec.bind(key, hello, phoneFp, helperFp, 1, SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE);
        got.put("frame.hello", hex(hello));
        got.put("frame.bind", hex(bind));
        got.put("frame.bind_ack", hex(SwutCodec.bindAck(key, hello, phoneFp, helperFp, bind)));
        got.put("frame.refuse_not_paired", hex(SwutCodec.refuse(SwutCodec.REFUSE_NOT_PAIRED)));
        got.put("frame.share", hex(SwutCodec.share(0, SwutVectors.BUS_ID, SwutVectors.NAME, SwutVectors.DEVICE_DESCRIPTOR,
                SwutVectors.CONFIGURATION, new int[] {0}, new byte[][] {SwutVectors.REPORT})));
        got.put("frame.share_ack", hex(SwutCodec.shareAck(SwutCodec.SHARE_ATTACHING, 0)));
        got.put("frame.share_ack_unconfirmed_keyboard",
                hex(SwutCodec.shareAck(SwutCodec.SHARE_REFUSED, SwutCodec.RULE_UNCONFIRMED_INPUT)));
        byte[] importRequest = SwutVectors.opReqImport(SwutVectors.BUS_ID);
        got.put("frame.data_import", hex(SwutCodec.data(importRequest, 0, importRequest.length)));
        got.put("frame.keepalive", hex(SwutCodec.keepalive()));
        got.put("frame.end_stopped", hex(SwutCodec.end(SwutCodec.END_STOPPED, 0)));

        // What must be refused, made the wrong way on purpose (aBind... and aBindAck... check they are)
        byte[] micKey = SwmcCodec.authKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
        got.put("refuse.bind_mic_key", hex(SwutCodec.bind(micKey, hello, phoneFp, helperFp, 1,
                SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE)));
        got.put("refuse.bind_swapped_fingerprints", hex(SwutCodec.bind(key, hello, helperFp, phoneFp, 1,
                SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE)));
        got.put("refuse.bind_other_hello", hex(SwutCodec.bind(key, SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 2),
                phoneFp, helperFp, 1, SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE)));
        byte[] otherBind = SwutCodec.bind(key, hello, phoneFp, helperFp, 1, SwutVectors.DEVICE_ID, SwutVectors.OTHER_PHONE_NONCE);
        got.put("refuse.bind_ack_other_bind", hex(SwutCodec.bindAck(key, hello, phoneFp, helperFp, otherBind)));

        assertEquals("every line is checked, and nothing else", want.keySet(), got.keySet());
        for (Map.Entry<String, String> e : want.entrySet()) {
            assertEquals(e.getKey(), e.getValue(), got.get(e.getKey()));
        }
    }

    @Test
    public void theCertificatesAreSelfSignedP256WithTheFixedKeys() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        byte[][] ders = {v("cert.phone"), v("cert.helper")};
        byte[][][] points = {{SwutVectors.PHONE_X, SwutVectors.PHONE_Y}, {SwutVectors.HELPER_X, SwutVectors.HELPER_Y}};
        String[] fingerprints = {"fp.phone", "fp.helper"};
        for (int i = 0; i < 2; i++) {
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(ders[i]));
            assertArrayEquals("the DER is the certificate as it is", ders[i], cert.getEncoded());
            ECPublicKey key = (ECPublicKey) cert.getPublicKey();
            assertEquals(SwmcCodec.p256().getCurve(), key.getParams().getCurve());
            assertEquals(new BigInteger(1, points[i][0]), key.getW().getAffineX());
            assertEquals(new BigInteger(1, points[i][1]), key.getW().getAffineY());
            cert.verify(key);   // self-signed
            assertEquals("SHA256withECDSA", cert.getSigAlgName());
            assertEquals("CN=Starwire", cert.getSubjectX500Principal().getName());
            assertEquals("CN=Starwire", cert.getIssuerX500Principal().getName());
            assertArrayEquals(v(fingerprints[i]), SwutCodec.fingerprint(cert.getEncoded()));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(ders[i]), v(fingerprints[i]));
        }
        assertFalse(Arrays.equals(v("fp.phone"), v("fp.helper")));
    }

    @Test
    public void theUsbKeyIsNotTheMicKey() throws Exception {
        byte[] micKey = SwmcCodec.authKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
        assertFalse(Arrays.equals(micKey, usbKey()));
        // Another device id gives another key from the same pairing key
        byte[] otherId = SwutVectors.DEVICE_ID.clone();
        otherId[7] ^= 1;
        assertFalse(Arrays.equals(usbKey(), SwutCodec.usbKey(SwutVectors.PAIR_KEY, otherId)));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.usbKey(new byte[31], SwutVectors.DEVICE_ID));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.usbKey(SwutVectors.PAIR_KEY, new byte[7]));
    }

    @Test
    public void aBindChecksOutOnlyInItsOwnSession() throws Exception {
        byte[] key = usbKey();
        byte[] hello = v("frame.hello");
        byte[] phoneFp = v("fp.phone");
        byte[] helperFp = v("fp.helper");
        byte[] bind = v("frame.bind");
        assertTrue(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, bind));
        assertEquals(1, SwutCodec.bindVersion(bind));
        assertArrayEquals(SwutVectors.DEVICE_ID, SwutCodec.bindDeviceId(bind));

        // The mic's key, the fingerprints the other way round, a HELLO someone changed on the way
        assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, v("refuse.bind_mic_key")));
        assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, v("refuse.bind_swapped_fingerprints")));
        assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, v("refuse.bind_other_hello")));

        // Replayed into another connection: the helper's nonce is new each time
        byte[] otherHello = SwutCodec.hello(SwutVectors.OTHER_PHONE_NONCE, 1, 1);
        assertFalse(SwutCodec.bindChecksOut(key, otherHello, phoneFp, helperFp, bind));
        // Someone in the middle shows each side a certificate of their own: any other fingerprint fails
        for (int bit = 0; bit < 256; bit++) {
            assertFalse(SwutCodec.bindChecksOut(key, hello, flip(phoneFp, bit), helperFp, bind));
            assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, flip(helperFp, bit), bind));
        }
        // Another device's key (the pairing was removed and made again, say)
        byte[] otherPairKey = SwutVectors.PAIR_KEY.clone();
        otherPairKey[0] ^= 1;
        assertFalse(SwutCodec.bindChecksOut(SwutCodec.usbKey(otherPairKey, SwutVectors.DEVICE_ID), hello, phoneFp, helperFp, bind));
        // Any changed bit of the BIND
        for (int bit = 0; bit < bind.length * 8; bit++) {
            assertFalse("bit " + bit, SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, flip(bind, bit)));
        }
        // Any changed bit of the HELLO on its way to Starwire, wherever the change still makes a HELLO Starwire takes
        int taken = 0;
        for (int bit = 0; bit < hello.length * 8; bit++) {
            byte[] changed = flip(hello, bit);
            if (SwutCodec.chooseVersion(changed) == 1) {
                taken++;
                byte[] theirs = SwutCodec.bind(key, changed, phoneFp, helperFp, 1, SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE);
                assertFalse("bit " + bit, SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, theirs));
            }
        }
        assertEquals("the nonce's 128 bits and 7 of the highest version's", 128 + 7, taken);
        // Truncated or too long
        for (int n = 0; n < bind.length; n++) {
            assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, Arrays.copyOf(bind, n)));
        }
        assertFalse(SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, Arrays.copyOf(bind, bind.length + 1)));
        assertFalse(SwutCodec.bindChecksOut(key, hello, new byte[31], helperFp, bind));
    }

    @Test
    public void aBindAckChecksOutOnlyForItsOwnBind() throws Exception {
        byte[] key = usbKey();
        byte[] hello = v("frame.hello");
        byte[] phoneFp = v("fp.phone");
        byte[] helperFp = v("fp.helper");
        byte[] bind = v("frame.bind");
        byte[] ack = v("frame.bind_ack");
        assertTrue(SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, ack));
        // An answer recorded from another connection, where Starwire's nonce was different
        assertFalse(SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, v("refuse.bind_ack_other_bind")));
        // Not the BIND itself sent back, nor one made with the mic's key
        byte[] micKey = SwmcCodec.authKey(SwutVectors.PAIR_KEY, SwutVectors.DEVICE_ID);
        assertFalse(SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, SwutCodec.bindAck(micKey, hello, phoneFp, helperFp, bind)));
        assertFalse(SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, bind));
        for (int bit = 0; bit < ack.length * 8; bit++) {
            assertFalse("bit " + bit, SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, flip(ack, bit)));
        }
        for (int bit = 0; bit < 256; bit++) {
            assertFalse(SwutCodec.bindAckChecksOut(key, hello, flip(phoneFp, bit), helperFp, bind, ack));
            assertFalse(SwutCodec.bindAckChecksOut(key, hello, phoneFp, flip(helperFp, bit), bind, ack));
        }
        for (int bit = 0; bit < bind.length * 8; bit++) {
            byte[] changed = flip(bind, bit);
            assertFalse("bit " + bit, SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, changed, ack));
        }
        assertFalse(SwutCodec.bindAckChecksOut(key, SwutCodec.hello(SwutVectors.OTHER_PHONE_NONCE, 1, 1),
                phoneFp, helperFp, bind, ack));
        assertFalse(SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, Arrays.copyOf(ack, ack.length - 1)));
    }

    @Test
    public void versionsComeFromTheHellosRange() throws Exception {
        byte[] nonce = SwutVectors.HELPER_NONCE;
        assertEquals(1, SwutCodec.chooseVersion(SwutCodec.hello(nonce, 1, 1)));
        assertEquals(1, SwutCodec.chooseVersion(SwutCodec.hello(nonce, 1, 3)));   // a newer helper that still speaks 1
        assertEquals(0, SwutCodec.chooseVersion(SwutCodec.hello(nonce, 2, 3)));   // one that doesn't: update Starwire
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.hello(nonce, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.hello(nonce, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.hello(new byte[15], 1, 1));

        byte[] hello = v("frame.hello");
        assertTrue(SwutCodec.isHello(hello));
        byte[] bad = hello.clone();
        bad[4] = 'X';   // magic
        assertEquals(-1, SwutCodec.chooseVersion(bad));
        bad = hello.clone();
        bad[8] = 0;     // lowest version 0
        assertEquals(-1, SwutCodec.chooseVersion(bad));
        bad = hello.clone();
        bad[8] = 2;     // lowest above highest
        assertEquals(-1, SwutCodec.chooseVersion(bad));
        bad = hello.clone();
        bad[10] = 1;    // reserved
        assertEquals(-1, SwutCodec.chooseVersion(bad));
        bad = hello.clone();
        bad[1] = 1;     // the frame header's reserved byte
        assertEquals(-1, SwutCodec.chooseVersion(bad));
        assertEquals(-1, SwutCodec.chooseVersion(Arrays.copyOf(hello, 27)));

        // A BIND may only ask for a version the HELLO offered, and Starwire won't make one that doesn't
        byte[] key = usbKey();
        byte[] phoneFp = v("fp.phone");
        byte[] helperFp = v("fp.helper");
        assertThrows(IllegalArgumentException.class,
                () -> SwutCodec.bind(key, hello, phoneFp, helperFp, 2, SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE));
        byte[] wide = SwutCodec.hello(nonce, 1, 2);
        byte[] asksForTwo = SwutCodec.bind(key, wide, phoneFp, helperFp, 2, SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE);
        assertEquals(2, SwutCodec.bindVersion(asksForTwo));
        assertTrue(SwutCodec.bindChecksOut(key, wide, phoneFp, helperFp, asksForTwo));
        assertFalse("a helper that offered only 1 refuses it", SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, asksForTwo));
        byte[] zero = v("frame.bind");
        zero[4] = 0;
        assertEquals(-1, SwutCodec.bindVersion(zero));
        assertNull(SwutCodec.bindDeviceId(zero));
    }

    // bindMac as usb-protocol.md writes it, worked out here and not by the codec
    private static byte[] docBindMac(byte[] key, byte[] hello, byte[] phoneFp, byte[] helperFp, byte[] bind) throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(SwutVectors.ascii("starwire-usb/1 bind"));
        input.write(hello);
        input.write(phoneFp);
        input.write(helperFp);
        input.write(bind, 0, 32);
        byte[] b = input.toByteArray();
        return SwmcCodec.mac16(key, b, 0, b.length);
    }

    @Test
    public void aBindForAVersionTheHelloDidntOfferIsRefusedEvenWithItsMac() throws Exception {
        byte[] key = usbKey();
        byte[] hello = v("frame.hello");
        byte[] phoneFp = v("fp.phone");
        byte[] helperFp = v("fp.helper");
        byte[] bind = v("frame.bind");
        assertArrayEquals("the doc's formula gives the vector's MAC",
                Arrays.copyOfRange(bind, 32, 48), docBindMac(key, hello, phoneFp, helperFp, bind));
        bind[4] = 2;
        System.arraycopy(docBindMac(key, hello, phoneFp, helperFp, bind), 0, bind, 32, 16);
        assertEquals(2, SwutCodec.bindVersion(bind));
        assertFalse("HELLO offered only version 1", SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, bind));
        assertTrue(SwutCodec.bindChecksOut(key, SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 2), phoneFp, helperFp,
                SwutCodec.bind(key, SwutCodec.hello(SwutVectors.HELPER_NONCE, 1, 2), phoneFp, helperFp, 2,
                        SwutVectors.DEVICE_ID, SwutVectors.PHONE_NONCE)));
    }

    @Test
    public void aShareRoundTripsAndBadOnesAreRefused() throws Exception {
        byte[] f = v("frame.share");
        SwutCodec.Share s = SwutCodec.parseShare(f);
        assertNotNull(s);
        assertEquals(0, s.flags);
        assertEquals(SwutVectors.BUS_ID, s.busId);
        assertEquals(SwutVectors.NAME, s.nameText());
        assertArrayEquals(SwutVectors.DEVICE_DESCRIPTOR, s.deviceDescriptor);
        assertArrayEquals(SwutVectors.CONFIGURATION, s.configuration);
        assertArrayEquals(new int[] {0}, s.reportInterfaces);
        assertArrayEquals(SwutVectors.REPORT, s.reports[0]);
        assertArrayEquals(f, SwutCodec.share(s.flags, s.busId, s.nameText(), s.deviceDescriptor, s.configuration,
                s.reportInterfaces, s.reports));

        // Every shorter or longer payload, with the header's length made to match, doesn't add up
        for (int n = 0; n <= f.length + 1 - SwutCodec.FRAME_HEADER; n++) {
            if (n == f.length - SwutCodec.FRAME_HEADER) {
                continue;
            }
            byte[] g = Arrays.copyOf(f, SwutCodec.FRAME_HEADER + n);
            g[2] = (byte) n;
            g[3] = (byte) (n >> 8);
            assertNull("payload of " + n, SwutCodec.parseShare(g));
        }
        // Fields in place but wrong: a flag that doesn't exist, a bus id with a space or DEL, a device
        // descriptor that isn't one, a configuration whose wTotalLength isn't its length, two report
        // descriptors claimed, an empty report descriptor
        int[][] edits = {{4, 2}, {6, 0x20}, {7, 0x7F}, {18, 17}, {19, 2}, {38, 8}, {39, 4}, {40, 0x23}, {72, 2}, {74, 0}};
        for (int[] e : edits) {
            byte[] g = f.clone();
            g[e[0]] = (byte) e[1];
            assertNull("byte " + e[0] + " = " + e[1], SwutCodec.parseShare(g));
        }
        // Two report descriptors for the same interface
        byte[] two = SwutCodec.share(0, "1-1", "x", SwutVectors.DEVICE_DESCRIPTOR, SwutVectors.CONFIGURATION,
                new int[] {0, 1}, new byte[][] {SwutVectors.REPORT, SwutVectors.REPORT});
        assertNotNull(SwutCodec.parseShare(two));
        two[two.length - SwutVectors.REPORT.length - 3] = 0;
        assertNull(SwutCodec.parseShare(two));
        byte[] confirmed = f.clone();
        confirmed[4] = SwutCodec.FLAG_CONFIRMED_INPUT;
        assertEquals(SwutCodec.FLAG_CONFIRMED_INPUT, SwutCodec.parseShare(confirmed).flags);
        assertNull(SwutCodec.parseShare(v("frame.bind")));
    }

    @Test
    public void starwireWontMakeAShareTheHelperWouldRefuse() {
        byte[] dev = SwutVectors.DEVICE_DESCRIPTOR;
        byte[] config = SwutVectors.CONFIGURATION;
        int[] one = {0};
        byte[][] report = {SwutVectors.REPORT};
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "", "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, null, "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "12345678901234567890123456789012", "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1 1", "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-é", "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(2, "1-1", "x", dev, config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", Arrays.copyOf(dev, 17), config, one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, Arrays.copyOf(config, 33), one, report));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, config, new int[] {0, 0},
                new byte[][] {SwutVectors.REPORT, SwutVectors.REPORT}));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, config, one, new byte[][] {new byte[0]}));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, config, one, new byte[][] {new byte[4097]}));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, config, new int[17], new byte[17][]));
        // Four 4096-byte report descriptors don't fit in one frame
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.share(0, "1-1", "x", dev, config, new int[] {0, 1, 2, 3},
                new byte[][] {new byte[4096], new byte[4096], new byte[4096], new byte[4096]}));
        // A long name is cut to 64 bytes without splitting a character
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            name.append('é');   // two bytes each in UTF-8
        }
        SwutCodec.Share s = SwutCodec.parseShare(SwutCodec.share(0, "1-1", name.toString(), dev, config, one, report));
        assertEquals(64, s.name.length);
        assertEquals(name.substring(0, 32), s.nameText());
    }

    // The bus id comes from a phone that may be hostile, and the helper may hand it to a command
    // (usbip.exe attach -b), where these characters mean something
    @Test
    public void aBusIdIsDigitsLettersAndDotUnderscoreColonDashOnly() throws Exception {
        byte[] dev = SwutVectors.DEVICE_DESCRIPTOR;
        byte[] config = SwutVectors.CONFIGURATION;
        int[] one = {0};
        byte[][] report = {SwutVectors.REPORT};
        byte[] f = v("frame.share");
        for (char c : "\"&|^%<>!$'`()*+,/;=?@[\\]{}~# ".toCharArray()) {
            String bad = "1" + c + "1";
            assertThrows(bad, IllegalArgumentException.class, () -> SwutCodec.share(0, bad, "x", dev, config, one, report));
            byte[] g = f.clone();
            g[7] = (byte) c;   // the '-' of the vectors' bus id 1-1
            assertNull(bad, SwutCodec.parseShare(g));
        }
        // usbipdcpp's two forms: bus and port path, or bus, address and port when Android hides the path
        for (String good : new String[] {"1-1.2", "2-1.4.3", "1-3:1", "Bus_a-Z9"}) {
            assertEquals(good, SwutCodec.parseShare(SwutCodec.share(0, good, "x", dev, config, one, report)).busId);
        }
    }

    @Test
    public void framesAndTheirHeaders() throws Exception {
        assertEquals(SwutCodec.TYPE_KEEPALIVE, SwutCodec.typeOf(v("frame.keepalive")));
        assertEquals(SwutCodec.REFUSE_NOT_PAIRED, SwutCodec.code(v("frame.refuse_not_paired"), SwutCodec.TYPE_REFUSE));
        assertEquals(SwutCodec.SHARE_REFUSED, SwutCodec.code(v("frame.share_ack_unconfirmed_keyboard"), SwutCodec.TYPE_SHARE_ACK));
        assertEquals(SwutCodec.RULE_UNCONFIRMED_INPUT, SwutCodec.detail(v("frame.share_ack_unconfirmed_keyboard"), SwutCodec.TYPE_SHARE_ACK));
        assertEquals(SwutCodec.END_STOPPED, SwutCodec.code(v("frame.end_stopped"), SwutCodec.TYPE_END));
        // A code is only read from the type asked for, with its reserved bytes zero
        assertEquals(-1, SwutCodec.code(v("frame.end_stopped"), SwutCodec.TYPE_SHARE_ACK));
        byte[] end = v("frame.end_stopped");
        end[7] = 1;
        assertEquals(-1, SwutCodec.code(end, SwutCodec.TYPE_END));
        byte[] refuse = v("frame.refuse_not_paired");
        refuse[5] = 1;   // REFUSE has no detail
        assertEquals(-1, SwutCodec.code(refuse, SwutCodec.TYPE_REFUSE));

        byte[] data = v("frame.data_import");
        assertEquals(SwutCodec.TYPE_DATA, SwutCodec.typeOf(data));
        assertArrayEquals(SwutVectors.opReqImport("1-1"), Arrays.copyOfRange(data, 4, data.length));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.data(new byte[1], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SwutCodec.data(new byte[16385], 0, 16385));
        assertEquals(SwutCodec.FRAME_HEADER + 16384, SwutCodec.data(new byte[16384], 0, 16384).length);

        // Headers: types 1 to 9, reserved byte 0, a payload of at most 16384, and the length matching the frame
        assertEquals(4 + 0x4000, SwutCodec.frameLength(new byte[] {7, 0, 0x00, 0x40}, 0));
        assertEquals(-1, SwutCodec.frameLength(new byte[] {7, 0, 0x01, 0x40}, 0));
        assertEquals(-1, SwutCodec.frameLength(new byte[] {0, 0, 0, 0}, 0));
        assertEquals(-1, SwutCodec.frameLength(new byte[] {10, 0, 0, 0}, 0));
        assertEquals(-1, SwutCodec.frameLength(new byte[] {8, 1, 0, 0}, 0));
        assertEquals(-1, SwutCodec.frameLength(new byte[] {8, 0, 0}, 0));
        assertEquals(-1, SwutCodec.typeOf(Arrays.copyOf(v("frame.keepalive"), 5)));
        assertEquals(-1, SwutCodec.typeOf(null));
    }

    // Nothing from the network makes the codec throw, whatever the bytes (a fixed seed, so every run is the same)
    @Test
    public void randomJunkNeverThrows() throws Exception {
        Random random = new Random(0x53575554L);
        byte[] key = usbKey();
        byte[] hello = v("frame.hello");
        byte[] phoneFp = v("fp.phone");
        byte[] helperFp = v("fp.helper");
        byte[] bind = v("frame.bind");
        byte[][] valid = {hello, bind, v("frame.bind_ack"), v("frame.share"), v("frame.share_ack"), v("frame.data_import"),
                v("frame.keepalive"), v("frame.end_stopped"), v("frame.refuse_not_paired")};
        int accepted = 0;
        for (int i = 0; i < 20000; i++) {
            byte[] f;
            if (i % 2 == 0) {
                f = new byte[random.nextInt(140)];
                random.nextBytes(f);
                if (f.length >= 4 && random.nextBoolean()) {
                    f[0] = (byte) (1 + random.nextInt(9));
                    f[1] = 0;
                    f[2] = (byte) (f.length - 4);
                    f[3] = 0;
                }
            }
            else {
                byte[] original = valid[random.nextInt(valid.length)];
                f = original.clone();
                for (int k = 1 + random.nextInt(3); k > 0; k--) {
                    f[random.nextInt(f.length)] ^= (byte) (1 + random.nextInt(255));
                }
                if (Arrays.equals(f, original)) {
                    continue;   // two changes cancelled out
                }
            }
            SwutCodec.typeOf(f);
            SwutCodec.frameLength(f, 0);
            SwutCodec.chooseVersion(f);
            SwutCodec.bindVersion(f);
            SwutCodec.bindDeviceId(f);
            for (int type = SwutCodec.TYPE_HELLO; type <= SwutCodec.TYPE_END; type++) {
                SwutCodec.code(f, type);
                SwutCodec.detail(f, type);
            }
            if (SwutCodec.bindChecksOut(key, hello, phoneFp, helperFp, f)
                    || SwutCodec.bindAckChecksOut(key, hello, phoneFp, helperFp, bind, f)) {
                accepted++;
            }
            SwutCodec.Share s = SwutCodec.parseShare(f);
            if (s != null) {
                assertTrue(s.busId.length() >= 1 && s.busId.length() <= 31 && s.name.length <= 64);
            }
        }
        assertEquals("a changed BIND or BIND_ACK checked out", 0, accepted);
    }
}
