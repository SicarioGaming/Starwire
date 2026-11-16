package com.limelight.binding.audio.securemic;

import static com.limelight.binding.audio.securemic.SwmcVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encrypted microphone's AES-GCM through the code Android 5.0 and 5.1 run (Android5Gcm, their
 * BouncyCastle's way), so the JVM catches what SecureMicDeviceTest found on the API 21 emulator:
 * every AES-GCM line of the shared vectors comes out the same, and a forged packet is refused
 * with -1. It used to throw "mac check in GCM failed" instead.
 *
 * Android5Gcm is an unsigned crypto provider. OpenJDK builds such as Temurin, which CI uses, load
 * it. Oracle's own JDK only loads signed providers, so these tests would fail there.
 */
public class AeadOnAndroid5Test {
    private static final Provider ANDROID_5 = Android5Gcm.provider();
    private static final byte[] TC16_KEY = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308");
    private static final byte[] TC16_IV = hex("cafebabefacedbaddecaf888");
    private static final byte[] TC16_AAD = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
    private static final byte[] TC16_PT = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
            + "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");

    private static Aead android5(byte[] key) throws Exception {
        Aead aead = new Aead(key, ANDROID_5);
        assertEquals("Android5Gcm", aead.providerName());
        return aead;
    }

    // The stand-in fails the way the API 21 emulator did, so the tests below test that case
    @Test
    public void theStandInSaysABadTagTheWayAndroid5Does() throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding", ANDROID_5);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TC16_KEY, "AES"), new GCMParameterSpec(128, TC16_IV));
        c.updateAAD(TC16_AAD);
        byte[] forged = new byte[TC16_PT.length + 16];
        try {
            c.doFinal(forged, 0, forged.length, new byte[forged.length], 0);
            fail("a forged tag opened");
        }
        catch (BadPaddingException e) {
            assertFalse("Android 5's BouncyCastle doesn't throw AEADBadTagException", e instanceof AEADBadTagException);
            assertEquals("mac check in GCM failed", e.getMessage());
        }
    }

    @Test
    public void theSharedVectorsHold() throws Exception {
        Map<String, String> want = SwmcVectors.read();
        assertEquals(SwmcVectors.SHA256, hex(java.security.MessageDigest.getInstance("SHA-256").digest(SwmcVectors.fileBytes())));

        // GCM spec test case 16, both ways
        Aead tc16 = android5(TC16_KEY);
        byte[] ct = new byte[TC16_PT.length + Aead.TAG_LENGTH];
        assertEquals(ct.length, tc16.seal(TC16_IV, 0, TC16_AAD, 0, TC16_AAD.length, TC16_PT, 0, TC16_PT.length, ct, 0));
        assertEquals(want.get("kat.gcm_tc16"), hex(ct));
        byte[] back = new byte[TC16_PT.length];
        assertEquals(TC16_PT.length, tc16.open(TC16_IV, 0, TC16_AAD, 0, TC16_AAD.length, ct, 0, ct.length, back, 0));
        assertArrayEquals(TC16_PT, back);

        // The audio packets, sealed as SecureVbanSender seals them, with the vectors' key
        byte[] kAudio = SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM,
                SwmcVectors.SERVER_RANDOM);
        assertEquals(want.get("key.audio"), hex(kAudio));
        byte[] vban = SwmcVectors.vbanFrame(7, 0);
        assertEquals(want.get("vban.frame7"), hex(vban));
        Aead seal = android5(kAudio);
        byte[] out = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        int n = SwmcCodec.audio(seal, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, 7, vban, vban.length, out);
        assertEquals(want.get("pkt.audio7"), hex(out, 0, n));
        n = SwmcCodec.audio(seal, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, SwmcCodec.MAX_COUNTER, vban, vban.length, out);
        assertEquals(want.get("pkt.audio_last"), hex(out, 0, n));

        // And the vectors' packets open, as in SecureMicDeviceTest#theSharedVectorsHoldOnThisDevice
        Aead open = android5(kAudio);
        byte[] pcm = new byte[2048];
        for (String name : new String[] {"pkt.audio7", "pkt.audio_last"}) {
            byte[] p = hex(want.get(name));
            assertEquals(name, 540, SwmcCodec.openAudio(open, p, p.length, pcm));
            assertEquals(name, want.get("vban.frame7"), hex(pcm, 0, 540));
        }
        byte[] audio7 = hex(want.get("pkt.audio7"));
        audio7[100] ^= 1;
        assertEquals(-1, SwmcCodec.openAudio(open, audio7, audio7.length, pcm));
    }

    @Test
    public void everyFlippedBitAndEveryTruncationIsRefused() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        Aead aead = android5(hex(v.get("key.audio")));
        byte[] good = hex(v.get("pkt.audio7"));
        byte[] out = new byte[2048];
        for (int at = 0; at < good.length; at++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] t = good.clone();
                t[at] ^= (byte) (1 << bit);
                assertEquals("byte " + at + " bit " + bit, -1, SwmcCodec.openAudio(aead, t, t.length, out));
            }
        }
        for (int length = 0; length < good.length; length++) {
            assertEquals("cut to " + length, -1, SwmcCodec.openAudio(aead, good, length, out));
        }
        assertEquals(-1, SwmcCodec.openAudio(aead, good, good.length + 10, out));
        byte[] longer = Arrays.copyOf(good, good.length + 1);
        assertEquals(-1, SwmcCodec.openAudio(aead, longer, longer.length, out));
        // The same Aead still opens a good packet after all that
        assertEquals(540, SwmcCodec.openAudio(aead, good, good.length, out));
        assertEquals(v.get("vban.frame7"), hex(out, 0, 540));
    }

    @Test
    public void theWrongKeyIsRefused() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] good = hex(v.get("pkt.audio7"));
        byte[] out = new byte[2048];
        byte[] otherPairing = SwmcVectors.PAIR_KEY.clone();
        otherPairing[0] ^= (byte) 0xFF;
        Aead wrongPairing = android5(SwmcCodec.audioKey(otherPairing, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM,
                SwmcVectors.SERVER_RANDOM));
        assertEquals(-1, SwmcCodec.openAudio(wrongPairing, good, good.length, out));
        Aead oldSession = android5(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, hex("0000000000000000"),
                SwmcVectors.SERVER_RANDOM));
        assertEquals(-1, SwmcCodec.openAudio(oldSession, good, good.length, out));
    }

    // A refused packet leaves none of its plaintext in the buffer, whichever GCM opened it:
    // Android 5's (which writes the plaintext out before it checks the tag), Conscrypt's (Android
    // 6.0 and later, and Robolectric brings it to the JVM) and the JDK's own. Each is named here.
    // Robolectric's tests can leave Conscrypt first in the JVM's list, so the platform's choice
    // would depend on which tests ran before. open clears the bytes it could have written, and
    // none of them touch the bytes before the offset. Android 5's and the JDK's leave the rest of
    // the buffer alone; Conscrypt's clears it too, to the end of the buffer.
    @Test
    public void aRefusedPacketLeavesNoneOfItsPlaintext() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] kAudio = hex(v.get("key.audio"));
        byte[] forged = hex(v.get("pkt.audio7"));
        forged[forged.length - 1] ^= 1;
        byte[] tc16 = hex(v.get("kat.gcm_tc16"));
        tc16[tc16.length - 1] ^= 1;
        for (Provider provider : new Provider[] {ANDROID_5, conscrypt(), Security.getProvider("SunJCE")}) {
            boolean toTheEnd = provider.getName().equals("Conscrypt");
            Aead audio = new Aead(kAudio, provider);
            byte[] out = filled(2048);
            assertEquals(-1, SwmcCodec.openAudio(audio, forged, forged.length, out));
            assertCleared(audio.providerName(), out, 0, 540, toTheEnd);

            // At an offset into the buffer, as Aead.open allows
            Aead kat = new Aead(TC16_KEY, provider);
            out = filled(TC16_PT.length + 10);
            assertEquals(-1, kat.open(TC16_IV, 0, TC16_AAD, 0, TC16_AAD.length, tc16, 0, tc16.length, out, 7));
            assertCleared(kat.providerName(), out, 7, TC16_PT.length, toTheEnd);
        }
    }

    // Conscrypt's provider, as Robolectric brings it, without putting it in the JVM's list
    private static Provider conscrypt() throws Exception {
        Provider conscrypt = (Provider) Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null);
        assertEquals("Conscrypt", conscrypt.getName());
        return conscrypt;
    }

    private static byte[] filled(int length) {
        byte[] b = new byte[length];
        Arrays.fill(b, (byte) 0x5A);
        return b;
    }

    // out[from..from+length) is zero, or with toTheEnd, out[from..) is. The rest still holds what
    // filled() put there.
    private static void assertCleared(String provider, byte[] out, int from, int length, boolean toTheEnd) {
        for (int i = 0; i < out.length; i++) {
            boolean inside = i >= from && (i < from + length || toTheEnd);
            assertEquals(provider + " byte " + i, inside ? 0 : 0x5A, out[i]);
        }
    }

    // A session's run of packets: the same Aead seals each one, and the platform's GCM on this JVM
    // opens what Android 5's sealed, and the other way round
    @Test
    public void android5AndTheJvmsOwnGcmAgreeOnAStream() throws Exception {
        byte[] kAudio = SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM,
                SwmcVectors.SERVER_RANDOM);
        Aead old = android5(kAudio);
        Aead platform = new Aead(kAudio);
        byte[] a = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        byte[] b = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        byte[] pcm = new byte[2048];
        for (int frame = 0; frame < 200; frame++) {
            byte[] vban = SwmcVectors.vbanFrame(frame, frame * 256);
            int n = SwmcCodec.audio(old, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, frame, vban, vban.length, a);
            int m = SwmcCodec.audio(platform, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, frame, vban, vban.length, b);
            assertEquals(hex(b, 0, m), hex(a, 0, n));
            assertEquals(vban.length, SwmcCodec.openAudio(platform, a, n, pcm));
            assertArrayEquals(vban, Arrays.copyOf(pcm, vban.length));
            assertEquals(vban.length, SwmcCodec.openAudio(old, b, m, pcm));
            assertArrayEquals(vban, Arrays.copyOf(pcm, vban.length));
            a[n - 1] ^= 1;
            assertEquals("frame " + frame, -1, SwmcCodec.openAudio(old, a, n, pcm));
        }
    }
}
