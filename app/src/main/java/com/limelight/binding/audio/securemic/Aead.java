package com.limelight.binding.audio.securemic;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;
import java.security.Provider;

/**
 * AES-256-GCM with a 12-byte nonce and a 16-byte tag, from the platform's own provider. That's
 * Conscrypt on Android 6.0 and later. Conscrypt has no GCM on Android 5.0 and 5.1, so there it's
 * the platform's copy of BouncyCastle. Both give the same bytes; they differ only in how they say
 * a tag doesn't match (see open). One instance per key, used by one thread at a time.
 */
public final class Aead {
    public static final int TAG_LENGTH = 16;
    public static final int NONCE_LENGTH = 12;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final Cipher cipher;

    public Aead(byte[] key) throws GeneralSecurityException {
        this(key, null);
    }

    /**
     * With this provider's AES-GCM instead of the platform's choice (null means the platform's).
     * The JVM tests use it to run what Android 5.0 and 5.1 run.
     */
    Aead(byte[] key, Provider provider) throws GeneralSecurityException {
        if (key.length != 32) {
            throw new GeneralSecurityException("AES-256 needs a 32-byte key");
        }
        this.key = new SecretKeySpec(key, "AES");
        cipher = provider == null ? Cipher.getInstance(TRANSFORMATION) : Cipher.getInstance(TRANSFORMATION, provider);
    }

    /** The provider doing the work, for the log. */
    public String providerName() {
        return cipher.getProvider().getName();
    }

    /**
     * Encrypts in[inAt..inAt+inLength) into out at outAt, ciphertext then tag. Returns the bytes
     * written (inLength + 16).
     */
    public int seal(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength,
                    byte[] in, int inAt, int inLength, byte[] out, int outAt) throws GeneralSecurityException {
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce, nonceAt, NONCE_LENGTH));
        cipher.updateAAD(aad, aadAt, aadLength);
        return cipher.doFinal(in, inAt, inLength, out, outAt);
    }

    /**
     * Decrypts ciphertext and tag into out at outAt. Returns the plaintext's length, or -1 if it
     * isn't authentic (out may then hold anything).
     */
    public int open(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength,
                    byte[] in, int inAt, int inLength, byte[] out, int outAt) throws GeneralSecurityException {
        if (inLength < TAG_LENGTH || out.length - outAt < inLength - TAG_LENGTH) {
            return -1;
        }
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce, nonceAt, NONCE_LENGTH));
        cipher.updateAAD(aad, aadAt, aadLength);
        try {
            return cipher.doFinal(in, inAt, inLength, out, outAt);
        }
        // The tag doesn't match. Conscrypt says so with AEADBadTagException. Android 5.0 and 5.1's
        // BouncyCastle throws its parent, a plain BadPaddingException ("mac check in GCM failed").
        // With no padding, a tag that doesn't match is all a BadPaddingException can mean here.
        catch (BadPaddingException e) {
            return -1;
        }
    }
}
