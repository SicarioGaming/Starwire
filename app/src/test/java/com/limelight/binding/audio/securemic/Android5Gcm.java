package com.limelight.binding.audio.securemic;

import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.OutputLengthException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM/NoPadding the way Android 5.0 and 5.1 do it, for JVM tests. Their Conscrypt has no
 * GCM (its OpenSSLProvider lists AES in ECB, CBC, CFB, CTR and OFB only), so Cipher.getInstance
 * gives the platform's BouncyCastle. Its BaseBlockCipher feeds GCMBlockCipher with processBytes
 * then doFinal, and turns every InvalidCipherTextException into a plain BadPaddingException. So a
 * tag that doesn't match comes out as BadPaddingException("mac check in GCM failed"), never
 * AEADBadTagException, as the API 21 emulator showed (SecureMicDeviceTest, 0.2.1's device run).
 *
 * This is BouncyCastle's own GCM (the app's copy, org.bouncycastle) behind those same steps,
 * following AOSP's external/bouncycastle at lollipop-release and lollipop-mr1-release. Those steps
 * are BouncyCastle's (The Legion of the Bouncy Castle Inc., MIT licence, as in the app's
 * open-source notices). Only what Aead uses is here: a GCMParameterSpec, AAD, and doFinal into an
 * array.
 */
public final class Android5Gcm extends CipherSpi {
    /** A provider with only this cipher in it. */
    static Provider provider() {
        return new OnlyGcm();
    }

    @SuppressWarnings("deprecation")
    private static final class OnlyGcm extends Provider {
        OnlyGcm() {
            super("Android5Gcm", 1.0, "AES-GCM as Android 5.0 and 5.1's BouncyCastle does it");
            put("Cipher.AES/GCM/NoPadding", Android5Gcm.class.getName());
        }
    }

    private final GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
    private byte[] iv;

    public Android5Gcm() {
    }

    @Override
    protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
        if (!"GCM".equalsIgnoreCase(mode)) {
            throw new NoSuchAlgorithmException(mode);
        }
    }

    @Override
    protected void engineSetPadding(String padding) throws NoSuchPaddingException {
        if (!"NoPadding".equalsIgnoreCase(padding)) {
            throw new NoSuchPaddingException(padding);
        }
    }

    @Override
    protected int engineGetBlockSize() {
        return 16;
    }

    @Override
    protected int engineGetOutputSize(int inputLen) {
        return cipher.getOutputSize(inputLen);
    }

    @Override
    protected byte[] engineGetIV() {
        return iv == null ? null : iv.clone();
    }

    @Override
    protected AlgorithmParameters engineGetParameters() {
        return null;
    }

    @Override
    protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        throw new InvalidKeyException("this stand-in needs a GCMParameterSpec");
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (!(params instanceof GCMParameterSpec)) {
            throw new InvalidAlgorithmParameterException("Cannot process GCMParameterSpec.");
        }
        GCMParameterSpec spec = (GCMParameterSpec) params;
        iv = spec.getIV();
        try {
            cipher.init(opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE,
                    new AEADParameters(new KeyParameter(key.getEncoded()), spec.getTLen(), iv));
        }
        catch (IllegalArgumentException e) {
            throw new InvalidKeyException(e.getMessage());
        }
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        throw new InvalidAlgorithmParameterException("this stand-in needs a GCMParameterSpec");
    }

    @Override
    protected void engineUpdateAAD(byte[] input, int offset, int length) {
        cipher.processAADBytes(input, offset, length);
    }

    @Override
    protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
        byte[] out = new byte[cipher.getUpdateOutputSize(inputLen)];
        int n = cipher.processBytes(input, inputOffset, inputLen, out, 0);
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    @Override
    protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
            throws ShortBufferException {
        try {
            return cipher.processBytes(input, inputOffset, inputLen, output, outputOffset);
        }
        catch (OutputLengthException e) {
            throw new ShortBufferException(e.getMessage());
        }
    }

    @Override
    protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
            throws IllegalBlockSizeException, BadPaddingException {
        byte[] out = new byte[engineGetOutputSize(inputLen)];
        try {
            int n = engineDoFinal(input, inputOffset, inputLen, out, 0);
            return n == out.length ? out : java.util.Arrays.copyOf(out, n);
        }
        catch (ShortBufferException e) {
            throw new IllegalBlockSizeException(e.getMessage());
        }
    }

    // As Android 5.0 and 5.1's BaseBlockCipher.engineDoFinal into an array: no length check first,
    // processBytes then doFinal, and the same three exceptions
    @Override
    protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
            throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
        try {
            int len = 0;
            if (inputLen != 0) {
                len = cipher.processBytes(input, inputOffset, inputLen, output, outputOffset);
            }
            return len + cipher.doFinal(output, outputOffset + len);
        }
        catch (OutputLengthException e) {
            throw new ShortBufferException(e.getMessage());
        }
        catch (DataLengthException e) {
            throw new IllegalBlockSizeException(e.getMessage());
        }
        catch (InvalidCipherTextException e) {
            throw new BadPaddingException(e.getMessage());
        }
    }
}
